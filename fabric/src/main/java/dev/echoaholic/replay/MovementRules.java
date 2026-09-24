package dev.echoaholic.replay;

/** Movement and timing constants of the replay loop, plus the small reach / cheap-mode math (no Minecraft types). */
public final class MovementRules {
	/** The echo reached its target when the horizontal distance is at most this (blocks). */
	public static final double REACH_HORIZONTAL = 1.0;
	/** ... and the vertical distance is at most this (blocks). */
	public static final double REACH_VERTICAL = 2.5;
	/** Distance to the next target beyond which the echo teleports instead of walking. */
	public static final double TELEPORT_DISTANCE = 8.0;
	/** Ticks an echo may fail to reach its target before it teleports there (if the target has room). */
	public static final int STUCK_TELEPORT_TICKS = 100;
	/** Ticks an entity may sit in a non-ticking chunk before it is discarded (its state is kept). */
	public static final int UNLOADED_DISCARD_TICKS = 200;
	/** How often (ticks) the cheap-mode distance is re-evaluated. */
	public static final int CHEAP_CHECK_INTERVAL = 20;
	/** Extra distance needed to enter cheap mode, so an echo at the border does not flip every check. */
	public static final int CHEAP_HYSTERESIS = 8;
	/** A world action this recent (ticks) decides the listed activity. */
	public static final int ACTIVITY_WINDOW = 40;
	/** Trail payloads are sent this often (ticks). */
	public static final int TRAIL_INTERVAL = 10;
	/** Modded players within this many blocks get trail payloads. */
	public static final double TRAIL_RANGE = 48.0;
	/** Most points in one trail payload. */
	public static final int TRAIL_POINTS = 20;
	/** Stream ticks between two trail points (20 points x 5 = the next 5 seconds). */
	public static final int TRAIL_STEP = 5;
	/** Movement below this (blocks per tick) counts as standing still. */
	public static final double MOVE_EPSILON = 0.01;

	private MovementRules() {}

	/** True iff an echo at offset (dx, dy, dz) from its target counts as having reached it. */
	public static boolean reached(double dx, double dy, double dz) {
		return dx * dx + dz * dz <= REACH_HORIZONTAL * REACH_HORIZONTAL && Math.abs(dy) <= REACH_VERTICAL;
	}

	/** True iff the squared distance to the next target is a jump (teleport rather than walk). */
	public static boolean isJump(double distanceSq) {
		return distanceSq > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
	}

	/**
	 * Next cheap-mode flag. A normal echo becomes cheap when the nearest player is farther than
	 * {@code distance + CHEAP_HYSTERESIS}; a cheap one returns to normal once a player is within {@code distance}.
	 *
	 * @param nearestSq squared distance to the nearest player in the same level ({@link Double#MAX_VALUE} for none)
	 */
	public static boolean nextCheap(boolean cheap, double nearestSq, int distance) {
		double limit = cheap ? distance : distance + CHEAP_HYSTERESIS;
		return nearestSq > limit * limit;
	}
}
