package dev.echoaholic.core.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.Death;
import dev.echoaholic.core.action.Dimension;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.action.Teleport;

/**
 * One player's live recording: owns the stream tick T, move sampling, pose change filtering and segment rollover.
 * The MC side calls {@link #record} once per recorded tick and persists each returned {@link SealedSegment}
 * ({@link SegmentStore#write} + {@link RingBuffer#add}), and calls {@link #flush()} when the stream stops for a while
 * (logout, server stop). Every segment starts with a move sample and (if a pose is given) the current pose, so it can be
 * replayed on its own.
 */
public final class StreamRecorder {
	private final int segmentTicks;
	private final MoveSampler sampler = new MoveSampler();
	private long tick;
	private long nextSeq;
	private SegmentWriter writer;
	private Pose lastPose;

	/** Resumes a stream whose next tick is {@code nextTick} and next segment number {@code nextSeq}. */
	public StreamRecorder(long nextTick, long nextSeq) {
		this(nextTick, nextSeq, SegmentWriter.SEGMENT_TICKS);
	}

	public StreamRecorder(long nextTick, long nextSeq, int segmentTicks) {
		if (nextTick < 0) throw new IllegalArgumentException("negative tick");
		if (segmentTicks < 1) throw new IllegalArgumentException("segmentTicks must be >= 1");
		this.tick = nextTick;
		this.nextSeq = nextSeq;
		this.segmentTicks = segmentTicks;
	}

	/** Stream tick T: the tick the next {@link #record} call records (= ticks recorded so far). */
	public long tick() {
		return tick;
	}

	/** Sequence number the next sealed segment gets. */
	public long nextSeq() {
		return nextSeq;
	}

	public boolean hasOpenSegment() {
		return writer != null;
	}

	/** Uncompressed bytes of the open segment (0 when none). */
	public int openSegmentBytes() {
		return writer == null ? 0 : writer.bytesSoFar();
	}

	/**
	 * Records the current tick and advances T. {@code pose} may be null (not tracked); it is stored only when it changed
	 * and at every segment start, before the other actions. A {@link Teleport}, {@link Dimension} or {@link Death} forces
	 * a move sample this tick. Returns the segment this tick completed, if any.
	 */
	public Optional<SealedSegment> record(Move current, Pose pose, List<? extends Action> actions) {
		Objects.requireNonNull(current, "current");
		boolean segmentStart = writer == null;
		if (segmentStart) {
			writer = new SegmentWriter(tick, segmentTicks);
			sampler.forceNext();
		}
		List<Action> list = new ArrayList<>((actions == null ? 0 : actions.size()) + 1);
		if (pose != null && (segmentStart || !pose.equals(lastPose))) {
			list.add(pose);
			lastPose = pose;
		}
		if (actions != null) {
			for (Action a : actions) {
				if (a instanceof Teleport || a instanceof Dimension || a instanceof Death) sampler.forceNext();
				list.add(a);
			}
		}
		Move move = sampler.sample(current) ? current : null;
		writer.append(tick, move, list);
		tick++;
		if (tick == writer.endTick()) return Optional.of(sealOpen());
		return Optional.empty();
	}

	/** Seals the open segment early (covering up to T); empty when no tick is open. */
	public Optional<SealedSegment> flush() {
		if (writer == null) return Optional.empty();
		if (tick == writer.startTick()) {
			writer = null;
			return Optional.empty();
		}
		return Optional.of(sealOpen());
	}

	private SealedSegment sealOpen() {
		long start = writer.startTick();
		byte[] bytes = writer.seal(tick);
		writer = null;
		return new SealedSegment(nextSeq++, start, tick, bytes);
	}
}
