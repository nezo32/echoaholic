package dev.echoaholic.core.stream;

/** Header of a sealed segment: it covers stream ticks [startTick, startTick + tickCount). */
public record SegmentHeader(int version, long startTick, int tickCount) {
	public long endTick() {
		return startTick + tickCount;
	}
}
