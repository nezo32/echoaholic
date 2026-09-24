package dev.echoaholic.replay;

import dev.echoaholic.Echoaholic;
import dev.echoaholic.Feedback;
import dev.echoaholic.core.BudgetScheduler;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.EchoSchedule;
import dev.echoaholic.core.VirtualInventory;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.ActionTypes;
import dev.echoaholic.core.action.ActionType;
import dev.echoaholic.core.action.Death;
import dev.echoaholic.core.action.Dimension;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Teleport;
import dev.echoaholic.core.stream.DecodedSegment;
import dev.echoaholic.core.stream.RingBuffer;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.TickEntry;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.entity.EchoListener;
import dev.echoaholic.entity.EchoVisuals;
import dev.echoaholic.net.EchoTrailPayload;
import dev.echoaholic.record.Recorder;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.storage.EchoWorldData;
import dev.echoaholic.storage.OwnerProfile;
import dev.echoaholic.storage.PlayerStream;
import dev.echoaholic.storage.StreamStore;
import dev.echoaholic.util.Ids;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The replay loop (ARCH §5): spawns and retires echoes from the schedule, keeps exactly one entity per echo alive where
 * its position is entity-ticking, advances each echo's cursor one stream tick per server tick (stalls only add lag),
 * steers it along the recorded path and runs the recorded actions through {@link ReplayHandlers} under the
 * {@link BudgetScheduler}. Server thread only.
 */
public final class EchoManager implements EchoListener {
	private static final int FROZEN = 0, STALLED = 1, ADVANCED = 2, GONE = 3;

	private final MinecraftServer server;
	private final EchoWorldData data;
	private final StreamStore store;
	private final Recorder recorder;
	private final BudgetScheduler budget = new BudgetScheduler();
	private final ReplayContext ctx = new ReplayContext(this);
	private final Map<UUID, EchoRuntime> runtimes = new HashMap<>();
	private final List<EchoRuntime> runtimeList = new ArrayList<>();
	private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
	private final float[] trailScratch = new float[MovementRules.TRAIL_POINTS * 3];

	private final List<EchoRuntime> orderScratch = new ArrayList<>();
	/** Owner -> echo number whose first segment was already prefetched. */
	private final Map<UUID, Integer> spawnPrefetched = new HashMap<>();
	private final double[] targetScratch = new double[5];
	private final double[] nextScratch = new double[5];
	private final double[] trailPoint = new double[5];

	private EchoConfig cfg = EchoConfig.DEFAULT;
	private long now;
	/** Counts tickOnce calls; a runtime created during one is not ticked in it (lag is exactly k * delay). */
	private long tickSeq;
	private TickStats lastStats = TickStats.EMPTY;

	public EchoManager(MinecraftServer server, EchoWorldData data, StreamStore store, Recorder recorder) {
		this.server = server;
		this.data = data;
		this.store = store;
		this.recorder = recorder;
		EchoEntity.setListener(this);
		reconcile();
	}

	// ------------------------------------------------------------------------------------------------ tick

	/** END_SERVER_TICK, after {@code recorder.tick()}. */
	public void tick() {
		tickOnce();
	}

	/** The tick body; returns what it did. */
	public TickStats tickOnce() {
		long start = System.nanoTime();
		tickSeq++;
		store.beginTick();
		cfg = data.config();
		now = server.getTickCount();
		if (!cfg.enabled()) {
			lastStats = new TickStats(0, 0, 0, 0, 0, 0, 0, 0, System.nanoTime() - start);
			return lastStats;
		}
		budget.beginTick(cfg.globalBlockOpsPerTick(), cfg.globalHazardOpsPerTick());
		if (!cfg.paused()) schedule();

		int echoes = 0, advanced = 0, stalled = 0, frozen = 0;
		List<EchoRuntime> order = budget.order(runtimeList, orderScratch);
		for (int i = 0, n = order.size(); i < n; i++) {
			EchoRuntime rt = order.get(i);
			if (rt.removed || rt.bornSeq == tickSeq) continue;
			echoes++;
			int r;
			try {
				r = tickEcho(rt);
			} catch (RuntimeException e) {
				Echoaholic.LOGGER.error("Echoaholic: echo #{} of {} failed this tick", rt.state.index, rt.owner, e);
				r = STALLED;
			}
			switch (r) {
				case FROZEN -> frozen++;
				case STALLED -> stalled++;
				case ADVANCED -> advanced++;
				default -> { }
			}
		}
		orderScratch.clear();
		lastStats = new TickStats(echoes, advanced, stalled, frozen, budget.blockOpsUsed(), budget.hazardOpsUsed(),
				budget.lookupsUsed(), budget.deferred(), System.nanoTime() - start);
		return lastStats;
	}

	public TickStats lastTickStats() {
		return lastStats;
	}

