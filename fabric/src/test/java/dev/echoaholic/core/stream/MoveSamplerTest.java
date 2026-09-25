package dev.echoaholic.core.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.action.Move;

class MoveSamplerTest {
	private static final Move ORIGIN = new Move(0, 64, 0, 0, 0);

	@Test
	void standingStillEmitsHeartbeatEvery20Ticks() {
		MoveSampler s = new MoveSampler();
		List<Integer> emitted = new ArrayList<>();
		for (int t = 0; t < 101; t++) {
			if (s.sample(ORIGIN)) emitted.add(t);
		}
		assertEquals(List.of(0, 20, 40, 60, 80, 100), emitted);
	}

	@Test
	void distanceThreshold() {
		MoveSampler s = new MoveSampler();
		assertTrue(s.sample(ORIGIN));
		assertFalse(s.sample(new Move(0.04, 64, 0, 0, 0)));
		assertFalse(s.sample(new Move(0.03, 64, 0.03, 0, 0)));
		assertTrue(s.sample(new Move(0.03, 64.03, 0.03, 0, 0)), "0.052 blocks from the last stored sample");
		assertFalse(s.sample(new Move(0.03, 64.03, 0.079, 0, 0)));
		assertTrue(s.sample(new Move(0.03, 64.03, 0.081, 0, 0)));
	}

	@Test
	void slowDriftIsMeasuredFromLastStoredSample() {
		MoveSampler s = new MoveSampler();
		int count = 0;
		for (int t = 0; t < 100; t++) {
			if (s.sample(new Move(t * 0.03, 64, 0, 0, 0))) count++;
		}
		assertEquals(50, count, "every second tick at 0.03 blocks/tick");
	}

	@Test
	void rotationThreshold() {
		MoveSampler s = new MoveSampler();
		assertTrue(s.sample(new Move(0, 64, 0, 179f, 0)));
		assertFalse(s.sample(new Move(0, 64, 0, -179.5f, 0)), "1.5 degrees across the wrap");
		assertTrue(s.sample(new Move(0, 64, 0, -178.5f, 0)), "2.5 degrees across the wrap");
		assertFalse(s.sample(new Move(0, 64, 0, -178.5f, 1.9f)));
		assertTrue(s.sample(new Move(0, 64, 0, -178.5f, 2.1f)));
		assertFalse(s.sample(new Move(0, 64, 0, 181.5f, 2.1f)), "same direction written differently");
	}

	@Test
	void forceAndReset() {
		MoveSampler s = new MoveSampler();
		assertTrue(s.sample(ORIGIN));
		assertFalse(s.sample(ORIGIN));
		s.forceNext();
		assertTrue(s.sample(ORIGIN));
		assertFalse(s.sample(ORIGIN));
		s.reset();
		assertTrue(s.sample(ORIGIN));
		// heartbeat restarts after a forced sample
		for (int i = 1; i < 20; i++) assertFalse(s.sample(ORIGIN), "tick " + i);
		assertTrue(s.sample(ORIGIN));
	}
}
