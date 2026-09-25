package dev.echoaholic.replay;

import dev.echoaholic.Echoaholic;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.ActionType;
import dev.echoaholic.core.action.ActionTypes;

import java.util.ArrayList;
import java.util.List;

/** Registry of replay handlers, keyed by core {@link ActionType} id. */
public final class ReplayHandlers {
	private static final ReplayHandler<?>[] BY_ID = new ReplayHandler<?>[256];
	private static final boolean[] FAILED = new boolean[256];

	private ReplayHandlers() {}

	/** Registers the handler of {@code type}; a second registration for the same type is an error. */
	public static synchronized <A extends Action> void register(ActionType<A> type, ReplayHandler<A> handler) {
		if (BY_ID[type.id()] != null) throw new IllegalStateException("duplicate replay handler for " + type.name());
		BY_ID[type.id()] = handler;
	}

	/**
	 * Runs the handler of {@code action}. No handler, or a handler that throws, counts as {@link ReplayHandler.Result#SKIPPED}
	 * (logged once per type) so a bad action never stops the server tick or blocks the echo forever.
	 */
	@SuppressWarnings("unchecked")
	public static ReplayHandler.Result dispatch(ReplayContext ctx, Action action) {
		int id = action.type().id();
		ReplayHandler<Action> handler = (ReplayHandler<Action>) BY_ID[id];
		if (handler == null) return ReplayHandler.Result.SKIPPED;
		try {
			ReplayHandler.Result r = handler.apply(ctx, action);
			return r == null ? ReplayHandler.Result.SKIPPED : r;
		} catch (RuntimeException e) {
			if (!FAILED[id]) {
				FAILED[id] = true;
				Echoaholic.LOGGER.warn("Echoaholic: replay of {} failed, skipping it", action.type().name(), e);
			}
			return ReplayHandler.Result.SKIPPED;
		}
	}

	/** Every registered action type has a handler, else IllegalStateException naming the missing ones. */
	public static void verifyComplete() {
		List<String> missing = new ArrayList<>();
		for (ActionType<?> type : ActionTypes.all()) {
			if (BY_ID[type.id()] == null) missing.add(type.name());
		}
		if (!missing.isEmpty()) throw new IllegalStateException("Echoaholic: no replay handler for " + missing);
	}
}
