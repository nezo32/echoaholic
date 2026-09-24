package dev.echoaholic.core.action;

/** Body pose flags; recorded only when they change (and at every segment start). */
public record Pose(boolean sneaking, boolean sprinting, boolean swimming, boolean fallFlying) implements Action {
	public static final Pose STANDING = new Pose(false, false, false, false);
}
