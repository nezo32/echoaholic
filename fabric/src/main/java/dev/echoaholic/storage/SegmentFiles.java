package dev.echoaholic.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32;

import dev.echoaholic.core.action.SegmentInput;
import dev.echoaholic.core.action.SegmentOutput;
import dev.echoaholic.core.stream.SegmentHeader;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.SegmentReader;

/**
 * On-disk layout of the recorded streams, plus the file operations {@link StreamStore} runs on its IO thread (and, for
 * {@link #recover}, once per player on the server thread). No Minecraft classes: unit-tested on its own.
 *
 * <pre>
 * &lt;world&gt;/data/echoaholic/streams/&lt;uuid&gt;/&lt;seq&gt;.seg   one sealed segment ({@link SegmentReader} format)
 * &lt;world&gt;/data/echoaholic/streams/&lt;uuid&gt;/index.bin   ring buffer index (below)
 * </pre>
 *
 * <p>Every write goes to {@code <name>.tmp}, is forced to disk, then moved over the target with
 * {@link StandardCopyOption#ATOMIC_MOVE} + {@link StandardCopyOption#REPLACE_EXISTING}, so a crash leaves either the old
 * or the new file, never a torn one.
 *
 * <p>Index layout: magic "EIDX", version byte, entry count (varint), then per entry (varlongs, ascending seq): seq delta
 * (the first entry absolute), gap between this startTick and the previous endTick (the first entry: absolute
 * startTick), tick count, byte size; finally a big-endian CRC32 of everything before it.
 */
public final class SegmentFiles {
	public static final String SEGMENT_SUFFIX = ".seg";
	public static final String INDEX_FILE = "index.bin";
	public static final String TMP_SUFFIX = ".tmp";

	static final byte[] INDEX_MAGIC = {'E', 'I', 'D', 'X'};
	static final int INDEX_VERSION = 1;
	/** More entries than any retention can produce (24 h of 1-tick segments would be absurd anyway). */
	static final int MAX_INDEX_ENTRIES = 1 << 20;
	private static final int CRC_BYTES = 4;

	private SegmentFiles() {}

	/** Folder of one player's stream: {@code streamsDir/<uuid>}. */
	public static Path ownerDir(Path streamsDir, UUID owner) {
		return streamsDir.resolve(owner.toString());
	}

	/** {@code ownerDir/<seq>.seg}. */
	public static Path segmentPath(Path ownerDir, long seq) {
		if (seq < 0) throw new IllegalArgumentException("negative seq " + seq);
		return ownerDir.resolve(seq + SEGMENT_SUFFIX);
	}

	/** {@code ownerDir/index.bin}. */
	public static Path indexPath(Path ownerDir) {
		return ownerDir.resolve(INDEX_FILE);
	}

