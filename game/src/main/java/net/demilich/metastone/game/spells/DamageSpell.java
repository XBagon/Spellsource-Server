package net.demilich.metastone.game.spells;

import java.util.Map;
import java.util.function.Predicate;

import co.paralleluniverse.fibers.Suspendable;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.Player;
import net.demilich.metastone.game.entities.Actor;
import net.demilich.metastone.game.entities.Entity;
import net.demilich.metastone.game.spells.desc.SpellArg;
import net.demilich.metastone.game.spells.desc.SpellDesc;
import net.demilich.metastone.game.spells.desc.valueprovider.ValueProvider;
import net.demilich.metastone.game.targeting.EntityReference;
import net.demilich.metastone.game.utils.Attribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deals {@link SpellArg#VALUE} damage to the specified {@code target}.
 * <p>
 * When {@link SpellArg#IGNORE_SPELL_DAMAGE} is set to {@code true}, ignores {@link Attribute#SPELL_DAMAGE} bonuses.
 * <p>
 * The amount of damage dealt can be modified by other, prior effects using {@link ModifyDamageSpell}.
 * <p>
 * For example, "Battlecry: Deal 6 damage to all other characters:"
 * <pre>
 *   "battlecry": {
 *     "targetSelection": "NONE",
 *     "spell": {
 *       "class": "DamageSpell",
 *       "target": "ALL_OTHER_CHARACTERS",
 *       "value": 6
 *     }
 *   }
 * </pre>
 * A significantly more complicated example, "Deal 10 damage to a minion and excess damage to adjacent ones."
 * <pre>
 *   "spell": {
 *     "class": "MetaSpell",
 *     "value": {
 *       "class": "AttributeValueProvider",
 *       "attribute": "HP"
 *     },
 *     "spells": [
 *       {
 *         "class": "AdjacentEffectSpell",
 *         "spell1": {
 *           "class": "DamageSpell",
 *           "value": {
 *             "class": "AlgebraicValueProvider",
 *             "operation": "MAXIMUM",
 *             "value1": 0,
 *             "value2": {
 *               "class": "AlgebraicValueProvider",
 *               "operation": "MINIMUM",
 *               "value1": 10,
 *               "value2": {
 *                 "class": "GameValueProvider",
 *                 "gameValue": "SPELL_VALUE"
 *               }
 *             }
 *           }
 *         },
 *         "spell2": {
 *           "class": "DamageSpell",
 *           "value": {
 *             "class": "AlgebraicValueProvider",
 *             "operation": "MINIMUM",
 *             "value1": 10,
 *             "value2": {
 *               "class": "AlgebraicValueProvider",
 *               "operation": "MAXIMUM",
 *               "value1": {
 *                 "class": "AlgebraicValueProvider",
 *                 "operation": "SUBTRACT",
 *                 "value1": 10,
 *                 "value2": {
 *                   "class": "GameValueProvider",
 *                   "gameValue": "SPELL_VALUE"
 *                 }
 *               },
 *               "value2": 0
 *             }
 *           }
 *         }
 *       }
 *     ]
 *   }
 * </pre>
 * Observe the way arithmetic is performed inside the {@code "value"} fields of the {@link DamageSpell}.
 *
 * @see HealSpell to heal. Don't use negative values.
 * @see MissilesSpell to fire random missiles that calculate spell damage correctly.
 */
public class DamageSpell extends Spell {
	private static Logger logger = LoggerFactory.getLogger(DamageSpell.class);

	public static SpellDesc create(EntityReference target, int damage) {
		return create(target, damage, false);
	}

	public static SpellDesc create(EntityReference target, int damage, boolean randomTarget) {
		return create(target, damage, null, randomTarget);
	}

	public static SpellDesc create(EntityReference target, int damage, Predicate<Entity> targetFilter, boolean randomTarget) {
		Map<SpellArg, Object> arguments = new SpellDesc(DamageSpell.class);
		arguments.put(SpellArg.VALUE, damage);
		arguments.put(SpellArg.TARGET, target);
		arguments.put(SpellArg.RANDOM_TARGET, randomTarget);
		if (targetFilter != null) {
			arguments.put(SpellArg.FILTER, targetFilter);
		}
		return new SpellDesc(arguments);
	}

	public static SpellDesc create(EntityReference target, ValueProvider damageModfier) {
		Map<SpellArg, Object> arguments = new SpellDesc(DamageSpell.class);
		arguments.put(SpellArg.VALUE, damageModfier);
		arguments.put(SpellArg.TARGET, target);
		return new SpellDesc(arguments);
	}

	public static SpellDesc create(int damage) {
		return create(null, damage);
	}

	public static SpellDesc create(ValueProvider damageModfier) {
		return create(null, damageModfier);
	}

	@Override
	@Suspendable
	protected void onCast(GameContext context, Player player, SpellDesc desc, Entity source, Entity target) {
		checkArguments(logger, context, source, desc, SpellArg.IGNORE_SPELL_DAMAGE, SpellArg.VALUE);
		if (!(target instanceof Actor)) {
			logger.error("onCast {} {}: Cannot deal damage to non-Actor target {}", context.getGameId(), source, target);
			return;
		}

		int damage = getDamage(context, player, desc, source, target);
		boolean ignoreSpellDamage = desc.getBool(SpellArg.IGNORE_SPELL_DAMAGE);
		if (damage < 0) {
			logger.error("onCast {} {}: Suspicious negative damage call", context.getGameId(), source);
		}
		context.getLogic().damage(player, (Actor) target, damage, source, ignoreSpellDamage);
	}

	public static int getDamage(GameContext context, Player player, SpellDesc desc, Entity source, Entity target) {
		int damage = 0;
		// TODO Rewrite to more accurate way to grab Damage Stack damage.
		if (!desc.containsKey(SpellArg.VALUE) && !context.getDamageStack().isEmpty()) {
			damage = context.getDamageStack().peek();
		} else {
			damage = desc.getValue(SpellArg.VALUE, context, player, target, source, 0);
		}
		return damage;
	}

}
