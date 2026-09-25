package dev.echoaholic.core.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class RingBufferTest {
	private static RingBuffer minutes(int count) {
		RingBuffer rb = new RingBuffer();
		for (int i = 0; i < count; i++) rb.add(new SegmentMeta(i, i * 1200L, (i + 1) * 1200L, 100 + i));
		return rb;
	}

	@Test
	void emptyBuffer() {
		RingBuffer rb = new RingBuffer();
		assertTrue(rb.isEmpty());
		assertEquals(0, rb.oldestRetainedTick());
		assertEquals(0, rb.newestEndTick());
		assertEquals(0, rb.totalBytes());
		assertTrue(rb.segmentFor(0).isEmpty());
		assertTrue(rb.segmentAtOrAfter(0).isEmpty());
		assertEquals(List.of(), rb.evict(1_000_000, 10));
	}

	@Test
	void addValidatesOrder() {
		RingBuffer rb = minutes(2);
		assertThrows(IllegalArgumentException.class, () -> rb.add(new SegmentMeta(1, 2400, 3600, 1)), "seq not increasing");
		assertThrows(IllegalArgumentException.class, () -> rb.add(new SegmentMeta(5, 2399, 3600, 1)), "overlap");
		rb.add(new SegmentMeta(7, 3000, 3100, 1)); // gap and seq jump are fine
		assertEquals(3, rb.size());
		assertThrows(IllegalArgumentException.class, () -> new SegmentMeta(0, 10, 10, 0));
		assertThrows(IllegalArgumentException.class, () -> new SegmentMeta(0, -1, 10, 0));
		assertThrows(IllegalArgumentException.class, () -> new SegmentMeta(0, 0, 10, -1));
	}

	@Test
	void evictionAtTheRetentionBorder() {
		RingBuffer rb = minutes(3); // [0,1200) [1200,2400) [2400,3600)
		assertEquals(List.of(), rb.evict(3600, 2401), "cutoff 1199: first segment still has tick 1199 in the window");
		assertEquals(0, rb.oldestRetainedTick());
		List<SegmentMeta> ev = rb.evict(3600, 2400); // cutoff 1200 == end of the first segment
		assertEquals(List.of(new SegmentMeta(0, 0, 1200, 100)), ev);
		assertEquals(1200, rb.oldestRetainedTick());
		assertEquals(101 + 102, rb.totalBytes());
		assertEquals(2, rb.size());
	}

	@Test
	void evictsOldestFirstAndAlwaysKeepsNewest() {
		RingBuffer rb = minutes(10);
		List<SegmentMeta> ev = rb.evict(12_000, 0);
		assertEquals(9, ev.size());
		for (int i = 0; i < 9; i++) assertEquals(i, ev.get(i).seq());
		assertEquals(1, rb.size());
		assertEquals(9 * 1200, rb.oldestRetainedTick());
		assertEquals(109, rb.totalBytes());
		assertEquals(List.of(), rb.evict(Long.MAX_VALUE / 2, 0), "the newest is never evicted");
		rb.add(new SegmentMeta(10, 12_000, 13_200, 5));
		assertEquals(List.of(new SegmentMeta(9, 10_800, 12_000, 109)), rb.evict(13_200, 1200));
		assertEquals(5, rb.totalBytes());
	}

	@Test
	void retentionOverALongStream() {
		RingBuffer rb = new RingBuffer();
		long retention = 6L * 72_000;
		long evicted = 0;
		for (int i = 0; i < 1000; i++) {
			rb.add(new SegmentMeta(i, i * 1200L, (i + 1) * 1200L, 1000));
			long now = (i + 1) * 1200L;
			evicted += rb.evict(now, retention).size();
			assertTrue(rb.oldestRetainedTick() <= now - Math.min(now, retention), "window fully covered");
			assertTrue(rb.oldestRetainedTick() > now - retention - 1200, "at most one segment beyond the window");
		}
		assertEquals(360, rb.size());
		assertEquals(640, evicted);
		assertEquals(360 * 1000, rb.totalBytes());
	}

	@Test
	void segmentForBinarySearch() {
		Random rnd = new Random(3);
		RingBuffer rb = new RingBuffer();
		List<SegmentMeta> metas = new ArrayList<>();
		long t = 500;
		for (int i = 0; i < 200; i++) {
			if (rnd.nextInt(10) == 0) t += 1 + rnd.nextInt(50); // gap
			long len = 1 + rnd.nextInt(1200);
			SegmentMeta m = new SegmentMeta(i, t, t + len, len);
			metas.add(m);
			rb.add(m);
			t += len;
		}
		int mi = 0;
		for (long tick = 0; tick < t + 10; tick++) {
			while (mi < metas.size() && metas.get(mi).endTick() <= tick) mi++;
			SegmentMeta expected = mi < metas.size() && metas.get(mi).contains(tick) ? metas.get(mi) : null;
			assertEquals(expected, rb.segmentFor(tick).orElse(null), "tick " + tick);
			SegmentMeta atOrAfter = mi < metas.size() ? metas.get(mi) : null;
			assertEquals(atOrAfter, rb.segmentAtOrAfter(tick).orElse(null), "tick " + tick);
		}
		assertEquals(metas, rb.segments());
		assertEquals(metas.getFirst().startTick(), rb.oldestRetainedTick());
		assertEquals(t, rb.newestEndTick());
	}

	@Test
	void restoreFromIndex() {
		RingBuffer rb = minutes(5);
		RingBuffer copy = new RingBuffer(rb.segments());
		assertEquals(rb.segments(), copy.segments());
		assertEquals(rb.totalBytes(), copy.totalBytes());
		List<SegmentMeta> broken = List.of(new SegmentMeta(0, 0, 100, 1), new SegmentMeta(1, 50, 150, 1));
		assertThrows(IllegalArgumentException.class, () -> new RingBuffer(broken));
		assertThrows(UnsupportedOperationException.class, () -> rb.segments().clear());
	}

	@Test
	void inMemoryStore() throws Exception {
		InMemorySegmentStore store = new InMemorySegmentStore();
		assertThrows(java.nio.file.NoSuchFileException.class, () -> store.read(1));
		byte[] data = {1, 2, 3};
		store.write(1, data);
		data[0] = 9;
		assertEquals(1, store.read(1)[0], "stored a copy");
		store.read(1)[1] = 9;
		assertEquals(2, store.read(1)[1], "returned a copy");
		store.write(0, new byte[5]);
		assertEquals(List.of(0L, 1L), store.seqs());
		assertEquals(8, store.totalBytes());
		store.delete(1);
		store.delete(42);
		assertEquals(List.of(0L), store.seqs());
	}
}
