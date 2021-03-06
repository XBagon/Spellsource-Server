package net.demilich.metastone.game.logic;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import net.demilich.metastone.game.actions.PhysicalAttackAction;
import net.demilich.metastone.game.entities.EntityZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.demilich.metastone.game.utils.Attribute;
import net.demilich.metastone.game.environment.Environment;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.Player;
import net.demilich.metastone.game.actions.ActionType;
import net.demilich.metastone.game.actions.GameAction;
import net.demilich.metastone.game.entities.Actor;
import net.demilich.metastone.game.entities.Entity;
import net.demilich.metastone.game.entities.heroes.Hero;
import net.demilich.metastone.game.entities.minions.Minion;
import net.demilich.metastone.game.targeting.EntityReference;
import net.demilich.metastone.game.targeting.TargetSelection;

public class TargetLogic implements Serializable {
	private static Logger logger = LoggerFactory.getLogger(TargetLogic.class);

	private static List<Entity> singleTargetAsList(Entity target) {
		ArrayList<Entity> list = new ArrayList<>(1);
		list.add(target);
		return list;
	}

	private boolean containsTaunters(List<? extends Entity> minions) {
		for (Entity entity : minions) {
			if ((entity.hasAttribute(Attribute.TAUNT) || entity.hasAttribute(Attribute.AURA_TAUNT))
					&& !entity.hasAttribute(Attribute.STEALTH) && !entity.hasAttribute(Attribute.IMMUNE) && !entity.hasAttribute(Attribute.AURA_STEALTH)) {
				return true;
			}
		}
		return false;
	}

	private List<Entity> filterTargets(GameContext context, Player player, GameAction action, List<Entity> potentialTargets) {
		List<Entity> validTargets = new ArrayList<>();
		for (Entity entity : potentialTargets) {
			// special case for 'SYSTEM' action, which are used in Sandbox Mode
			// we do not want to restrict those actions by STEALTH or
			// UNTARGETABLE_BY_SPELLS
			if (action.getActionType() == ActionType.SYSTEM && action.canBeExecutedOn(context, player, entity)) {
				validTargets.add(entity);
				continue;
			}

			if ((action.getActionType() == ActionType.SPELL || action.getActionType() == ActionType.HERO_POWER)
					&& (entity.hasAttribute(Attribute.UNTARGETABLE_BY_SPELLS) || (entity.hasAttribute(Attribute.AURA_UNTARGETABLE_BY_SPELLS)))) {
				continue;
			}

			// Implements Shimmering Courser
			if ((action.getActionType() == ActionType.SPELL || action.getActionType() == ActionType.HERO_POWER)
					&& (entity.hasAttribute(Attribute.UNTARGETABLE_BY_OPPONENT_SPELLS) && player.getId() != entity.getOwner())) {
				continue;
			}

			// You can summon next to permanents but not anything else.
			if (action.getActionType() != ActionType.SUMMON && entity.hasAttribute(Attribute.PERMANENT)) {
				continue;
			}

			if (entity.getOwner() != player.getId() && (entity.hasAttribute(Attribute.STEALTH) || entity.hasAttribute(Attribute.IMMUNE) || entity.hasAttribute(Attribute.AURA_IMMUNE) || entity.hasAttribute(Attribute.AURA_STEALTH))) {
				continue;
			}

			if (action.canBeExecutedOn(context, player, entity)) {
				validTargets.add(entity);
			}
		}
		return validTargets;
	}

	/**
	 * Find an entity in the game context using targeting rules.
	 *
	 * @param context   The current game context
	 * @param targetKey A {@link EntityReference}
	 * @return The found entity.
	 * @throws NullPointerException If the entity isn't found.
	 */
	public Entity findEntity(GameContext context, EntityReference targetKey) throws NullPointerException {
		final int targetId = targetKey.getId();
		Entity environmentResult = findInEnvironment(context, targetKey);

		if (environmentResult != null) {
			return environmentResult;
		}

		return context.getEntities().filter(e -> e.getId() == targetId)
				.findFirst()
				.orElseThrow(() -> new NullPointerException("Target not found exception: " + targetKey));
	}

