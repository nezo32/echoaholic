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
 * 5 stream ticks), for the optional client trail. The points are sent as an exact origin (doubles, normally the
 * echo's position) plus float offsets {@code dx0,dy0,dz0,dx1,…}, so they stay precise at any distance from the world
 * origin. Only sent to clients that have the {@code echoaholic:trail} channel; vanilla clients get nothing.
 */
public record EchoTrailPayload(int entityId, double originX, double originY, double originZ, float[] offsets)
		implements CustomPacketPayload {
	/** 100 stream ticks sampled every 5 ticks. */
	public static final int MAX_POINTS = 20;

	public static final CustomPacketPayload.Type<EchoTrailPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Echoaholic.MOD_ID, "trail"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EchoTrailPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				int n = p.points();
				buf.writeVarInt(p.entityId);
				buf.writeDouble(p.originX);
				buf.writeDouble(p.originY);
				buf.writeDouble(p.originZ);
				buf.writeVarInt(n);
				for (int i = 0; i < n * 3; i++) {
					buf.writeFloat(p.offsets[i]);
				}
			},
			buf -> {
				int id = buf.readVarInt();
				double ox = buf.readDouble();
				double oy = buf.readDouble();
				double oz = buf.readDouble();
				int n = buf.readVarInt();
				if (n < 0 || n > MAX_POINTS) throw new DecoderException("Echo trail has " + n + " points (max " + MAX_POINTS + ")");
				float[] offsets = new float[n * 3];
				for (int i = 0; i < offsets.length; i++) {
					offsets[i] = buf.readFloat();
				}
				return new EchoTrailPayload(id, ox, oy, oz, offsets);
			});

	/** Defensive copy, truncated to whole points and at most {@link #MAX_POINTS}. */
	public EchoTrailPayload {
		int n = Math.min(MAX_POINTS, offsets.length / 3);
		offsets = Arrays.copyOf(offsets, n * 3);
	}

	/** Number of points. */
	public int points() {
		return offsets.length / 3;
	}

	/** Absolute x of point {@code i}. */
	public double x(int i) {
		return originX + offsets[i * 3];
	}

	/** Absolute y of point {@code i}. */
	public double y(int i) {
		return originY + offsets[i * 3 + 1];
	}

	/** Absolute z of point {@code i}. */
	public double z(int i) {
		return originZ + offsets[i * 3 + 2];
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
