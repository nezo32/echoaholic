package dev.echoaholic.replay.handler;

import dev.echoaholic.core.action.Attack;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandler;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Melee hit: the living entity nearest to the recorded target position (within 2 blocks, never the echo itself) takes
 * the recorded damage as a mob attack from the echo. Players get the recorded damage exactly: the difficulty scaling
 * vanilla applies to mob attacks on players is inverted first (on Peaceful players take no damage, as from any mob).
 * Armor, shields and damage cooldowns apply as usual; the owner and other echoes can be hit too. No target in range: SKIPPED (the entity lookup is spent).
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

		DamageSource source = level.damageSources().mobAttack(echo);
		float damage = a.damage();
		if (target instanceof Player && source.scalesWithDifficulty()) {
			damage = unscaleForPlayer(level.getDifficulty(), damage);
		}
		if (damage > 0) target.hurtServer(level, source, damage);

		WorldHandlers.finish(ctx, WorldHandlers.ghost(a.weaponItem()), Activity.FIGHTING);
		return Result.DONE;
	}

	/**
	 * Inverts {@code Player.hurtServer}'s difficulty scaling of mob attacks (identical on 26.2 and 26.3), so a player
	 * takes exactly the recorded damage: easy {@code min(d/2+1, d)}, hard {@code d*1.5}. Peaceful returns 0: no damage,
	 * as in vanilla peaceful, where mobs don't hurt players.
	 */
	static float unscaleForPlayer(Difficulty difficulty, float damage) {
		return switch (difficulty) {
			case PEACEFUL -> 0.0F;
			// min(x/2+1, x) == d: x = d while d <= 2, else x = 2(d-1)
			case EASY -> damage <= 2.0F ? damage : 2.0F * (damage - 1.0F);
			case NORMAL -> damage;
			case HARD -> damage / 1.5F;
		};
	}
}
