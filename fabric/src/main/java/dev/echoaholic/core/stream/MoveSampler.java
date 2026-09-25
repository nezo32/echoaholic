package dev.echoaholic.core.stream;

import dev.echoaholic.core.action.Move;

/**
 * Decides on which ticks a move sample is stored: when the position moved more than {@link #MIN_DISTANCE} blocks or the
 * yaw/pitch changed more than {@link #MIN_ANGLE} degrees since the last stored sample, when {@link #HEARTBEAT_TICKS}
 * ticks passed without one, and on the first call after construction, {@link #forceNext()} or {@link #reset()}.
 */
public final class MoveSampler {
	public static final double MIN_DISTANCE = 0.05;
	public static final float MIN_ANGLE = 2f;
	public static final int HEARTBEAT_TICKS = 20;

	private Move last;
	private int sinceLast;
	private boolean force = true;

	/** Call once per recorded tick with the current move; true -&gt; store it this tick. */
	public boolean sample(Move current) {
		sinceLast++;
		boolean emit = force || last == null || sinceLast >= HEARTBEAT_TICKS
				|| current.distanceTo(last) > MIN_DISTANCE
				|| Math.abs(Move.angleDelta(last.yRot(), current.yRot())) > MIN_ANGLE
				|| Math.abs(Move.angleDelta(last.xRot(), current.xRot())) > MIN_ANGLE;
		if (emit) {
			last = current;
			sinceLast = 0;
			force = false;
		}
		return emit;
	}

	/** The next {@link #sample} call emits (segment start, teleport). */
	public void forceNext() {
		force = true;
	}

	/** Forgets the last sample; the next call emits. */
	public void reset() {
		last = null;
		sinceLast = 0;
		force = true;
	}
}
