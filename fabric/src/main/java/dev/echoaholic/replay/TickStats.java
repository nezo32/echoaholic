package dev.echoaholic.replay;

/**
 * What one {@link EchoManager#tickOnce()} did (for gametests and the perf test).
 *
 * @param echoes echoes visited
 * @param advanced echoes whose cursor moved forward
 * @param stalled echoes that were active but held their cursor (budget, path, loading, collapse)
 * @param frozen echoes frozen (paused, owner not streaming, chunk not entity-ticking)
 * @param blockOps block ops granted by the budget (hazard ops included)
 * @param hazardOps hazard ops granted
 * @param lookups entity lookups granted
 * @param deferred budget refusals (actions that waited)
 * @param nanos wall time of the tick body
 */
public record TickStats(int echoes, int advanced, int stalled, int frozen, int blockOps, int hazardOps, int lookups,
		int deferred, long nanos) {
	public static final TickStats EMPTY = new TickStats(0, 0, 0, 0, 0, 0, 0, 0, 0L);
}
