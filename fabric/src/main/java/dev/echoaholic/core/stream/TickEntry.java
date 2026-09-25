package dev.echoaholic.core.stream;

import java.util.List;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.Move;

/**
 * What was recorded at one stream tick.
 *
 * @param move absolute (decoded) move sample, or null when the tick has none
 * @param actions actions in recorded order (possibly empty)
 */
public record TickEntry(long tick, Move move, List<Action> actions) {
	public TickEntry {
		actions = List.copyOf(actions);
	}

	/** A tick with nothing recorded. */
	public static TickEntry empty(long tick) {
		return new TickEntry(tick, null, List.of());
	}

	public boolean hasMove() {
		return move != null;
	}

	public boolean isEmpty() {
		return move == null && actions.isEmpty();
	}
}
