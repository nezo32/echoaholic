package dev.echoaholic.core.stream;

/**
 * Index entry of a stored segment.
 *
 * @param seq storage sequence number (strictly increasing per player)
 * @param startTick first stream tick covered
 * @param endTick first stream tick NOT covered (exclusive)
 * @param byteSize sealed (compressed) size in bytes
 */
public record SegmentMeta(long seq, long startTick, long endTick, long byteSize) {
	public SegmentMeta {
		if (startTick < 0 || endTick <= startTick) throw new IllegalArgumentException("bad tick range " + startTick + ".." + endTick);
		if (byteSize < 0) throw new IllegalArgumentException("negative size");
	}

	public boolean contains(long tick) {
		return tick >= startTick && tick < endTick;
	}

	public long tickCount() {
		return endTick - startTick;
	}
}
