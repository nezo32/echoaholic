package dev.echoaholic.core.stream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Index of one player's stored segments, oldest first. Pure: the MC side deletes the evicted segments from disk.
 *
 * <p>Retention: at stream tick {@code now} the window is {@code [now - retention, now)}; a segment is evicted only when
 * all of its ticks are before the window ({@code endTick <= now - retention}), so a segment touching the border stays.
 * The newest segment is never evicted.
 */
public final class RingBuffer {
	private final List<SegmentMeta> segments = new ArrayList<>();
	private long totalBytes;
	private long floorTick;

	public RingBuffer() {}

	/** Restores an index (validated as by {@link #add}). */
	public RingBuffer(Collection<SegmentMeta> restored) {
		for (SegmentMeta m : restored) add(m);
	}

	/**
	 * Appends the newest segment. Its seq must be greater and its startTick not before the previous segment's endTick
	 * (gaps are allowed, e.g. after a failed write).
	 */
	public void add(SegmentMeta meta) {
		if (!segments.isEmpty()) {
			SegmentMeta last = segments.getLast();
			if (meta.seq() <= last.seq()) throw new IllegalArgumentException("seq " + meta.seq() + " not after " + last.seq());
			if (meta.startTick() < last.endTick()) {
				throw new IllegalArgumentException("segment at " + meta.startTick() + " overlaps previous ending " + last.endTick());
			}
		} else if (meta.startTick() < floorTick) {
			throw new IllegalArgumentException("segment at " + meta.startTick() + " before evicted data ending " + floorTick);
		}
		segments.add(meta);
		totalBytes += meta.byteSize();
	}

	/** Evicts segments entirely older than {@code now - retentionTicks}, keeping the newest; returns them oldest first. */
	public List<SegmentMeta> evict(long nowTick, long retentionTicks) {
		long cutoff = nowTick - retentionTicks;
		List<SegmentMeta> evicted = new ArrayList<>();
		while (segments.size() > 1 && segments.getFirst().endTick() <= cutoff) {
			SegmentMeta m = segments.removeFirst();
			totalBytes -= m.byteSize();
			floorTick = m.endTick();
			evicted.add(m);
		}
		return evicted;
	}

	/** First tick still held; when empty, the end of the last evicted segment (0 for a fresh buffer). */
	public long oldestRetainedTick() {
		return segments.isEmpty() ? floorTick : segments.getFirst().startTick();
	}

	/** Exclusive end of the newest segment (same as {@link #oldestRetainedTick()} when empty). */
	public long newestEndTick() {
		return segments.isEmpty() ? floorTick : segments.getLast().endTick();
	}

	public long totalBytes() {
		return totalBytes;
	}

	public int size() {
		return segments.size();
	}

	public boolean isEmpty() {
		return segments.isEmpty();
	}

	/** Snapshot, oldest first. */
	public List<SegmentMeta> segments() {
		return List.copyOf(segments);
	}

	/** The segment containing {@code tick} (binary search). */
	public Optional<SegmentMeta> segmentFor(long tick) {
		int i = floorByStart(tick);
		if (i < 0) return Optional.empty();
		SegmentMeta m = segments.get(i);
		return m.contains(tick) ? Optional.of(m) : Optional.empty();
	}

	/** The segment containing {@code tick}, else the first one starting after it (skips gaps). */
	public Optional<SegmentMeta> segmentAtOrAfter(long tick) {
		int i = floorByStart(tick);
		if (i >= 0 && segments.get(i).contains(tick)) return Optional.of(segments.get(i));
		return i + 1 < segments.size() ? Optional.of(segments.get(i + 1)) : Optional.empty();
	}

	/** Index of the last segment with startTick &lt;= tick, or -1. */
	private int floorByStart(long tick) {
		int lo = 0, hi = segments.size() - 1, found = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (segments.get(mid).startTick() <= tick) {
				found = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return found;
	}
}
