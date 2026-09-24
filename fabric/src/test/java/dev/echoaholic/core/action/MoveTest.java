package dev.echoaholic.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class MoveTest {
	@Test
	void quantizationError() {
		Random rnd = new Random(1);
		for (int i = 0; i < 100_000; i++) {
			double v = (rnd.nextDouble() - 0.5) * 6.0e7;
			assertTrue(Math.abs(Move.dequantize(Move.quantize(v)) - v) <= 1.0 / 128 + 1e-9, "v=" + v);
			float a = (rnd.nextFloat() - 0.5f) * 7200f;
			float back = Move.angleFromByte(Move.angleByte(a));
			assertTrue(Math.abs(Move.angleDelta(a, back)) <= 360.0 / 512 + 1e-3, "a=" + a);
			assertTrue(back >= -180f && back < 180f);
		}
	}

	@Test
	void angleHelpers() {
		assertEquals(0, Move.angleByte(0f));
		assertEquals(64, Move.angleByte(90f));
		assertEquals(128, Move.angleByte(180f));
		assertEquals(128, Move.angleByte(-180f));
		assertEquals(192, Move.angleByte(-90f));
		assertEquals(0, Move.angleByte(720f));
		assertEquals(-180f, Move.angleFromByte(128));
		assertEquals(-90f, Move.angleFromByte(192));
		assertEquals(2f, Move.angleDelta(179f, -179f), 1e-4);
		assertEquals(-2f, Move.angleDelta(-179f, 179f), 1e-4);
		assertEquals(180f, Move.angleDelta(0f, 180f), 1e-4);
		assertEquals(10f, Move.angleDelta(355f, 5f), 1e-4);
	}

	@Test
	void validation() {
		assertThrows(IllegalArgumentException.class, () -> new Move(Double.NaN, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Move(0, Double.POSITIVE_INFINITY, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Move(0, 0, 2e9, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Move(0, 0, 0, Float.NaN, 0));
		assertThrows(IllegalArgumentException.class, () -> new Move(0, 0, 0, 0, Float.NEGATIVE_INFINITY));
		new Move(-Move.MAX_ABS_COORD, Move.MAX_ABS_COORD, 0, 1e30f, -1e30f);
	}

	@Test
	void quantizedAndDistance() {
		Move m = new Move(1.004, 64.01, -2.5, 91f, -45.5f);
		Move q = m.quantized();
		assertEquals(1.0, q.x());
		assertEquals(64.015625, q.y());
		assertEquals(-2.5, q.z());
		assertEquals(q, q.quantized());
		assertEquals(5.0, new Move(0, 0, 0, 0, 0).distanceTo(new Move(3, 4, 0, 90, 0)), 1e-12);
	}
}
