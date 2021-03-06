package com.hiddenswitch.spellsource.util;

import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.strands.SettableFuture;
import co.paralleluniverse.strands.Strand;
import com.google.common.collect.Sets;
import com.hiddenswitch.spellsource.Games;
import com.hiddenswitch.spellsource.Port;
import com.hiddenswitch.spellsource.client.ApiClient;
import com.hiddenswitch.spellsource.client.ApiException;
import com.hiddenswitch.spellsource.client.api.DefaultApi;
import com.hiddenswitch.spellsource.client.models.*;
import io.vertx.core.Handler;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.TestContext;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class UnityClient {
	private static Logger logger = LoggerFactory.getLogger(UnityClient.class);
	public static final String BASE = "http://localhost:";
	public static String basePath = BASE + Integer.toString(Port.port());
	private ApiClient apiClient;
	private DefaultApi api;
	private volatile boolean gameOver;
	private Handler<UnityClient> onGameOver;
	private Account account;
	private TestContext context;
	private WebsocketClientEndpoint endpoint;
	private WebsocketClientEndpoint realtime;
	private AtomicReference<SettableFuture<Void>> matchmakingFut = new AtomicReference<>(new SettableFuture<>());
	private AtomicInteger turnsToPlay = new AtomicInteger(999);
	private List<java.util.function.Consumer<ServerToClientMessage>> handlers = new ArrayList<>();
	private String loginToken;
	private String thisUrl;
	private boolean shouldDisconnect = true;


	public UnityClient(TestContext context) {
		apiClient = new ApiClient();
		thisUrl = basePath;
		apiClient.setBasePath(basePath);
		api = new DefaultApi(apiClient);
		this.context = context;
		List<UnityClient> clients = context.get("clients");
		if (clients == null) {
			clients = new LinkedList<>();
			context.put("clients", clients);
		}
		clients.add(this);
	}

	public UnityClient(TestContext context, int port) {
		this(context);
		apiClient = new ApiClient();
		thisUrl = BASE + Integer.toString(port);
		apiClient.setBasePath(thisUrl);
		api = new DefaultApi(apiClient);
	}

	public UnityClient(TestContext context, String token) {
		this(context);
		this.loginToken = token;
		api.getApiClient().setApiKey(loginToken);
	}

	public UnityClient createUserAccount() {
		return createUserAccount(null);
	}

	public UnityClient createUserAccount(String username) {
		if (username == null) {
			username = RandomStringUtils.randomAlphanumeric(10);
		}

		try {
			CreateAccountResponse car = api.createAccount(new CreateAccountRequest().email(username + "@hiddenswitch.com").name(username).password("testpass"));
			loginToken = car.getLoginToken();
			api.getApiClient().setApiKey(loginToken);
			account = car.getAccount();
			context.assertNotNull(account);
			context.assertTrue(account.getDecks().size() > 0);
			logger.debug("createUserAccount: Created account " + car.getAccount().getId());
		} catch (ApiException e) {
			context.fail(e.getMessage());
		}
		return this;
	}

	public UnityClient loginWithUserAccount(String username) {
		return loginWithUserAccount(username, "testpass");
	}

	public void gameOver(Handler<UnityClient> handler) {
		onGameOver = io.vertx.ext.sync.Sync.fiberHandler(handler);
	}

	@Suspendable
	public Future<Void> matchmake(String deckId, String queueId) {
		if (deckId == null) {
			deckId = account.getDecks().get(random(account.getDecks().size())).getId();
		}

		SettableFuture<Void> fut = new SettableFuture<Void>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				realtime.sendMessage(Json.encode(new Envelope()
						.method(new EnvelopeMethod()
								.methodId(RandomStringUtils.randomAlphanumeric(10))
								.dequeue(new EnvelopeMethodDequeue().queueId(queueId)))));
				return super.cancel(mayInterruptIfRunning);
			}
		};

		matchmakingFut.set(fut);
		final long matchmakingThreadId = Strand.currentStrand().getId();

		if (realtime == null) {
			realtime = new WebsocketClientEndpoint(api.getApiClient().getBasePath().replace("http://", "ws://") + "/realtime", loginToken);
			realtime.addMessageHandler(message -> {
				context.assertNotEquals(matchmakingThreadId, Strand.currentStrand().getId());
				Envelope env = Json.decodeValue(message, Envelope.class);

				if (env.getResult() != null && env.getResult().getEnqueue() != null) {
					if (matchmakingFut.get().isCancelled()) {
						context.fail(new IllegalStateException("matchmaking was cancelled"));
					}

					// Might have been cancelled
					matchmakingFut.get().set(null);
				}
			});
		}

		realtime.sendMessage(Json.encode(new Envelope()
				.method(new EnvelopeMethod()
						.methodId(RandomStringUtils.randomAlphanumeric(10))
						.enqueue(new MatchmakingQueuePutRequest()
								.queueId(queueId)
								.deckId(deckId)))));

		return fut;
	}

	@Suspendable
	public void matchmakeQuickPlay(String deckId) {
		Future<Void> matchmaking = matchmake(deckId, "quickPlay");
		try {
			matchmaking.get();
			play();
		} catch (InterruptedException | ExecutionException ex) {
			matchmaking.cancel(true);
		}
	}

	@Suspendable
	public void matchmakeConstructedPlay(String deckId) {
		Future<Void> matchmaking = matchmake(deckId, "constructed");
		try {
			matchmaking.get();
			play();
		} catch (InterruptedException | ExecutionException ex) {
			matchmaking.cancel(true);
		}
	}

	public void play() {
		this.gameOver = false;
		logger.debug("play: Playing userId " + getUserId());
		// Get the port from the url
		final URL basePathUrl;
		try {
			basePathUrl = new URL(thisUrl);
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
		String url = "ws://" + basePathUrl.getHost() + ":" + Integer.toString(basePathUrl.getPort()) + "/" + Games.WEBSOCKET_PATH + "-clustered";

		endpoint = new WebsocketClientEndpoint(url, loginToken);
		endpoint.addMessageHandler(h -> {
			logger.debug("play: Handing message for userId " + getUserId());
			ServerToClientMessage message = Json.decodeValue(h, ServerToClientMessage.class);

			for (java.util.function.Consumer<ServerToClientMessage> handler : handlers) {
				if (handler != null) {
					handler.accept(message);
				}
			}

			logger.debug("play: Starting to handle message for userId " + getUserId() + " of type " + message.getMessageType().toString());
			switch (message.getMessageType()) {
				case ON_TURN_END:
					if (turnsToPlay.getAndDecrement() <= 0
							&& shouldDisconnect) {
						disconnect();
					}
					break;
				case ON_UPDATE:
					assertValidStateAndChanges(message);
					break;
				case ON_GAME_EVENT:
					context.assertNotNull(message.getEvent());
					assertValidStateAndChanges(message);
					break;
				case ON_MULLIGAN:
					context.assertNotNull(message.getStartingCards());
					context.assertTrue(message.getStartingCards().size() > 0);
					endpoint.sendMessage(serialize(new ClientToServerMessage()
							.messageType(MessageType.UPDATE_MULLIGAN)
							.repliesTo(message.getId())
							.discardedCardIndices(Collections.singletonList(0))));
					break;
				case ON_REQUEST_ACTION:
					assertValidActions(message);
					assertValidStateAndChanges(message);
					context.assertNotNull(message.getGameState());
					context.assertNotNull(message.getChanges());
					context.assertNotNull(message.getActions());
					final int actionCount = message.getActions().getCompatibility().size();
					context.assertTrue(actionCount > 0);
					// There should always be an end turn, choose one, discover or battlecry action
					// Pick a random action
					int random = random(actionCount);
					endpoint.sendMessage(serialize(new ClientToServerMessage()
							.messageType(MessageType.UPDATE_ACTION)
							.repliesTo(message.getId())
							.actionIndex(random)));
					logger.debug("play: UserId " + getUserId() + " sent action with ID " + Integer.toString(random));
					break;
				case ON_GAME_END:
					// The game has ended.
					endpoint.close();
					this.gameOver = true;
					if (onGameOver != null) {
						onGameOver.handle(this);
					}
					logger.debug("play: UserId " + getUserId() + " received game end message.");
					break;
			}
			logger.debug("play: Done handling message for userId " + getUserId() + " of type " + message.getMessageType().toString());
		});

		logger.debug("play: UserId " + getUserId() + " sent first message.");
		endpoint.sendMessage(serialize(new ClientToServerMessage()
				.messageType(MessageType.FIRST_MESSAGE)));
	}

	protected String getUserId() {
		if (getAccount() == null) {
			return "(token=" + getToken() + ")";
		}
		return getAccount().getId();
	}

	protected void assertValidActions(ServerToClientMessage message) {

	}

	public void disconnect() {
		if (endpoint != null) {
			endpoint.close();
		}
	}

	protected void assertValidStateAndChanges(ServerToClientMessage message) {
		context.assertNotNull(message.getGameState());
		context.assertNotNull(message.getChanges());
		context.assertNotNull(message.getGameState().getTurnNumber());
		context.assertTrue(message.getGameState().getEntities().stream().allMatch(e -> e.getId() >= 0));
		context.assertTrue(message.getChanges().stream().allMatch(e -> e.getId() >= 0));
		context.assertTrue(message.getGameState().getEntities().stream().filter(e -> e.getEntityType() == Entity.EntityTypeEnum.PLAYER).count() == 2);
		context.assertTrue(message.getGameState().getEntities().stream().filter(e -> e.getEntityType() == Entity.EntityTypeEnum.HERO).count() >= 2);
		context.assertTrue(message.getGameState().getEntities().stream().filter(e ->
				e.getEntityType() == Entity.EntityTypeEnum.HERO
						&& e.getState().getLocation().getZone() == EntityLocation.ZoneEnum.HERO
		).allMatch(h ->
				null != h.getState().getMaxMana()));
		context.assertNotNull(message.getGameState().getTurnNumber());
		if (message.getGameState().getTurnNumber() > 0) {
			context.assertTrue(message.getGameState().getEntities().stream().filter(e -> e.getEntityType() == Entity.EntityTypeEnum.HERO).anyMatch(h ->
					h.getState().getMaxMana() >= 1));
		}
		final Set<Integer> entityIds = message.getGameState().getEntities().stream().map(Entity::getId).collect(Collectors.toSet());
		final Set<Integer> changeIds = message.getChanges().stream().map(EntityChangeSetInner::getId).collect(Collectors.toSet());
		final boolean contains = entityIds.containsAll(changeIds);
		if (!contains) {
			context.fail(/*message.toString()*/ "An ID is missing! " + Sets.difference(changeIds, entityIds).toString());
		}
		if (message.getMessageType() == MessageType.ON_GAME_EVENT
				&& message.getEvent() != null
				&& message.getEvent().getEventType() == GameEvent.EventTypeEnum.TRIGGER_FIRED) {
			context.assertTrue(entityIds.contains(message.getEvent().getTriggerFired().getTriggerSourceId()));
		}
		context.assertTrue(contains);
	}

	private int random(int upper) {
		return RandomUtils.nextInt(0, upper);
	}

	private String serialize(Object obj) {
		return Json.encode(obj);
	}

	public ApiClient getApiClient() {
		return apiClient;
	}

	public void setApiClient(ApiClient apiClient) {
		this.apiClient = apiClient;
	}

	public DefaultApi getApi() {
		return api;
	}

	public void setApi(DefaultApi api) {
		this.api = api;
	}

	public boolean isGameOver() {
		return gameOver;
	}

	public void setGameOver(boolean gameOver) {
		this.gameOver = gameOver;
	}

	public Account getAccount() {
		return account;
	}

	public AtomicInteger getTurnsToPlay() {
		return turnsToPlay;
	}

	public void addMessageHandler(java.util.function.Consumer<ServerToClientMessage> handler) {
		handlers.add(handler);
	}

	@Suspendable
	public UnityClient waitUntilDone() {
		logger.debug("waitUntilDone: UserId " + getUserId() + " is waiting");
		float time = 0f;
		while (!(time > 90f || this.isGameOver())) {
			try {
				Strand.sleep(1000);
			} catch (SuspendExecution | InterruptedException suspendExecution) {
				suspendExecution.printStackTrace();
				return this;
			}

			time += 1f;
		}
		return this;
	}

	public UnityClient loginWithUserAccount(String username, String password) {
		try {
			LoginResponse lr = api.login(new LoginRequest().email(username + "@hiddenswitch.com").password(password));
			loginToken = lr.getLoginToken();
			api.getApiClient().setApiKey(loginToken);
			account = lr.getAccount();
			context.assertNotNull(account);
		} catch (ApiException e) {
			context.fail(e.getMessage());
		}
		return this;
	}

	public String getToken() {
		return loginToken;
	}

	public void concede() {
		endpoint.sendMessageSync(serialize(new ClientToServerMessage()
				.messageType(MessageType.CONCEDE)));
	}

	public boolean isShouldDisconnect() {
		return shouldDisconnect;
	}

	public void setShouldDisconnect(boolean shouldDisconnect) {
		this.shouldDisconnect = shouldDisconnect;
	}
}
