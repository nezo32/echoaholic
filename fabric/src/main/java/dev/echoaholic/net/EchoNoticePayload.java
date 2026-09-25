package dev.echoaholic.net;

import dev.echoaholic.Echoaholic;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: "Echo #k of you joined" ({@link #JOINED}) or "Echo #k faded" ({@link #FADED}). Sent instead of
 * the vanilla overlay + sound packets when the client has Echoaholic, so the client can apply its own settings.
 */
public record EchoNoticePayload(int kind, int echoIndex) implements CustomPacketPayload {
	public static final int JOINED = 0;
	public static final int FADED = 1;

	public static final CustomPacketPayload.Type<EchoNoticePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Echoaholic.MOD_ID, "notice"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EchoNoticePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, EchoNoticePayload::kind,
			ByteBufCodecs.VAR_INT, EchoNoticePayload::echoIndex,
			EchoNoticePayload::new);

	@Override
	public Type<EchoNoticePayload> type() {
		return TYPE;
	}

	/** Called once from Echoaholic#onInitialize (runs on both sides). */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}
}
