package dev.echoaholic.core.action;

/**
 * Something a player did during one recorded tick. Every implementation is a record registered in {@link ActionTypes}
 * with a stable id and a codec. Movement is not an action: it is the per-tick {@link Move} sample.
 */
public interface Action {
	/** The registered type of this action. */
	default ActionType<?> type() {
		return ActionTypes.of(this);
	}
}