	private Entity findInEnvironment(GameContext context, EntityReference targetKey) {
		if (!context.getEventTargetStack().isEmpty() && targetKey.equals(EntityReference.EVENT_TARGET)) {
			return context.resolveSingleTarget(context.getEventTargetStack().peek());
		}
		if (!context.getEventSourceStack().isEmpty() && targetKey.equals(EntityReference.EVENT_TARGET)) {
			return context.resolveSingleTarget(context.getEventSourceStack().peek());
		}
		return null;
	}

	private List<Entity> getEntities(GameContext context, Player player, TargetSelection targetRequirement, boolean omitPermanents) {
		Player opponent = context.getOpponent(player);
		List<Entity> entities = new ArrayList<>();
		if (targetRequirement == TargetSelection.ENEMY_HERO || targetRequirement == TargetSelection.ENEMY_CHARACTERS
				|| targetRequirement == TargetSelection.ANY || targetRequirement == TargetSelection.HEROES) {
			entities.add(opponent.getHero());
		}
		if (targetRequirement == TargetSelection.ENEMY_MINIONS || targetRequirement == TargetSelection.ENEMY_CHARACTERS
				|| targetRequirement == TargetSelection.MINIONS || targetRequirement == TargetSelection.ANY) {
			entities.addAll(opponent.getMinions());
		}
		if (targetRequirement == TargetSelection.FRIENDLY_HERO || targetRequirement == TargetSelection.FRIENDLY_CHARACTERS
				|| targetRequirement == TargetSelection.ANY || targetRequirement == TargetSelection.HEROES) {
			entities.add(player.getHero());
		}
		if (targetRequirement == TargetSelection.FRIENDLY_MINIONS || targetRequirement == TargetSelection.FRIENDLY_CHARACTERS
				|| targetRequirement == TargetSelection.MINIONS || targetRequirement == TargetSelection.ANY) {
			entities.addAll(player.getMinions());
		}

		if (omitPermanents) {
			return withoutPermanents(entities);
		} else {
			return entities;
		}
	}

	private List<Entity> getEntities(GameContext context, Player player, TargetSelection targetRequirement) {
		return getEntities(context, player, targetRequirement, true);
	}

	private List<Entity> getTaunters(List<? extends Entity> entities) {
		List<Entity> taunters = new ArrayList<>();
		for (Entity entity : entities) {
			if ((entity.hasAttribute(Attribute.TAUNT) || entity.hasAttribute(Attribute.AURA_TAUNT)) && !entity.hasAttribute(Attribute.STEALTH) && !entity.hasAttribute(Attribute.IMMUNE)) {
				taunters.add(entity);
			}
		}
		return taunters;
	}

	public static <E extends Entity> List<E> withoutPermanents(List<E> in) {
		return in.stream().filter(e -> !e.hasAttribute(Attribute.PERMANENT)).collect(Collectors.toList());
	}

	public List<Entity> getValidTargets(GameContext context, Player player, GameAction action) {
		TargetSelection targetRequirement = action.getTargetRequirement();
		ActionType actionType = action.getActionType();
		Player opponent = context.getOpponent(player);

		// if there is a minion with TAUNT and the action is of type physical
		// attack only allow corresponding minions as targets
		if (actionType == ActionType.PHYSICAL_ATTACK
				&& (targetRequirement == TargetSelection.ENEMY_CHARACTERS || targetRequirement == TargetSelection.ENEMY_MINIONS)
				&& (containsTaunters(withoutPermanents(opponent.getMinions())) || containsTaunters(opponent.getHeroZone()))) {
			List<Entity> entities = new ArrayList<>(opponent.getMinions());
			entities.add(opponent.getHero());
			return getTaunters(entities);
		}
		if (actionType == ActionType.SUMMON) {
			// you can summon next to any friendly minion or provide no target
			// (=null)
			// in which case the minion will appear to the very right of your
			// board
			List<Entity> summonTargets = this.getEntities(context, player, targetRequirement, false);
			summonTargets.add(null);
			return summonTargets;
		}
		List<Entity> potentialTargets = this.getEntities(context, player, targetRequirement);
		return filterTargets(context, player, action, potentialTargets);
	}

