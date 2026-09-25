package dev.echoaholic.record;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.stream.StreamRecorder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The live recording of one online player: the core {@link StreamRecorder}, the actions captured since the last
 * recorded tick, and the position/dimension of the last recorded tick (for teleport and dimension detection). Server
 * thread only.
 */
public final class PlayerRecording {
	/** Most actions buffered for one tick; anything beyond is dropped (protects the stream from pathological spam). */
	static final int MAX_PENDING = 512;

	private final UUID owner;
	private StreamRecorder recorder;
	private int segmentTicks;
	private final List<Action> pending = new ArrayList<>();
	private @Nullable Vec3 lastPos;
	private @Nullable ResourceKey<Level> lastDimension;
	private boolean streamed;

	PlayerRecording(UUID owner, StreamRecorder recorder, int segmentTicks) {
		this.owner = owner;
		this.recorder = recorder;
		this.segmentTicks = segmentTicks;
	}

	public UUID owner() {
		return owner;
	}

	/** Stream tick T (ticks recorded so far). */
	public long tick() {
		return recorder.tick();
	}

	public long nextSeq() {
		return recorder.nextSeq();
	}

	/** Whether T advanced in the current server tick. */
	public boolean streamed() {
		return streamed;
	}

	StreamRecorder recorder() {
		return recorder;
	}

	int segmentTicks() {
		return segmentTicks;
	}

	/** Replaces the core recorder (wipe, or a segment length change between segments). The open segment is dropped. */
	void reset(StreamRecorder recorder, int segmentTicks) {
		this.recorder = recorder;
		this.segmentTicks = segmentTicks;
		pending.clear();
	}

	void add(Action a) {
		if (pending.size() < MAX_PENDING) pending.add(a);
	}

	/** Moves the buffered actions into {@code out}. */
	void drainTo(List<Action> out) {
		out.addAll(pending);
		pending.clear();
	}

	void clearPending() {
		pending.clear();
	}

	@Nullable Vec3 lastPos() {
		return lastPos;
	}

	@Nullable ResourceKey<Level> lastDimension() {
		return lastDimension;
	}

	void recorded(Vec3 pos, ResourceKey<Level> dimension) {
		lastPos = pos;
		lastDimension = dimension;
		streamed = true;
	}

	void setStreamed(boolean streamed) {
		this.streamed = streamed;
	}
}
