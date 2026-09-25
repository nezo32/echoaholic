package dev.echoaholic.record;

import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.core.action.Death;
import dev.echoaholic.util.Ids;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Capture points that Fabric API already provides. Block breaks: {@code AFTER} fires after the block was removed and
 * before durability and drops, so the main-hand stack is still the tool that was used. Deaths: players only.
 */
public final class RecordHooks {
	private RecordHooks() {}

	public static void register() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!(player instanceof ServerPlayer sp) || !EchoCapture.recording(sp)) return;
			EchoCapture.capture(sp, new BlockBreak(pos.getX(), pos.getY(), pos.getZ(), Ids.state(state),
					Ids.heldItem(sp.getMainHandItem())));
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer sp) EchoCapture.capture(sp, Death.INSTANCE);
		});
	}
}