	/** The seq of a segment file name ({@code "<digits>.seg"}), or -1 for any other name. */
	public static long parseSeq(String fileName) {
		if (!fileName.endsWith(SEGMENT_SUFFIX)) return -1;
		String digits = fileName.substring(0, fileName.length() - SEGMENT_SUFFIX.length());
		if (digits.isEmpty() || digits.length() > 18) return -1;
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			if (c < '0' || c > '9') return -1;
		}
		return Long.parseLong(digits);
	}

	/** Writes {@code bytes} to {@code target} atomically (tmp file, fsync, atomic rename), creating parent folders. */
	public static void writeAtomic(Path target, byte[] bytes) throws IOException {
		Path parent = target.toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
		Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
		try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE)) {
			ByteBuffer buf = ByteBuffer.wrap(bytes);
			while (buf.hasRemaining()) ch.write(buf);
			ch.force(true);
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Deletes a file; a missing file is not an error. */
	public static void delete(Path file) throws IOException {
		Files.deleteIfExists(file);
	}

	/** Deletes every file of a player's folder, then the folder itself; a missing folder is not an error. */
	public static void deleteOwnerDir(Path ownerDir) throws IOException {
		if (!Files.isDirectory(ownerDir)) return;
		IOException failure = null;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(ownerDir)) {
			for (Path f : files) {
				try {
					Files.deleteIfExists(f);
				} catch (IOException e) {
					if (failure == null) failure = e;
				}
			}
		} catch (NoSuchFileException e) {
			return;
		}
		if (failure != null) throw failure;
		try {
			Files.deleteIfExists(ownerDir);
		} catch (java.nio.file.DirectoryNotEmptyException e) {
			// a file was written concurrently by someone else: keep the folder
		}
	}

	/** Encodes a ring index. The entries must be ordered as a {@link dev.echoaholic.core.stream.RingBuffer} holds them. */
	public static byte[] encodeIndex(List<SegmentMeta> segments) {
		SegmentOutput out = new SegmentOutput();
		out.writeBytes(INDEX_MAGIC);
		out.writeByte(INDEX_VERSION);
		out.writeVarInt(segments.size());
		long prevSeq = 0;
		long prevEnd = 0;
		boolean first = true;
		for (SegmentMeta m : segments) {
			if (!first && (m.seq() <= prevSeq || m.startTick() < prevEnd)) {
				throw new IllegalArgumentException("index entries out of order at seq " + m.seq());
			}
			out.writeVarLong(first ? m.seq() : m.seq() - prevSeq);
			out.writeVarLong(first ? m.startTick() : m.startTick() - prevEnd);
			out.writeVarLong(m.tickCount());
			out.writeVarLong(m.byteSize());
			prevSeq = m.seq();
			prevEnd = m.endTick();
			first = false;
		}
		byte[] body = out.toByteArray();
		CRC32 crc = new CRC32();
		crc.update(body);
		return ByteBuffer.allocate(body.length + CRC_BYTES).put(body).putInt((int) crc.getValue()).array();
	}

	/** Decodes {@link #encodeIndex} output. Truncation, a bad checksum, magic, version or order -> IOException. */
	public static List<SegmentMeta> decodeIndex(byte[] bytes) throws IOException {
		if (bytes.length < INDEX_MAGIC.length + 1 + 1 + CRC_BYTES) throw new IOException("index too short");
		int bodyLength = bytes.length - CRC_BYTES;
		CRC32 crc = new CRC32();
		crc.update(bytes, 0, bodyLength);
		if ((int) crc.getValue() != ByteBuffer.wrap(bytes, bodyLength, CRC_BYTES).getInt()) {
			throw new IOException("index checksum mismatch");
		}
		SegmentInput in = new SegmentInput(bytes, 0, bodyLength);
		for (byte b : INDEX_MAGIC) {
			if (in.readByte() != (b & 0xFF)) throw new IOException("not an echo index (bad magic)");
		}
		int version = in.readByte();
		if (version != INDEX_VERSION) throw new IOException("unsupported index version " + version);
		int count = in.readVarInt();
		if (count < 0 || count > MAX_INDEX_ENTRIES) throw new IOException("bad index entry count " + count);
		List<SegmentMeta> out = new ArrayList<>(Math.min(count, 4096));
		long seq = 0;
		long end = 0;
		for (int i = 0; i < count; i++) {
			long seqPart = in.readVarLong();
			long startPart = in.readVarLong();
			long ticks = in.readVarLong();
			long size = in.readVarLong();
			if (i > 0 && seqPart < 1) throw new IOException("index seq not increasing");
			if (seqPart < 0 || startPart < 0 || ticks < 1 || size < 0) throw new IOException("bad index entry " + i);
			long nextSeq = i == 0 ? seqPart : seq + seqPart;
			long start = i == 0 ? startPart : end + startPart;
			if (nextSeq < seq || start < end || start > Long.MAX_VALUE - ticks) throw new IOException("index overflow");
			seq = nextSeq;
			end = start + ticks;
			out.add(new SegmentMeta(seq, start, end, size));
		}
		if (in.hasRemaining()) throw new IOException("trailing bytes in index");
		return out;
	}

	/** Reads a segment file's header into its index entry (the byte size is the file size). */
	public static SegmentMeta readMeta(Path segmentFile, long seq) throws IOException {
		byte[] bytes = Files.readAllBytes(segmentFile);
		SegmentHeader h = SegmentReader.header(bytes);
		return new SegmentMeta(seq, h.startTick(), h.endTick(), bytes.length);
	}

	/**
	 * Result of {@link #recover}.
	 *
	 * @param segments the ring to restore, oldest first (valid for {@link dev.echoaholic.core.stream.RingBuffer})
	 * @param obsolete files that belong to no retained segment (evicted or superseded segments, unreadable segments,
	 *        leftover {@code .tmp} files); safe to delete
	 * @param problems human-readable notes for a warning (empty when the index was clean and complete)
	 * @param rewriteIndex whether index.bin is missing, damaged or out of date and should be written again
	 */
	public record Recovery(List<SegmentMeta> segments, List<Path> obsolete, List<String> problems, boolean rewriteIndex) {}

	/**
	 * Reconstructs one player's ring from their folder, surviving a crash at any point:
	 * <ul>
	 * <li>index.bin is trusted for the segments it lists whose file exists (a missing file is dropped);</li>
	 * <li>segment files newer than the index's last seq (written, but the index update was lost) are added from their
	 * headers; files at or below it that the index does not list were evicted and are obsolete;</li>
	 * <li>a missing or damaged index is rebuilt from the headers of every segment file;</li>
	 * <li>unreadable segments are obsolete. When segments overlap in stream time (a {@code clear} whose deletion did not
	 * finish before a crash, then new recording), the higher seq (newer) wins.</li>
	 * </ul>
	 */
	public static Recovery recover(Path ownerDir) throws IOException {
		List<Path> obsolete = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		if (!Files.isDirectory(ownerDir)) return new Recovery(List.of(), obsolete, problems, false);

		TreeMap<Long, Path> files = new TreeMap<>();
		try (DirectoryStream<Path> dir = Files.newDirectoryStream(ownerDir)) {
			for (Path f : dir) {
				String name = f.getFileName().toString();
				if (name.endsWith(TMP_SUFFIX)) {
					obsolete.add(f);
					continue;
				}
				long seq = parseSeq(name);
				if (seq >= 0 && Files.isRegularFile(f)) files.put(seq, f);
			}
		}

		List<SegmentMeta> indexed = null;
		Path indexFile = indexPath(ownerDir);
		if (Files.isRegularFile(indexFile)) {
			try {
				indexed = decodeIndex(Files.readAllBytes(indexFile));
			} catch (IOException e) {
				problems.add("index unreadable (" + e.getMessage() + "), rebuilt from segment headers");
			}
		} else if (!files.isEmpty()) {
			problems.add("index missing, rebuilt from segment headers");
		}

		List<SegmentMeta> candidates = new ArrayList<>();
		boolean rewrite = indexed == null && !files.isEmpty();
		long lastIndexed = -1;
		Set<Long> indexedSeqs = new HashSet<>();
		if (indexed != null) {
			for (SegmentMeta m : indexed) {
				lastIndexed = Math.max(lastIndexed, m.seq());
				indexedSeqs.add(m.seq());
				if (files.containsKey(m.seq())) {
					candidates.add(m);
				} else {
					problems.add("segment " + m.seq() + " listed in the index is missing");
					rewrite = true;
				}
			}
		}
		for (var e : files.entrySet()) {
			long seq = e.getKey();
			if (indexedSeqs.contains(seq)) continue;
			if (indexed != null && seq <= lastIndexed) {
				obsolete.add(e.getValue());
				continue;
			}
			try {
				candidates.add(readMeta(e.getValue(), seq));
				rewrite = true;
				if (indexed != null) problems.add("segment " + seq + " was not in the index, recovered from its header");
			} catch (IOException ex) {
				problems.add("segment " + seq + " unreadable (" + ex.getMessage() + ")");
				obsolete.add(e.getValue());
				rewrite = true;
			}
		}

		// newest first: keep a segment only when it ends before everything newer that was kept
		candidates.sort(Comparator.comparingLong(SegmentMeta::seq).reversed());
		List<SegmentMeta> kept = new ArrayList<>(candidates.size());
		long floor = Long.MAX_VALUE;
		for (SegmentMeta m : candidates) {
			if (m.endTick() <= floor) {
				kept.add(m);
				floor = m.startTick();
			} else {
				problems.add("segment " + m.seq() + " overlaps newer data, discarded");
				Path f = files.get(m.seq());
				if (f != null) obsolete.add(f);
				rewrite = true;
			}
		}
		kept.sort(Comparator.comparingLong(SegmentMeta::seq));
		return new Recovery(List.copyOf(kept), List.copyOf(obsolete), List.copyOf(problems), rewrite);
	}

	/** File name for logs: the owner folder and file, e.g. {@code <uuid>/12.seg}. */
	static String describe(Path file) {
		Path parent = file.getParent();
		return parent == null ? file.toString() : parent.getFileName() + "/" + file.getFileName();
	}
}
