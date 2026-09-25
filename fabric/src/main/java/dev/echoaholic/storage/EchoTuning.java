package dev.echoaholic.storage;

import dev.echoaholic.core.stream.SegmentWriter;

/**
 * Tick-level overrides of the minute/hour based settings, so gametests can run an echo schedule in seconds instead of
 * minutes. Production never sets them: every field is 0 ("no override") and the values come from
 * {@link dev.echoaholic.core.EchoConfig}.
 *
 * <p>The fields are static and volatile because they are process-wide (the integrated server reuses the JVM) and are
 * written by the test thread while the server thread reads them. Tests must call {@link #reset()} when done.
 */
public final class EchoTuning {
	/**
	 * Echo delay in ticks, replacing {@code delayMinutes * 1200}; 0 = no override. Must be at least
	 * {@link #segmentTicks()} so an echo never replays a segment that is still open.
	 */
	public static volatile long delayTicksOverride;

	/** Ticks per recorded segment, replacing {@link SegmentWriter#SEGMENT_TICKS}; 0 (or negative) = no override. */
	public static volatile int segmentTicksOverride;

	/** Ring buffer retention in ticks, replacing {@code bufferHours * 72000}; 0 = no override. */
	public static volatile long bufferTicksOverride;

	private EchoTuning() {}

	/** Ticks per segment: the override when set, else {@link SegmentWriter#SEGMENT_TICKS} (one minute). */
	public static int segmentTicks() {
		int override = segmentTicksOverride;
		return override > 0 ? override : SegmentWriter.SEGMENT_TICKS;
	}

	/** Clears every override (production values again). */
	public static void reset() {
		delayTicksOverride = 0;
		segmentTicksOverride = 0;
		bufferTicksOverride = 0;
	}
}
