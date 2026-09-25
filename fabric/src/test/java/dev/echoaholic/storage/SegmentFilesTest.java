package dev.echoaholic.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.stream.RingBuffer;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.SegmentWriter;

class SegmentFilesTest {
	@TempDir
	Path tmp;

	/** A real sealed segment covering [start, start + ticks) with one move per tick. */
	private static byte[] segment(long start, int ticks) {
		SegmentWriter w = new SegmentWriter(start, ticks);
		for (long t = start; t < start + ticks; t++) w.append(t, new Move(t * 0.1, 64, 0, 0, 0), List.of());
		return w.seal();
	}

	/** Writes segment {@code seq} and returns its meta. */
	private static SegmentMeta write(Path dir, long seq, long start, int ticks) throws IOException {
		byte[] bytes = segment(start, ticks);
		SegmentFiles.writeAtomic(SegmentFiles.segmentPath(dir, seq), bytes);
		return new SegmentMeta(seq, start, start + ticks, bytes.length);
	}

	private static void writeIndex(Path dir, List<SegmentMeta> metas) throws IOException {
		SegmentFiles.writeAtomic(SegmentFiles.indexPath(dir), SegmentFiles.encodeIndex(metas));
	}

	@Test
	void layout() {
		UUID owner = UUID.fromString("0f0e0d0c-0b0a-0908-0706-050403020100");
		Path dir = SegmentFiles.ownerDir(tmp, owner);
		assertEquals(tmp.resolve("0f0e0d0c-0b0a-0908-0706-050403020100"), dir);
		assertEquals(dir.resolve("42.seg"), SegmentFiles.segmentPath(dir, 42));
		assertEquals(dir.resolve("index.bin"), SegmentFiles.indexPath(dir));
		assertThrows(IllegalArgumentException.class, () -> SegmentFiles.segmentPath(dir, -1));
	}

	@Test
	void parseSeq() {
		assertEquals(0, SegmentFiles.parseSeq("0.seg"));
		assertEquals(1234567, SegmentFiles.parseSeq("1234567.seg"));
		assertEquals(-1, SegmentFiles.parseSeq("index.bin"));
		assertEquals(-1, SegmentFiles.parseSeq("12.seg.tmp"));
		assertEquals(-1, SegmentFiles.parseSeq(".seg"));
		assertEquals(-1, SegmentFiles.parseSeq("-3.seg"));
		assertEquals(-1, SegmentFiles.parseSeq("1a.seg"));
		assertEquals(-1, SegmentFiles.parseSeq("1234567890123456789.seg"));
	}

	@Test
	void indexRoundTrip() throws IOException {
		List<SegmentMeta> metas = List.of(
				new SegmentMeta(3, 0, 1200, 900),
				new SegmentMeta(4, 1200, 2400, 1_000_000),
				new SegmentMeta(9, 5000, 5001, 0),
				new SegmentMeta(1L << 40, 1L << 45, (1L << 45) + 1200, 17));
		assertEquals(metas, SegmentFiles.decodeIndex(SegmentFiles.encodeIndex(metas)));
		assertEquals(List.of(), SegmentFiles.decodeIndex(SegmentFiles.encodeIndex(List.of())));
	}

	@Test
	void indexIsCompact() {
		List<SegmentMeta> metas = new ArrayList<>();
		for (int i = 0; i < 360; i++) metas.add(new SegmentMeta(i, i * 1200L, (i + 1) * 1200L, 3000));
		// 6 h of 1-minute segments: header + ~6 bytes per entry + crc
		assertTrue(SegmentFiles.encodeIndex(metas).length < 360 * 7 + 16);
	}

	@Test
	void encodeRejectsUnorderedEntries() {
		assertThrows(IllegalArgumentException.class, () -> SegmentFiles.encodeIndex(List.of(
				new SegmentMeta(2, 0, 10, 1), new SegmentMeta(1, 10, 20, 1))));
		assertThrows(IllegalArgumentException.class, () -> SegmentFiles.encodeIndex(List.of(
				new SegmentMeta(1, 0, 10, 1), new SegmentMeta(2, 5, 20, 1))));
	}

	@Test
	void decodeRejectsEveryCorruption() {
		byte[] good = SegmentFiles.encodeIndex(List.of(new SegmentMeta(1, 0, 1200, 500), new SegmentMeta(2, 1200, 2400, 600)));
		for (int i = 0; i < good.length; i++) {
			for (int bit = 0; bit < 8; bit++) {
				byte[] bad = good.clone();
				bad[i] ^= (byte) (1 << bit);
				assertThrows(IOException.class, () -> SegmentFiles.decodeIndex(bad), "flip byte " + i + " bit " + bit);
			}
		}
		for (int len = 0; len < good.length; len++) {
			byte[] truncated = java.util.Arrays.copyOf(good, len);
			assertThrows(IOException.class, () -> SegmentFiles.decodeIndex(truncated), "length " + len);
		}
	}

