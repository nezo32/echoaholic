package dev.echoaholic.core.action;

import java.io.IOException;

/** Binary form of one action type (without the type id, which the caller writes). */
public interface ActionCodec<A extends Action> {
	void write(A action, SegmentOutput out);

	/** Reads one action; malformed data -> IOException. */
	A read(SegmentInput in) throws IOException;
}
