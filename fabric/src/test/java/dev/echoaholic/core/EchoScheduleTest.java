package dev.echoaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.EchoSchedule.EchoSlot;
import dev.echoaholic.core.EchoSchedule.SpawnPlan;

class EchoScheduleTest {
	private static final long DELAY = 6000;
	private static final long RETENTION = 6L * 72_000;

	private static List<EchoSlot> slots(int... indices) {
		List<EchoSlot> out = new ArrayList<>();
		for (int i : indices) out.add(new EchoSlot(i, 1000L * i));
		return out;
	}

	@Test
	void spawnDueExactlyAtMultiples() {
		assertFalse(EchoSchedule.echoSpawnDue(0, 1, DELAY));
		assertFalse(EchoSchedule.echoSpawnDue(5999, 1, DELAY));
		assertTrue(EchoSchedule.echoSpawnDue(6000, 1, DELAY));
		assertFalse(EchoSchedule.echoSpawnDue(17_999, 3, DELAY));
		assertTrue(EchoSchedule.echoSpawnDue(18_000, 3, DELAY));
		assertTrue(EchoSchedule.echoSpawnDue(Long.MAX_VALUE, Integer.MAX_VALUE, 144_000));
		assertFalse(EchoSchedule.echoSpawnDue(1_000_000, Integer.MAX_VALUE, 144_000)); // no int overflow
		assertThrows(IllegalArgumentException.class, () -> EchoSchedule.echoSpawnDue(0, 0, DELAY));
		assertThrows(IllegalArgumentException.class, () -> EchoSchedule.echoSpawnDue(0, 1, 0));
		assertThrows(IllegalArgumentException.class, () -> EchoSchedule.plan(0, 0, DELAY, 32, 0, RETENTION, List.of()));
	}

	@Test
	void firstTickNeverSpawns() {
		SpawnPlan p = EchoSchedule.plan(0, 1, 1, 32, 0, RETENTION, List.of());
		assertFalse(p.spawn());
		assertEquals(List.of(), p.retire());
	}

	@Test
	void lagAndCursorHelpers() {
		assertEquals(6000, EchoSchedule.lagTicks(6000, 0));
		assertTrue(EchoSchedule.isBehindBuffer(99, 100));
		assertFalse(EchoSchedule.isBehindBuffer(100, 100));
	}

	/** Echo k spawns exactly at T == k * delay with cursor 0; cursors then advance with T so lag stays k * delay. */
	@Test
	void echoLagIsIndexTimesDelay() {
		long delay = 1200;
		List<EchoSlot> live = new ArrayList<>();
		int next = 1;
		for (long t = 0; t <= 10 * delay; t++) {
			SpawnPlan p = EchoSchedule.plan(t, next, delay, 32, 0, RETENTION, live);
			assertEquals(List.of(), p.retire());
			if (p.spawn()) {
				assertEquals(next * delay, t, "echo " + next + " spawned late/early");
				assertEquals(next, p.newIndex());
				assertEquals(0, p.newCursor());
				live.add(new EchoSlot(p.newIndex(), p.newCursor()));
				next++;
			}
			for (EchoSlot s : live) assertEquals((long) s.index() * delay, EchoSchedule.lagTicks(t, s.cursor()));
			live.replaceAll(s -> new EchoSlot(s.index(), s.cursor() + 1));
		}
		assertEquals(11, next);
	}

	@Test
	void onlyOneSpawnPerCallCatchUpOnFollowingTicks() {
		long delay = 1200;
		long t = 5 * delay;
		List<EchoSlot> live = new ArrayList<>();
		int next = 1;
		for (int call = 0; call < 5; call++, t++) {
			SpawnPlan p = EchoSchedule.plan(t, next, delay, 32, 0, RETENTION, live);
			assertTrue(p.spawn());
			assertEquals(next, p.newIndex());
			// catch-up echoes still get lag k * delay
			assertEquals(next * delay, t - p.newCursor());
			live.add(new EchoSlot(p.newIndex(), p.newCursor()));
			next++;
			live.replaceAll(s -> new EchoSlot(s.index(), s.cursor() + 1));
		}
		assertFalse(EchoSchedule.plan(t, next, delay, 32, 0, RETENTION, live).spawn());
	}

	@Test
	void delayIncreaseOnlyAffectsFutureSpawns() {
		// echoes 1 and 2 spawned at 6000 and 12000 with delay 6000; at T = 15000 the delay becomes 12000
		List<EchoSlot> live = List.of(new EchoSlot(1, 9000), new EchoSlot(2, 3000));
		for (long t = 15_000; t < 36_000; t++) {
			assertFalse(EchoSchedule.plan(t, 3, 12_000, 32, 0, RETENTION, live).spawn(), "t=" + t);
		}
		SpawnPlan p = EchoSchedule.plan(36_000, 3, 12_000, 32, 0, RETENTION, live);
		assertTrue(p.spawn());
		assertEquals(0, p.newCursor());
		assertEquals(List.of(), p.retire());
	}

