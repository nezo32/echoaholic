package dev.echoaholic.core.action;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Growable binary sink for one segment body: LEB128 varints, zigzag, big-endian raw floats/doubles and strings through
 * a per-segment palette with inline definitions (the first use writes the text, later uses only its index), so a
 * segment decodes front to back without a separate table.
 */
public final class SegmentOutput {
	/** Longest string (UTF-8 bytes) a segment may hold. */
	public static final int MAX_STRING_BYTES = 65_536;

	private byte[] buf = new byte[256];
	private int size;
	private final Map<String, Integer> palette = new HashMap<>();

	public int size() {
		return size;
	}

	/** Distinct strings defined so far. */
	public int paletteSize() {
		return palette.size();
	}

	/** Copy of the bytes written. */
	public byte[] toByteArray() {
		return Arrays.copyOf(buf, size);
	}

	public void writeByte(int b) {
		ensure(1);
		buf[size++] = (byte) b;
	}

	public void writeBytes(byte[] bytes) {
		ensure(bytes.length);
		System.arraycopy(bytes, 0, buf, size, bytes.length);
		size += bytes.length;
	}

	public void writeBoolean(boolean v) {
		writeByte(v ? 1 : 0);
	}

	/** Unsigned LEB128 of the int's 32 bits (negative values take 5 bytes; prefer zigzag for signed data). */
	public void writeVarInt(int v) {
		while ((v & ~0x7F) != 0) {
			writeByte((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		writeByte(v);
	}

	/** Unsigned LEB128 of the long's 64 bits. */
	public void writeVarLong(long v) {
		while ((v & ~0x7FL) != 0) {
			writeByte((int) (v & 0x7F) | 0x80);
			v >>>= 7;
		}
		writeByte((int) v);
	}

	public void writeZigZagInt(int v) {
		writeVarInt((v << 1) ^ (v >> 31));
	}

	public void writeZigZagLong(long v) {
		writeVarLong((v << 1) ^ (v >> 63));
	}

	public void writeFloat(float v) {
		writeInt(Float.floatToRawIntBits(v));
	}

	public void writeDouble(double v) {
		long bits = Double.doubleToRawLongBits(v);
		writeInt((int) (bits >>> 32));
		writeInt((int) bits);
	}

	private void writeInt(int v) {
		ensure(4);
		buf[size++] = (byte) (v >>> 24);
		buf[size++] = (byte) (v >>> 16);
		buf[size++] = (byte) (v >>> 8);
		buf[size++] = (byte) v;
	}

	/**
	 * Palette string: varint 0 + UTF-8 length + bytes defines the next palette entry; varint i &gt; 0 references entry
	 * i - 1. Strings longer than {@link #MAX_STRING_BYTES} in UTF-8 are rejected.
	 */
	public void writeString(String s) {
		Integer index = palette.get(s);
		if (index != null) {
			writeVarInt(index + 1);
			return;
		}
		byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
		if (utf8.length > MAX_STRING_BYTES) throw new IllegalArgumentException("string too long: " + utf8.length + " bytes");
		palette.put(s, palette.size());
		writeVarInt(0);
		writeVarInt(utf8.length);
		writeBytes(utf8);
	}

	private void ensure(int extra) {
		if (size + extra > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
	}
}
