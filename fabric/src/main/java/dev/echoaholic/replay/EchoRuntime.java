package dev.echoaholic.replay;

import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.stream.DecodedSegment;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.storage.PlayerStream;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transient (never saved) replay state of one echo, owned by {@link EchoManager} on the server thread. The persistent
 * part lives in {@link EchoState}.
 */
public final class EchoRuntime {
	final UUID owner;
	final PlayerStream stream;
	final EchoState state;

	/** The single live entity of this echo, or null (not spawned yet, unloaded, changing dimension). */
	@Nullable EchoEntity entity;
	/** Level of {@link EchoState#dimension}, resolved once per change. */
	@Nullable ServerLevel level;
	/** Decoded segment holding the cursor (a cache reference, not a copy). */
	@Nullable DecodedSegment segment;
	/** Index into {@code segment.entries()} of the first entry with tick &gt;= cursor. */
	int entryIndex;
	/** Cursor value {@link #entryIndex} was computed for (-1 = recompute). */
	long entryCursor = -1;
	/** Pending load of {@link #pendingSeq}. */
	@Nullable CompletableFuture<DecodedSegment> pending;
	long pendingSeq = -1;

	int unloadedTicks;
	int stuckTicks;
	boolean cheap;
	int cheapCountdown;
	/** Spawn burst on the first entity spawn only (new echoes, not restarts / reloads / dimension changes). */
	boolean burstPending;
	boolean removed;
	/** The cursor jumped (gap, failed load): a far target is reached by teleport instead of walking. */
	boolean resync;

	Pose pose = Pose.STANDING;
	Activity activity = Activity.IDLE;
	@Nullable Activity lastWorldActivity;
	long lastWorldActionTick = Long.MIN_VALUE / 2;
	boolean moved;
	/** Last steering target, to know whether the echo walks. */
	double lastTargetX = Double.NaN, lastTargetY, lastTargetZ;

	EchoRuntime(UUID owner, PlayerStream stream, EchoState state) {
		this.owner = owner;
		this.stream = stream;
		this.state = state;
		this.cheapCountdown = Math.floorMod(state.index, MovementRules.CHEAP_CHECK_INTERVAL);
	}

	public UUID owner() {
		return owner;
	}

	public EchoState state() {
		return state;
	}

	public @Nullable EchoEntity entity() {
		return entity;
	}

	public Activity activity() {
		return activity;
	}

	public boolean cheap() {
		return cheap;
	}

	/** The last replayed body pose. */
	public Pose pose() {
		return pose;
	}
}