	/** Spawn / retire decisions for every streaming owner. Allocation-free unless something is due. */
	private void schedule() {
		long delay = data.delayTicks();
		long retention = data.bufferTicks();
		int cap = cfg.maxEchoes();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (int i = 0, n = players.size(); i < n; i++) {
			ServerPlayer player = players.get(i);
			UUID owner = player.getUUID();
			if (!recorder.isStreaming(owner)) continue;
			PlayerStream stream = data.playerIfPresent(owner);
			if (stream == null) continue;
			long t = stream.streamTick;
			RingBuffer ring = store.ring(owner);
			long oldest = ring.oldestRetainedTick();
			long dueTick = (long) stream.nextEchoIndex * delay;
			if (t < dueTick && t >= dueTick - MovementRules.PREFETCH_TICKS) prefetchSpawn(stream, ring, dueTick, retention);
			boolean needed = t >= dueTick || stream.echoes.size() > cap;
			if (!needed) {
				for (int j = 0, m = stream.echoes.size(); j < m; j++) {
					if (stream.echoes.get(j).cursor < oldest) {
						needed = true;
						break;
					}
				}
			}
			if (!needed) continue;

			List<EchoSchedule.EchoSlot> slots = new ArrayList<>(stream.echoes.size());
			for (EchoState s : stream.echoes) slots.add(new EchoSchedule.EchoSlot(s.index, s.cursor));
			EchoSchedule.SpawnPlan plan = EchoSchedule.plan(t, stream.nextEchoIndex, delay, cap, oldest, retention, slots);
			for (int index : plan.retire()) {
				EchoState s = stream.echo(index);
				if (s == null) continue;
				boolean behindBuffer = s.cursor < oldest;
				retire(stream, s, !behindBuffer ? player : null);
			}
			if (plan.spawn()) {
				EchoState s = new EchoState(UUID.randomUUID(), plan.newIndex(), plan.newCursor());
				if (s.inventory == null) s.inventory = new VirtualInventory();
				stream.nextEchoIndex = plan.newIndex() + 1;
				stream.echoes.add(s);
				addRuntime(owner, stream, s, true);
				data.setDirty();
				Feedback.joined(player, plan.newIndex());
			}
		}
	}

	/**
	 * The next echo is about to be due: start loading the segment its cursor will start in (EchoSchedule's rule at the
	 * due tick: max(0, oldest, due - retention, due - k * delay)), once per echo number.
	 */
	private void prefetchSpawn(PlayerStream stream, RingBuffer ring, long dueTick, long retention) {
		Integer done = spawnPrefetched.get(stream.owner);
		if (done != null && done == stream.nextEchoIndex) return;
		spawnPrefetched.put(stream.owner, stream.nextEchoIndex);
		long cursor = Math.max(Math.max(0L, ring.oldestRetainedTick()), dueTick - retention);
		SegmentMeta meta = ring.segmentAtOrAfter(cursor).orElse(null);
		if (meta != null && store.cached(stream.owner, meta.seq()) == null) store.load(stream.owner, meta.seq());
	}

