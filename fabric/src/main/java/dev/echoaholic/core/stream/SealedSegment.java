package dev.echoaholic.core.stream;

/**
 * A finished segment ready for {@link SegmentStore#write} and {@link RingBuffer#add}. The array is not copied; do not
 * modify it.
 */
public record SealedSegment(long seq, long startTick, long endTick, byte[] bytes) {
	public SegmentMeta meta() {
		return new SegmentMeta(seq, startTick, endTick, bytes.length);
	}
}
