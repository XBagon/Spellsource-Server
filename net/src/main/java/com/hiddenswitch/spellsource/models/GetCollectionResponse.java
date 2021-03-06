package com.hiddenswitch.spellsource.models;

import com.hiddenswitch.spellsource.impl.util.DeckType;
import com.hiddenswitch.spellsource.Games;
import com.hiddenswitch.spellsource.Logic;
import com.hiddenswitch.spellsource.client.models.CardRecord;
import com.hiddenswitch.spellsource.client.models.InventoryCollection;
import com.hiddenswitch.spellsource.impl.util.InventoryRecord;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.cards.CardCatalogue;
import net.demilich.metastone.game.cards.Card;
import net.demilich.metastone.game.cards.desc.CardDesc;
import net.demilich.metastone.game.decks.Deck;
import net.demilich.metastone.game.decks.DeckWithId;
import net.demilich.metastone.game.entities.heroes.HeroClass;
import org.apache.commons.lang3.builder.EqualsBuilder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.toList;

/**
 * Created by bberman on 1/22/17.
 */
public final class GetCollectionResponse implements Serializable {
	private List<GetCollectionResponse> responses;
	private List<InventoryRecord> inventoryRecords;
	private CollectionTypes collectionType;
	private HeroClass heroClass;
	private String name;
	private String collectionId;
	private String userId;
	private boolean trashed;
	private DeckType deckType;
	private String heroCardId;
	private String format;

	private GetCollectionResponse() {
	}

	public static GetCollectionResponse batch(List<GetCollectionResponse> responses) {
		return new GetCollectionResponse()
				.withResponses(responses);
	}

	public static GetCollectionResponse user(String collectionId, List<InventoryRecord> inventoryRecords) {
		return new GetCollectionResponse()
				.withCollectionId(collectionId)
				.withCardRecords(inventoryRecords)
				.withUserId(collectionId)
				.withCollectionType(CollectionTypes.USER);
	}

	public static GetCollectionResponse deck(String userId, String deckId, String name, HeroClass heroClass, String heroCardId, String format, DeckType deckType, List<InventoryRecord> inventoryRecords, boolean trashed) {
		return new GetCollectionResponse()
				.withTrashed(trashed)
				.withCollectionType(CollectionTypes.DECK)
				.withCollectionId(deckId)
				.withCardRecords(inventoryRecords)
				.withHeroClass(heroClass)
				.withHeroCardId(heroCardId)
				.withUserId(userId)
				.withDeckType(deckType)
				.withFormat(format)
				.withName(name);
	}

	public Deck asDeck(String userId) {
		Deck deck = new DeckWithId(getCollectionId());
		deck.setHeroClass(getHeroClass());
		deck.setName(getName());
		String heroCardId = getHeroCardId();
		if (heroCardId != null) {
			deck.setHeroCard((Card) CardCatalogue.getCardById(heroCardId));
		}

		getInventoryRecords().stream().map(cardRecord -> Logic.getDescriptionFromRecord(cardRecord, userId, getCollectionId()))
				.filter(Objects::nonNull)
				.map(CardDesc::create)
				.forEach(deck.getCards()::addCard);

		return deck;
	}

	public List<InventoryRecord> getInventoryRecords() {
		return inventoryRecords;
	}

	public void setInventoryRecords(List<InventoryRecord> inventoryRecords) {
		this.inventoryRecords = inventoryRecords;
	}

	public HeroClass getHeroClass() {
		return heroClass;
	}

	public void setHeroClass(HeroClass heroClass) {
		this.heroClass = heroClass;
	}

	public GetCollectionResponse withCardRecords(List<InventoryRecord> inventoryRecords) {
		this.inventoryRecords = inventoryRecords;
		return this;
	}

	public GetCollectionResponse withHeroClass(final HeroClass heroClass) {
		this.heroClass = heroClass;
		return this;
	}

	public CollectionTypes getCollectionType() {
		return collectionType;
	}

	public void setCollectionType(CollectionTypes collectionType) {
		this.collectionType = collectionType;
	}