	/** One echo, one tick. Returns FROZEN / STALLED / ADVANCED / GONE. */
	private int tickEcho(EchoRuntime rt) {
		EchoState st = rt.state;
		BudgetScheduler.EchoBudget b = budget.forEcho(st.echoId, cfg.echoBlockOpsPerTick(), cfg.echoEntityLookupsPerTick());
		boolean active = !cfg.paused() && recorder.isStreaming(rt.owner);

		EchoEntity e = rt.entity;
		if (e != null && e.isRemoved()) {
			snapshot(rt, e);
			rt.entity = null;
			e = null;
		}

		// A new echo has no position yet: take it from the stream at its cursor.
		if (st.dimension == null || st.dimension.isEmpty()) {
			if (!active) {
				rt.activity = Activity.PAUSED;
				return FROZEN;
			}
			DecodedSegment seg = segment(rt);
			if (rt.removed) return GONE;
			if (seg == null || !locate(rt, seg)) {
				rt.activity = Activity.WAITING;
				return STALLED;
			}
		}

		// Jump pre-pass: a Teleport / Dimension at the cursor runs before anything depends on the current spot. Its move
		// sample is already the destination (possibly in another level), and the destination may be entity-ticking
		// while the current spot is not. Works with or without an entity.
		if (active && st.collapseTicks == 0) {
			DecodedSegment seg = segment(rt);
			if (rt.removed) return GONE;
			if (seg != null) {
				TickEntry entry = entryAt(rt, seg, st.cursor);
				if (hasJump(rt, entry, st.actionsDone)) {
					int r = runLeadingMeta(rt, entry, b);
					if (r != ADVANCED) return r;
					e = rt.entity;
				}
			}
		}

		ServerLevel level = level(rt);
		BlockPos pos = e != null ? e.blockPosition() : scratchPos.set(st.x, st.y, st.z);
		if (!level.isPositionEntityTicking(pos)) {
			rt.activity = Activity.PAUSED;
			if (e != null) {
				stopSteer(rt, e);
				if (++rt.unloadedTicks > MovementRules.UNLOADED_DISCARD_TICKS) {
					snapshot(rt, e);
					rt.entity = null;
					e.managedDiscard();
				}
			}
			return FROZEN;
		}
		rt.unloadedTicks = 0;

		if (e == null) {
			rt.cheap = MovementRules.nextCheap(rt.cheap, nearestPlayerSq(level, st.x, st.y, st.z), cfg.cheapModeDistance());
			e = spawnEntity(rt, level);
			if (e == null) {
				rt.activity = Activity.PAUSED;
				return FROZEN;
			}
		} else if (--rt.cheapCountdown <= 0) {
			rt.cheapCountdown = MovementRules.CHEAP_CHECK_INTERVAL;
			updateCheap(rt, e, level);
		}

		if (!active) {
			stopSteer(rt, e);
			if (st.collapseTicks > 0) e.startCollapse(st.collapseTicks); // stay down while frozen
			rt.activity = Activity.PAUSED;
			return FROZEN;
		}

		if (st.collapseTicks > 0) {
			st.collapseTicks--;
			// the manager's hold drives the collapse: re-arm the entity so it never stands up before the hold ends
			if (st.collapseTicks > 0) e.startCollapse(st.collapseTicks);
			stopSteer(rt, e);
			rt.activity = Activity.COLLAPSED;
			return STALLED;
		}

		DecodedSegment seg = segment(rt);
		if (rt.removed) return GONE;
		if (seg == null) {
			rt.activity = Activity.WAITING;
			return STALLED;
		}

		long c = st.cursor;
		TickEntry entry = entryAt(rt, seg, c);
		// A Death (or a jump the pre-pass did not see) at the cursor: run the leading meta actions before the reach check.
		if (hasPendingJump(entry, st.actionsDone)) {
			int r = runLeadingMeta(rt, entry, b);
			if (r != ADVANCED) return r;
			e = rt.entity;
			if (e == null) return STALLED; // respawn in a level position that is not ticking yet
			level = level(rt);
		}
		double[] tgt = targetScratch;
		boolean hasTarget = seg.positionAt(c, tgt);
		// A jump action still pending behind a world action: do not walk, the actions run in order below.
		if (hasTarget && !hasPendingJump(entry, st.actionsDone)) {
			if (rt.cheap) {
				e.snapTo(tgt[0], tgt[1], tgt[2], (float) tgt[3], (float) tgt[4]);
			} else {
				double dx = tgt[0] - e.getX(), dy = tgt[1] - e.getY(), dz = tgt[2] - e.getZ();
				if (!MovementRules.reached(dx, dy, dz)) {
					double distSq = dx * dx + dy * dy + dz * dz;
					if (rt.resync && MovementRules.isJump(distSq)) {
						teleport(rt, tgt[0], tgt[1], tgt[2]);
					} else {
						rt.stuckTicks++;
						steer(rt, e, tgt);
						if (rt.stuckTicks > MovementRules.STUCK_TELEPORT_TICKS
								&& level.noCollision(e, e.getBoundingBox().move(dx, dy, dz))) {
							teleport(rt, tgt[0], tgt[1], tgt[2]);
						}
						rt.resync = false;
						rt.activity = Activity.WAITING;
						return STALLED;
					}
				}
			}
		}
		rt.stuckTicks = 0;
		rt.resync = false;

		// Actions of tick c, resuming after the ones already done (budget stall mid-tick).
		if (entry != null) {
			List<Action> actions = entry.actions();
			int count = actions.size();
			if (st.actionsDone < count) ctx.bind(rt, cfg, b, c);
			for (int i = st.actionsDone; i < count; i++) {
				Action a = actions.get(i);
				ReplayHandler.Result r = ReplayHandlers.dispatch(ctx, a);
				if (rt.removed) return GONE;
				if (r == ReplayHandler.Result.WAIT) {
					st.actionsDone = i;
					rt.activity = Activity.WAITING;
					return STALLED;
				}
				if (r == ReplayHandler.Result.DONE) noteActionActivity(rt, a);
				if (rt.entity == null) {
					// moved to a level position that is not ticking: the rest of this tick waits for the respawn
					st.actionsDone = i + 1;
					return STALLED;
				}
			}
		}
		st.actionsDone = 0;
		st.cursor = c + 1;
		e = rt.entity;

		// Segment boundary: move into the next segment on this same tick when it is ready (prefetched), so the echo
		// does not idle; otherwise prefetch it while the cursor nears the end of this one.
		DecodedSegment nextSeg = seg;
		if (st.cursor >= seg.endTick()) {
			nextSeg = segment(rt);
			if (rt.removed) return ADVANCED;
		} else {
			prefetch(rt, seg);
		}

		// Next target. A tick that jumps (Teleport / Dimension) is handled by the pre-pass: its sample may be in
		// another level, so never walk or teleport toward it here.
		if (st.collapseTicks > 0) {
			stopSteer(rt, e);
		} else if (!rt.cheap && nextSeg != null && nextSeg.contains(st.cursor)
				&& !hasJump(rt, entryAt(rt, nextSeg, st.cursor), 0)) {
			double[] nxt = nextScratch;
			if (nextSeg.positionAt(st.cursor, nxt)) {
				if (MovementRules.isJump(e.distanceToSqr(nxt[0], nxt[1], nxt[2]))) {
					teleport(rt, nxt[0], nxt[1], nxt[2]);
				} else {
					steer(rt, e, nxt);
				}
			}
		}
		if (hasTarget) trackMovement(rt, tgt);
		rt.activity = restingActivity(rt);

		if (!rt.cheap && Math.floorMod(now + st.index, (long) MovementRules.TRAIL_INTERVAL) == 0) {
			sendTrail(rt, e, (ServerLevel) e.level(), nextSeg != null ? nextSeg : seg);
		}
		return ADVANCED;
	}

	/**
	 * Starts loading the segment after {@code seg} once the cursor is within {@link MovementRules#PREFETCH_TICKS} of its
	 * end (once per segment). The future is kept as the runtime's pending load, so {@link #segment} promotes it.
	 */
	private void prefetch(EchoRuntime rt, DecodedSegment seg) {
		long end = seg.endTick();
		if (end - rt.state.cursor > MovementRules.PREFETCH_TICKS || rt.prefetchedEnd == end) return;
		SegmentMeta next = store.ring(rt.owner).segmentAtOrAfter(end).orElse(null);
		if (next == null) return; // not sealed yet: try again next tick
		rt.prefetchedEnd = end;
		if (store.cached(rt.owner, next.seq()) != null) return;
		if (rt.pending != null && rt.pendingSeq == next.seq()) return;
		rt.pending = store.load(rt.owner, next.seq());
		rt.pendingSeq = next.seq();
	}