	@Test
	void writeAtomicReplacesAndLeavesNoTmp() throws IOException {
		Path target = tmp.resolve("a").resolve("b").resolve("file.bin");
		SegmentFiles.writeAtomic(target, new byte[] {1, 2, 3});
		SegmentFiles.writeAtomic(target, new byte[] {4, 5});
		assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(target));
		try (var files = Files.list(target.getParent())) {
			assertEquals(List.of(target), files.toList());
		}
	}

	@Test
	void readMetaFromHeader() throws IOException {
		SegmentMeta m = write(tmp, 7, 2400, 1200);
		assertEquals(m, SegmentFiles.readMeta(SegmentFiles.segmentPath(tmp, 7), 7));
	}

	@Test
	void recoverMissingFolderIsEmpty() throws IOException {
		SegmentFiles.Recovery r = SegmentFiles.recover(tmp.resolve("nobody"));
		assertEquals(List.of(), r.segments());
		assertFalse(r.rewriteIndex());
	}

	@Test
	void recoverTrustsCleanIndex() throws IOException {
		List<SegmentMeta> metas = List.of(write(tmp, 0, 0, 100), write(tmp, 1, 100, 100), write(tmp, 2, 200, 50));
		writeIndex(tmp, metas);
		SegmentFiles.Recovery r = SegmentFiles.recover(tmp);
		assertEquals(metas, r.segments());
		assertEquals(List.of(), r.problems());
		assertEquals(List.of(), r.obsolete());
		assertFalse(r.rewriteIndex());
		new RingBuffer(r.segments()); // valid ring
	}

	@Test
	void recoverRebuildsMissingOrCorruptIndex() throws IOException {
		List<SegmentMeta> metas = List.of(write(tmp, 5, 0, 100), write(tmp, 6, 100, 100), write(tmp, 8, 300, 100));
		SegmentFiles.Recovery missing = SegmentFiles.recover(tmp);
		assertEquals(metas, missing.segments());
		assertTrue(missing.rewriteIndex());
		assertFalse(missing.problems().isEmpty());

		Files.write(SegmentFiles.indexPath(tmp), new byte[] {'E', 'I', 'D', 'X', 1, 9, 9, 9});
		SegmentFiles.Recovery corrupt = SegmentFiles.recover(tmp);
		assertEquals(metas, corrupt.segments());
		assertTrue(corrupt.rewriteIndex());
	}

	@Test
	void recoverAddsSegmentsWrittenAfterTheIndex() throws IOException {
		SegmentMeta a = write(tmp, 0, 0, 100);
		writeIndex(tmp, List.of(a));
		SegmentMeta b = write(tmp, 1, 100, 100); // crash before the index update
		SegmentFiles.Recovery r = SegmentFiles.recover(tmp);
		assertEquals(List.of(a, b), r.segments());
		assertTrue(r.rewriteIndex());
	}

	@Test
	void recoverDropsIndexedButMissingAndMarksEvictedObsolete() throws IOException {
		write(tmp, 0, 0, 100); // evicted: not in the index, deletion lost in a crash
		SegmentMeta b = write(tmp, 1, 100, 100);
		SegmentMeta c = new SegmentMeta(2, 200, 300, 10); // listed, file never written
		writeIndex(tmp, List.of(b, c));
		Files.write(tmp.resolve("7.seg.tmp"), new byte[] {1});
		SegmentFiles.Recovery r = SegmentFiles.recover(tmp);
		assertEquals(List.of(b), r.segments());
		assertTrue(r.rewriteIndex());
		assertTrue(r.obsolete().contains(SegmentFiles.segmentPath(tmp, 0)));
		assertTrue(r.obsolete().contains(tmp.resolve("7.seg.tmp")));
	}

	@Test
	void recoverDiscardsUnreadableSegments() throws IOException {
		SegmentMeta a = write(tmp, 0, 0, 100);
		Files.write(SegmentFiles.segmentPath(tmp, 1), new byte[] {1, 2, 3, 4});
		SegmentFiles.Recovery r = SegmentFiles.recover(tmp);
		assertEquals(List.of(a), r.segments());
		assertTrue(r.obsolete().contains(SegmentFiles.segmentPath(tmp, 1)));
	}

	@Test
	void recoverPrefersNewerSeqOnOverlap() throws IOException {
		// a clear whose deletion was lost: old seqs 0..2 cover 0..300, the new stream restarted at 0 with seq 3, 4
		write(tmp, 0, 0, 100);
		write(tmp, 1, 100, 100);
		write(tmp, 2, 200, 100);
		SegmentMeta n3 = write(tmp, 3, 0, 100);
		SegmentMeta n4 = write(tmp, 4, 100, 100);
		SegmentFiles.Recovery r = SegmentFiles.recover(tmp);
		assertEquals(List.of(n3, n4), r.segments());
		assertEquals(3, r.obsolete().size());
		new RingBuffer(r.segments());

		// same with the stale index still present
		writeIndex(tmp, List.of(new SegmentMeta(0, 0, 100, 1), new SegmentMeta(1, 100, 200, 1), new SegmentMeta(2, 200, 300, 1)));
		SegmentFiles.Recovery withIndex = SegmentFiles.recover(tmp);
		assertEquals(List.of(n3, n4), withIndex.segments());
		assertTrue(withIndex.rewriteIndex());
	}

	@Test
	void deleteOwnerDirRemovesEverything() throws IOException {
		Path dir = tmp.resolve("owner");
		write(dir, 0, 0, 10);
		writeIndex(dir, List.of());
		SegmentFiles.deleteOwnerDir(dir);
		assertFalse(Files.exists(dir));
		SegmentFiles.deleteOwnerDir(dir); // missing: no error
	}
}
