package dev.echoaholic.record;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.echoaholic.Echoaholic;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.Dimension;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.action.Teleport;
import dev.echoaholic.core.stream.RingBuffer;
import dev.echoaholic.core.stream.SealedSegment;
import dev.echoaholic.core.stream.StreamRecorder;
import dev.echoaholic.storage.EchoTuning;
import dev.echoaholic.storage.EchoWorldData;
import dev.echoaholic.storage.OwnerProfile;
import dev.echoaholic.storage.PlayerStream;
import dev.echoaholic.storage.StreamStore;
import dev.echoaholic.util.Ids;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Records every online player's stream (ARCH §3.2). Capture hooks buffer actions through {@link #capture}; once per
 * server tick ({@link #tick}, before the echo manager) each recorded player gets one stream tick: dimension/teleport
 * detection, pose, move sample and the buffered actions. Sealed segments go to the {@link StreamStore}, then the ring
 * buffer is trimmed to the retention window.
 *
 * <p>Gating: the stream runs only while the mode is on and the player is in survival or adventure. Otherwise it is
 * paused, not flushed: T stands still and the open segment stays open. Server thread only.
 */
public final class Recorder {
	/** A jump longer than this between two recorded ticks is a teleport (legit movement stays under ~4 blocks/tick). */
	public static final double TELEPORT_DISTANCE = 8.0;
	private static final double TELEPORT_DISTANCE_SQR = TELEPORT_DISTANCE * TELEPORT_DISTANCE;

	private final MinecraftServer server;
	private final EchoWorldData data;
	private final StreamStore store;
	private final Map<UUID, PlayerRecording> recordings = new LinkedHashMap<>();
	private final List<Action> scratch = new ArrayList<>();

	public Recorder(MinecraftServer server, EchoWorldData data, StreamStore store) {
		this.server = server;
		this.data = data;
		this.store = store;
	}

	/**
	 * Starts tracking a player and refreshes their cached profile. Crash recovery: T and the next segment number resume
	 * after whatever is newer, the saved meta or the segments on disk.
	 */
	public void onJoin(ServerPlayer p) {
		UUID id = p.getUUID();
		PlayerStream meta = data.player(id);
		meta.profile = OwnerProfile.of(p.getGameProfile());
		data.setDirty();
		if (recordings.containsKey(id)) return;
		RingBuffer ring = store.ring(id);
		long tick = Math.max(meta.streamTick, ring.newestEndTick());
		long seq = meta.nextSeq;
		if (!ring.isEmpty()) seq = Math.max(seq, ring.segments().getLast().seq() + 1);
		meta.streamTick = tick;
		meta.nextSeq = seq;
		int segmentTicks = EchoTuning.segmentTicks();
		recordings.put(id, new PlayerRecording(id, new StreamRecorder(tick, seq, segmentTicks), segmentTicks));
	}

	/** Seals the open segment and stops tracking the player. Idempotent. */
	public void onLeave(ServerPlayer p) {
		PlayerRecording rec = recordings.remove(p.getUUID());
		if (rec != null) flush(rec);
	}

	/** END_SERVER_TICK, before the echo manager. */
	public void tick() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!recordings.containsKey(p.getUUID())) onJoin(p); // join event missed (e.g. joined before start)
		}
		boolean enabled = data.config().enabled();
		Iterator<PlayerRecording> it = recordings.values().iterator();
		while (it.hasNext()) {
			PlayerRecording rec = it.next();
			rec.setStreamed(false);
			ServerPlayer p = server.getPlayerList().getPlayer(rec.owner()); // respawn replaces the object: look up by id
			if (p == null) {
				it.remove(); // leave event missed
				flush(rec);
				continue;
			}
			if (!enabled || !streamingMode(p)) {
				rec.clearPending(); // paused: T and the open segment stay as they are
				continue;
			}
			try {
				step(rec, p);
			} catch (RuntimeException e) {
				rec.clearPending();
				Echoaholic.LOGGER.error("Echoaholic: recording tick failed for {}", rec.owner(), e);
			}
		}
	}

	private void step(PlayerRecording rec, ServerPlayer p) {
		int segmentTicks = EchoTuning.segmentTicks();
		StreamRecorder recorder = rec.recorder();
		if (!recorder.hasOpenSegment() && segmentTicks != rec.segmentTicks()) {
			// tuning changed (gametests): switch between segments only
			List<Action> keep = new ArrayList<>();
			rec.drainTo(keep);
			rec.reset(new StreamRecorder(recorder.tick(), recorder.nextSeq(), segmentTicks), segmentTicks);
			keep.forEach(rec::add);
			recorder = rec.recorder();
		}
		ResourceKey<Level> dim = p.level().dimension();
		Vec3 pos = p.position();
		List<Action> actions = scratch;
		actions.clear();
		ResourceKey<Level> lastDim = rec.lastDimension();
		Vec3 lastPos = rec.lastPos();
		if (!recorder.hasOpenSegment() || (lastDim != null && !lastDim.equals(dim))) {
			// D8 keyframe at every segment start; doubles as the real dimension change record
			actions.add(new Dimension(Ids.dimension(dim), pos.x, pos.y, pos.z));
		} else if (lastPos != null && lastPos.distanceToSqr(pos) > TELEPORT_DISTANCE_SQR) {
			actions.add(new Teleport(pos.x, pos.y, pos.z));
		}
		rec.drainTo(actions);
		Pose pose = new Pose(p.isShiftKeyDown(), p.isSprinting(), p.isSwimming() || p.isVisuallySwimming(), p.isFallFlying());
		Move move = new Move(pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
		Optional<SealedSegment> sealed = recorder.record(move, pose, actions);
		actions.clear();
		rec.recorded(pos, dim);
		if (sealed.isPresent()) {
			store.append(rec.owner(), sealed.get());
			store.evict(rec.owner(), recorder.tick(), data.bufferTicks());
		}
		updateMeta(rec);
	}

	private static boolean streamingMode(ServerPlayer p) {
		GameType mode = p.gameMode();
		return mode == GameType.SURVIVAL || mode == GameType.ADVENTURE;
	}

	/** Mode on, the player is tracked and in survival or adventure. */
	public boolean isRecording(ServerPlayer p) {
		return data.config().enabled() && streamingMode(p) && recordings.containsKey(p.getUUID()) && isRealPlayer(p);
	}

	/** The online instance of that UUID; mod fake players (which may carry a real player's UUID) never are. */
	private boolean isRealPlayer(ServerPlayer p) {
		return !(p instanceof FakePlayer) && server.getPlayerList().getPlayer(p.getUUID()) == p;
	}

	/** Online, and T advanced in this server tick. */
	public boolean isStreaming(UUID owner) {
		PlayerRecording rec = recordings.get(owner);
		return rec != null && rec.streamed();
	}

	/** Buffers an action for the player's next recorded tick; dropped when the player is not being recorded. */
	public void capture(ServerPlayer p, Action a) {
		if (!isRecording(p)) return;
		PlayerRecording rec = recordings.get(p.getUUID());
		if (rec != null) rec.add(a);
	}

	/** SERVER_STOPPING: seals every open segment. Players stay tracked; a later tick opens a new segment. */
	public void flushAll() {
		for (PlayerRecording rec : recordings.values()) flush(rec);
	}

	/** /echoaholic clear: drops the open segment and restarts the stream at T = 0 (segment numbers keep counting). */
	public void wipe(UUID owner) {
		PlayerStream meta = data.player(owner);
		PlayerRecording rec = recordings.get(owner);
		long seq = meta.nextSeq;
		if (rec != null) {
			seq = Math.max(seq, rec.nextSeq());
			int segmentTicks = EchoTuning.segmentTicks();
			rec.reset(new StreamRecorder(0, seq, segmentTicks), segmentTicks);
			rec.setStreamed(false);
		}
		meta.streamTick = 0;
		meta.nextSeq = seq;
		data.setDirty();
	}

	/** The live recording of an online player, or null (tests / tooling). */
	public @Nullable PlayerRecording recording(UUID owner) {
		return recordings.get(owner);
	}

	private void flush(PlayerRecording rec) {
		rec.clearPending();
		try {
			rec.recorder().flush().ifPresent(s -> store.append(rec.owner(), s));
		} catch (RuntimeException e) {
			Echoaholic.LOGGER.error("Echoaholic: could not seal the stream of {}", rec.owner(), e);
		}
		updateMeta(rec);
		data.setDirty();
	}

	private void updateMeta(PlayerRecording rec) {
		PlayerStream meta = data.player(rec.owner());
		meta.streamTick = rec.tick();
		meta.nextSeq = rec.nextSeq();
	}
}
