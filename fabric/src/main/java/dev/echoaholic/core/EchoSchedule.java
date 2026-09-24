package dev.echoaholic.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Pure spawn / retire math for one player's echoes.
 *
 * <p>Time model: the stream tick {@code T} counts the ticks recorded for the player (it only advances while the player
 * is online, in survival/adventure and the mode is on). Echo #k (k = 1, 2, ...) is due when {@code T >= k * delay}. It
 * starts replaying at {@code cursor = max(0, oldestRetained, T - retention, T - k * delay)}, so in the normal case (spawn
 * exactly at {@code T == k * delay}, nothing evicted yet) its cursor is 0 and its lag is exactly {@code k * delay}.
 * Starting no earlier than {@code T - retention} keeps a fresh echo inside the retention window, so the next eviction
 * does not retire it at once; starting no earlier than {@code T - k * delay} means echoes that catch up after the delay
 * was lowered still get lag {@code k * delay}. Afterwards each echo owns its cursor and advances it one stream tick per
 * server tick; stalls only add lag.
 *
 * <p>Because the minimum delay (1200 ticks) is not below the segment length, a cursor never points into the segment
 * still being written: echoes only read sealed segments.
 */
public final class EchoSchedule {
	private EchoSchedule() {}

	/** A live echo: its number k and its stream cursor. */
	public record EchoSlot(int index, long cursor) {}

	/**
	 * Outcome of one {@link #plan} call. {@code newIndex} / {@code newCursor} are meaningful only when {@code spawn};
	 * {@code retire} lists echo numbers to remove now, ascending, before the new echo joins.
	 */
	public record SpawnPlan(boolean spawn, int newIndex, long newCursor, List<Integer> retire) {
		public SpawnPlan {
			retire = List.copyOf(retire);
		}
	}

	/** True iff echo number {@code nextIndex} is due at stream tick {@code streamTick}. */
	public static boolean echoSpawnDue(long streamTick, int nextIndex, long delayTicks) {
		requireIndex(nextIndex);
		requireDelay(delayTicks);
		return streamTick >= (long) nextIndex * delayTicks;
	}

	/** Ticks an echo at {@code cursor} is behind the live stream. */
	public static long lagTicks(long streamTick, long cursor) {
		return streamTick - cursor;
	}

	/** True iff the echo's cursor points at data the ring buffer no longer holds (-> retire it). */
	public static boolean isBehindBuffer(long cursor, long oldestRetainedTick) {
		return cursor < oldestRetainedTick;
	}

	/**
	 * Echo numbers to retire so that one more echo fits under {@code maxEchoes}: the smallest numbers (oldest echoes)
	 * first, ascending. If the cap was lowered far below the live count, all surplus echoes are retired at once.
	 */
	public static List<Integer> retirementsForSpawn(List<EchoSlot> live, int maxEchoes) {
		return oldestBeyond(live, Math.max(0, maxEchoes - 1));
	}

	/**
	 * Decides what happens to one player's echoes at stream tick {@code streamTick}. Retires (ascending numbers) every
	 * echo behind the buffer, then enough of the oldest remaining echoes to respect {@code maxEchoes} (all surplus at
	 * once when the cap was lowered; one more slot is freed when an echo spawns). At most one echo spawns per call;
	 * further due echoes spawn on the following calls. A delay change only affects echoes not spawned yet.
	 *
	 * @param streamTick current stream tick T of the player
	 * @param nextIndex number the next echo will get (starts at 1, never reused)
	 * @param delayTicks current echo delay in ticks (&gt;= 1)
	 * @param maxEchoes current cap (values below 1 are treated as 1)
	 * @param oldestRetainedTick first tick still held by the ring buffer
	 * @param retentionTicks ring buffer retention in ticks
	 * @param live the player's live echoes (any order)
	 */
	public static SpawnPlan plan(long streamTick, int nextIndex, long delayTicks, int maxEchoes, long oldestRetainedTick,
			long retentionTicks, List<EchoSlot> live) {
		requireIndex(nextIndex);
		requireDelay(delayTicks);
		int cap = Math.max(1, maxEchoes);
		TreeSet<Integer> retire = new TreeSet<>();
		List<EchoSlot> keep = new ArrayList<>(live.size());
		for (EchoSlot slot : live) {
			if (isBehindBuffer(slot.cursor(), oldestRetainedTick)) retire.add(slot.index());
			else keep.add(slot);
		}
		long cursor = Math.max(Math.max(0L, oldestRetainedTick),
				Math.max(streamTick - retentionTicks, streamTick - (long) nextIndex * delayTicks));
		boolean spawn = echoSpawnDue(streamTick, nextIndex, delayTicks) && cursor < streamTick;
		retire.addAll(oldestBeyond(keep, spawn ? cap - 1 : cap));
		if (!spawn) return new SpawnPlan(false, 0, 0L, new ArrayList<>(retire));
		return new SpawnPlan(true, nextIndex, cursor, new ArrayList<>(retire));
	}

	/** Numbers of the oldest echoes beyond the newest {@code keep}, ascending. */
	private static List<Integer> oldestBeyond(List<EchoSlot> live, int keep) {
		int surplus = live.size() - keep;
		if (surplus <= 0) return List.of();
		return live.stream().map(EchoSlot::index).sorted(Comparator.naturalOrder()).limit(surplus).toList();
	}

	private static void requireIndex(int nextIndex) {
		if (nextIndex < 1) throw new IllegalArgumentException("echo numbers start at 1: " + nextIndex);
	}

	private static void requireDelay(long delayTicks) {
		if (delayTicks < 1) throw new IllegalArgumentException("delay must be >= 1 tick: " + delayTicks);
	}
}
