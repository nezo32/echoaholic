package dev.echoaholic.client;

import dev.echoaholic.Feedback;
import dev.echoaholic.core.NotifySettings;
import dev.echoaholic.net.EchoNoticePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Receives {@link EchoNoticePayload} and shows the actionbar message / plays the chime according to
 * {@link NotifyConfig}. JOINED: message + sound; FADED: message only.
 */
public final class NotifyClient {
	private NotifyClient() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(EchoNoticePayload.TYPE,
				(payload, ctx) -> handle(payload.kind(), payload.echoIndex(), ctx.player()));
	}

	/** Shows / plays according to NotifyConfig.get(). Public so the client gametest can call it directly. */
	public static void handle(int kind, int echoIndex, LocalPlayer player) {
		if (player == null) return;
		NotifySettings settings = NotifyConfig.get();
		if (kind == EchoNoticePayload.JOINED) {
			if (settings.message()) {
				player.sendOverlayMessage(Feedback.joinedMessage(echoIndex));
			}
			if (settings.sound()) {
				player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE,
						SoundSource.PLAYERS, Feedback.VOLUME, Feedback.PITCH, false);
			}
		} else if (kind == EchoNoticePayload.FADED) {
			if (settings.message()) {
				player.sendOverlayMessage(Feedback.fadedMessage(echoIndex));
			}
		}
	}
}
