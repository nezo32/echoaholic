package dev.echoaholic.core.stream;

import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** {@link SegmentStore} kept in memory (tests, tools). Stores copies. */
public final class InMemorySegmentStore implements SegmentStore {
	private final TreeMap<Long, byte[]> segments = new TreeMap<>();

	@Override
	public byte[] read(long seq) throws NoSuchFileException {
		byte[] bytes = segments.get(seq);
		if (bytes == null) throw new NoSuchFileException("segment " + seq);
		return Arrays.copyOf(bytes, bytes.length);
	}

	@Override
	public void write(long seq, byte[] bytes) {
		segments.put(seq, Arrays.copyOf(bytes, bytes.length));
	}

	@Override
	public void delete(long seq) {
		segments.remove(seq);
	}

	/** Stored sequence numbers, ascending. */
	public List<Long> seqs() {
		return List.copyOf(segments.keySet());
	}

	public long totalBytes() {
		return segments.values().stream().mapToLong(b -> b.length).sum();
	}
}
