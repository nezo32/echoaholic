package dev.echoaholic.core.stream;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.Dimension;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Teleport;

/**
 * A decoded segment with random access by stream tick. Immutable; cache it per echo cursor on the MC side.
 *
 * <p>{@link #positionAt} interpolates linearly between the surrounding move samples, except across a jump (the next
 * sample carries a {@link Teleport} / {@link Dimension} action or is more than {@link #JUMP_DISTANCE} blocks away), where
 * it holds the previous sample until the jump tick.
 */
public final class DecodedSegment {
	/** Sample distance (blocks) treated as a jump rather than movement. */
	public static final double JUMP_DISTANCE = 8.0;

	private final long startTick;
	private final long endTick;
	private final List<TickEntry> entries;
	private final long[] entryTicks;
	private final long[] moveTicks;
	private final Move[] moves;
	private final boolean[] jumpTo;

	DecodedSegment(long startTick, long endTick, List<TickEntry> entries) {
		this.startTick = startTick;
		this.endTick = endTick;
		this.entries = List.copyOf(entries);
		this.entryTicks = new long[entries.size()];
		int moveCount = 0;
		for (int i = 0; i < entries.size(); i++) {
			entryTicks[i] = entries.get(i).tick();
			if (entries.get(i).hasMove()) moveCount++;
		}
		this.moveTicks = new long[moveCount];
		this.moves = new Move[moveCount];
		this.jumpTo = new boolean[moveCount];
		int m = 0;
		for (TickEntry e : entries) {
			if (!e.hasMove()) continue;
			moveTicks[m] = e.tick();
			moves[m] = e.move();
			jumpTo[m] = hasJumpAction(e.actions());
			m++;
		}
	}

	private static boolean hasJumpAction(List<Action> actions) {
		for (Action a : actions) {
			if (a instanceof Teleport || a instanceof Dimension) return true;
		}
		return false;
	}

	public long startTick() {
		return startTick;
	}

	/** Exclusive end tick. */
	public long endTick() {
		return endTick;
	}

	public int tickCount() {
		return (int) (endTick - startTick);
	}

	public boolean contains(long tick) {
		return tick >= startTick && tick < endTick;
	}

	/** Every tick with content, ascending. */
	public List<TickEntry> entries() {
		return entries;
	}

	/** What was recorded at {@code tick} (an empty entry when nothing). Tick outside the segment -> IllegalArgumentException. */
	public TickEntry entriesAt(long tick) {
		requireContains(tick);
		int i = Arrays.binarySearch(entryTicks, tick);
		return i >= 0 ? entries.get(i) : TickEntry.empty(tick);
	}

	/** The latest move sample at or before {@code tick} in this segment. */
	public Optional<SampledMove> lastMoveAtOrBefore(long tick) {
		int i = floorIndex(moveTicks, tick);
		return i < 0 ? Optional.empty() : Optional.of(new SampledMove(moveTicks[i], moves[i]));
	}

	/** The first move sample strictly after {@code tick} in this segment. */
	public Optional<SampledMove> firstMoveAfter(long tick) {
		int i = floorIndex(moveTicks, tick) + 1;
		return i >= moves.length ? Optional.empty() : Optional.of(new SampledMove(moveTicks[i], moves[i]));
	}

	/**
	 * Interpolated position and rotation at {@code tick}; empty when the segment has no sample at or before it. After
	 * the last sample the last one is held. Tick outside the segment -> IllegalArgumentException.
	 */
	public Optional<Move> positionAt(long tick) {
		requireContains(tick);
		int i = floorIndex(moveTicks, tick);
		if (i < 0) return Optional.empty();
		Move a = moves[i];
		if (moveTicks[i] == tick || i + 1 >= moves.length) return Optional.of(a);
		Move b = moves[i + 1];
		if (jumpTo[i + 1] || a.distanceTo(b) > JUMP_DISTANCE) return Optional.of(a);
		double t = (tick - moveTicks[i]) / (double) (moveTicks[i + 1] - moveTicks[i]);
		float yaw = wrap(a.yRot() + (float) (Move.angleDelta(a.yRot(), b.yRot()) * t));
		float pitch = (float) (a.xRot() + (b.xRot() - a.xRot()) * t);
		return Optional.of(new Move(lerp(a.x(), b.x(), t), lerp(a.y(), b.y(), t), lerp(a.z(), b.z(), t), yaw, pitch));
	}

	/**
	 * Allocation-free {@link #positionAt(long)}: writes x, y, z, yRot, xRot into {@code out[0..4]} and returns true, or
	 * returns false (out untouched) when the segment has no sample at or before {@code tick}. Tick outside the segment
	 * -> IllegalArgumentException.
	 */
	public boolean positionAt(long tick, double[] out) {
		requireContains(tick);
		int i = floorIndex(moveTicks, tick);
		if (i < 0) return false;
		Move a = moves[i];
		if (moveTicks[i] == tick || i + 1 >= moves.length || jumpTo[i + 1] || a.distanceTo(moves[i + 1]) > JUMP_DISTANCE) {
			out[0] = a.x();
			out[1] = a.y();
			out[2] = a.z();
			out[3] = a.yRot();
			out[4] = a.xRot();
			return true;
		}
		Move b = moves[i + 1];
		double t = (tick - moveTicks[i]) / (double) (moveTicks[i + 1] - moveTicks[i]);
		out[0] = lerp(a.x(), b.x(), t);
		out[1] = lerp(a.y(), b.y(), t);
		out[2] = lerp(a.z(), b.z(), t);
		out[3] = wrap(a.yRot() + (float) (Move.angleDelta(a.yRot(), b.yRot()) * t));
		out[4] = (float) (a.xRot() + (b.xRot() - a.xRot()) * t);
		return true;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static float wrap(float deg) {
		float d = deg % 360f;
		if (d >= 180f) d -= 360f;
		else if (d < -180f) d += 360f;
		return d;
	}

	/** Index of the last element &lt;= key, or -1. */
	private static int floorIndex(long[] sorted, long key) {
		int i = Arrays.binarySearch(sorted, key);
		return i >= 0 ? i : -i - 2;
	}

	private void requireContains(long tick) {
		if (!contains(tick)) throw new IllegalArgumentException("tick " + tick + " outside [" + startTick + ", " + endTick + ")");
	}
}
