package dev.echoaholic.core.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.Death;
import dev.echoaholic.core.action.Dimension;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.action.SegmentOutput;
import dev.echoaholic.core.action.Swing;
import dev.echoaholic.core.action.Teleport;

class SegmentTest {
	private static final double POS_EPS = 1.0 / 128 + 1e-9;
	private static final double ANGLE_EPS = 360.0 / 512 + 1e-3;

	private static void assertClose(Move expected, Move actual) {
		assertTrue(Math.abs(expected.x() - actual.x()) <= POS_EPS, expected + " vs " + actual);
		assertTrue(Math.abs(expected.y() - actual.y()) <= POS_EPS, expected + " vs " + actual);
		assertTrue(Math.abs(expected.z() - actual.z()) <= POS_EPS, expected + " vs " + actual);
		assertTrue(Math.abs(Move.angleDelta(expected.yRot(), actual.yRot())) <= ANGLE_EPS, expected + " vs " + actual);
		assertTrue(Math.abs(Move.angleDelta(expected.xRot(), actual.xRot())) <= ANGLE_EPS, expected + " vs " + actual);
	}

	private record Recorded(long tick, Move move, List<Action> actions) {}

	/** Random walk with occasional jumps, teleports and actions; a third of the ticks are empty. */
	private static List<Recorded> randomStream(long start, int ticks, long seed) {
		Random rnd = new Random(seed);
		List<Recorded> out = new ArrayList<>();
		double x = -1234.3, y = 70, z = 98765.1;
		float yaw = 0, pitch = 0;
		for (long t = start; t < start + ticks; t++) {
			int kind = rnd.nextInt(3);
			if (kind == 0) continue;
			x += rnd.nextGaussian() * 0.3;
			y += rnd.nextGaussian() * 0.1;
			z += rnd.nextGaussian() * 0.3;
			yaw += (float) (rnd.nextGaussian() * 30);
			pitch = (float) Math.max(-90, Math.min(90, pitch + rnd.nextGaussian() * 10));
			List<Action> actions = new ArrayList<>();
			if (rnd.nextInt(50) == 0) {
				x += 100 * rnd.nextGaussian();
				actions.add(new Teleport(x, y, z));
			}
			if (rnd.nextInt(5) == 0) {
				actions.add(new BlockBreak((int) x, (int) y - 1, (int) z, "minecraft:stone_" + rnd.nextInt(5), "minecraft:iron_pickaxe"));
			}
			if (rnd.nextInt(9) == 0) actions.add(new Pose(rnd.nextBoolean(), rnd.nextBoolean(), false, false));
			if (rnd.nextInt(13) == 0) actions.add(Swing.INSTANCE);
			Move move = kind == 2 || actions.isEmpty() ? new Move(x, y, z, yaw, pitch) : null;
			out.add(new Recorded(t, move, actions));
		}
		return out;
	}

	@Test
	void roundTripWithRandomAccess() throws IOException {
		long start = 7 * 1200 + 311;
		List<Recorded> stream = randomStream(start, 1200, 5);
		SegmentWriter w = new SegmentWriter(start);
		for (Recorded r : stream) w.append(r.tick(), r.move(), r.actions());
		assertEquals(stream.size(), w.entryCount());
		byte[] sealed = w.seal();
		DecodedSegment d = SegmentReader.decode(sealed);
		assertEquals(start, d.startTick());
		assertEquals(start + 1200, d.endTick());
		assertEquals(1200, d.tickCount());
		assertEquals(stream.size(), d.entries().size());
		int i = 0;
		for (long t = start; t < start + 1200; t++) {
			TickEntry e = d.entriesAt(t);
			assertEquals(t, e.tick());
			if (i < stream.size() && stream.get(i).tick() == t) {
				Recorded r = stream.get(i++);
				assertEquals(r.actions(), e.actions());
				if (r.move() == null) {
					assertNull(e.move());
				} else {
					assertEquals(r.move().quantized(), e.move(), "decoded move equals the quantized input");
					assertClose(r.move(), e.move());
				}
			} else {
				assertTrue(e.isEmpty());
			}
		}
		assertEquals(new SegmentHeader(1, start, 1200), SegmentReader.header(sealed));
		assertThrows(IllegalArgumentException.class, () -> d.entriesAt(start - 1));
		assertThrows(IllegalArgumentException.class, () -> d.entriesAt(start + 1200));
	}