	/**
	 * Runs the meta actions (Teleport, Dimension, Death, Pose, Swing) at the front of {@code entry}, from actionsDone.
	 * Returns ADVANCED to continue the tick, GONE when the echo was removed, STALLED on a budget WAIT.
	 */
	private int runLeadingMeta(EchoRuntime rt, TickEntry entry, BudgetScheduler.EchoBudget b) {
		EchoState st = rt.state;
		List<Action> actions = entry.actions();
		ctx.bind(rt, cfg, b, st.cursor);
		while (st.actionsDone < actions.size() && isMeta(actions.get(st.actionsDone))) {
			ReplayHandler.Result r = ReplayHandlers.dispatch(ctx, actions.get(st.actionsDone));
			if (rt.removed) return GONE;
			if (r == ReplayHandler.Result.WAIT) {
				rt.activity = Activity.WAITING;
				return STALLED;
			}
			st.actionsDone++;
		}
		return ADVANCED;
	}

	/**
	 * True iff {@code entry} still has a real jump at or after {@code from}: a Teleport, or a Dimension action into
	 * another level or farther than {@link MovementRules#TELEPORT_DISTANCE}. The segment-start keyframe (D8) in the
	 * echo's own level nearby is not a jump.
	 */
	private static boolean hasJump(EchoRuntime rt, @Nullable TickEntry entry, int from) {
		if (entry == null) return false;
		List<Action> actions = entry.actions();
		for (int i = from, n = actions.size(); i < n; i++) {
			Action a = actions.get(i);
			if (a instanceof Teleport) return true;
			if (a instanceof Dimension d) {
				EchoState st = rt.state;
				if (!d.dimensionId().equals(st.dimension)) return true;
				EchoEntity e = rt.entity;
				double x = e != null ? e.getX() : st.x, y = e != null ? e.getY() : st.y, z = e != null ? e.getZ() : st.z;
				double dx = d.x() - x, dy = d.y() - y, dz = d.z() - z;
				if (MovementRules.isJump(dx * dx + dy * dy + dz * dz)) return true;
			}
		}
		return false;
	}

	/** True iff {@code entry} still has a Teleport, Dimension or Death action at or after {@code from}. */
	private static boolean hasPendingJump(@Nullable TickEntry entry, int from) {
		if (entry == null) return false;
		List<Action> actions = entry.actions();
		for (int i = from, n = actions.size(); i < n; i++) {
			Action a = actions.get(i);
			if (a instanceof Dimension || a instanceof Teleport || a instanceof Death) return true;
		}
		return false;
	}

	/** Actions that only affect the echo itself and may run before the reach check. */
	private static boolean isMeta(Action a) {
		ActionType<?> t = a.type();
		return t == ActionTypes.TELEPORT || t == ActionTypes.DIMENSION || t == ActionTypes.DEATH || t == ActionTypes.POSE
				|| t == ActionTypes.SWING;
	}

	/** Steers toward {@code p} = x, y, z, yRot, xRot; skips the call (and its Vec3) when nothing changed. */
	private void steer(EchoRuntime rt, EchoEntity e, double[] p) {
		boolean freeFlight = rt.pose.fallFlying() || rt.pose.swimming();
		if (rt.steering && rt.steerX == p[0] && rt.steerY == p[1] && rt.steerZ == p[2] && rt.steerFree == freeFlight) {
			// same target: only turn (mining / fighting in place still faces the recorded direction)
			float yRot = (float) p[3], xRot = (float) p[4];
			if (yRot != rt.steerYRot || xRot != rt.steerXRot) {
				e.look(yRot, xRot);
				rt.steerYRot = yRot;
				rt.steerXRot = xRot;
			}
			return;
		}
		e.steer(new Vec3(p[0], p[1], p[2]), (float) p[3], (float) p[4], freeFlight);
		rt.steering = true;
		rt.steerX = p[0];
		rt.steerY = p[1];
		rt.steerZ = p[2];
		rt.steerFree = freeFlight;
		rt.steerYRot = (float) p[3];
		rt.steerXRot = (float) p[4];
	}

	/** Stands still (keeps the current rotation). */
	private static void stopSteer(EchoRuntime rt, EchoEntity e) {
		e.steer(null, e.getYRot(), e.getXRot(), false);
		rt.steering = false;
	}

	private void trackMovement(EchoRuntime rt, double[] p) {
		if (Double.isNaN(rt.lastTargetX)) {
			rt.moved = false;
		} else {
			double dx = p[0] - rt.lastTargetX, dy = p[1] - rt.lastTargetY, dz = p[2] - rt.lastTargetZ;
			rt.moved = dx * dx + dy * dy + dz * dz > MovementRules.MOVE_EPSILON * MovementRules.MOVE_EPSILON;
		}
		rt.lastTargetX = p[0];
		rt.lastTargetY = p[1];
		rt.lastTargetZ = p[2];
	}

	private Activity restingActivity(EchoRuntime rt) {
		if (rt.state.collapseTicks > 0) return Activity.COLLAPSED;
		if (rt.lastWorldActivity != null && now - rt.lastWorldActionTick <= MovementRules.ACTIVITY_WINDOW) {
			return rt.lastWorldActivity;
		}
		return rt.moved ? Activity.WALKING : Activity.IDLE;
	}

	private void noteActionActivity(EchoRuntime rt, Action a) {
		ActionType<?> type = a.type();
		Activity act;
		if (type == ActionTypes.BLOCK_BREAK) act = Activity.MINING;
		else if (type == ActionTypes.BLOCK_PLACE) act = Activity.BUILDING;
		else if (type == ActionTypes.ATTACK || type == ActionTypes.SHOOT) act = Activity.FIGHTING;
		else return;
		noteWorldActivity(rt, act);
	}

