package dev.echoaholic.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VirtualInventoryTest {
	@Test
	void addHasTake() {
		VirtualInventory inv = new VirtualInventory();
		assertTrue(inv.isEmpty());
		assertFalse(inv.has("minecraft:dirt"));
		assertFalse(inv.take("minecraft:dirt"));
		inv.add("minecraft:dirt", 2);
		assertTrue(inv.has("minecraft:dirt"));
		assertEquals(2, inv.count("minecraft:dirt"));
		assertTrue(inv.take("minecraft:dirt"));
		assertTrue(inv.take("minecraft:dirt"));
		assertFalse(inv.take("minecraft:dirt"));
		assertFalse(inv.has("minecraft:dirt"));
		assertEquals(0, inv.distinctIds(), "empty entries are removed");
		inv.add("minecraft:dirt", 0);
		inv.add("minecraft:dirt", -4);
		assertTrue(inv.isEmpty());
	}

	@Test
	void countSaturates() {
		VirtualInventory inv = new VirtualInventory();
		inv.add("minecraft:stone", Integer.MAX_VALUE);
		assertEquals(VirtualInventory.MAX_COUNT, inv.count("minecraft:stone"));
		inv.add("minecraft:cobblestone", VirtualInventory.MAX_COUNT - 1);
		inv.add("minecraft:cobblestone", Integer.MAX_VALUE);
		assertEquals(VirtualInventory.MAX_COUNT, inv.count("minecraft:cobblestone"));
	}

	@Test
	void invalidIdsRejected() {
		VirtualInventory inv = new VirtualInventory();
		for (String bad : new String[] {"", "a;b", "a=b", "a b", "a\nb"}) {
			assertThrows(IllegalArgumentException.class, () -> inv.add(bad, 1), bad);
		}
		assertThrows(IllegalArgumentException.class, () -> inv.add(null, 1));
	}

	@Test
	void distinctCapDropsSmallestCountThenOldest() {
		VirtualInventory inv = new VirtualInventory();
		for (int i = 0; i < VirtualInventory.MAX_IDS; i++) inv.add("m:item" + i, 10 + i);
		inv.add("m:item5", 1); // existing ids never evict
		assertEquals(VirtualInventory.MAX_IDS, inv.distinctIds());
		inv.add("m:new", 1);
		assertEquals(VirtualInventory.MAX_IDS, inv.distinctIds());
		assertFalse(inv.has("m:item0"), "smallest count (10) dropped");
		assertTrue(inv.has("m:new"), "the new id is always kept");
		// m:new (1) is now the smallest
		inv.add("m:item1", -1);
		inv.add("m:other", 5);
		assertFalse(inv.has("m:new"));
		assertTrue(inv.has("m:other"));
		VirtualInventory tie = new VirtualInventory();
		for (int i = 0; i < VirtualInventory.MAX_IDS; i++) tie.add("t:" + i, 3);
		tie.add("t:x", 3);
		assertFalse(tie.has("t:0"));
		assertTrue(tie.has("t:1"));
		// the same history always gives the same result, also through encode/decode
		VirtualInventory again = VirtualInventory.decode(tie.encode());
		again.add("t:y", 1);
		tie.add("t:y", 1);
		assertEquals(tie, again);
	}

	@Test
	void encodeDecodeKeepsOrder() {
		VirtualInventory inv = new VirtualInventory();
		inv.add("minecraft:water_bucket", 1);
		inv.add("minecraft:dirt", 64);
		inv.add("minecraft:oak_log", 3);
		assertEquals("minecraft:water_bucket=1;minecraft:dirt=64;minecraft:oak_log=3", inv.encode());
		VirtualInventory back = VirtualInventory.decode(inv.encode());
		assertEquals(inv, back);
		assertEquals(List.of("minecraft:water_bucket", "minecraft:dirt", "minecraft:oak_log"), List.copyOf(back.snapshot().keySet()));
		assertEquals("", new VirtualInventory().encode());
		assertTrue(VirtualInventory.decode("").isEmpty());
		assertTrue(VirtualInventory.decode(null).isEmpty());
	}

	@Test
	void decodeSkipsMalformed() {
		VirtualInventory inv = VirtualInventory.decode("a:x=2;;=5;b:y;c:z=abc;d:w=-3;e:v=4;f:u=99999999999;g:t=1=2");
		Map<String, Integer> expected = new LinkedHashMap<>();
		expected.put("a:x", 2);
		expected.put("e:v", 4);
		assertEquals(expected, inv.snapshot());
	}

	@Test
	void snapshotRestore() {
		VirtualInventory inv = new VirtualInventory();
		inv.add("minecraft:bone_meal", 7);
		inv.add("minecraft:lava_bucket", 1);
		Map<String, Integer> snap = inv.snapshot();
		assertThrows(UnsupportedOperationException.class, () -> snap.put("x:y", 1));
		assertEquals(inv, VirtualInventory.restore(snap));
		Map<String, Integer> dirty = new LinkedHashMap<>();
		dirty.put("ok:id", 2_000_000);
		dirty.put("bad id", 1);
		dirty.put("neg:id", -1);
		dirty.put("null:id", null);
		VirtualInventory r = VirtualInventory.restore(dirty);
		assertEquals(Map.of("ok:id", VirtualInventory.MAX_COUNT), r.snapshot());
		inv.take("minecraft:bone_meal");
		assertEquals(7, snap.get("minecraft:bone_meal"), "snapshot is a copy");
	}
}
