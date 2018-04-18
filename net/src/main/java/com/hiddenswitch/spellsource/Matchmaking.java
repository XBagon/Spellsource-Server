package com.hiddenswitch.spellsource;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.channels.Channels;
import co.paralleluniverse.strands.channels.QueueChannel;
import co.paralleluniverse.strands.channels.QueueObjectChannel;
import co.paralleluniverse.strands.concurrent.Semaphore;
import co.paralleluniverse.strands.queues.ArrayQueue;
import com.hiddenswitch.spellsource.impl.DeckId;
import com.hiddenswitch.spellsource.impl.GameId;
import com.hiddenswitch.spellsource.impl.UserId;
import com.hiddenswitch.spellsource.impl.server.Configuration;
import com.hiddenswitch.spellsource.impl.util.UserRecord;
import com.hiddenswitch.spellsource.models.*;
import com.hiddenswitch.spellsource.util.Rpc;
import com.hiddenswitch.spellsource.util.SharedData;
import com.hiddenswitch.spellsource.util.SuspendableMap;
import com.hiddenswitch.spellsource.util.Sync;
import io.vertx.core.Closeable;
import io.vertx.core.Context;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.Lock;
import net.demilich.metastone.game.decks.Deck;
import org.apache.commons.lang3.RandomStringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The matchmaking service is the primary entry point into ranked games for clients.
 */
public interface Matchmaking extends Verticle {
	Logger LOGGER = LoggerFactory.getLogger(Matchmaking.class);
	Map<UserId, Strand> LOCAL_STRANDS = new ConcurrentHashMap<>();
	Map<String, QueueChannel<QueueEntryX>> QUEUES = new ConcurrentHashMap<>();

	/**
	 * Creates a bot game
	 *
	 * @param userId
	 * @param deckId
	 * @param botDeckId
	 * @return
	 * @throws SuspendExecution
	 * @throws InterruptedException
	 */
	static GameId bot(UserId userId, DeckId deckId, DeckId botDeckId) throws SuspendExecution, InterruptedException {
		LOGGER.debug("matchmakeAndJoin: Matchmaker is creating an AI game for " + userId);
		Lock botLock = null;
		GameId gameId = GameId.create();

		try {
			// TODO: Move this lock into pollBotId
			botLock = SharedData.lock("Matchmaking::takingBot", 30000L);

			// The player has been waiting too long. Match to an AI.
			// Retrieve a bot and use it to play against the opponent
			UserRecord bot = Accounts.get(Bots.pollBotId());
			if (botDeckId != null) {
				LOGGER.info("matchmakeAndJoin: UserId {} requested bot to play deckId {}", userId, botDeckId);
			}

			if (botDeckId == null) {
				botDeckId = new DeckId(Bots.getRandomDeck(bot));
			}

			MatchCreateResponse matchCreateResponse =
					Matchmaking.createMatch(
							MatchCreateRequest.botMatch(gameId, userId, new UserId(bot.getId()), deckId, botDeckId));

			LOGGER.debug("matchmakeAndJoin: User " + userId + " is unlocked by the AI bot creation path.");
			botLock.release();
			return gameId;
		} finally {
			if (botLock != null) {
				botLock.release();
			}
		}
	}

	/**
	 * Creates a two-player match with the given settings.
	 *
	 * @param gameId
	 * @param userId
	 * @param deckId
	 * @param otherUserId
	 * @param otherDeckId
	 * @throws SuspendExecution
	 * @throws InterruptedException
	 */
	static GameId vs(GameId gameId, UserId userId, DeckId deckId, UserId otherUserId, DeckId otherDeckId) throws SuspendExecution, InterruptedException {
		MatchCreateRequest request = new MatchCreateRequest()
				.withDeckId1(deckId)
				.withDeckId2(otherDeckId)
				.withUserId1(userId)
				.withUserId2(otherUserId)
				.withGameId(gameId);

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("createMatch: Creating match for request " + request.toString());
		}

		MatchCreateResponse createMatchResponse = createMatch(request);

		CreateGameSessionResponse createGameSessionResponse = createMatchResponse.getCreateGameSessionResponse();

		if (createGameSessionResponse.pending) {
			LOGGER.debug("matchmakeAndJoin: Retrying createMatch... ");
			int i = 0;
			final int retries = 4;
			final int retryDelay = 500;
			for (; i < retries; i++) {
				Strand.sleep(retryDelay);

				LOGGER.debug("matchmakeAndJoin: Checking if the Games service has created a game for gameId " + gameId);
				createGameSessionResponse = Games.getConnections().get(gameId);
				if (!createGameSessionResponse.pending) {
					break;
				}
			}

			if (i >= retries) {
				throw new NullPointerException("Timed out while waiting for a match to be created for users " + userId + " and " + otherUserId);
			}
		}