	/** Called by handlers through {@link ReplayContext#activity}. */
	void noteWorldActivity(EchoRuntime rt, Activity a) {
		if (a == Activity.MINING || a == Activity.BUILDING || a == Activity.FIGHTING) {
			rt.lastWorldActivity = a;
			rt.lastWorldActionTick = now;
		}
	}

	// ------------------------------------------------------------------------------------------------ segments

	/**
	 * The decoded segment holding the echo's cursor, or null when it is still loading or the stream has no data there
	 * yet. Skips gaps (and segments that failed to load) by moving the cursor; retires the echo when its cursor fell
	 * behind the ring buffer. Never blocks.
	 */
	private @Nullable DecodedSegment segment(EchoRuntime rt) {
		EchoState st = rt.state;
		DecodedSegment seg = rt.segment;
		if (seg != null && seg.contains(st.cursor)) return seg;
		rt.segment = null;

		RingBuffer ring = store.ring(rt.owner);
		SegmentMeta meta = ring.segmentFor(st.cursor).orElse(null);
		if (meta == null) {
			if (st.cursor < ring.oldestRetainedTick()) {
				retire(rt.stream, st, null);
				return null;
			}
			meta = ring.segmentAtOrAfter(st.cursor).orElse(null);
			if (meta == null) return null; // not sealed yet
			jumpCursor(rt, meta.startTick());
		}

		DecodedSegment decoded = store.cached(rt.owner, meta.seq());
		if (decoded == null) {
			if (rt.pending == null || rt.pendingSeq != meta.seq()) {
				rt.pending = store.load(rt.owner, meta.seq());
				rt.pendingSeq = meta.seq();
			}
			CompletableFuture<DecodedSegment> f = rt.pending;
			if (!f.isDone()) {
				// bytes already in memory (just sealed / read): decode now, within the store's per-tick budget
				decoded = store.decodeNowIfResident(rt.owner, meta.seq());
				if (decoded == null) return null;
			} else if (!f.isCompletedExceptionally() && !f.isCancelled()) {
				decoded = f.join();
			}
			rt.pending = null;
			rt.pendingSeq = -1;
			if (decoded == null || !decoded.contains(st.cursor)) {
				// unreadable segment: treat as a gap
				jumpCursor(rt, meta.endTick());
				return null;
			}
		}
		rt.segment = decoded;
		rt.entryCursor = -1;
		return decoded;
	}

	private void jumpCursor(EchoRuntime rt, long cursor) {
		if (cursor <= rt.state.cursor) return;
		rt.state.cursor = cursor;
		rt.state.actionsDone = 0;
		rt.resync = true;
	}

