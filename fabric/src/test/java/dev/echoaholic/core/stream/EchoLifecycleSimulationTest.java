package dev.echoaholic.core.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.EchoSchedule;
import dev.echoaholic.core.EchoSchedule.EchoSlot;
import dev.echoaholic.core.EchoSchedule.SpawnPlan;
import dev.echoaholic.core.action.Move;

/**
 * Recorder + ring buffer + schedule over several simulated hours with relogs (partial segments): echoes only read
 * sealed, retained data; fresh echoes are never retired by the next eviction; only cap retirements (oldest first) and a
 * deliberately stalled echo's behind-buffer retirement happen.
 */
class EchoLifecycleSimulationTest {
	@Test
	void echoesAlwaysReadSealedRetainedData() {
		long retention = EchoConfig.TICKS_PER_HOUR;
		long delay = EchoConfig.TICKS_PER_MINUTE;
		int max = 64;
		int stalledIndex = 3;
		StreamRecorder recorder = new StreamRecorder(0, 0);
		RingBuffer buffer = new RingBuffer();
		List<EchoSlot> live = new ArrayList<>();
		Set<Integer> everRetired = new HashSet<>();
		int next = 1;
		int capRetirements = 0;
		long stalledRetiredAt = -1;
		Move still = new Move(0, 64, 0, 0, 0);
		for (int step = 0; step < 220_000; step++) {
			recorder.record(still, null, List.of()).ifPresent(s -> buffer.add(s.meta()));
			if (step % 37_003 == 37_002) recorder.flush().ifPresent(s -> buffer.add(s.meta())); // relog
			long t = recorder.tick();
			buffer.evict(t, retention);

			SpawnPlan plan = EchoSchedule.plan(t, next, delay, max, buffer.oldestRetainedTick(), retention, live);
			List<EchoSlot> sorted = new ArrayList<>(live);
			sorted.sort(Comparator.comparingInt(EchoSlot::index));
			for (int index : plan.retire()) {
				assertTrue(everRetired.add(index));
				EchoSlot slot = live.stream().filter(s -> s.index() == index).findFirst().orElseThrow();
				if (EchoSchedule.isBehindBuffer(slot.cursor(), buffer.oldestRetainedTick())) {
					assertEquals(stalledIndex, index, "only the stalled echo may fall behind the buffer");
					stalledRetiredAt = t;
				} else {
					assertTrue(plan.spawn() && live.size() == max, "cap retirement only when a spawn needs the slot");
					assertEquals(sorted.getFirst().index() == stalledIndex && stalledRetiredAt < 0
							? sorted.get(1).index() : sorted.getFirst().index(), index, "oldest first");
					capRetirements++;
				}
			}
			live.removeIf(s -> plan.retire().contains(s.index()));
			if (plan.spawn()) {
				assertEquals(next, plan.newIndex());
				live.add(new EchoSlot(plan.newIndex(), plan.newCursor()));
				next++;
			}
			assertTrue(live.size() <= max);
			for (EchoSlot s : live) {
				assertTrue(s.cursor() < t, "cursor behind the live stream");
				assertTrue(buffer.segmentFor(s.cursor()).isPresent(), "echo " + s.index() + " at " + s.cursor() + " has sealed data");
				assertTrue(EchoSchedule.lagTicks(t, s.cursor()) >= delay, "lag at least one delay");
			}
			live.replaceAll(s -> s.index() == stalledIndex ? s : new EchoSlot(s.index(), s.cursor() + 1));
		}
		assertTrue(stalledRetiredAt > 0, "stalled echo retired once its data was evicted");
		assertTrue(stalledRetiredAt >= retention && stalledRetiredAt <= retention + 2 * delay, "at " + stalledRetiredAt);
		assertTrue(capRetirements > 100);
		assertTrue(next > 180);
	}
}
