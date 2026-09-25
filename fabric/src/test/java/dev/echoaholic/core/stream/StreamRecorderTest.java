package dev.echoaholic.core.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.action.Teleport;

class StreamRecorderTest {
	private static final Move STILL = new Move(10.5, 64, -3.5, 90, 0);
	private static final Pose WALK = Pose.STANDING;
	private static final Pose SNEAK = new Pose(true, false, false, false);

	private static List<Pose> poses(DecodedSegment d) {
		List<Pose> out = new ArrayList<>();
		for (TickEntry e : d.entries()) {
			for (Action a : e.actions()) {
				if (a instanceof Pose p) out.add(p);
			}
		}
		return out;
	}

	@Test
	void rollsOverEverySegment() throws IOException {
		StreamRecorder r = new StreamRecorder(0, 0);
		List<SealedSegment> sealed = new ArrayList<>();
		for (int t = 0; t < 3 * 1200; t++) {
			assertEquals(t, r.tick());
			Optional<SealedSegment> s = r.record(STILL, WALK, List.of());
			if (t % 1200 == 1199) assertTrue(s.isPresent(), "tick " + t);
			else assertTrue(s.isEmpty(), "tick " + t);
			s.ifPresent(sealed::add);
		}
		assertEquals(3, sealed.size());
		for (int i = 0; i < 3; i++) {
			SealedSegment s = sealed.get(i);
			assertEquals(i, s.seq());
			assertEquals(i * 1200L, s.startTick());
			assertEquals((i + 1) * 1200L, s.endTick());
			assertEquals(new SegmentMeta(i, i * 1200L, (i + 1) * 1200L, s.bytes().length), s.meta());
			DecodedSegment d = SegmentReader.decode(s.bytes());
			TickEntry first = d.entriesAt(s.startTick());
			assertTrue(first.hasMove(), "segment starts with a move sample");
			assertEquals(List.of(WALK), first.actions(), "and with the current pose");
			assertEquals(60, d.entries().size(), "start + heartbeats every 20 ticks");
		}
		assertEquals(3, r.nextSeq());
		assertFalse(r.hasOpenSegment());
		assertEquals(0, r.openSegmentBytes());
	}

	@Test
	void poseOnlyWhenChanged() throws IOException {
		StreamRecorder r = new StreamRecorder(0, 0, 100);
		SealedSegment s = null;
		for (int t = 0; t < 100; t++) {
			Pose p = t >= 50 && t < 70 ? SNEAK : WALK;
			s = r.record(STILL, p, List.of()).orElse(null);
		}
		DecodedSegment d = SegmentReader.decode(s.bytes());
		assertEquals(List.of(WALK, SNEAK, WALK), poses(d));
		assertEquals(List.of(SNEAK), d.entriesAt(50).actions());
		assertEquals(List.of(WALK), d.entriesAt(70).actions());
		// null pose: not tracked
		StreamRecorder n = new StreamRecorder(0, 0, 10);
		SealedSegment ns = null;
		for (int t = 0; t < 10; t++) ns = n.record(STILL, null, List.of()).orElse(null);
		assertEquals(List.of(), poses(SegmentReader.decode(ns.bytes())));
	}

	@Test
	void teleportForcesAMoveSample() throws IOException {
		StreamRecorder r = new StreamRecorder(0, 0, 10);
		r.record(STILL, null, List.of());
		Move tiny = new Move(10.51, 64, -3.5, 90, 0);
		r.record(tiny, null, List.of(new BlockBreak(1, 2, 3, "minecraft:stone", "")));
		r.record(tiny, null, List.of(new Teleport(10.51, 64, -3.5)));
		SealedSegment s = null;
		for (int t = 3; t < 10; t++) s = r.record(tiny, null, List.of()).orElse(null);
		DecodedSegment d = SegmentReader.decode(s.bytes());
		assertFalse(d.entriesAt(1).hasMove(), "0.01 blocks: no sample");
		assertTrue(d.entriesAt(2).hasMove(), "teleport tick: sample forced");
		assertEquals(new BlockBreak(1, 2, 3, "minecraft:stone", ""), d.entriesAt(1).actions().getFirst());
	}

