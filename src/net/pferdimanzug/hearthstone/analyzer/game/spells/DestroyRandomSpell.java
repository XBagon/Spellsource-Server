package net.pferdimanzug.hearthstone.analyzer.game.spells;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.pferdimanzug.hearthstone.analyzer.game.GameContext;
import net.pferdimanzug.hearthstone.analyzer.game.Player;
import net.pferdimanzug.hearthstone.analyzer.game.entities.Actor;

public class DestroyRandomSpell extends DestroySpell {
	
	@Override
	public void cast(GameContext context, Player player, List<Actor> targets) {
		Actor randomTarget = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
		onCast(context, player, randomTarget);
	}

}