	/**
	 * Resolves an {@link EntityReference} from the point of view of the specified player, context and entity.
	 *
	 * @param context   The game context
	 * @param player    The player from whose point of view this resolution is being interpreted. For example, {@link
	 *                  EntityReference#FRIENDLY_MINIONS} will interpret this argument as friendly.
	 * @param source    The entity from whose point of view this resolution is being interpreted. For example, {@link
	 *                  EntityReference#SELF} will return the source entity; {@link EntityReference#MINIONS_TO_RIGHT} will
	 *                  refer to the right of this argument.
	 * @param targetKey The {@link EntityReference} to interpet.
	 * @return {@code null} if no target key is specified or an {@link EntityReference#NONE} was passed; otherwise, a
	 * possibly empty list of entities.
	 * @see EntityReference for more about the meaning of the specified entitiy references that are groups of entities.
	 */
	@SuppressWarnings("deprecation")
	public List<Entity> resolveTargetKey(GameContext context, Player player, Entity source, EntityReference targetKey) {
		if (targetKey == null || targetKey.equals(EntityReference.NONE)) {
			return null;
		}
		if (targetKey.equals(EntityReference.ALL_CHARACTERS)) {
			return this.getEntities(context, player, TargetSelection.ANY);
		} else if (targetKey.equals(EntityReference.ALL_MINIONS)) {
			return this.getEntities(context, player, TargetSelection.MINIONS);
		} else if (targetKey.equals(EntityReference.ENEMY_CHARACTERS)) {
			return this.getEntities(context, player, TargetSelection.ENEMY_CHARACTERS);
		} else if (targetKey.equals(EntityReference.ENEMY_HERO)) {
			return this.getEntities(context, player, TargetSelection.ENEMY_HERO);
		} else if (targetKey.equals(EntityReference.ENEMY_MINIONS)) {
			return this.getEntities(context, player, TargetSelection.ENEMY_MINIONS);
		} else if (targetKey.equals(EntityReference.ENEMY_MINIONS_LEFT_TO_RIGHT)) {
			List<Entity> enemyMinions = this.getEntities(context, player, TargetSelection.ENEMY_MINIONS);
			enemyMinions.sort(Comparator.comparingInt(e -> e.getEntityLocation().getIndex()));
			return enemyMinions;
		} else if (targetKey.equals(EntityReference.FRIENDLY_CHARACTERS)) {
			return this.getEntities(context, player, TargetSelection.FRIENDLY_CHARACTERS);
		} else if (targetKey.equals(EntityReference.FRIENDLY_HERO)) {
			return this.getEntities(context, player, TargetSelection.FRIENDLY_HERO);
		} else if (targetKey.equals(EntityReference.FRIENDLY_MINIONS)) {
			return this.getEntities(context, player, TargetSelection.FRIENDLY_MINIONS);
		} else if (targetKey.equals(EntityReference.OTHER_FRIENDLY_MINIONS)) {
			List<Entity> targets = this.getEntities(context, player, TargetSelection.FRIENDLY_MINIONS);
			targets.remove(source);
			return targets;
		} else if (targetKey.equals(EntityReference.OTHER_ENEMY_MINIONS)) {
			List<Entity> targets = this.getEntities(context, player, TargetSelection.ENEMY_MINIONS);
			targets.remove(source);
			return targets;
		} else if (targetKey.equals(EntityReference.LEFTMOST_FRIENDLY_CARD_HAND)) {
			if (player.getHand().size() == 0) {
				return new ArrayList<>();
			}
			return new ArrayList<>(player.getHand().subList(0, 1));
		} else if (targetKey.equals(EntityReference.ALL_OTHER_CHARACTERS)) {
			List<Entity> targets = this.getEntities(context, player, TargetSelection.ANY);
			targets.remove(source);
			return targets;
		} else if (targetKey.equals(EntityReference.ALL_OTHER_MINIONS)) {
			List<Entity> targets = this.getEntities(context, player, TargetSelection.MINIONS);
			targets.remove(source);
			return targets;
		} else if (targetKey.equals(EntityReference.ADJACENT_MINIONS)) {
			return new ArrayList<>(context.getAdjacentMinions(source.getReference()));
		} else if (targetKey.equals(EntityReference.ATTACKER_ADJACENT_MINIONS)) {
			return new ArrayList<>(context.getAdjacentMinions(context.resolveSingleTarget(context.getAttackerReferenceStack().peek()).getReference()));
		} else if (targetKey.equals(EntityReference.OPPOSITE_MINIONS)) {
			return new ArrayList<>(context.getOppositeMinions(source.getReference()));
		} else if (targetKey.equals(EntityReference.MINIONS_TO_LEFT)) {
			return new ArrayList<>(context.getLeftMinions(source.getReference()));
		} else if (targetKey.equals(EntityReference.MINIONS_TO_RIGHT)) {
			return new ArrayList<>(context.getRightMinions(player, source.getReference()));
		} else if (targetKey.equals(EntityReference.LEFTMOST_ENEMY_MINION)) {
			final List<Entity> minions = this.getEntities(context, player, TargetSelection.ENEMY_MINIONS);
			if (minions.size() == 0) {
				return new ArrayList<>();
			}
			return singleTargetAsList(minions.get(0));
		} else if (targetKey.equals(EntityReference.LEFTMOST_FRIENDLY_MINION)) {
			final List<Entity> minions = this.getEntities(context, player, TargetSelection.FRIENDLY_MINIONS);
			if (minions.size() == 0) {
				return new ArrayList<>();
			}
			return singleTargetAsList(minions.get(0));
		} else if (targetKey.equals(EntityReference.RIGHTMOST_ENEMY_MINION)) {
			final List<Entity> minions = this.getEntities(context, player, TargetSelection.ENEMY_MINIONS);
			if (minions.size() == 0) {
				return new ArrayList<>();
			}
			return singleTargetAsList(minions.get(minions.size() - 1));
		} else if (targetKey.equals(EntityReference.RIGHTMOST_FRIENDLY_MINION)) {
			final List<Entity> minions = this.getEntities(context, player, TargetSelection.FRIENDLY_MINIONS);
			if (minions.size() == 0) {
				return new ArrayList<>();
			}
			return singleTargetAsList(minions.get(minions.size() - 1));
		} else if (targetKey.equals(EntityReference.SELF)) {
			return singleTargetAsList(source);
		} else if (targetKey.equals(EntityReference.EVENT_TARGET)) {
			EntityReference target = context.getEventTargetStack().peek();
			if (target == null || target.equals(EntityReference.NONE)) {
				return new ArrayList<>();
			}
			return singleTargetAsList(context.resolveSingleTarget(target));
		} else if (targetKey.equals(EntityReference.EVENT_SOURCE)) {
			EntityReference target = context.getEventSourceStack().peek();
			if (target == null || target.equals(EntityReference.NONE)) {
				return new ArrayList<>();
			}
			return singleTargetAsList(context.resolveSingleTarget(target));
		} else if (targetKey.equals(EntityReference.TARGET)) {
			EntityReference targetKey1 = (EntityReference) context.getEnvironment().get(Environment.TARGET);
			if (targetKey1 == null) {
				return new ArrayList<>();
			}
			return singleTargetAsList(context.resolveSingleTarget(targetKey1));
		} else if (targetKey.equals(EntityReference.SPELL_TARGET)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getSpellTargetStack().peek()));
		} else if (targetKey.equals(EntityReference.KILLED_MINION)) {
			return singleTargetAsList(context.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.KILLED_MINION)));
		} else if (targetKey.equals(EntityReference.ATTACKER)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getAttackerReferenceStack().peek()));
		} else if (targetKey.equals(EntityReference.OUTPUT)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getOutputStack().peek()));
		} else if (targetKey.equals(EntityReference.FRIENDLY_WEAPON)) {
			if (player.getHero().getWeapon() != null) {
				return singleTargetAsList(player.getHero().getWeapon());
			} else {
				return new ArrayList<>();
			}
		} else if (targetKey.equals(EntityReference.ENEMY_WEAPON)) {
			Player opponent = context.getOpponent(player);
			if (opponent.getHero().getWeapon() != null) {
				return singleTargetAsList(opponent.getHero().getWeapon());
			} else {
				return new ArrayList<>();
			}
		} else if (targetKey.equals(EntityReference.FRIENDLY_HAND)) {
			return new ArrayList<>(player.getHand().toList());
		} else if (targetKey.equals(EntityReference.ENEMY_HAND)) {
			return new ArrayList<>(context.getOpponent(player).getHand().toList());
		} else if (targetKey.equals(EntityReference.FRIENDLY_PLAYER)) {
			return singleTargetAsList(player);
		} else if (targetKey.equals(EntityReference.ENEMY_PLAYER)) {
			return singleTargetAsList(context.getOpponent(player));
		} else if (targetKey.equals(EntityReference.FRIENDLY_DECK)) {
			return new ArrayList<>(player.getDeck().toList());
		} else if (targetKey.equals(EntityReference.ENEMY_DECK)) {
			return new ArrayList<>(context.getOpponent(player).getDeck().toList());
		} else if (targetKey.equals(EntityReference.FRIENDLY_TOP_CARD)) {
			if (player.getDeck().size() == 0) {
				return new ArrayList<>();
			} else {
				return singleTargetAsList(player.getDeck().get(0));
			}
		} else if (targetKey.equals(EntityReference.ENEMY_TOP_CARD)) {
			Player opponent = context.getOpponent(player);
			if (opponent.getDeck().size() == 0) {
				return new ArrayList<>();
			} else {
				return singleTargetAsList(opponent.getDeck().get(0));
			}
		} else if (targetKey.equals(EntityReference.BOTH_DECKS)) {
			ArrayList<Entity> friendly = new ArrayList<>(player.getDeck().toList());
			friendly.addAll(context.getOpponent(player).getDeck().toList());
			return friendly;
		} else if (targetKey.equals(EntityReference.BOTH_HANDS)) {
			ArrayList<Entity> friendly = new ArrayList<>(player.getHand().toList());
			friendly.addAll(context.getOpponent(player).getHand().toList());
			return friendly;
		} else if (targetKey.equals(EntityReference.LAST_CARD_PLAYED)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getLastCardPlayed()));
		} else if (targetKey.equals(EntityReference.FRIENDLY_LAST_CARD_PLAYED)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getLastCardPlayed(player.getId())));
		} else if (targetKey.equals(EntityReference.ENEMY_LAST_CARD_PLAYED)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getLastCardPlayed(context.getOpponent(player).getId())));
		} else if (targetKey.equals(EntityReference.TRANSFORM_REFERENCE)) {
			return singleTargetAsList(context.resolveSingleTarget((EntityReference) context.getEnvironment().get(Environment.TRANSFORM_REFERENCE)));
		} else if (targetKey.equals(EntityReference.FRIENDLY_SET_ASIDE)) {
			return new ArrayList<>(player.getSetAsideZone());
		} else if (targetKey.equals(EntityReference.ENEMY_SET_ASIDE)) {
			return new ArrayList<>(context.getOpponent(player).getSetAsideZone());
		} else if (targetKey.equals(EntityReference.FRIENDLY_GRAVEYARD)) {
			return new ArrayList<>(player.getGraveyard());
		} else if (targetKey.equals(EntityReference.ENEMY_GRAVEYARD)) {
			return new ArrayList<>(context.getOpponent(player).getGraveyard());
		} else if (targetKey.equals(EntityReference.FRIENDLY_HERO_POWER)) {
			return singleTargetAsList(player.getHeroPowerZone().get(0));
		} else if (targetKey.equals(EntityReference.ENEMY_HERO_POWER)) {
			return singleTargetAsList(context.getOpponent(player).getHeroPowerZone().get(0));
		} else if (targetKey.equals(EntityReference.ALL_ENTITIES)) {
			return context.getEntities().collect(Collectors.toList());
		} else if (targetKey.equals(EntityReference.TRIGGER_HOST)) {
			return singleTargetAsList(context.resolveSingleTarget(context.getTriggerHostStack().peek()));
		} else if (targetKey.equals(EntityReference.PHYSICAL_ATTACK_TARGETS)) {
			return getValidTargets(context, player, new PhysicalAttackAction(source.getReference()));
		}
		return singleTargetAsList(findEntity(context, targetKey));
	}

}
