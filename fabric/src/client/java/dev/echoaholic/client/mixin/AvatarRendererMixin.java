package dev.echoaholic.client.mixin;

import dev.echoaholic.client.EchoRenderTint;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks echo render states with their ghost tint (players and ordinary mannequins get none). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void echoaholic$markEcho(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		FabricRenderState data = (FabricRenderState) state;
		Integer tint = EchoRenderTint.tintFor(entity);
		// states may be reused: clear an old mark, but don't allocate a data map for every player
		if (tint != null || data.getData(EchoRenderTint.TINT) != null) {
			data.setData(EchoRenderTint.TINT, tint);
		}
	}
}
