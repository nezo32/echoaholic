package dev.echoaholic.replay;

import dev.echoaholic.core.action.Action;

/**
 * Replays one action type for an echo. Contract: check the cheap preconditions first (fail: {@link Result#SKIPPED}),
 * then take the budget (refused: {@link Result#WAIT}), and only then change the world.
 */
@FunctionalInterface
public interface ReplayHandler<A extends Action> {
	enum Result {
		/** The action happened. */
		DONE,
		/** A precondition failed: the action is consumed without effect (no budget used). */
		SKIPPED,
		/** The budget refused: the cursor stalls on this action and it is retried next tick. */
		WAIT
	}

	Result apply(ReplayContext ctx, A action);
}
