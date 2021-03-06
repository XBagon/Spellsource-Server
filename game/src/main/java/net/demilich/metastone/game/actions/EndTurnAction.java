package net.demilich.metastone.game.actions;

import co.paralleluniverse.fibers.Suspendable;
import net.demilich.metastone.game.GameContext;
import net.demilich.metastone.game.entities.Entity;
import net.demilich.metastone.game.targeting.TargetSelection;

import java.util.Collections;
import java.util.List;

/**
 * This action ends the player's current turn.
 * <p>
 * Sometimes, the action is not available. This is typically due to a pending {@link DiscoverAction} or {@link
 * BattlecryAction}.
 */
public class EndTurnAction extends GameAction {

	public EndTurnAction() {
		setActionType(ActionType.END_TURN);
		setTargetRequirement(TargetSelection.NONE);
	}

	@Override
	@Suspendable
	public void execute(GameContext context, int playerId) {
		context.endTurn();
	}

	@Override
	public String toString() {
		return String.format("[%s]", getActionType());
	}

	@Override
	public Entity getSource(GameContext context) {
		return context.getActivePlayer().getHero();
	}

	@Override
	public List<Entity> getTargets(GameContext context, int player) {
		return Collections.emptyList();
	}

	@Override
	public String getDescription(GameContext context, int playerId) {
		return String.format("%s ended their turn.", context.getActivePlayer().getName());
	}
}
