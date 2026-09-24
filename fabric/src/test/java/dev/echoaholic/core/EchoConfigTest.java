package dev.echoaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.EchoConfig.Key;
import dev.echoaholic.core.EchoConfig.Kind;

class EchoConfigTest {
	@Test
	void defaults() {
		EchoConfig d = EchoConfig.DEFAULT;
		assertFalse(d.enabled());
		assertEquals(5, d.delayMinutes());
		assertEquals(32, d.maxEchoes());
		assertEquals(6, d.bufferHours());
		assertEquals(4, d.echoBlockOpsPerTick());
		assertEquals(2, d.echoEntityLookupsPerTick());
		assertEquals(128, d.globalBlockOpsPerTick());
		assertEquals(2, d.globalHazardOpsPerTick());
		assertEquals(128, d.cheapModeDistance());
		assertFalse(d.triggerBlocks());
		assertTrue(d.freeTnt());
		assertFalse(d.paused());
		assertEquals(6000, d.delayTicks());
		assertEquals(432_000, d.bufferTicks());
		assertEquals(d, d.clamped());
		assertEquals(64, EchoConfig.MAX_ECHOES_CAP);
	}

	@Test
	void clampedLowAndHigh() {
		EchoConfig low = new EchoConfig(true, 0, -5, 0, 0, -1, 0, 0, 0, true, false, true).clamped();
		assertEquals(new EchoConfig(true, 1, 1, 1, 1, 1, 1, 1, 16, true, false, true), low);
		EchoConfig high = new EchoConfig(false, 999, 999, 999, 999, 999, 99_999, 999, 99_999, false, true, false).clamped();
		assertEquals(new EchoConfig(false, 120, 64, 24, 64, 32, 4096, 64, 1024, false, true, false), high);
		EchoConfig extreme = new EchoConfig(false, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
				Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, false, false, false);
		assertEquals(1200, extreme.delayTicks());
		assertEquals(72_000, extreme.bufferTicks());
		assertEquals(120 * 1200, high.delayTicks());
		assertEquals(24 * 72_000, high.bufferTicks());
	}

	@Test
	void withersCopyAndClamp() {
		EchoConfig d = EchoConfig.DEFAULT;
		assertEquals(120, d.withDelayMinutes(500).delayMinutes());
		assertEquals(1, d.withDelayMinutes(0).delayMinutes());
		assertEquals(64, d.withMaxEchoes(65).maxEchoes());
		assertEquals(8, d.withMaxEchoes(8).maxEchoes());
		assertEquals(24, d.withBufferHours(25).bufferHours());
		assertEquals(64, d.withEchoBlockOpsPerTick(100).echoBlockOpsPerTick());
		assertEquals(32, d.withEchoEntityLookupsPerTick(100).echoEntityLookupsPerTick());
		assertEquals(4096, d.withGlobalBlockOpsPerTick(1 << 20).globalBlockOpsPerTick());
		assertEquals(1, d.withGlobalHazardOpsPerTick(0).globalHazardOpsPerTick());
		assertEquals(16, d.withCheapModeDistance(1).cheapModeDistance());
		assertTrue(d.withEnabled(true).enabled());
		assertTrue(d.withTriggerBlocks(true).triggerBlocks());
		assertFalse(d.withFreeTnt(false).freeTnt());
		assertTrue(d.withPaused(true).paused());
		assertEquals(5, d.withEnabled(true).delayMinutes());
		assertEquals(EchoConfig.DEFAULT, d); // untouched
	}

	@Test
	void keysCoverEveryComponentWithMatchingIds() {
		Set<String> components = new HashSet<>();
		for (RecordComponent c : EchoConfig.class.getRecordComponents()) components.add(c.getName());
		Set<String> ids = new HashSet<>();
		for (Key k : Key.values()) assertTrue(ids.add(k.id()), "duplicate id " + k.id());
		assertEquals(components, ids);
		assertEquals(12, Key.values().length);
	}

	@Test
	void keyDefaultsMatchDefaultAndRangesAreSane() {
		for (Key k : Key.values()) {
			assertEquals(k.defaultValue(), k.get(EchoConfig.DEFAULT), k.id());
			assertTrue(k.min() <= k.defaultValue() && k.defaultValue() <= k.max(), k.id());
			if (k.kind() == Kind.BOOL) {
				assertEquals(0, k.min());
				assertEquals(1, k.max());
			}
			assertEquals(k, Key.byId(k.id()).orElseThrow());
		}
		assertEquals(1, Key.FREE_TNT.defaultValue());
		assertEquals(0, Key.TRIGGER_BLOCKS.defaultValue());
		assertEquals(1, Key.BUFFER_HOURS.min());
		assertEquals(24, Key.BUFFER_HOURS.max());
		assertEquals("bufferHours", Key.BUFFER_HOURS.id());
		assertTrue(Key.byId("nope").isEmpty());
		assertTrue(Key.byId("BUFFERHOURS").isEmpty());
	}

	@Test
	void keySetGetClampsAndTouchesOnlyItsField() {
		for (Key k : Key.values()) {
			EchoConfig atMax = k.set(EchoConfig.DEFAULT, Integer.MAX_VALUE);
			assertEquals(k.max(), k.get(atMax), k.id());
			EchoConfig atMin = k.set(EchoConfig.DEFAULT, k.kind() == Kind.BOOL ? 0 : Integer.MIN_VALUE);
			assertEquals(k.min(), k.get(atMin), k.id());
			for (Key other : Key.values()) {
				if (other != k) assertEquals(other.get(EchoConfig.DEFAULT), other.get(atMax), k.id() + " changed " + other.id());
			}
		}
		assertEquals(10, Key.DELAY_MINUTES.set(EchoConfig.DEFAULT, 10).delayMinutes());
		assertTrue(Key.PAUSED.set(EchoConfig.DEFAULT, 7).paused());
	}

	@Test
	void parseAndFormat() {
		assertEquals(OptionalInt.of(1), Key.FREE_TNT.parse("TRUE"));
		assertEquals(OptionalInt.of(1), Key.FREE_TNT.parse(" on "));
		assertEquals(OptionalInt.of(0), Key.FREE_TNT.parse("off"));
		assertEquals(OptionalInt.of(0), Key.FREE_TNT.parse("0"));
		assertEquals(OptionalInt.empty(), Key.FREE_TNT.parse("yes"));
		assertEquals(OptionalInt.empty(), Key.FREE_TNT.parse(null));
		assertEquals(OptionalInt.of(12), Key.BUFFER_HOURS.parse("12"));
		assertEquals(OptionalInt.of(-3), Key.BUFFER_HOURS.parse("-3"));
		assertEquals(OptionalInt.empty(), Key.BUFFER_HOURS.parse("12h"));
		assertEquals(OptionalInt.empty(), Key.BUFFER_HOURS.parse("99999999999"));
		assertEquals(OptionalInt.empty(), Key.BUFFER_HOURS.parse(""));
		assertEquals("true", Key.FREE_TNT.format(EchoConfig.DEFAULT));
		assertEquals("false", Key.TRIGGER_BLOCKS.format(EchoConfig.DEFAULT));
		assertEquals("128", Key.GLOBAL_BLOCK_OPS.format(EchoConfig.DEFAULT));
		assertEquals(List.of("enabled", "delayMinutes", "maxEchoes", "bufferHours"),
				Arrays.stream(Key.values()).limit(4).map(Key::id).toList());
	}
}
