package dev.echoaholic.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SegmentIoTest {
	@Test
	void varIntSizesAndRoundTrip() throws IOException {
		int[] values = {0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455, 268_435_456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
		int[] sizes = {1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5};
		for (int i = 0; i < values.length; i++) {
			SegmentOutput out = new SegmentOutput();
			out.writeVarInt(values[i]);
			assertEquals(sizes[i], out.size(), "size of " + values[i]);
			SegmentInput in = new SegmentInput(out.toByteArray());
			assertEquals(values[i], in.readVarInt());
			assertFalse(in.hasRemaining());
		}
	}

	@Test
	void varLongAndZigZag() throws IOException {
		long[] longs = {0, 1, -1, 63, -64, 64, -65, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 35, -(1L << 35), 1_920_000_000L};
		SegmentOutput out = new SegmentOutput();
		for (long v : longs) {
			out.writeVarLong(v);
			out.writeZigZagLong(v);
		}
		int[] ints = {0, 1, -1, 63, -64, 64, -65, Integer.MAX_VALUE, Integer.MIN_VALUE};
		for (int v : ints) out.writeZigZagInt(v);
		SegmentInput in = new SegmentInput(out.toByteArray());
		for (long v : longs) {
			assertEquals(v, in.readVarLong());
			assertEquals(v, in.readZigZagLong());
		}
		for (int v : ints) assertEquals(v, in.readZigZagInt());
		assertFalse(in.hasRemaining());
		SegmentOutput small = new SegmentOutput();
		small.writeZigZagInt(-64);
		small.writeZigZagLong(63);
		assertEquals(2, small.size(), "small signed values take one byte each");
		SegmentOutput max = new SegmentOutput();
		max.writeVarLong(-1);
		assertEquals(10, max.size());
	}

	@Test
	void floatsDoublesBooleans() throws IOException {
		SegmentOutput out = new SegmentOutput();
		out.writeFloat(-1.25f);
		out.writeDouble(Math.PI);
		out.writeBoolean(true);
		out.writeBoolean(false);
		assertEquals(4 + 8 + 2, out.size());
		SegmentInput in = new SegmentInput(out.toByteArray());
		assertEquals(-1.25f, in.readFloat());
		assertEquals(Math.PI, in.readDouble());
		assertEquals(true, in.readBoolean());
		assertEquals(false, in.readBoolean());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {2}).readBoolean());
	}

	@Test
	void malformedInput() {
		assertThrows(IOException.class, () -> new SegmentInput(new byte[0]).readByte());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {(byte) 0x80}).readVarInt());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F}).readVarInt());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1}).readVarInt());
		byte[] elevenBytes = new byte[11];
		java.util.Arrays.fill(elevenBytes, (byte) 0xFF);
		assertThrows(IOException.class, () -> new SegmentInput(elevenBytes).readVarLong());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {1, 2, 3}).readFloat());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[7]).readDouble());
		// string: length beyond data, length beyond limit, bad UTF-8, bad palette index
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {0, 5, 'a'}).readString());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {0, (byte) 0x81, (byte) 0x80, 0x08}).readString());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {0, 2, (byte) 0xC3, 0x28}).readString());
		assertThrows(IOException.class, () -> new SegmentInput(new byte[] {3}).readString());
		assertThrows(IllegalArgumentException.class, () -> new SegmentInput(new byte[2], 1, 3));
	}

	@Test
	void paletteInlineDefinitions() throws IOException {
		SegmentOutput out = new SegmentOutput();
		out.writeString("a");
		out.writeString("b");
		out.writeString("a");
		out.writeString("");
		out.writeString("b");
		byte[] bytes = out.toByteArray();
		// define a, define b, ref 1, define "", ref 2
		assertEquals(3 + 3 + 1 + 2 + 1, bytes.length);
		assertEquals(1, bytes[6]);
		SegmentInput in = new SegmentInput(bytes);
		assertEquals("a", in.readString());
		assertEquals("b", in.readString());
		assertEquals("a", in.readString());
		assertEquals("", in.readString());
		assertEquals("b", in.readString());
		SegmentOutput uni = new SegmentOutput();
		uni.writeString("𝄞ж");
		assertEquals("𝄞ж", new SegmentInput(uni.toByteArray()).readString());
		assertEquals(2 + "𝄞ж".getBytes(StandardCharsets.UTF_8).length, uni.size());
	}

	@Test
	void outputGrows() throws IOException {
		SegmentOutput out = new SegmentOutput();
		for (int i = 0; i < 100_000; i++) out.writeVarInt(i);
		SegmentInput in = new SegmentInput(out.toByteArray());
		for (int i = 0; i < 100_000; i++) assertEquals(i, in.readVarInt());
	}
}
