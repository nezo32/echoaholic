package dev.echoaholic;

import dev.echoaholic.net.EchoNoticePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * "Echo #k has joined you" / "Echo #k has faded" on the actionbar, only for the echo's owner.
 *
 * <p>If the owner's client has Echoaholic (it accepts the {@code echoaholic:notice} channel), the server sends only an
 * {@link EchoNoticePayload}, and the client shows the message and plays the sound according to its own notification
 * settings. Otherwise (vanilla client, server-only install) the server sends the overlay message and, for a join, the
 * sound packet itself.
 */
public final class Feedback {
	/** Join chime: {@link SoundEvents#ILLUSIONER_MIRROR_MOVE} (the illusioner making copies of itself). */
	public static final float VOLUME = 0.5F;
	public static final float PITCH = 1.3F;

	private Feedback() {}

	/**
	 * "👥 Echo #7 has joined you": {@code 👥 Echo #7} aqua (the § code in the lang value colors the text before the
	 * number, the number itself is an aqua argument), the rest white.
	 */
	public static Component joinedMessage(int k) {
		// fallback: server-only installs (vanilla clients have no mod lang)
		return Component.translatableWithFallback("echoaholic.message.joined", "§b👥 Echo #%s has joined you", number(k))
				.withStyle(ChatFormatting.WHITE);
	}

	/** "Echo #7 has faded" (the oldest echo was retired at the cap): gray, the number aqua. */
	public static Component fadedMessage(int k) {
		return Component.translatableWithFallback("echoaholic.message.faded", "Echo #%s has faded", number(k))
				.withStyle(ChatFormatting.GRAY);
	}

	/** Builds the message of a notice kind ({@link EchoNoticePayload#JOINED} / {@link EchoNoticePayload#FADED}). */
	public static Component message(int kind, int k) {
		return kind == EchoNoticePayload.FADED ? fadedMessage(k) : joinedMessage(k);
	}

	private static Component number(int k) {
		return Component.literal(String.valueOf(k)).withStyle(ChatFormatting.AQUA);
	}

	/** A new echo of {@code owner} joined the world: message + chime. */
	public static void joined(ServerPlayer owner, int k) {
		send(owner, EchoNoticePayload.JOINED, k, ServerPlayNetworking.canSend(owner, EchoNoticePayload.TYPE));
	}

	/** The oldest echo of {@code owner} was retired at the cap: message only, no sound. */
	public static void faded(ServerPlayer owner, int k) {
		send(owner, EchoNoticePayload.FADED, k, ServerPlayNetworking.canSend(owner, EchoNoticePayload.TYPE));
	}

	/** modded = the client has the echoaholic:notice channel. Public for gametests. */
	public static void send(ServerPlayer player, int kind, int k, boolean modded) {
		if (modded) {
			// the client decides message/sound from its own config
			player.connection.send(ServerPlayNetworking.createClientboundPacket(new EchoNoticePayload(kind, k)));
			return;
		}
		player.sendOverlayMessage(message(kind, k));
		if (kind != EchoNoticePayload.JOINED) return;
		player.connection.send(new ClientboundSoundPacket(
				BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ILLUSIONER_MIRROR_MOVE),
				SoundSource.PLAYERS, player.getX(), player.getY(), player.getZ(),
				VOLUME, PITCH, player.getRandom().nextLong()));
	}
}
