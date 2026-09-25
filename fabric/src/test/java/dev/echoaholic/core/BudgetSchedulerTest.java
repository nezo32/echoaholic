package dev.echoaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.BudgetScheduler.EchoBudget;

class BudgetSchedulerTest {
	@Test
	void perEchoCaps() {
		BudgetScheduler s = new BudgetScheduler();
		s.beginTick(128, 2);
		EchoBudget b = s.forEcho("a", 4, 2);
		for (int i = 0; i < 4; i++) assertTrue(b.tryBlockOp());
		assertFalse(b.tryBlockOp());
		assertTrue(b.tryEntityLookup());
		assertTrue(b.tryEntityLookup());
		assertFalse(b.tryEntityLookup());
		assertEquals(4, s.blockOpsUsed());
		assertEquals(2, s.lookupsUsed());
		assertEquals(2, s.deferred());
		assertSame(b, s.forEcho("a", 4, 2), "same echo in the same tick shares its budget");
		assertFalse(s.forEcho("a", 64, 32).tryBlockOp());
		assertTrue(s.forEcho("b", 4, 2).tryBlockOp());
	}

	@Test
	void globalBlockCap() {
		BudgetScheduler s = new BudgetScheduler();
		s.beginTick(10, 2);
		int granted = 0;
		for (String id : List.of("a", "b", "c")) {
			EchoBudget b = s.forEcho(id, 4, 2);
			while (b.tryBlockOp()) granted++;
		}
		assertEquals(10, granted);
		assertEquals(10, s.blockOpsUsed());
		assertEquals(0, s.globalBlockOpsLeft());
		assertEquals(3, s.deferred());
		assertEquals(0, s.forEcho("d", 4, 2).blockOpsLeft());
	}

	@Test
	void hazardConsumesBlockAndHazard() {
		BudgetScheduler s = new BudgetScheduler();
		s.beginTick(128, 2);
		EchoBudget a = s.forEcho("a", 4, 2);
		EchoBudget b = s.forEcho("b", 4, 2);
		assertTrue(a.tryHazardOp());
		assertTrue(b.tryHazardOp());
		assertFalse(a.tryHazardOp(), "global hazard pool is empty");
		assertEquals(2, s.hazardOpsUsed());
		assertEquals(2, s.blockOpsUsed());
		assertEquals(3, a.blockOpsLeft(), "refused hazard op did not take a block op");
		assertTrue(a.tryBlockOp());
		assertTrue(a.tryBlockOp());
		assertTrue(a.tryBlockOp());
		assertFalse(a.tryBlockOp());
		// hazard refused when the echo has no block op left even if the hazard pool has room
		s.beginTick(128, 5);
		EchoBudget c = s.forEcho("c", 1, 1);
		assertTrue(c.tryBlockOp());
		assertFalse(c.tryHazardOp());
		assertEquals(0, s.hazardOpsUsed());
		// hazard refused when the global block pool is empty
		s.beginTick(1, 5);
		assertTrue(s.forEcho("x", 4, 1).tryBlockOp());
		assertFalse(s.forEcho("y", 4, 1).tryHazardOp());
	}

	@Test
	void beginTickResetsPoolsAndBudgets() {
		BudgetScheduler s = new BudgetScheduler();
		s.beginTick(1, 1);
		EchoBudget first = s.forEcho("a", 1, 1);
		assertTrue(first.tryBlockOp());
		assertFalse(first.tryBlockOp());
		assertEquals(1, s.deferred());
		s.beginTick(1, 1);
		assertEquals(0, s.blockOpsUsed());
		assertEquals(0, s.deferred());
		assertEquals(1, s.totalDeferred());
		assertTrue(s.forEcho("a", 1, 1).tryBlockOp());
		s.beginTick(-5, -5);
		assertFalse(s.forEcho("a", 4, 4).tryBlockOp());
		assertFalse(s.forEcho("a", 4, 4).tryHazardOp());
	}

	@Test
	void orderRotatesEachTick() {
		BudgetScheduler s = new BudgetScheduler();
		List<String> echoes = List.of("a", "b", "c");
		assertEquals(echoes, s.order(echoes));
		s.beginTick(1, 1);
		assertEquals(List.of("a", "b", "c"), s.order(echoes));
		s.beginTick(1, 1);
		assertEquals(List.of("b", "c", "a"), s.order(echoes));
		s.beginTick(1, 1);
		assertEquals(List.of("c", "a", "b"), s.order(echoes));
		s.beginTick(1, 1);
		assertEquals(List.of("a", "b", "c"), s.order(echoes));
		assertEquals(List.of(), s.order(List.of()));
		assertEquals(List.of("z"), s.order(List.of("z")));
	}

