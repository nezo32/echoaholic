package dev.echoaholic.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import dev.echoaholic.EchoServer;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.core.action.Dimension;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.action.Teleport;
import dev.echoaholic.core.stream.SealedSegment;
import dev.echoaholic.core.stream.StreamRecorder;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.storage.PlayerStream;
import dev.echoaholic.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Builds recorded streams with the real core writer ({@link StreamRecorder}), exactly as the production recorder does
 * (including the D8 {@link Dimension} keyframe at every segment start), and injects them as the past of a test owner.
 *
 * <p>Positions are absolute world coordinates. {@link #replay} is the standard harness: the stream becomes the
 * owner's recorded past, the owner logs in (survival, so its stream keeps advancing and the echo is not frozen), and
 * echo #1 starts replaying from tick 0.
 */
public final class SyntheticStreams {
	private SyntheticStreams() {}

	/** A stream under construction. Every {@code tick} call records one stream tick at the current position. */
	public static final class Stream {
		private final StreamRecorder recorder;
		private final List<SealedSegment> sealed = new ArrayList<>();
		private final List<Vec3> positions = new ArrayList<>();
		private String dimension;
		private double x, y, z;
		private float yRot, xRot;
		private Pose pose = Pose.STANDING;
		private final List<Action> pending = new ArrayList<>();

		public Stream(ResourceKey<Level> dimension, Vec3 start, int segmentTicks) {
			this.recorder = new StreamRecorder(0, 0, segmentTicks);
			this.dimension = Ids.dimension(dimension);
			this.x = start.x;
			this.y = start.y;
			this.z = start.z;
		}

		public Stream(ResourceKey<Level> dimension, Vec3 start) {
			this(dimension, start, TestSupport.SEGMENT);
		}

		/** The stream tick the next {@link #tick} records. */
		public long now() {
			return recorder.tick();
		}

		public Vec3 pos() {
			return new Vec3(x, y, z);
		}

		/** Recorded position of every tick so far (index = stream tick). */
		public List<Vec3> positions() {
			return Collections.unmodifiableList(positions);
		}

		public Stream look(float yRot, float xRot) {
			this.yRot = yRot;
			this.xRot = xRot;
			return this;
		}

		/** Moves the recorded position without recording a tick (the next {@link #tick} records it). */
		public Stream moveTo(Vec3 p) {
			x = p.x;
			y = p.y;
			z = p.z;
			return this;
		}

		public Stream pose(Pose p) {
			this.pose = p;
			return this;
		}

		/** Records one tick at the current position with {@code actions}. */
		public Stream tick(Action... actions) {
			List<Action> list = new ArrayList<>(actions.length + pending.size() + 1);
			if (!recorder.hasOpenSegment()) list.add(new Dimension(dimension, x, y, z)); // D8 keyframe
			list.addAll(pending);
			pending.clear();
			Collections.addAll(list, actions);
			positions.add(pos());
			recorder.record(new Move(x, y, z, yRot, xRot), pose, list).ifPresent(sealed::add);
			return this;
		}

		public Stream idle(int ticks) {
			for (int i = 0; i < ticks; i++) tick();
			return this;
		}

		/** Walks in a straight line at {@code speed} blocks per tick, one tick per step (arrives on the last one). */
		public Stream walkTo(Vec3 target, double speed) {
			Vec3 from = pos();
			double dist = from.distanceTo(target);
			int steps = Math.max(1, (int) Math.ceil(dist / speed));
			float yaw = (float) (Math.toDegrees(Math.atan2(-(target.x - from.x), target.z - from.z)));
			yRot = yaw;
			for (int i = 1; i <= steps; i++) {
				double t = i / (double) steps;
				x = from.x + (target.x - from.x) * t;
				y = from.y + (target.y - from.y) * t;
				z = from.z + (target.z - from.z) * t;
				tick();
			}
			return this;
		}

		/** A recorded teleport (ender pearl, /tp): the position jumps and the tick carries a {@link Teleport}. */
		public Stream teleport(Vec3 to) {
			x = to.x;
			y = to.y;
			z = to.z;
			return tick(new Teleport(to.x, to.y, to.z));
		}

		/** A recorded dimension change: the tick carries a {@link Dimension} action. */
		public Stream changeDimension(ResourceKey<Level> dim, Vec3 to) {
			dimension = Ids.dimension(dim);
			x = to.x;
			y = to.y;
			z = to.z;
			return tick(new Dimension(dimension, to.x, to.y, to.z));
		}

		/** Seals the open segment and returns every segment, oldest first. */
		public List<SealedSegment> finish() {
			recorder.flush().ifPresent(sealed::add);
			return List.copyOf(sealed);
		}

		/** Total stream ticks recorded. */
		public long length() {
			return positions.size();
		}
	}

	/** A break of {@code state} at an absolute position with an empty hand. */
	public static BlockBreak breakOf(BlockPos abs, String state) {
		return breakOf(abs, state, "minecraft:air");
	}

	/** A break of {@code state} at an absolute position with {@code tool}. */
	public static BlockBreak breakOf(BlockPos abs, String state, String tool) {
		return new BlockBreak(abs.getX(), abs.getY(), abs.getZ(), state, tool);
	}

	/**
	 * Makes {@code segments} the recorded past of {@code owner}: appends them to the store (readable at once) and moves
	 * the meta to their end, as a crash-recovered stream would be. The schedule is disabled for the owner
	 * ({@link TestSupport#NO_SCHEDULE}).
	 */
	public static PlayerStream inject(EchoServer es, UUID owner, List<SealedSegment> segments) {
		long end = 0, nextSeq = 0;
		for (SealedSegment s : segments) {
			es.store().append(owner, s);
			end = Math.max(end, s.endTick());
			nextSeq = Math.max(nextSeq, s.seq() + 1);
		}
		// Warm the decoded-segment cache now (blocking): the gametest server sprints, so an echo waiting for a disk read
		// queued behind other tests' IO would burn hundreds of ticks of a test's tick budget in a fraction of a second.
		for (SealedSegment s : segments) {
			es.store().load(owner, s.seq()).join();
			es.store().cached(owner, s.seq());
		}
		PlayerStream ps = es.data().player(owner);
		ps.streamTick = Math.max(ps.streamTick, end);
		ps.nextSeq = Math.max(ps.nextSeq, nextSeq);
		ps.nextEchoIndex = TestSupport.NO_SCHEDULE;
		return ps;
	}

	/** The running replay of a synthetic stream: the owner (online, survival) and echo #1. */
	public record Replay(UUID owner, ServerPlayer player, EchoState echo, Stream stream) {
		public long cursor() {
			return echo.cursor;
		}
	}

	/**
	 * The standard harness: injects {@code s} as a fresh owner's past, logs the owner in at {@code ownerRel} (relative to
	 * the structure) and spawns echo #1 at cursor 0.
	 */
	public static Replay replay(GameTestHelper h, Stream s, Vec3 ownerRel) {
		EchoServer es = TestSupport.echoWorld(h);
		UUID owner = UUID.randomUUID();
		inject(es, owner, s.finish());
		ServerPlayer p = TestSupport.survivalPlayer(h, owner);
		TestSupport.teleport(h, p, ownerRel);
		EchoState echo = es.manager().spawnEcho(owner, 1, 0);
		return new Replay(owner, p, echo, s);
	}

	/** Same harness with the owner parked 40 blocks above the structure (near enough for full mode, out of the way). */
	public static Replay replay(GameTestHelper h, Stream s) {
		return replay(h, s, new Vec3(1.5, 40, 1.5));
	}
}
