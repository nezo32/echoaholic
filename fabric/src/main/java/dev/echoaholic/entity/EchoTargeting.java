package dev.echoaholic.entity;

import dev.echoaholic.mixin.MobAccessor;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Monsters target echoes. When a hostile goal-driven mob enters a level it gets an {@link EchoTargetGoal} at the same
 * priority as its own nearest-target goal, so echoes and players are on equal footing.
 *
 * <p>Skipped: neutral mobs (endermen, zombified piglins ...), brain-driven mobs (they ignore goal selectors) and mobs
 * without a nearest-target goal of their own (they don't hunt players either). {@code ENTITY_LOAD} fires again when a
 * chunk is re-tracked, so the goal is added at most once per mob.
 */
public final class EchoTargeting {
	private static final Set<EntityType<?>> BRAIN_MOBS = Set.of(EntityTypes.PIGLIN, EntityTypes.PIGLIN_BRUTE,
			EntityTypes.HOGLIN, EntityTypes.ZOGLIN, EntityTypes.WARDEN, EntityTypes.BREEZE, EntityTypes.CREAKING);

	private EchoTargeting() {}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> addGoal(entity));
	}

	/** Adds the echo target goal to {@code entity} when it qualifies; true when added. */
	public static boolean addGoal(Entity entity) {
		if (!(entity instanceof Mob mob) || !(entity instanceof Enemy) || entity instanceof NeutralMob) return false;
		if (BRAIN_MOBS.contains(entity.getType())) return false;
		GoalSelector targets = ((MobAccessor) mob).echoaholic$targetSelector();
		int priority = Integer.MAX_VALUE;
		for (WrappedGoal wrapped : targets.getAvailableGoals()) {
			if (wrapped.getGoal() instanceof EchoTargetGoal) return false;
			if (wrapped.getGoal() instanceof NearestAttackableTargetGoal<?>) priority = Math.min(priority, wrapped.getPriority());
		}
		if (priority == Integer.MAX_VALUE) return false;
		targets.addGoal(priority, new EchoTargetGoal(mob));
		return true;
	}
}