	@Test
	void moveAccuracyEveryTickAndKeyframes() throws IOException {
		Random rnd = new Random(11);
		List<Move> moves = new ArrayList<>();
		double x = 29_999_000.5, y = -60, z = -29_999_000.25;
		for (int t = 0; t < 1200; t++) {
			switch (t % 100) {
				case 50 -> x += 8.0; // exactly the keyframe limit: still a delta
				case 51 -> x += 8.0 + 1.0 / 32; // beyond: keyframe
				case 52 -> z -= 5000;
				default -> {
					x += rnd.nextGaussian() * 0.2;
					y += rnd.nextGaussian() * 0.05;
					z += rnd.nextGaussian() * 0.2;
				}
			}
			moves.add(new Move(x, y, z, rnd.nextFloat() * 720 - 360, rnd.nextFloat() * 180 - 90));
		}
		SegmentWriter w = new SegmentWriter(0);
		for (int t = 0; t < moves.size(); t++) w.append(t, moves.get(t), List.of());
		DecodedSegment d = SegmentReader.decode(w.seal());
		for (int t = 0; t < moves.size(); t++) assertClose(moves.get(t), d.entriesAt(t).move());
	}

	@Test
	void deltaIsSmallerThanKeyframe() {
		SegmentWriter w = new SegmentWriter(0, 100);
		w.append(0, new Move(1_000_000, 64, -1_000_000, 0, 0), null);
		int keyframe = w.bytesSoFar();
		w.append(1, new Move(1_000_000.2, 64, -1_000_000, 0, 0), null);
		int delta = w.bytesSoFar() - keyframe;
		assertEquals(3, delta, "tick delta + flags + one axis");
		w.append(2, new Move(1_000_000.2, 64, -1_000_000, 0, 0), null);
		assertEquals(2, w.bytesSoFar() - keyframe - delta, "heartbeat without change: tick delta + flags");
		int before = w.bytesSoFar();
		w.append(3, new Move(1_000_010, 64, -1_000_000, 0, 0), null);
		assertTrue(w.bytesSoFar() - before > 8, "a jump over 8 blocks is a keyframe");
		before = w.bytesSoFar();
		w.append(4, new Move(1_000_010, 64, -1_000_000, 45, 0), null);
		assertEquals(4, w.bytesSoFar() - before, "rotation: tick delta + flags + 2 angle bytes");
	}

	@Test
	void positionAtInterpolates() throws IOException {
		SegmentWriter w = new SegmentWriter(100, 100);
		w.append(110, new Move(0, 64, 0, 170, 10), List.of());
		w.append(120, new Move(1, 64, 2, -170, 20), List.of());
		w.append(125, null, List.of(Swing.INSTANCE));
		DecodedSegment d = SegmentReader.decode(w.seal());
		assertTrue(d.positionAt(100).isEmpty(), "before the first sample");
		assertTrue(d.positionAt(109).isEmpty());
		Move at110 = d.positionAt(110).orElseThrow();
		assertEquals(0, at110.x());
		Move mid = d.positionAt(115).orElseThrow();
		assertEquals(0.5, mid.x(), 1e-9);
		assertEquals(1.0, mid.z(), 1e-9);
		assertEquals(64, mid.y(), 1e-9);
		assertEquals(15, mid.xRot(), 0.8);
		assertTrue(Math.abs(Move.angleDelta(180f, mid.yRot())) < 1.5, "yaw takes the short way: " + mid.yRot());
		Move quarter = d.positionAt(112).orElseThrow();
		assertEquals(0.2, quarter.x(), 1e-9);
		// after the last sample: hold it
		assertEquals(d.entriesAt(120).move(), d.positionAt(199).orElseThrow());
		assertEquals(110, d.lastMoveAtOrBefore(119).orElseThrow().tick());
		assertEquals(120, d.lastMoveAtOrBefore(120).orElseThrow().tick());
		assertEquals(120, d.firstMoveAfter(119).orElseThrow().tick());
		assertTrue(d.firstMoveAfter(120).isEmpty());
		assertTrue(d.lastMoveAtOrBefore(0).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> d.positionAt(200));
	}

