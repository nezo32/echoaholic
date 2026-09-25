package dev.echoaholic.replay;

import java.util.Locale;

/** What an echo is doing right now, as shown by {@code /echoaholic list}. */
public enum Activity {
	WALKING, MINING, BUILDING, FIGHTING, IDLE, COLLAPSED, PAUSED, WAITING;

	/** Translation key, e.g. {@code echoaholic.activity.mining}. */
	public String langKey() {
		return "echoaholic.activity." + name().toLowerCase(Locale.ROOT);
	}
}