	public GetCollectionResponse withCollectionType(final CollectionTypes collectionType) {
		this.collectionType = collectionType;
		return this;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public GetCollectionResponse withName(final String name) {
		this.name = name;
		return this;
	}

	public String getCollectionId() {
		return collectionId;
	}

	public void setCollectionId(String collectionId) {
		this.collectionId = collectionId;
	}

	private GetCollectionResponse withCollectionId(String collectionId) {
		this.collectionId = collectionId;
		return this;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public GetCollectionResponse withUserId(String userId) {
		this.userId = userId;
		return this;
	}

	public List<GetCollectionResponse> getResponses() {
		return responses;
	}

	public void setResponses(List<GetCollectionResponse> responses) {
		this.responses = responses;
	}

	public GetCollectionResponse withResponses(final List<GetCollectionResponse> responses) {
		this.responses = responses;
		return this;
	}

	public InventoryCollection asInventoryCollection() {
		if (getResponses() != null
				&& getResponses().size() > 0) {
			throw new RuntimeException();
		}

		String displayName = getCollectionId();

		if (getName() != null) {
			displayName = getName();
		}

		final HeroClass fakeHeroClass = getHeroClass() == null ? HeroClass.RED : getHeroClass();
		GameContext emptyContext = GameContext.uninitialized(fakeHeroClass, fakeHeroClass);

		List<InventoryRecord> inventoryRecords = getInventoryRecords();
		List<CardRecord> records = new ArrayList<>();

		for (InventoryRecord cr : inventoryRecords) {
			final CardDesc record = Logic.getDescriptionFromRecord(cr, cr.getUserId(), getCollectionId());

			if (record == null) {
				continue;
			}
			final Card instance = record.create();
			records.add(new CardRecord()
					.userId(cr.getUserId())
					.collectionIds(cr.getCollectionIds())
					.entity(Games.getEntity(emptyContext, instance, 0))
					.id(cr.getId())
					.allianceId(cr.getAllianceId())
					.donorUserId(cr.getDonorUserId()));
		}

		InventoryCollection collection = new InventoryCollection()
				.name(displayName)
				.id(getCollectionId())
				.type(InventoryCollection.TypeEnum.valueOf(getCollectionType().toString()))
				.format(getFormat())
				.deckType(getCollectionType() == CollectionTypes.DECK ? InventoryCollection.DeckTypeEnum.valueOf(getDeckType().toString()) : null)
				.inventory(records);

		if (getHeroClass() != null) {
			collection.heroClass(getHeroClass().toString());
		}

		return collection;
	}

	public boolean getTrashed() {
		return trashed;
	}

	public boolean isTrashed() {
		return trashed;
	}

	public void setTrashed(boolean trashed) {
		this.trashed = trashed;
	}

	public GetCollectionResponse withTrashed(final boolean trashed) {
		this.trashed = trashed;
		return this;
	}

	public DeckType getDeckType() {
		return deckType;
	}

	public void setDeckType(DeckType deckType) {
		this.deckType = deckType;
	}

	public GetCollectionResponse withDeckType(final DeckType deckType) {
		this.deckType = deckType;
		return this;
	}

	public String getHeroCardId() {
		return heroCardId;
	}

	public void setHeroCardId(String heroCardId) {
		this.heroCardId = heroCardId;
	}

	public GetCollectionResponse withHeroCardId(String heroCardId) {
		this.heroCardId = heroCardId;
		return this;
	}

	public static GetCollectionResponse empty() {
		return new GetCollectionResponse();
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof GetCollectionResponse)) {
			return false;
		}

		GetCollectionResponse rhs = (GetCollectionResponse) obj;

		final boolean idEquals = new EqualsBuilder()
				.append(collectionId, rhs.collectionId)
				.isEquals();

		return idEquals
				|| new EqualsBuilder()
				.append(collectionType, rhs.collectionType)
				.append(heroClass, rhs.heroClass)
				.append(deckType, rhs.deckType)
				.append(heroCardId, rhs.heroCardId)
				.append(inventoryRecords == null ? null : inventoryRecords.stream().map(InventoryRecord::getCardId).toArray(),
						rhs.inventoryRecords == null ? null : rhs.inventoryRecords.stream().map(InventoryRecord::getCardId).toArray()).isEquals();

	}

	public String getFormat() {
		return format;
	}

	public void setFormat(String format) {
		this.format = format;
	}

	public GetCollectionResponse withFormat(String format) {
		this.format = format;
		return this;
	}
}


