package dev.echoaholic.core.stream;

import static dev.echoaholic.core.stream.SegmentFormat.*;

import java.util.List;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.ActionTypes;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.SegmentOutput;

/**
 * Encodes one segment covering at most {@code capacity} stream ticks from {@code startTick}. Ticks must be appended in
 * strictly increasing order; the first move of a segment is always a keyframe so every segment decodes on its own.
 * Single use: after {@link #seal} it rejects further calls.
 */
public final class SegmentWriter {
	/** Default segment length: one minute of stream ticks. */
	public static final int SEGMENT_TICKS = 1200;

	private final long startTick;
	private final int capacity;
	private final SegmentOutput body = new SegmentOutput();
	private long lastTick;
	private long lastEntryTick;
	private int entries;
	private boolean hasPrevMove;
	private long px, py, pz;
	private int pyaw, ppitch;
	private boolean sealed;

	public SegmentWriter(long startTick) {
		this(startTick, SEGMENT_TICKS);
	}

	public SegmentWriter(long startTick, int capacity) {
		if (startTick < 0) throw new IllegalArgumentException("negative startTick " + startTick);
		if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
		this.startTick = startTick;
		this.capacity = capacity;
		this.lastTick = startTick - 1;
		this.lastEntryTick = startTick - 1;
	}

	public long startTick() {
		return startTick;
	}

	/** Exclusive end of the full segment: startTick + capacity. */
	public long endTick() {
		return startTick + capacity;
	}

	/** Last tick passed to {@link #append} (startTick - 1 before the first). */
	public long lastTick() {
		return lastTick;
	}

	/** Ticks with content written so far. */
	public int entryCount() {
		return entries;
	}

	/** Uncompressed body bytes so far (the sealed size is typically much smaller). */
	public int bytesSoFar() {
		return body.size();
	}

	public boolean isSealed() {
		return sealed;
	}

	/**
	 * Records one tick. {@code move} may be null (no sample this tick); {@code actions} may be null or empty. A tick
	 * with neither writes nothing but still counts for ordering.
	 */
	public void append(long tick, Move move, List<? extends Action> actions) {
		if (sealed) throw new IllegalStateException("segment already sealed");
		if (tick < startTick || tick >= endTick()) {
			throw new IllegalArgumentException("tick " + tick + " outside [" + startTick + ", " + endTick() + ")");
		}
		if (tick <= lastTick) throw new IllegalArgumentException("tick " + tick + " not after " + lastTick);
		lastTick = tick;
		int actionCount = actions == null ? 0 : actions.size();
		if (move == null && actionCount == 0) return;

		body.writeVarLong(tick - lastEntryTick);
		lastEntryTick = tick;
		entries++;
		int flags = actionCount > 0 ? ACTIONS : 0;
		if (move == null) {
			body.writeByte(flags);
		} else {
			long qx = Move.quantize(move.x()), qy = Move.quantize(move.y()), qz = Move.quantize(move.z());
			int yaw = Move.angleByte(move.yRot()), pitch = Move.angleByte(move.xRot());
			long dx = qx - px, dy = qy - py, dz = qz - pz;
			flags |= MOVE;
			if (!hasPrevMove || Math.abs(dx) > KEYFRAME_DELTA || Math.abs(dy) > KEYFRAME_DELTA || Math.abs(dz) > KEYFRAME_DELTA) {
				body.writeByte(flags | KEYFRAME);
				body.writeZigZagLong(qx);
				body.writeZigZagLong(qy);
				body.writeZigZagLong(qz);
				body.writeByte(yaw);
				body.writeByte(pitch);
			} else {
				boolean rot = yaw != pyaw || pitch != ppitch;
				flags |= (dx != 0 ? DX : 0) | (dy != 0 ? DY : 0) | (dz != 0 ? DZ : 0) | (rot ? ROT : 0);
				body.writeByte(flags);
				if (dx != 0) body.writeZigZagLong(dx);
				if (dy != 0) body.writeZigZagLong(dy);
				if (dz != 0) body.writeZigZagLong(dz);
				if (rot) {
					body.writeByte(yaw);
					body.writeByte(pitch);
				}
			}
			hasPrevMove = true;
			px = qx;
			py = qy;
			pz = qz;
			pyaw = yaw;
			ppitch = pitch;
		}
		if (actionCount > 0) {
			body.writeVarInt(actionCount);
			for (Action a : actions) ActionTypes.write(a, body);
		}
	}

	/** Seals the full segment (tickCount = capacity). */
	public byte[] seal() {
		return seal(endTick());
	}

	/**
	 * Seals a segment covering [startTick, endTick) (a partial one when the stream stops early). {@code endTick} must be
	 * after the last appended tick and at most {@link #endTick()}; the segment must cover at least one tick.
	 */
	public byte[] seal(long endTick) {
		if (sealed) throw new IllegalStateException("segment already sealed");
		if (endTick <= startTick || endTick <= lastTick || endTick > endTick()) {
			throw new IllegalArgumentException("bad endTick " + endTick + " for segment at " + startTick + " (last " + lastTick + ")");
		}
		sealed = true;
		SegmentOutput out = new SegmentOutput();
		out.writeBytes(MAGIC);
		out.writeByte(VERSION);
		out.writeVarLong(startTick);
		out.writeVarInt((int) (endTick - startTick));
		out.writeBytes(body.toByteArray());
		return deflate(out.toByteArray());
	}
}