	/** The recorded entry at exactly tick {@code c}, or null; walks an index forward instead of searching. */
	private static @Nullable TickEntry entryAt(EchoRuntime rt, DecodedSegment seg, long c) {
		List<TickEntry> entries = seg.entries();
		int i;
		if (rt.entrySegment == seg && rt.entryCursor == c) {
			i = rt.entryIndex;
		} else if (rt.entrySegment == seg && rt.entryCursor == c - 1 && rt.entryCursor >= 0) {
			i = rt.entryIndex;
			if (i < entries.size() && entries.get(i).tick() < c) i++;
		} else {
			int lo = 0, hi = entries.size();
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (entries.get(mid).tick() < c) lo = mid + 1;
				else hi = mid;
			}
			i = lo;
		}
		rt.entryIndex = i;
		rt.entryCursor = c;
		rt.entrySegment = seg;
		return i < entries.size() && entries.get(i).tick() == c ? entries.get(i) : null;
	}

	/** Positions a new echo from its segment: the last Dimension keyframe at or before the cursor + the move there. */
	private boolean locate(EchoRuntime rt, DecodedSegment seg) {
		EchoState st = rt.state;
		Dimension dim = null;
		for (TickEntry entry : seg.entries()) {
			if (entry.tick() > st.cursor) break;
			for (Action a : entry.actions()) {
				if (a instanceof Dimension d) dim = d;
			}
		}
		Move move = seg.positionAt(st.cursor).orElse(null);
		String dimId;
		double x, y, z;
		float yRot = 0f, xRot = 0f;
		if (move != null) {
			x = move.x();
			y = move.y();
			z = move.z();
			yRot = move.yRot();
			xRot = move.xRot();
		} else if (dim != null) {
			x = dim.x();
			y = dim.y();
			z = dim.z();
		} else {
			ServerPlayer owner = server.getPlayerList().getPlayer(rt.owner);
			if (owner == null) return false;
			x = owner.getX();
			y = owner.getY();
			z = owner.getZ();
		}
		if (dim != null) {
			dimId = dim.dimensionId();
		} else {
			ServerPlayer owner = server.getPlayerList().getPlayer(rt.owner);
			dimId = Ids.dimension(owner != null ? owner.level().dimension() : Level.OVERWORLD);
		}
		st.dimension = dimId;
		st.x = x;
		st.y = y;
		st.z = z;
		st.yRot = yRot;
		st.xRot = xRot;
		rt.level = null;
		data.setDirty();
		return true;
	}

	// ------------------------------------------------------------------------------------------------ entities

	private ServerLevel level(EchoRuntime rt) {
		ServerLevel level = rt.level;
		if (level != null) return level;
		ResourceKey<Level> key = rt.state.dimension == null ? null : Ids.dimensionOf(rt.state.dimension);
		level = key == null ? null : server.getLevel(key);
		if (level == null) {
			level = server.overworld();
			rt.state.dimension = Ids.dimension(Level.OVERWORLD);
		}
		rt.level = level;
		return level;
	}

	private @Nullable EchoEntity spawnEntity(EchoRuntime rt, ServerLevel level) {
		EchoState st = rt.state;
		OwnerProfile profile = rt.stream.profile;
		ResolvableProfile resolvable = profile != null ? profile.toResolvable(rt.owner) : ResolvableProfile.createUnresolved(rt.owner);
		String name = profile != null ? profile.name() : ownerName(rt.owner);
		EchoEntity e = new EchoEntity(level, st.echoId, rt.owner, st.index, resolvable, name);
		e.snapTo(st.x, st.y, st.z, st.yRot, st.xRot);
		if (st.health > 0f) e.setHealth(Math.min(st.health, e.getMaxHealth()));
		e.setIgnoreBlockTriggers(!cfg.triggerBlocks());
		e.setCheap(rt.cheap);
		if (!level.addFreshEntity(e)) return null;
		rt.entity = e;
		rt.level = level;
		rt.steering = false;
		if (!rt.cheap && !rt.pose.equals(dev.echoaholic.core.action.Pose.STANDING)) e.applyPose(rt.pose);
		if (st.collapseTicks > 0) e.startCollapse(st.collapseTicks);
		if (rt.burstPending) {
			rt.burstPending = false;
			if (!rt.cheap) EchoVisuals.spawnBurst(level, e.position());
		}
		return e;
	}

	private String ownerName(UUID owner) {
		ServerPlayer p = server.getPlayerList().getPlayer(owner);
		return p != null ? p.getGameProfile().name() : owner.toString().substring(0, 8);
	}

	private void updateCheap(EchoRuntime rt, EchoEntity e, ServerLevel level) {
		boolean next = MovementRules.nextCheap(rt.cheap, nearestPlayerSq(level, e.getX(), e.getY(), e.getZ()),
				cfg.cheapModeDistance());
		e.setIgnoreBlockTriggers(!cfg.triggerBlocks());
		if (next == rt.cheap) return;
		rt.cheap = next;
		e.setCheap(next);
		if (!next) e.applyPose(rt.pose);
	}

	private static double nearestPlayerSq(ServerLevel level, double x, double y, double z) {
		double best = Double.MAX_VALUE;
		List<ServerPlayer> players = level.players();
		for (int i = 0, n = players.size(); i < n; i++) {
			double d = players.get(i).distanceToSqr(x, y, z);
			if (d < best) best = d;
		}
		return best;
	}

	/** Same-level snap. */
	void teleport(EchoRuntime rt, double x, double y, double z) {
		EchoEntity e = rt.entity;
		rt.stuckTicks = 0;
		if (e == null) {
			rt.state.x = x;
			rt.state.y = y;
			rt.state.z = z;
			return;
		}
		e.teleportTo(x, y, z);
		stopSteer(rt, e); // otherwise the entity walks back toward its pre-jump target for a tick
	}

	/** Dimension action: same level = teleport when far (the per-segment keyframe case); other level = discard + respawn. */
	void changeDimension(EchoRuntime rt, ResourceKey<Level> key, double x, double y, double z) {
		ServerLevel target = server.getLevel(key);
		if (target == null) return;
		EchoEntity e = rt.entity;
		ServerLevel current = level(rt);
		if (target == current) {
			EchoState s0 = rt.state;
			double distSq = e != null ? e.distanceToSqr(x, y, z)
					: (x - s0.x) * (x - s0.x) + (y - s0.y) * (y - s0.y) + (z - s0.z) * (z - s0.z);
			if (MovementRules.isJump(distSq)) teleport(rt, x, y, z);
			return;
		}
		EchoState st = rt.state;
		if (e != null) {
			snapshot(rt, e);
			rt.entity = null;
			e.managedDiscard();
		}
		st.dimension = Ids.dimension(key);
		st.x = x;
		st.y = y;
		st.z = z;
		rt.level = target;
		rt.stuckTicks = 0;
		rt.unloadedTicks = 0;
		data.setDirty();
		if (target.isPositionEntityTicking(scratchPos.set(x, y, z))) spawnEntity(rt, target);
	}

	void collapse(EchoRuntime rt, int ticks) {
		rt.state.collapseTicks = ticks;
		EchoEntity e = rt.entity;
		if (e != null) {
			stopSteer(rt, e);
			e.startCollapse(ticks);
		}
	}

	private static void snapshot(EchoRuntime rt, EchoEntity e) {
		EchoState st = rt.state;
		st.x = e.getX();
		st.y = e.getY();
		st.z = e.getZ();
		st.yRot = e.getYRot();
		st.xRot = e.getXRot();
		float health = e.getHealth();
		if (health > 0f) st.health = health;
		if (e.level() instanceof ServerLevel level) {
			st.dimension = Ids.dimension(level.dimension());
			rt.level = level;
		}
	}

	// ------------------------------------------------------------------------------------------------ trail

	private void sendTrail(EchoRuntime rt, EchoEntity e, ServerLevel level, DecodedSegment seg) {
		double rangeSq = MovementRules.TRAIL_RANGE * MovementRules.TRAIL_RANGE;
		List<ServerPlayer> players = level.players();
		EchoTrailPayload payload = null;
		for (int i = 0, n = players.size(); i < n; i++) {
			ServerPlayer p = players.get(i);
			if (p.distanceToSqr(e) > rangeSq || !ServerPlayNetworking.canSend(p, EchoTrailPayload.TYPE)) continue;
			if (payload == null) {
				payload = trail(rt, e, seg);
				if (payload == null) return;
			}
			ServerPlayNetworking.send(p, payload);
		}
	}

	/** The next 100 stream ticks after the cursor, every 5 ticks; stops at a jump, a gap or an uncached segment. */
	private @Nullable EchoTrailPayload trail(EchoRuntime rt, EchoEntity e, DecodedSegment seg) {
		float[] buf = trailScratch;
		int n = 0;
		long c = rt.state.cursor;
		DecodedSegment s = seg;
		double px = e.getX(), py = e.getY(), pz = e.getZ();
		double jumpSq = DecodedSegment.JUMP_DISTANCE * DecodedSegment.JUMP_DISTANCE;
		for (int j = 1; j <= MovementRules.TRAIL_POINTS; j++) {
			long t = c + (long) j * MovementRules.TRAIL_STEP;
			if (!s.contains(t)) {
				Optional<SegmentMeta> meta = store.ring(rt.owner).segmentFor(t);
				if (meta.isEmpty()) break;
				DecodedSegment next = store.cached(rt.owner, meta.get().seq());
				if (next == null) break;
				s = next;
			}
			double[] m = trailPoint;
			if (!s.positionAt(t, m)) break;
			double dx = m[0] - px, dy = m[1] - py, dz = m[2] - pz;
			if (dx * dx + dy * dy + dz * dz > jumpSq) break;
			buf[n * 3] = (float) m[0];
			buf[n * 3 + 1] = (float) m[1];
			buf[n * 3 + 2] = (float) m[2];
			n++;
			px = m[0];
			py = m[1];
			pz = m[2];
		}
		return n == 0 ? null : new EchoTrailPayload(e.getId(), Arrays.copyOf(buf, n * 3));
	}

	// ------------------------------------------------------------------------------------------------ bookkeeping

	private EchoRuntime addRuntime(UUID owner, PlayerStream stream, EchoState state, boolean burst) {
		EchoRuntime rt = new EchoRuntime(owner, stream, state);
		rt.burstPending = burst;
		rt.bornSeq = tickSeq;
		prefetchCurrent(rt);
		runtimes.put(state.echoId, rt);
		runtimeList.add(rt);
		return rt;
	}

	/** Starts loading the segment holding the runtime's cursor (new, restored or respawned runtimes). */
	private void prefetchCurrent(EchoRuntime rt) {
		SegmentMeta meta = store.ring(rt.owner).segmentAtOrAfter(rt.state.cursor).orElse(null);
		if (meta == null || store.cached(rt.owner, meta.seq()) != null) return;
		rt.pending = store.load(rt.owner, meta.seq());
		rt.pendingSeq = meta.seq();
	}

	/** Removes an echo for good (state + runtime + entity). {@code fadedTo} non-null = cap retirement notice. */
	private void retire(PlayerStream stream, EchoState state, @Nullable ServerPlayer fadedTo) {
		stream.echoes.remove(state);
		EchoRuntime rt = runtimes.remove(state.echoId);
		budget.forget(state.echoId);
		if (rt != null) {
			rt.removed = true;
			runtimeList.remove(rt);
			EchoEntity e = rt.entity;
			rt.entity = null;
			if (e != null) {
				if (e.level() instanceof ServerLevel level) EchoVisuals.retirePuff(level, e.position());
				e.managedDiscard();
			}
		}
		data.setDirty();
		if (fadedTo != null) Feedback.faded(fadedTo, state.index);
	}

	/** Builds runtimes for stored echoes that have none and drops runtimes whose state is gone. */
	private void reconcile() {
		for (PlayerStream stream : data.players()) {
			for (EchoState s : stream.echoes) {
				if (s.inventory == null) s.inventory = new VirtualInventory();
				if (!runtimes.containsKey(s.echoId)) addRuntime(stream.owner, stream, s, false);
			}
		}
		for (int i = runtimeList.size() - 1; i >= 0; i--) {
			EchoRuntime rt = runtimeList.get(i);
			PlayerStream stream = data.playerIfPresent(rt.owner);
			if (stream == null || !stream.echoes.contains(rt.state)) {
				runtimeList.remove(i);
				runtimes.remove(rt.state.echoId);
				rt.removed = true;
				EchoEntity e = rt.entity;
				rt.entity = null;
				if (e != null) e.managedDiscard();
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ EchoListener

	@Override
	public void onDied(EchoEntity e, DamageSource src) {
		EchoRuntime rt = runtimes.get(e.echoId());
		if (rt == null || rt.entity != e) return;
		if (e.level() instanceof ServerLevel level) EchoVisuals.retirePuff(level, e.position());
		stopSteer(rt, e); // the corpse must not keep walking during the death animation
		rt.entity = null; // vanilla finishes the death animation and removes it
		budget.forget(rt.state.echoId);
		rt.stream.echoes.remove(rt.state);
		runtimes.remove(rt.state.echoId);
		runtimeList.remove(rt);
		rt.removed = true;
		data.setDirty();
	}

	@Override
	public void onRemoved(EchoEntity e, Entity.RemovalReason reason) {
		EchoRuntime rt = runtimes.get(e.echoId());
		if (rt == null || rt.entity != e) return; // ours (managedDiscard detaches first) or a stale instance
		snapshot(rt, e);
		rt.entity = null;
		if (reason == Entity.RemovalReason.KILLED) onKilledWithoutDeath(rt);
	}

	private void onKilledWithoutDeath(EchoRuntime rt) {
		rt.stream.echoes.remove(rt.state);
		runtimes.remove(rt.state.echoId);
		runtimeList.remove(rt);
		rt.removed = true;
		data.setDirty();
	}

	// ------------------------------------------------------------------------------------------------ commands / lifecycle

	/** The owner's echoes, sorted by number. */
	public List<EchoInfo> list(UUID owner) {
		PlayerStream stream = data.playerIfPresent(owner);
		if (stream == null || stream.echoes.isEmpty()) return List.of();
		List<EchoState> states = new ArrayList<>(stream.echoes);
		states.sort(Comparator.comparingInt(s -> s.index));
		boolean enabled = data.config().enabled();
		List<EchoInfo> out = new ArrayList<>(states.size());
		for (EchoState s : states) {
			EchoRuntime rt = runtimes.get(s.echoId);
			EchoEntity e = rt != null ? rt.entity : null;
			float health = e != null ? e.getHealth() : s.health;
			Activity activity = !enabled || rt == null ? Activity.PAUSED : rt.activity;
			ResourceKey<Level> dim;
			BlockPos pos;
			if (e != null) {
				dim = e.level().dimension();
				pos = e.blockPosition();
			} else {
				ResourceKey<Level> key = s.dimension == null || s.dimension.isEmpty() ? null : Ids.dimensionOf(s.dimension);
				dim = key != null ? key : Level.OVERWORLD;
				pos = BlockPos.containing(s.x, s.y, s.z);
			}
			out.add(new EchoInfo(s.index, Math.max(0L, stream.streamTick - s.cursor), health, activity, dim, pos,
					rt != null && rt.cheap, e != null));
		}
		return out;
	}

	/** Discards the owner's echo entities and removes their states; returns how many there were. */
	public int clearEchoes(UUID owner) {
		PlayerStream stream = data.playerIfPresent(owner);
		if (stream == null) return 0;
		int count = stream.echoes.size();
		for (EchoState s : new ArrayList<>(stream.echoes)) {
			EchoRuntime rt = runtimes.remove(s.echoId);
			budget.forget(s.echoId);
			if (rt == null) continue;
			rt.removed = true;
			runtimeList.remove(rt);
			EchoEntity e = rt.entity;
			rt.entity = null;
			if (e != null) {
				if (e.level() instanceof ServerLevel level) EchoVisuals.retirePuff(level, e.position());
				e.managedDiscard();
			}
		}
		stream.echoes.clear();
		spawnPrefetched.remove(owner);
		data.setDirty();
		return count;
	}

	/** Mode OFF: every entity is discarded, states kept (D6). Mode ON: entities come back on the next ticks. */
	public void onModeChanged(boolean enabled) {
		if (enabled) {
			reconcile();
			for (int i = 0, n = runtimeList.size(); i < n; i++) {
				EchoRuntime rt = runtimeList.get(i);
				if (rt.segment == null && rt.pending == null) prefetchCurrent(rt);
			}
			return;
		}
		despawnAll();
	}

	public void onOwnerJoin(ServerPlayer player) {
		reconcile();
	}

	/** The owner's echoes freeze where they are (they stay in the world). */
	public void onOwnerLeave(UUID owner) {
		for (int i = 0, n = runtimeList.size(); i < n; i++) {
			EchoRuntime rt = runtimeList.get(i);
			EchoEntity e = rt.entity;
			if (rt.owner.equals(owner) && e != null) {
				stopSteer(rt, e);
				rt.activity = Activity.PAUSED;
			}
		}
		store.forgetResident(owner); // their in-memory segment bytes are not needed while they are away
	}

	/** Copies entity position / rotation / health / dimension into the persisted states. */
	public void snapshotAll() {
		for (int i = 0, n = runtimeList.size(); i < n; i++) {
			EchoRuntime rt = runtimeList.get(i);
			if (rt.entity != null) snapshot(rt, rt.entity);
		}
	}

	/** Snapshot, then discard every echo entity (SERVER_STOPPING, mode OFF). States stay. */
	public void despawnAll() {
		snapshotAll();
		for (int i = 0, n = runtimeList.size(); i < n; i++) {
			EchoRuntime rt = runtimeList.get(i);
			EchoEntity e = rt.entity;
			rt.entity = null;
			rt.segment = null;
			rt.pending = null;
			rt.pendingSeq = -1;
			rt.prefetchedEnd = -1;
			if (e != null) e.managedDiscard();
		}
		data.setDirty();
	}

	public @Nullable EchoEntity entity(UUID echoId) {
		EchoRuntime rt = runtimes.get(echoId);
		return rt == null ? null : rt.entity;
	}

	public @Nullable EchoEntity entity(UUID owner, int index) {
		PlayerStream stream = data.playerIfPresent(owner);
		EchoState s = stream == null ? null : stream.echo(index);
		return s == null ? null : entity(s.echoId);
	}

	/** Transient replay state of an echo (tests / tooling). */
	public @Nullable EchoRuntime runtime(UUID echoId) {
		return runtimes.get(echoId);
	}

	// ------------------------------------------------------------------------------------------------ test hooks

	/**
	 * Creates echo {@code index} of {@code owner} at {@code cursor}, bypassing the schedule (no notice). Its entity appears
	 * on the next tick where its position is entity-ticking. Bumps {@code nextEchoIndex} past {@code index}. An existing
	 * echo with that number is returned unchanged.
	 */
	public EchoState spawnEcho(UUID owner, int index, long cursor) {
		PlayerStream stream = data.player(owner);
		EchoState existing = stream.echo(index);
		if (existing != null) return existing;
		EchoState s = new EchoState(UUID.randomUUID(), index, cursor);
		if (s.inventory == null) s.inventory = new VirtualInventory();
		stream.echoes.add(s);
		if (stream.nextEchoIndex <= index) stream.nextEchoIndex = index + 1;
		addRuntime(owner, stream, s, true);
		data.setDirty();
		return s;
	}

	public BudgetScheduler budget() {
		return budget;
	}
}