	@Test
	void orderIntoReusedList() {
		BudgetScheduler s = new BudgetScheduler();
		List<String> echoes = List.of("a", "b", "c");
		List<String> out = new ArrayList<>(List.of("junk"));
		s.beginTick(1, 1);
		s.beginTick(1, 1);
		assertSame(out, s.order(echoes, out));
		assertEquals(List.of("b", "c", "a"), out);
		assertEquals(s.order(echoes), s.order(echoes, out));
		assertEquals(List.of(), s.order(List.<String>of(), out));
	}

	@Test
	void budgetsAreReusedAndRefilledEachTick() {
		BudgetScheduler s = new BudgetScheduler();
		s.beginTick(10, 1);
		EchoBudget a = s.forEcho("a", 1, 1);
		assertTrue(a.tryBlockOp());
		assertTrue(a.tryEntityLookup());
		s.beginTick(10, 1);
		EchoBudget again = s.forEcho("a", 2, 1);
		assertSame(a, again, "the budget object is reused");
		assertEquals(2, again.blockOpsLeft(), "refilled with this tick's caps");
		assertEquals(1, again.lookupsLeft());
		s.forget("a");
		s.beginTick(10, 1);
		assertEquals(2, s.forEcho("a", 2, 1).blockOpsLeft());
	}

	/** 10 greedy echoes, room for 2 per tick: rotation gives every echo exactly the same share. */
	@Test
	void roundRobinIsFair() {
		BudgetScheduler s = new BudgetScheduler();
		List<Integer> echoes = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
		int[] done = new int[10];
		for (int tick = 0; tick < 1000; tick++) {
			s.beginTick(8, 2);
			for (int id : s.order(echoes)) {
				EchoBudget b = s.forEcho(id, 4, 2);
				while (b.tryBlockOp()) done[id]++;
			}
			assertTrue(s.blockOpsUsed() <= 8);
		}
		for (int d : done) assertEquals(800, d);
	}

	private enum Cost {
		FREE, BLOCK, HAZARD, LOOKUP
	}

	private record Act(int echo, int seq, Cost cost) {}

	/**
	 * Replay contract simulation: each echo walks its queue in order, stalling on a refusal. Every action executes
	 * exactly once, in order, and no tick exceeds any cap.
	 */
	@Test
	void deferredActionsAreNeverDropped() {
		Random rnd = new Random(42);
		int echoes = 12;
		List<ArrayDeque<Act>> queues = new ArrayList<>();
		int total = 0;
		for (int e = 0; e < echoes; e++) {
			ArrayDeque<Act> q = new ArrayDeque<>();
			int n = 200 + rnd.nextInt(400);
			for (int i = 0; i < n; i++) q.add(new Act(e, i, Cost.values()[rnd.nextInt(4)]));
			queues.add(q);
			total += n;
		}
		List<Integer> ids = new ArrayList<>();
		for (int e = 0; e < echoes; e++) ids.add(e);
		int[] nextSeq = new int[echoes];
		int executed = 0;
		BudgetScheduler s = new BudgetScheduler();
		int ticks = 0;
		while (executed < total) {
			assertTrue(++ticks < 100_000, "stuck");
			s.beginTick(16, 2);
			int hazards = 0;
			for (int e : s.order(ids)) {
				EchoBudget b = s.forEcho(e, 4, 2);
				ArrayDeque<Act> q = queues.get(e);
				int blocksThisEcho = 0;
				while (!q.isEmpty()) {
					Act a = q.peek();
					boolean ok = switch (a.cost()) {
						case FREE -> true;
						case BLOCK -> b.tryBlockOp();
						case HAZARD -> b.tryHazardOp();
						case LOOKUP -> b.tryEntityLookup();
					};
					if (!ok) break; // cursor stalls; the action stays pending
					q.poll();
					assertEquals(nextSeq[e]++, a.seq(), "out of order");
					executed++;
					if (a.cost() == Cost.BLOCK || a.cost() == Cost.HAZARD) blocksThisEcho++;
					if (a.cost() == Cost.HAZARD) hazards++;
				}
				assertTrue(blocksThisEcho <= 4);
			}
			assertTrue(s.blockOpsUsed() <= 16);
			assertTrue(hazards <= 2);
			assertEquals(hazards, s.hazardOpsUsed());
		}
		for (int e = 0; e < echoes; e++) assertTrue(queues.get(e).isEmpty());
		assertTrue(s.totalDeferred() > 0, "the simulation must actually exercise deferral");
	}
}
