package dev.echoaholic.core.action;

/** Arm swing without any other effect (visual only). */
public record Swing() implements Action {
	public static final Swing INSTANCE = new Swing();
}
