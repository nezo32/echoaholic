package dev.echoaholic.core.action;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reader for {@link SegmentOutput} data. Truncated or malformed input -> IOException, never garbage. */
public final class SegmentInput {
	private final byte[] data;
	private final int end;
	private int pos;
	private final List<String> palette = new ArrayList<>();

	public SegmentInput(byte[] data) {
		this(data, 0, data.length);
	}

	public SegmentInput(byte[] data, int offset, int end) {
		if (offset < 0 || end > data.length || offset > end) throw new IllegalArgumentException("bad range");
		this.data = data;
		this.pos = offset;
		this.end = end;
	}

	public boolean hasRemaining() {
		return pos < end;
	}

	public int position() {
		return pos;
	}

	public int readByte() throws IOException {
		if (pos >= end) throw new IOException("unexpected end of segment data");
		return data[pos++] & 0xFF;
	}

	public boolean readBoolean() throws IOException {
		int b = readByte();
		if (b > 1) throw new IOException("bad boolean " + b);
		return b == 1;
	}

	public int readVarInt() throws IOException {
		int result = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			int b = readByte();
			if (shift == 28 && (b & 0xF0) != 0) throw new IOException("varint too long");
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) return result;
		}
		throw new IOException("varint too long");
	}

	public long readVarLong() throws IOException {
		long result = 0;
		for (int shift = 0; shift < 70; shift += 7) {
			int b = readByte();
			if (shift == 63 && (b & 0xFE) != 0) throw new IOException("varlong too long");
			result |= (long) (b & 0x7F) << shift;
			if ((b & 0x80) == 0) return result;
		}
		throw new IOException("varlong too long");
	}

	public int readZigZagInt() throws IOException {
		int v = readVarInt();
		return (v >>> 1) ^ -(v & 1);
	}

	public long readZigZagLong() throws IOException {
		long v = readVarLong();
		return (v >>> 1) ^ -(v & 1);
	}

	public float readFloat() throws IOException {
		return Float.intBitsToFloat(readInt());
	}

	public double readDouble() throws IOException {
		long hi = readInt() & 0xFFFFFFFFL;
		long lo = readInt() & 0xFFFFFFFFL;
		return Double.longBitsToDouble((hi << 32) | lo);
	}

	private int readInt() throws IOException {
		if (end - pos < 4) throw new IOException("unexpected end of segment data");
		int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
		pos += 4;
		return v;
	}

	/** Palette string (see {@link SegmentOutput#writeString}). */
	public String readString() throws IOException {
		int ref = readVarInt();
		if (ref != 0) {
			if (ref < 0 || ref > palette.size()) throw new IOException("bad palette index " + ref + " (size " + palette.size() + ")");
			return palette.get(ref - 1);
		}
		int len = readVarInt();
		if (len < 0 || len > SegmentOutput.MAX_STRING_BYTES) throw new IOException("bad string length " + len);
		if (end - pos < len) throw new IOException("unexpected end of segment data");
		String s;
		try {
			s = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(data, pos, len)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("bad UTF-8 in segment string", e);
		}
		pos += len;
		palette.add(s);
		return s;
	}
}
