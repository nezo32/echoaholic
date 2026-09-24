package dev.echoaholic.core.action;

/** The player died; echoes play a 60-tick collapse. */
public record Death() implements Action {
	public static final Death INSTANCE = new Death();

	/** Length of the collapse animation in ticks. */
	public static final int COLLAPSE_TICKS = 60;
}
