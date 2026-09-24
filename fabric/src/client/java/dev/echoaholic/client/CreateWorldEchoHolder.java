package dev.echoaholic.client;

/**
 * Duck interface on CreateWorldScreen (CreateWorldScreenMixin): the Game tab values for this screen.
 * Defaults: mode ON, delay 5 minutes.
 */
public interface CreateWorldEchoHolder {
	boolean echoaholic$isModeEnabled();

	void echoaholic$setModeEnabled(boolean enabled);

	int echoaholic$getDelayMinutes();

	void echoaholic$setDelayMinutes(int minutes);
}
