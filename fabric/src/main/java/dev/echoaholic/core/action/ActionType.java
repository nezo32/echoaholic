package dev.echoaholic.core.action;

import java.util.Objects;

/**
 * A registered action type: stable id (1..255, part of the file format), record class, short name and codec.
 * The MC side keys its replay handlers by these instances.
 */
public record ActionType<A extends Action>(int id, String name, Class<A> actionClass, ActionCodec<A> codec) {
	public ActionType {
		if (id < 1 || id > 255) throw new IllegalArgumentException("action id must be 1..255: " + id);
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(actionClass, "actionClass");
		Objects.requireNonNull(codec, "codec");
	}

	/** Writes {@code action} (must be of this type) without the id. */
	public void write(Action action, SegmentOutput out) {
		codec.write(actionClass.cast(action), out);
	}
}
