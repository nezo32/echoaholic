package dev.echoaholic.mixin;

import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls the private {@code Mannequin.setHideDescription}, which removes the "Mannequin" line under an echo's name. */
@Mixin(Mannequin.class)
public interface MannequinInvoker {
	@Invoker("setHideDescription")
	void echoaholic$setHideDescription(boolean hide);
}