	@Test
	void positionAtHoldsAcrossJumps() throws IOException {
		SegmentWriter w = new SegmentWriter(0, 100);
		w.append(0, new Move(0, 64, 0, 0, 0), List.of());
		w.append(10, new Move(5, 64, 0, 0, 0), List.of(new Teleport(5, 64, 0)));
		w.append(20, new Move(5, 64, 50, 0, 0), List.of());
		w.append(30, new Move(5, 64, 52, 0, 0), List.of(new Dimension("minecraft:the_end", 5, 64, 52)));
		w.append(40, new Move(6, 64, 52, 0, 0), List.of(Death.INSTANCE));
		DecodedSegment d = SegmentReader.decode(w.seal());
		assertEquals(0, d.positionAt(9).orElseThrow().x(), "teleport: hold before the jump");
		assertEquals(5, d.positionAt(10).orElseThrow().x());
		assertEquals(0, d.positionAt(19).orElseThrow().z(), "far sample: hold");
		assertEquals(50, d.positionAt(29).orElseThrow().z(), "dimension change: hold");
		assertEquals(5.5, d.positionAt(35).orElseThrow().x(), 1e-9, "death does not stop interpolation");
	}

	@Test
	void emptyAndPartialSegments() throws IOException {
		SegmentWriter empty = new SegmentWriter(2400);
		empty.append(2400, null, List.of());
		empty.append(2401, null, null);
		assertEquals(0, empty.entryCount());
		DecodedSegment e = SegmentReader.decode(empty.seal());
		assertTrue(e.entries().isEmpty());
		assertTrue(e.positionAt(2500).isEmpty());
		assertTrue(e.entriesAt(3599).isEmpty());

		SegmentWriter partial = new SegmentWriter(2400);
		partial.append(2400, new Move(1, 2, 3, 4, 5), List.of(new Pose(true, false, false, false)));
		partial.append(2410, null, List.of(new BlockPlace(1, 2, 3, "minecraft:dirt", "minecraft:dirt")));
		DecodedSegment p = SegmentReader.decode(partial.seal(2411));
		assertEquals(2411, p.endTick());
		assertEquals(11, p.tickCount());
		assertEquals(2, p.entries().size());
		assertTrue(p.contains(2410));
		assertFalse(p.contains(2411));

		SegmentWriter one = new SegmentWriter(0, 1);
		one.append(0, new Move(0, 0, 0, 0, 0), null);
		assertEquals(1, SegmentReader.decode(one.seal()).entries().size());
	}

	@Test
	void writerContract() {
		SegmentWriter w = new SegmentWriter(1200);
		assertThrows(IllegalArgumentException.class, () -> w.append(1199, null, null));
		assertThrows(IllegalArgumentException.class, () -> w.append(2400, null, null));
		w.append(1300, new Move(0, 0, 0, 0, 0), null);
		assertThrows(IllegalArgumentException.class, () -> w.append(1300, null, null));
		assertThrows(IllegalArgumentException.class, () -> w.append(1299, null, null));
		assertThrows(IllegalArgumentException.class, () -> w.seal(1300), "must cover the last tick");
		assertThrows(IllegalArgumentException.class, () -> w.seal(2401));
		assertEquals(2400, w.endTick());
		w.seal(1301);
		assertTrue(w.isSealed());
		assertThrows(IllegalStateException.class, () -> w.append(1301, null, null));
		assertThrows(IllegalStateException.class, w::seal);
		SegmentWriter fresh = new SegmentWriter(5);
		assertThrows(IllegalArgumentException.class, () -> fresh.seal(5), "zero-length segment");
		assertThrows(IllegalArgumentException.class, () -> new SegmentWriter(-1));
		assertThrows(IllegalArgumentException.class, () -> new SegmentWriter(0, 0));
	}

