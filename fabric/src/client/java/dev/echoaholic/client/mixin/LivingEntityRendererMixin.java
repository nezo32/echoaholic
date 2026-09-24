package dev.echoaholic.client.mixin;

import dev.echoaholic.client.EchoRenderTint;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Echo cyan, translucent model: the model tint (ARGB) is multiplied into the skin, and PlayerModel's
 * entity-translucent render type honours its alpha. The name tag and item/armor layers are not affected.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "getModelTint(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)I",
			at = @At("HEAD"), cancellable = true)
	private void echoaholic$tint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
		Integer tint = ((FabricRenderState) state).getData(EchoRenderTint.TINT);
		if (tint != null) cir.setReturnValue(tint);
	}
}
