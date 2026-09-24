package dev.echoaholic.core.stream;

import java.io.IOException;

/** Storage of one player's sealed segments by sequence number (the disk implementation lives outside core). */
public interface SegmentStore {
	/** Sealed bytes of segment {@code seq}; a missing segment -> {@link java.nio.file.NoSuchFileException}. */
	byte[] read(long seq) throws IOException;

	/** Stores (or replaces) segment {@code seq}. */
	void write(long seq, byte[] bytes) throws IOException;

	/** Removes segment {@code seq}; missing is not an error. */
	void delete(long seq) throws IOException;
}