	private static byte[] sealedFrom(int version, long start, int count, byte[] body) {
		SegmentOutput out = new SegmentOutput();
		out.writeBytes("ECHO".getBytes());
		out.writeByte(version);
		out.writeVarLong(start);
		out.writeVarInt(count);
		out.writeBytes(body);
		return SegmentFormat.deflate(out.toByteArray());
	}

	@Test
	void craftedBodiesDecode() throws IOException {
		// sanity check of the crafting helper: one keyframe at tick 0
		byte[] ok = sealedFrom(1, 0, 10, new byte[] {1, SegmentFormat.MOVE | SegmentFormat.KEYFRAME, 0, 0, 0, 0, 0});
		assertEquals(new Move(0, 0, 0, 0, 0), SegmentReader.decode(ok).entriesAt(0).move());
	}

	@Test
	void corruptionIsIOException() {
		final byte mk = SegmentFormat.MOVE | SegmentFormat.KEYFRAME;
		List<byte[]> bad = List.of(
				new byte[0],
				new byte[] {1, 2, 3, 4},
				"ECHO".getBytes(),
				sealedFrom(2, 0, 10, new byte[0]), // version
				sealedFrom(1, 0, 0, new byte[0]), // zero ticks
				sealedFrom(1, -5, 10, new byte[0]), // negative start
				sealedFrom(1, 0, 10, new byte[] {0, mk, 0, 0, 0, 0, 0}), // tick delta 0
				sealedFrom(1, 0, 10, new byte[] {11, mk, 0, 0, 0, 0, 0}), // beyond the segment
				sealedFrom(1, 0, 10, new byte[] {1, (byte) 0x80}), // unknown flag
				sealedFrom(1, 0, 10, new byte[] {1, 0}), // empty entry
				sealedFrom(1, 0, 10, new byte[] {1, SegmentFormat.MOVE | SegmentFormat.DX, 2}), // delta before keyframe
				sealedFrom(1, 0, 10, new byte[] {1, (byte) (mk | SegmentFormat.DX), 0, 0, 0, 0, 0}), // keyframe with delta flag
				sealedFrom(1, 0, 10, new byte[] {1, SegmentFormat.ROT, 0, 0}), // move flag without move
				sealedFrom(1, 0, 10, new byte[] {1, SegmentFormat.ACTIONS, 0}), // zero actions
				sealedFrom(1, 0, 10, new byte[] {1, SegmentFormat.ACTIONS, 1, 99}), // unknown action id
				sealedFrom(1, 0, 10, new byte[] {1, SegmentFormat.ACTIONS, 2, 10}), // missing second action
				sealedFrom(1, 0, 10, new byte[] {1, mk, 0, 0}), // truncated move
				sealedFrom(1, 0, 10, new byte[] {1, mk, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
						(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01, 0, 0, 0, 0})); // coordinate out of range
		for (int i = 0; i < bad.size(); i++) {
			byte[] b = bad.get(i);
			assertThrows(IOException.class, () -> SegmentReader.decode(b), "case " + i);
		}
		byte[] bad2 = new byte[] {'E', 'C', 'H', 'X', 1, 0, 1};
		assertThrows(IOException.class, () -> SegmentReader.decode(SegmentFormat.deflate(bad2)));
	}

	@Test
	void damagedSealedBytesAreIOException() {
		SegmentWriter w = new SegmentWriter(0);
		List<Recorded> stream = randomStream(0, 1200, 3);
		for (Recorded r : stream) w.append(r.tick(), r.move(), r.actions());
		byte[] sealed = w.seal();
		for (int len = 0; len < sealed.length; len += 7) {
			byte[] cut = Arrays.copyOf(sealed, len);
			assertThrows(IOException.class, () -> SegmentReader.decode(cut), "truncated to " + len);
		}
		byte[] trailing = Arrays.copyOf(sealed, sealed.length + 3);
		assertThrows(IOException.class, () -> SegmentReader.decode(trailing));
		Random rnd = new Random(9);
		for (int i = 0; i < 300; i++) {
			byte[] flipped = sealed.clone();
			flipped[rnd.nextInt(flipped.length)] ^= (byte) (1 << rnd.nextInt(8));
			assertThrows(IOException.class, () -> SegmentReader.decode(flipped), "bit flip " + i);
		}
	}
}
