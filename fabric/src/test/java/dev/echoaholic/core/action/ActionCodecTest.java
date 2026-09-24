package dev.echoaholic.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ActionCodecTest {
	private static final String LONG_STATE = "minecraft:" + "x".repeat(60_000);
	private static final String UNICODE = "mod:блок_é_✓_𝄞[facing=north]";

	/** At least one sample per type, with extreme values. */
	static List<Action> samples() {
		List<Action> out = new ArrayList<>();
		out.add(new Pose(false, false, false, false));
		out.add(new Pose(true, true, true, true));
		out.add(new Pose(true, false, true, false));
		out.add(new BlockBreak(0, 0, 0, "minecraft:stone", ""));
		out.add(new BlockBreak(-30_000_000, -64, 29_999_999, "minecraft:oak_log[axis=y]", "minecraft:diamond_axe"));
		out.add(new BlockBreak(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, LONG_STATE, UNICODE));
		out.add(new BlockPlace(12, 320, -7, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", "minecraft:oak_stairs"));
		out.add(new BlockPlace(Integer.MAX_VALUE, Integer.MIN_VALUE, 0, "", ""));
		out.add(new Attack(1.5, 64.0, -2.25, 7.5f, "minecraft:netherite_sword"));
		out.add(new Attack(-3.0e7, Double.MAX_VALUE, Double.MIN_VALUE, Float.MAX_VALUE, ""));
		out.add(new Attack(Double.NaN, -0.0, Double.NEGATIVE_INFINITY, Float.NaN, "x"));
		out.add(new Shoot("minecraft:arrow", 0.1, 65.62, -0.3, 1.2345678901234, -0.01, 2.5e-17, "minecraft:bow"));
		out.add(new Shoot("minecraft:trident", -1e300, 1e-300, 0, -0.0, 0, 3, "minecraft:trident"));
		for (UseItem.Kind kind : UseItem.Kind.values()) {
			out.add(new UseItem(kind, -kind.id() * 1000, 70, kind.id(), kind.id() % 6, "minecraft:item_" + kind, "extra_" + kind));
		}
		out.add(new UseItem(UseItem.Kind.BUCKET_EMPTY, Integer.MIN_VALUE, -2048, Integer.MAX_VALUE, -1, "minecraft:water_bucket", "minecraft:water"));
		out.add(new UseItem(UseItem.Kind.SHEAR_ENTITY, 0, 0, 0, Integer.MIN_VALUE, "minecraft:shears", ""));
		out.add(new Dimension("minecraft:the_nether", 12.5, 70, -3.75));
		out.add(new Dimension(UNICODE, -2.9999999e7, -64, 2.9999999e7));
		out.add(new Teleport(0, 0, 0));
		out.add(new Teleport(-1234.5678, 319.999, 1e9));
		out.add(new Death());
		out.add(Death.INSTANCE);
		out.add(new Swing());
		return out;
	}

	private static List<Action> roundTrip(List<Action> actions) throws IOException {
		SegmentOutput out = new SegmentOutput();
		for (Action a : actions) ActionTypes.write(a, out);
		SegmentInput in = new SegmentInput(out.toByteArray());
		List<Action> back = new ArrayList<>();
		for (int i = 0; i < actions.size(); i++) back.add(ActionTypes.read(in));
		assertFalse(in.hasRemaining(), "trailing bytes");
		return back;
	}

	@Test
	void everyTypeHasASample() {
		Set<ActionType<?>> covered = new HashSet<>();
		for (Action a : samples()) covered.add(a.type());
		assertEquals(Set.copyOf(ActionTypes.all()), covered);
	}

	@Test
	void roundTripEveryType() throws IOException {
		List<Action> samples = samples();
		assertEquals(samples, roundTrip(samples));
		for (Action a : samples) assertEquals(List.of(a), roundTrip(List.of(a)), a.getClass().getSimpleName());
	}

	@Test
	void rawBitsArePreserved() throws IOException {
		Attack a = new Attack(-0.0, Double.longBitsToDouble(0x7ff8000000000123L), 0, Float.intBitsToFloat(0x7fc00042), "");
		Attack back = (Attack) roundTrip(List.of(a)).getFirst();
		assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(back.tx()));
		assertEquals(0x7ff8000000000123L, Double.doubleToRawLongBits(back.ty()));
		assertEquals(0x7fc00042, Float.floatToRawIntBits(back.damage()));
	}

	@Test
	void registryIsConsistent() {
		Set<Integer> ids = new HashSet<>();
		Set<String> names = new HashSet<>();
		int previous = 0;
		for (ActionType<?> t : ActionTypes.all()) {
			assertTrue(ids.add(t.id()));
			assertTrue(names.add(t.name()));
			assertTrue(t.id() > previous, "all() is in id order");
			previous = t.id();
			assertSame(t, ActionTypes.byId(t.id()).orElseThrow());
			assertTrue(t.actionClass().isRecord());
		}
		assertEquals(10, ActionTypes.all().size());
		assertSame(ActionTypes.BLOCK_BREAK, new BlockBreak(0, 0, 0, "a", "b").type());
		assertSame(ActionTypes.DEATH, Death.INSTANCE.type());
		assertTrue(ActionTypes.byId(0).isEmpty());
		assertTrue(ActionTypes.byId(255).isEmpty());
		assertTrue(ActionTypes.byId(-1).isEmpty());
		assertTrue(ActionTypes.byId(1000).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> ActionTypes.of(new Action() {}));
		assertThrows(IllegalArgumentException.class, () -> new ActionType<>(0, "x", Swing.class, ActionTypes.SWING.codec()));
		assertThrows(IllegalArgumentException.class, () -> new ActionType<>(256, "x", Swing.class, ActionTypes.SWING.codec()));
	}

	@Test
	void stableWireIds() {
		// ids are part of the file format
		assertEquals(1, ActionTypes.POSE.id());
		assertEquals(2, ActionTypes.BLOCK_BREAK.id());
		assertEquals(3, ActionTypes.BLOCK_PLACE.id());
		assertEquals(4, ActionTypes.ATTACK.id());
		assertEquals(5, ActionTypes.SHOOT.id());
		assertEquals(6, ActionTypes.USE_ITEM.id());
		assertEquals(7, ActionTypes.DIMENSION.id());
		assertEquals(8, ActionTypes.TELEPORT.id());
		assertEquals(9, ActionTypes.DEATH.id());
		assertEquals(10, ActionTypes.SWING.id());
		int[] kindIds = Arrays.stream(UseItem.Kind.values()).mapToInt(UseItem.Kind::id).toArray();
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), Arrays.stream(kindIds).boxed().toList());
		assertTrue(UseItem.Kind.byId(7).isEmpty());
	}

	@Test
	void paletteReusesStrings() throws IOException {
		SegmentOutput out = new SegmentOutput();
		BlockBreak b = new BlockBreak(1, 2, 3, "minecraft:oak_log[axis=y]", "minecraft:iron_axe");
		ActionTypes.write(b, out);
		int first = out.size();
		ActionTypes.write(b, out);
		int second = out.size() - first;
		assertEquals(1 + 3 + 1 + 1, second, "id + 3 one-byte coords + two palette refs");
		assertEquals(2, out.paletteSize());
		ActionTypes.write(new BlockPlace(1, 2, 3, "minecraft:oak_log[axis=y]", "minecraft:oak_log"), out);
		assertEquals(3, out.paletteSize(), "block state shared across types");
		SegmentInput in = new SegmentInput(out.toByteArray());
		assertEquals(b, ActionTypes.read(in));
		assertEquals(b, ActionTypes.read(in));
		assertEquals(new BlockPlace(1, 2, 3, "minecraft:oak_log[axis=y]", "minecraft:oak_log"), ActionTypes.read(in));
	}

	@Test
	void unknownIdsAreCorruption() {
		assertThrows(IOException.class, () -> ActionTypes.read(new SegmentInput(new byte[] {0})));
		assertThrows(IOException.class, () -> ActionTypes.read(new SegmentInput(new byte[] {(byte) 200})));
		assertThrows(IOException.class, () -> ActionTypes.read(new SegmentInput(new byte[] {(byte) 255})));
		// use-item with unknown kind 9
		assertThrows(IOException.class, () -> ActionTypes.read(new SegmentInput(new byte[] {6, 9, 0, 0, 0, 0, 0, 0})));
		// pose with unknown bits
		assertThrows(IOException.class, () -> ActionTypes.read(new SegmentInput(new byte[] {1, 0x10})));
		// palette reference before any definition
		assertThrows(IOException.class, () -> ActionTypes.read(new SegmentInput(new byte[] {2, 0, 0, 0, 1, 1})));
	}

	@Test
	void everyTruncationIsIOException() throws IOException {
		List<Action> samples = samples();
		SegmentOutput out = new SegmentOutput();
		for (Action a : samples) ActionTypes.write(a, out);
		byte[] full = out.toByteArray();
		assertEquals(samples, roundTrip(samples));
		for (int len = 0; len < full.length; len += (len < 2000 ? 1 : 997)) {
			SegmentInput in = new SegmentInput(Arrays.copyOf(full, len));
			int finalLen = len;
			assertThrows(IOException.class, () -> {
				for (int i = 0; i < samples.size(); i++) ActionTypes.read(in);
			}, "prefix " + finalLen);
		}
	}

	@Test
	void recordsRejectNulls() {
		assertThrows(NullPointerException.class, () -> new BlockBreak(0, 0, 0, null, ""));
		assertThrows(NullPointerException.class, () -> new BlockPlace(0, 0, 0, "", null));
		assertThrows(NullPointerException.class, () -> new Attack(0, 0, 0, 0, null));
		assertThrows(NullPointerException.class, () -> new Shoot(null, 0, 0, 0, 0, 0, 0, ""));
		assertThrows(NullPointerException.class, () -> new UseItem(null, 0, 0, 0, 0, "", ""));
		assertThrows(NullPointerException.class, () -> new UseItem(UseItem.Kind.IGNITE, 0, 0, 0, 0, "", null));
		assertThrows(NullPointerException.class, () -> new Dimension(null, 0, 0, 0));
	}

	@Test
	void tooLongStringRejectedOnWrite() {
		SegmentOutput out = new SegmentOutput();
		String huge = "y".repeat(SegmentOutput.MAX_STRING_BYTES + 1);
		assertThrows(IllegalArgumentException.class, () -> ActionTypes.write(new BlockBreak(0, 0, 0, huge, ""), out));
		// multi-byte characters count in UTF-8 bytes
		String wide = "é".repeat(SegmentOutput.MAX_STRING_BYTES / 2 + 1);
		assertThrows(IllegalArgumentException.class, () -> out.writeString(wide));
		out.writeString("é".repeat(SegmentOutput.MAX_STRING_BYTES / 2));
	}
}
