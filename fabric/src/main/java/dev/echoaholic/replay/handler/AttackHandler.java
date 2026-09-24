package dev.echoaholic.replay.handler;

import dev.echoaholic.core.action.Attack;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandler;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Melee hit: the living entity nearest to the recorded target position (within 2 blocks, never the echo itself) takes
 * the recorded damage as a mob attack from the echo. Armor, shields and damage cooldowns apply as usual; the owner and
 * other echoes can be hit too. No target in range: SKIPPED (the entity lookup is spent).
 */
final class AttackHandler implements ReplayHandler<Attack> {
	static final double REACH = 2.0;

	@Override
	public Result apply(ReplayContext ctx, Attack a) {
		if (!(a.damage() > 0) || !Float.isFinite(a.damage())) return Result.SKIPPED;

		if (!ctx.budget().tryEntityLookup()) return Result.WAIT;

		ServerLevel level = ctx.level();
		EchoEntity echo = ctx.echo();
		AABB box = new AABB(a.tx() - REACH, a.ty() - REACH, a.tz() - REACH, a.tx() + REACH, a.ty() + REACH, a.tz() + REACH);
		List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
				e -> e != echo && e.isAlive() && !e.isSpectator() && e.distanceToSqr(a.tx(), a.ty(), a.tz()) <= REACH * REACH);
		LivingEntity target = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : candidates) {
			double d = e.distanceToSqr(a.tx(), a.ty(), a.tz());
			if (d < best) {
				best = d;
				target = e;
			}
		}
		if (target == null) return Result.SKIPPED;

		target.hurtServer(level, level.damageSources().mobAttack(echo), a.damage());

		WorldHandlers.finish(ctx, WorldHandlers.ghost(a.weaponItem()), Activity.FIGHTING);
		return Result.DONE;
	}
}