	@Test
	void flushSealsPartialAndResumesUnaligned() throws IOException {
		StreamRecorder r = new StreamRecorder(0, 0);
		assertTrue(r.flush().isEmpty(), "nothing open yet");
		for (int t = 0; t < 500; t++) assertTrue(r.record(STILL, WALK, List.of()).isEmpty());
		assertTrue(r.hasOpenSegment());
		assertTrue(r.openSegmentBytes() > 0);
		SealedSegment partial = r.flush().orElseThrow();
		assertEquals(0, partial.startTick());
		assertEquals(500, partial.endTick());
		assertEquals(500, SegmentReader.decode(partial.bytes()).tickCount());
		assertTrue(r.flush().isEmpty());
		assertEquals(500, r.tick());
		Optional<SealedSegment> next = Optional.empty();
		for (int t = 500; t < 1700; t++) next = r.record(STILL, WALK, List.of());
		SealedSegment full = next.orElseThrow();
		assertEquals(1, full.seq());
		assertEquals(500, full.startTick());
		assertEquals(1700, full.endTick());
		DecodedSegment d = SegmentReader.decode(full.bytes());
		assertTrue(d.entriesAt(500).hasMove());
		assertEquals(List.of(WALK), d.entriesAt(500).actions(), "pose repeated at every segment start");
		assertTrue(r.flush().isEmpty(), "just rolled over: nothing to flush");

		// resuming from saved state
		StreamRecorder resumed = new StreamRecorder(r.tick(), r.nextSeq());
		assertEquals(1700, resumed.tick());
		resumed.record(STILL, WALK, List.of());
		SealedSegment one = resumed.flush().orElseThrow();
		assertEquals(new SegmentMeta(2, 1700, 1701, one.bytes().length), one.meta());
	}

	@Test
	void constantVelocityReplaysAccurately() throws IOException {
		StreamRecorder r = new StreamRecorder(0, 0);
		List<Move> truth = new ArrayList<>();
		List<DecodedSegment> segments = new ArrayList<>();
		for (int t = 0; t < 2400; t++) {
			double speed = t < 1200 ? 0.02 : 0.21; // slow drift (sparse samples), then walking
			double x = t < 1200 ? t * 0.02 : 1200 * 0.02 + (t - 1200) * speed;
			Move m = new Move(x, 64, -x / 2, 45f + t * 0.5f, 0);
			truth.add(m);
			r.record(m, null, List.of()).ifPresent(s -> {
				try {
					segments.add(SegmentReader.decode(s.bytes()));
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
		}
		assertEquals(2, segments.size());
		for (int t = 0; t < 2400; t++) {
			DecodedSegment d = segments.get(t / 1200);
			Move got = d.positionAt(t).orElseThrow();
			Move want = truth.get(t);
			// between two samples: exact up to quantization; after a segment's last sample the position is held, which
			// the sampler bounds by its thresholds (0.05 blocks, 2 degrees)
			boolean between = d.firstMoveAfter(t).isPresent() || d.entriesAt(t).hasMove();
			assertTrue(got.distanceTo(want) <= (between ? 0.02 : 0.065), "tick " + t + ": " + got + " vs " + want);
			assertTrue(Math.abs(Move.angleDelta(want.yRot(), got.yRot())) <= (between ? 0.75 : 2.75), "tick " + t);
		}
	}

	@Test
	void rejectsBadArguments() {
		assertThrows(IllegalArgumentException.class, () -> new StreamRecorder(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> new StreamRecorder(0, 0, 0));
		assertThrows(NullPointerException.class, () -> new StreamRecorder(0, 0).record(null, null, List.of()));
		StreamRecorder r = new StreamRecorder(0, 0);
		r.record(STILL, null, null);
		assertEquals(1, r.tick());
	}
}
