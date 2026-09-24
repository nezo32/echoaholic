package dev.echoaholic.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/** Makes a monster hunt echoes like players (needs line of sight). Added by {@link EchoTargeting}. */
public class EchoTargetGoal extends NearestAttackableTargetGoal<EchoEntity> {
	public EchoTargetGoal(Mob mob) {
		super(mob, EchoEntity.class, true);
	}
}