	@Test
	void delayDecreaseSpawnsNextWithItsOwnLag() {
		List<EchoSlot> live = List.of(new EchoSlot(1, 9000), new EchoSlot(2, 3000));
		SpawnPlan p = EchoSchedule.plan(15_000, 3, 1200, 32, 0, RETENTION, live);
		assertTrue(p.spawn());
		assertEquals(3, p.newIndex());
		assertEquals(15_000 - 3 * 1200, p.newCursor());
		assertEquals(List.of(), p.retire());
	}

	@Test
	void capRetiresSmallestIndexFirst() {
		List<EchoSlot> live = new ArrayList<>();
		for (int i = 1; i <= 32; i++) live.add(new EchoSlot(i, 0));
		Collections.shuffle(live, new Random(7));
		SpawnPlan p = EchoSchedule.plan(33 * DELAY, 33, DELAY, 32, 0, RETENTION, live);
		assertTrue(p.spawn());
		assertEquals(List.of(1), p.retire());
		assertEquals(List.of(3, 4), EchoSchedule.retirementsForSpawn(slots(9, 4, 3, 12), 3));
		assertEquals(List.of(), EchoSchedule.retirementsForSpawn(slots(9, 4), 3));
		assertEquals(List.of(), EchoSchedule.retirementsForSpawn(List.of(), 1));
		assertEquals(List.of(2, 5), EchoSchedule.retirementsForSpawn(slots(5, 2), 1));
	}

	@Test
	void loweredCapRetiresAllSurplusAtOnce() {
		List<EchoSlot> live = new ArrayList<>();
		for (int i = 1; i <= 32; i++) live.add(new EchoSlot(i, 0));
		// no spawn due: shrink to the cap
		SpawnPlan quiet = EchoSchedule.plan(32 * DELAY + 1, 33, DELAY * 10, 8, 0, RETENTION, live);
		assertFalse(quiet.spawn());
		assertEquals(IntStream.rangeClosed(1, 24).boxed().toList(), quiet.retire());
		// spawn due: one more slot is freed
		SpawnPlan spawning = EchoSchedule.plan(33 * DELAY, 33, DELAY, 8, 0, RETENTION, live);
		assertTrue(spawning.spawn());
		assertEquals(IntStream.rangeClosed(1, 25).boxed().toList(), spawning.retire());
		// a cap below 1 behaves like 1
		SpawnPlan zero = EchoSchedule.plan(33 * DELAY, 33, DELAY, 0, 0, RETENTION, slots(3, 1));
		assertEquals(List.of(1, 3), zero.retire());
		assertTrue(zero.spawn());
	}

	@Test
	void behindBufferRetiredFirstThenCap() {
		List<EchoSlot> live = List.of(new EchoSlot(1, 5), new EchoSlot(2, 100), new EchoSlot(3, 200));
		SpawnPlan p = EchoSchedule.plan(4 * 60, 4, 60, 2, 50, RETENTION, live);
		assertTrue(p.spawn());
		assertEquals(List.of(1, 2), p.retire());
		assertEquals(50, p.newCursor());
		// behind-buffer retirement also happens without a spawn and under the cap
		SpawnPlan q = EchoSchedule.plan(10, 4, 60, 32, 50, RETENTION, live);
		assertFalse(q.spawn());
		assertEquals(List.of(1), q.retire());
		// cursor exactly at the oldest retained tick is fine
		assertEquals(List.of(), EchoSchedule.plan(10, 4, 60, 32, 5, RETENTION, live).retire());
	}

	@Test
	void newCursorStaysInsideRetentionWindow() {
		long t = 10 * RETENTION;
		// buffer still holds a little more than the window (segment granularity)
		SpawnPlan p = EchoSchedule.plan(t, (int) (t / DELAY), DELAY, 32, t - RETENTION - 700, RETENTION, List.of());
		assertTrue(p.spawn());
		assertEquals(t - RETENTION, p.newCursor());
		// buffer holds less than the window (e.g. data lost): start at the oldest retained tick
		SpawnPlan q = EchoSchedule.plan(t, (int) (t / DELAY), DELAY, 32, t - 1000, RETENTION, List.of());
		assertEquals(t - 1000, q.newCursor());
		// nothing replayable: no spawn
		SpawnPlan r = EchoSchedule.plan(t, (int) (t / DELAY), DELAY, 32, t, RETENTION, List.of());
		assertFalse(r.spawn());
	}

	@Test
	void retireListIsImmutable() {
		SpawnPlan p = EchoSchedule.plan(33 * DELAY, 33, DELAY, 1, 0, RETENTION, slots(1, 2));
		assertThrows(UnsupportedOperationException.class, () -> p.retire().add(9));
	}
}
