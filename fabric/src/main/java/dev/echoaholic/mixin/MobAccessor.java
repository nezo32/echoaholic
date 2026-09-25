package dev.echoaholic.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code Mob.targetSelector} so monsters can get the echo target goal. */
@Mixin(Mob.class)
public interface MobAccessor {
	@Accessor("targetSelector")
	GoalSelector echoaholic$targetSelector();
}
