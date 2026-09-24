package dev.echoaholic.net;

import java.util.Arrays;

import dev.echoaholic.Echoaholic;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: the path one echo will walk in the next 5 seconds (up to {@link #MAX_POINTS} points, one every
 * 5 stream ticks), for the optional client trail. {@code xyz} holds the points as x0,y0,z0,x1,y1,z1,… Only sent to
 * clients that have the {@code echoaholic:trail} channel; vanilla clients get nothing.
 */
public record EchoTrailPayload(int entityId, float[] xyz) implements CustomPacketPayload {
	/** 100 stream ticks sampled every 5 ticks. */
	public static final int MAX_POINTS = 20;

	public static final CustomPacketPayload.Type<EchoTrailPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Echoaholic.MOD_ID, "trail"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EchoTrailPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				int n = p.points();
				buf.writeVarInt(p.entityId);
				buf.writeVarInt(n);
				for (int i = 0; i < n * 3; i++) {
					buf.writeFloat(p.xyz[i]);
				}
			},
			buf -> {
				int id = buf.readVarInt();
				int n = buf.readVarInt();
				if (n < 0 || n > MAX_POINTS) throw new DecoderException("Echo trail has " + n + " points (max " + MAX_POINTS + ")");
				float[] xyz = new float[n * 3];
				for (int i = 0; i < xyz.length; i++) {
					xyz[i] = buf.readFloat();
				}
				return new EchoTrailPayload(id, xyz);
			});

	/** Defensive copy, truncated to whole points and at most {@link #MAX_POINTS}. */
	public EchoTrailPayload {
		int n = Math.min(MAX_POINTS, xyz.length / 3);
		xyz = Arrays.copyOf(xyz, n * 3);
	}

	/** Number of points. */
	public int points() {
		return xyz.length / 3;
	}

	@Override
	public Type<EchoTrailPayload> type() {
		return TYPE;
	}

	/** Called once from Echoaholic#onInitialize (runs on both sides), right after EchoNoticePayload.register(). */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}
}
