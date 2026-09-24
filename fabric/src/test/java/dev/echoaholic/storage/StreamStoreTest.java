package dev.echoaholic.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.stream.DecodedSegment;
import dev.echoaholic.core.stream.SealedSegment;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.SegmentWriter;

/** StreamStore against a real temp folder (no Minecraft classes involved). */
class StreamStoreTest {
	private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@TempDir
	Path tmp;
	private StreamStore store;

	@BeforeEach
	void open() {
		store = new StreamStore(tmp);
	}

	@AfterEach
	void closeStore() {
		store.close();
	}

	private static SealedSegment sealed(long seq, long start, int ticks) {
		SegmentWriter w = new SegmentWriter(start, ticks);
		for (long t = start; t < start + ticks; t++) w.append(t, new Move(t, 70, -t, 0, 0), List.of());
		return new SealedSegment(seq, start, start + ticks, w.seal());
	}

	private Path dir() {
		return SegmentFiles.ownerDir(tmp, OWNER);
	}

	private static DecodedSegment await(CompletableFuture<DecodedSegment> f) throws Exception {
		return f.get(10, TimeUnit.SECONDS);
	}

	@Test
	void freshSegmentIsReadableAtOnce() throws Exception {
		store.append(OWNER, sealed(0, 0, 20));
		assertEquals(List.of(new SegmentMeta(0, 0, 20, sealed(0, 0, 20).bytes().length)), store.ring(OWNER).segments());
		CompletableFuture<DecodedSegment> f = store.load(OWNER, 0);
		assertTrue(f.isDone());
		assertEquals(20, f.join().tickCount());
		assertSame(f.join(), store.cached(OWNER, 0));
	}

	@Test
	void recentSegmentsStayInMemoryAfterTheWrite() throws Exception {
		for (int i = 0; i < 6; i++) store.append(OWNER, sealed(i, i * 20L, 20));
		store.flush(true);
		// prove no disk read: remove the files of the newest segments
		for (int i = 2; i < 6; i++) Files.delete(SegmentFiles.segmentPath(dir(), i));
		assertNotNull(store.cached(OWNER, 5)); // decoded from memory without a future
		for (int i = 2; i < 5; i++) {
			CompletableFuture<DecodedSegment> f = store.load(OWNER, i);
			assertTrue(f.isDone() && !f.isCompletedExceptionally(), "seq " + i);
		}
		// older than the last RECENT_PER_OWNER: read from disk
		CompletableFuture<DecodedSegment> old = store.load(OWNER, 1);
		assertEquals(20, await(old).startTick());
	}

	@Test
	void persistsAndReopensIdentically() throws Exception {
		for (int i = 0; i < 5; i++) store.append(OWNER, sealed(i, i * 20L, 20));
		store.flush(true);
		for (int i = 0; i < 5; i++) assertTrue(Files.isRegularFile(SegmentFiles.segmentPath(dir(), i)));
		assertTrue(Files.isRegularFile(SegmentFiles.indexPath(dir())));
		List<SegmentMeta> before = store.ring(OWNER).segments();
		long bytes = store.totalBytes(OWNER);
		store.close();

		store = new StreamStore(tmp);
		assertEquals(before, store.ring(OWNER).segments());
		assertEquals(bytes, store.totalBytes(OWNER));
		assertNull(store.cached(OWNER, 3));
		DecodedSegment d = await(store.load(OWNER, 3));
		assertEquals(60, d.startTick());
		assertEquals(80, d.endTick());
		assertEquals(61.0, d.positionAt(61).orElseThrow().x(), 0.01);
		assertSame(d, store.cached(OWNER, 3));
	}

	@Test
	void evictDeletesFilesAndUpdatesIndex() throws Exception {
		for (int i = 0; i < 4; i++) store.append(OWNER, sealed(i, i * 20L, 20));
		List<SegmentMeta> evicted = store.evict(OWNER, 80, 40);
		assertEquals(List.of(0L, 1L), evicted.stream().map(SegmentMeta::seq).toList());
		store.flush(true);
		assertFalse(Files.exists(SegmentFiles.segmentPath(dir(), 0)));
		assertFalse(Files.exists(SegmentFiles.segmentPath(dir(), 1)));
		List<SegmentMeta> kept = store.ring(OWNER).segments();
		store.close();

		store = new StreamStore(tmp);
		assertEquals(kept, store.ring(OWNER).segments());
		assertEquals(40, store.ring(OWNER).oldestRetainedTick());
	}

	@Test
	void wipeDeletesOldFilesButKeepsLaterAppends() throws Exception {
		store.append(OWNER, sealed(0, 0, 20));
		store.append(OWNER, sealed(1, 20, 20));
		store.wipe(OWNER);
		assertTrue(store.ring(OWNER).isEmpty());
		assertNull(store.cached(OWNER, 0));
		store.append(OWNER, sealed(2, 0, 20)); // stream restarts at T=0, seq keeps counting
		store.flush(true);
		assertFalse(Files.exists(SegmentFiles.segmentPath(dir(), 0)));
		assertFalse(Files.exists(SegmentFiles.segmentPath(dir(), 1)));
		assertTrue(Files.exists(SegmentFiles.segmentPath(dir(), 2)));
		store.close();

		store = new StreamStore(tmp);
		assertEquals(List.of(2L), store.ring(OWNER).segments().stream().map(SegmentMeta::seq).toList());
	}

	@Test
	void wipeWithNothingAfterRemovesTheFolder() {
		store.append(OWNER, sealed(0, 0, 20));
		store.wipe(OWNER);
		store.flush(true);
		assertFalse(Files.exists(dir()));
	}

	@Test
	void missingSegmentLoadFails() {
		CompletableFuture<DecodedSegment> f = store.load(OWNER, 99);
		ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
		assertTrue(e.getCause() instanceof IOException, String.valueOf(e.getCause()));
		assertSame(f, store.load(OWNER, 99)); // stays failed: a gap for the replay loop
		assertNull(store.cached(OWNER, 99));
	}

	@Test
	void corruptIndexIsRebuiltFromHeaders() throws Exception {
		for (int i = 0; i < 3; i++) store.append(OWNER, sealed(i, i * 20L, 20));
		store.flush(true);
		List<SegmentMeta> before = store.ring(OWNER).segments();
		store.close();
		Files.write(SegmentFiles.indexPath(dir()), new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});

		store = new StreamStore(tmp);
		assertEquals(before, store.ring(OWNER).segments());
		store.flush(true);
		assertEquals(before, SegmentFiles.decodeIndex(Files.readAllBytes(SegmentFiles.indexPath(dir()))));
	}

	@Test
	void segmentNotFittingTheRingIsDropped() {
		store.append(OWNER, sealed(5, 0, 20));
		store.append(OWNER, sealed(4, 20, 20)); // seq going backwards
		store.append(OWNER, sealed(6, 10, 20)); // overlapping ticks
		assertEquals(List.of(5L), store.ring(OWNER).segments().stream().map(SegmentMeta::seq).toList());
	}

	@Test
	void closedStoreDropsWritesAndFailsLoads() throws Exception {
		store.append(OWNER, sealed(0, 0, 20));
		store.close();
		store.append(OWNER, sealed(1, 20, 20)); // ring updated, write dropped with a warning
		assertTrue(store.load(OWNER, 1).isCompletedExceptionally());
		assertNotNull(store.load(OWNER, 0).getNow(null)); // written before close, still in memory
		assertNotNull(store.ring(OWNER));
		store.close(); // idempotent
	}
}