		LOGGER.debug("matchmakeAndJoin: Users " + userId + " and " + otherUserId + " are unlocked because a game has been successfully created for them with gameId " + gameId);
		return gameId;
	}

	/**
	 * Gets information about the current match the user is in. This is used for reconnecting.
	 *
	 * @param request The user's ID.
	 * @return The current match information. This may be a queue entry, the match, or nothing (but not null) if the user
	 * is not in a match.
	 * @throws SuspendExecution
	 * @throws InterruptedException
	 */
	static CurrentMatchResponse getCurrentMatch(CurrentMatchRequest request) throws SuspendExecution, InterruptedException {
		LOGGER.debug("getCurrentMatch: Retrieving information for userId " + request.getUserId());
		GameId gameId = Games.getGames().get(new UserId(request.getUserId()));
		if (gameId != null) {
			LOGGER.debug("getCurrentMatch: User " + request.getUserId() + " has match " + gameId);
			return CurrentMatchResponse.response(gameId.toString());
		} else {
			LOGGER.debug("getCurrentMatch: User " + request.getUserId() + " does not have match.");
			return CurrentMatchResponse.response(null);
		}
	}

	/**
	 * Ends a match and allows the user to re-enter the queue.
	 * <p>
	 * The Games service, which also has an end game session function, is distinct from this one. This method allows the
	 * user to enter the queue again (typically users can only be in one queue at a time, either playing a game inside the
	 * matchmaking queue or waiting to be matched into a game). Typical users should not be able to play multiple games at
	 * once.
	 *
	 * @param request The user or game ID to exit from a match.
	 * @return Information about the expiration/cancellation requested.
	 * @throws SuspendExecution
	 * @throws InterruptedException
	 */
	static MatchExpireResponse expireOrEndMatch(MatchExpireRequest request) throws SuspendExecution, InterruptedException {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("expireOrEndMatch: Expiring match " + request.gameId);
		}

		if (request.users == null) {
			throw new NullPointerException("Request does not contain users specified");
		}

		for (UserId userId : request.users) {
			Games.getGames().remove(userId);
		}

		/*
		// End the game in alliance mode
		Logic.endGame(new EndGameRequest()
				.withPlayers(new EndGameRequest.Player()
								.withDeckId(decks.get(0).toString()),
						new EndGameRequest.Player()
								.withDeckId(decks.get(1).toString())));

		if (logger.isDebugEnabled()) {
			logger.debug("expireOrEndMatch: Called Logic::endGame");
		}
		*/

		return new MatchExpireResponse();
	}

	/**
	 * Creates a match without entering a queue entry between two users.
	 *
	 * @param request All the required information to create a game.
	 * @return Connection information for both users.
	 * @throws SuspendExecution
	 * @throws InterruptedException
	 */
	static MatchCreateResponse createMatch(MatchCreateRequest request) throws SuspendExecution, InterruptedException {
		Logic.triggers();

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("createMatch: Creating match for request " + request.toString());
		}
		final String deckId1 = request.getDeckId1().toString();
		final String deckId2 = request.getDeckId2().toString();
		final String userId1 = request.getUserId1().toString();
		final String userId2 = request.getUserId2().toString();
		final String gameId = request.getGameId().toString();

		StartGameResponse startGameResponse = Logic.startGame(new StartGameRequest()
				.withGameId(gameId)
				.withPlayers(new StartGameRequest.Player()
								.withId(0)
								.withUserId(userId1)
								.withDeckId(deckId1),
						new StartGameRequest.Player()
								.withId(1)
								.withUserId(userId2)
								.withDeckId(deckId2)));

		final Deck deck1 = startGameResponse.getPlayers().get(0).getDeck();
		final Deck deck2 = startGameResponse.getPlayers().get(1).getDeck();

		final CreateGameSessionRequest createGameSessionRequest = new CreateGameSessionRequest()
				.withPregame1(new Configuration(deck1, userId1)
						.withAI(request.isBot1())
						.withAttributes(startGameResponse.getConfig1().getAttributes()))
				.withPregame2(new Configuration(deck2, userId2)
						.withAI(request.isBot2())
						.withAttributes(startGameResponse.getConfig2().getAttributes()))
				.withGameId(gameId);
		CreateGameSessionResponse createGameSessionResponse = Rpc.connect(Games.class).sync().createGameSession(createGameSessionRequest);
		Games.getGames().put(new UserId(userId1), new GameId(gameId));
		Games.getGames().put(new UserId(userId2), new GameId(gameId));
		return new MatchCreateResponse(createGameSessionResponse);
	}

	static SuspendableMap<UserId, Long> getUserLocks() throws SuspendExecution {
		return SharedData.getClusterWideMap("Matchmaking::strands");
	}

	static Map<UserId, Strand> getLocalStrands() {
		return LOCAL_STRANDS;
	}

	static QueueChannel<QueueEntryX> getQueue(String key) {
		return QUEUES.computeIfAbsent(key, (k) -> new QueueObjectChannel<>(new ArrayQueue<>(1), Channels.OverflowPolicy.THROW, false, false));
	}

	static Semaphore getParty() {
		Context context = Vertx.currentContext();
		Semaphore party;
		synchronized (context) {
			String key = "Matchmaking::party";
			party = context.get(key);
			if (party == null) {
				party = new Semaphore(2);
				context.put(key, party);
			}
		}
		return party;
	}

	static Closeable cancellation() {
		EventBus eb = Vertx.currentContext().owner().eventBus();
		MessageConsumer<JsonObject> consumer = eb.<JsonObject>consumer("Matchmaking::cancel", Sync.suspendableHandler(message -> {
			UserId userId = new UserId(message.body().getString("userId"));
			if (getLocalStrands().containsKey(userId)) {
				getLocalStrands().get(userId).interrupt();
			}
		}));

		return consumer::unregister;
	}

	static void cancel(UserId userId) throws SuspendExecution, InterruptedException {
		Vertx.currentContext().owner().eventBus().publish("Matchmaking::cancel", new JsonObject().put("userId", userId.toString()));
	}

	/**
	 * @param request
	 * @throws SuspendExecution
	 */
	@Nullable
	static GameId matchmake(MatchmakingRequest request) throws SuspendExecution, InterruptedException {
		Lock lock = null;
		final UserId userId = new UserId(request.getUserId());
		try {
			lock = SharedData.lock("Matchmaking::lock." + request.getUserId(), 350L);
			GameId gameId = Games.getGames().get(userId);
			if (gameId != null) {
				LOGGER.debug("matchmake: User {} already has a game {}", request.getUserId(), gameId);
				return gameId;
			}

			if (request.isBotMatch()) {
				LOGGER.debug("matchmake: User {} is getting a bot game", request.getUserId());
				return bot(userId, new DeckId(request.getDeckId()), request.getBotDeckId() == null ? null : new DeckId(request.getBotDeckId()));
			}


			long timeout = request.getTimeout();

			Map<UserId, Strand> strands = getLocalStrands();
			if (strands.putIfAbsent(userId, Strand.currentStrand()) != null) {
				throw new ConcurrentModificationException();
			}

			QueueChannel<QueueEntryX> firstUser = getQueue("Matchmaking::firstUser");
			QueueChannel<QueueEntryX> secondUser = getQueue("Matchmaking::secondUser");
			Semaphore party = getParty();
			long startTime = System.currentTimeMillis();
			try {
				// There are at most two users who can modify the queue
				if (party.tryAcquire(timeout, TimeUnit.MILLISECONDS)) {
					try {
						// Test if we are the first user
						if (firstUser.trySend(new QueueEntryX(request, new GameId(RandomStringUtils.randomNumeric(42))))) {
							// One user is in the queue at this point (this user)
							while (true) {
								try {
									timeout -= System.currentTimeMillis() - startTime;
									if (timeout < 0) {
										// We timed out for whatever reason
										return null;
									}
									// Wait until the second user is added
									QueueEntryX match = secondUser.receive(timeout, TimeUnit.MILLISECONDS);
									if (match == null) {
										// We timed out, which is as good as cancelling.
										throw new InterruptedException();
									} else {
										// A second user has released us and now we have our match
										// Second user creates game
										return match.gameId;
									}
								} catch (InterruptedException canceled) {
									// We timed out or cancelled waiting for a second user. The semaphore and the queue semantics
									// ensure we're the user in firstUser, unless a second user has already gotten us
									if (firstUser.tryReceive() == null) {
										// A second user has already consumed us to start a match. We have to continue to wait
										// for the second user.
										continue;
									} else {
										// We successfully cancelled. The semaphore ensures that this user was the one that removed
										// itself from first user if a second user didn't.
										return null;
									}
								}

							}
						} else {
							// The queue has a user waiting in it. This means we are the second user. Retrieve the first
							// user. If we consume this first, the first user cannot possibly cancel the matchmaking. It
							// doesn't really matter if this poll is interruptible, but the exception either way (cancel or
							// interrupt) will lead us to the right places.
							try {
								QueueEntryX match = firstUser.receive(timeout, TimeUnit.MILLISECONDS);
								if (match == null) {
									throw new InterruptedException();
								} else {
									vs(match.gameId,
											new UserId(match.req.getUserId()),
											new DeckId(match.req.getDeckId()),
											userId,
											new DeckId(request.getDeckId()));
									// Queue ourselves into the second user slot, to signal to the first user whom they're matching
									// with.
									secondUser.sendNonSuspendable(new QueueEntryX(request, match.gameId));
									// At this point, the first user isn't permitted to cancel gracefully. The test for valid
									// matching will be kicked off to the match connection.
									LOGGER.debug("matchmake: Matching {} and {} into game {}", match.req.getUserId(), userId, match.gameId);
									return match.gameId;
								}
							} catch (InterruptedException canceled) {
								// We timed out or cancelled.
								return null;
							}
						}
					} finally {
						party.release();
					}
				} else {
					// Timed out
					return null;
				}
			} finally {
				lock.release();
				strands.remove(userId);
			}
		} catch (Throwable t) {
			LOGGER.debug("matchmake: User {} is already waiting on another invocation");
			return null;
		} finally {
			if (lock != null) {
				lock.release();
			}
		}
	}

	class QueueEntryX {
		MatchmakingRequest req;
		GameId gameId;

		public QueueEntryX(MatchmakingRequest req, GameId gameId) {
			this.req = req;
			this.gameId = gameId;
		}
	}
}
