package dev.echoaholic.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick work budget for all echoes of a server. Pure and single-threaded (server thread only).
 *
 * <p>Each server tick: {@link #beginTick} resets the global pools and rotates the round-robin offset; then for every echo
 * in {@link #order} the replay loop takes {@link #forEcho} and asks it before each costly action. Contract with replay:
 * when a {@code tryX()} returns false the action stays pending and the echo's cursor does not move past it this tick,
 * so over-budget work is delayed, never dropped.
 */
public final class BudgetScheduler {
	private final Map<Object, EchoBudget> budgets = new HashMap<>();
	private long rotation = -1;
	private int globalBlockOps;
	private int globalHazardOps;
	private int blockOpsUsed;
	private int hazardOpsUsed;
	private int lookupsUsed;
	private int deferred;
	private long totalDeferred;

	/** Starts a tick: fresh global pools (negative values count as 0), per-echo budgets forgotten, rotation +1. */
	public void beginTick(int globalBlockOps, int globalHazardOps) {
		this.globalBlockOps = Math.max(0, globalBlockOps);
		this.globalHazardOps = Math.max(0, globalHazardOps);
		blockOpsUsed = 0;
		hazardOpsUsed = 0;
		lookupsUsed = 0;
		deferred = 0;
		budgets.clear();
		rotation++;
	}

	/** Echoes in this tick's service order: the list rotated left by the tick's offset (a new list). */
	public <T> List<T> order(List<T> echoes) {
		int n = echoes.size();
		if (n == 0) return List.of();
		int start = (int) Math.floorMod(rotation < 0 ? 0 : rotation, (long) n);
		List<T> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(echoes.get((start + i) % n));
		return out;
	}

	/**
	 * Budget of one echo for the current tick. Asking again for the same id in the same tick returns the same budget
	 * (caps are not refilled).
	 */
	public EchoBudget forEcho(Object echoId, int perEchoBlockOps, int perEchoLookups) {
		return budgets.computeIfAbsent(echoId, id -> new EchoBudget(Math.max(0, perEchoBlockOps), Math.max(0, perEchoLookups)));
	}

	/** Block ops granted this tick (hazard ops included). */
	public int blockOpsUsed() {
		return blockOpsUsed;
	}

	/** Hazard ops granted this tick. */
	public int hazardOpsUsed() {
		return hazardOpsUsed;
	}

	/** Entity lookups granted this tick. */
	public int lookupsUsed() {
		return lookupsUsed;
	}

	/** Requests refused this tick (each refusal means one action waited). */
	public int deferred() {
		return deferred;
	}

	/** Requests refused since this scheduler was created. */
	public long totalDeferred() {
		return totalDeferred;
	}

	/** Global block ops still available this tick. */
	public int globalBlockOpsLeft() {
		return globalBlockOps - blockOpsUsed;
	}

	/** Global hazard ops still available this tick. */
	public int globalHazardOpsLeft() {
		return globalHazardOps - hazardOpsUsed;
	}

	private boolean refuse() {
		deferred++;
		totalDeferred++;
		return false;
	}

	/** One echo's allowance for the current tick. */
	public final class EchoBudget {
		private final int blockOps;
		private final int lookups;
		private int blockOpsTaken;
		private int lookupsTaken;

		private EchoBudget(int blockOps, int lookups) {
			this.blockOps = blockOps;
			this.lookups = lookups;
		}

		/** Takes one block op (per-echo and global); false -> action must wait. */
		public boolean tryBlockOp() {
			if (blockOpsTaken >= blockOps || blockOpsUsed >= globalBlockOps) return refuse();
			blockOpsTaken++;
			blockOpsUsed++;
			return true;
		}

		/** Takes one block op AND one global hazard op (explosion, fire, fluid), all or nothing. */
		public boolean tryHazardOp() {
			if (blockOpsTaken >= blockOps || blockOpsUsed >= globalBlockOps || hazardOpsUsed >= globalHazardOps) {
				return refuse();
			}
			blockOpsTaken++;
			blockOpsUsed++;
			hazardOpsUsed++;
			return true;
		}

		/** Takes one entity lookup (per-echo cap only). */
		public boolean tryEntityLookup() {
			if (lookupsTaken >= lookups) return refuse();
			lookupsTaken++;
			lookupsUsed++;
			return true;
		}

		public int blockOpsLeft() {
			return Math.max(0, Math.min(blockOps - blockOpsTaken, globalBlockOps - blockOpsUsed));
		}

		public int lookupsLeft() {
			return lookups - lookupsTaken;
		}
	}
}
