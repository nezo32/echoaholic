package dev.echoaholic.mode;

import org.jspecify.annotations.Nullable;

/**
 * Duck interface on {@code LevelStorageSource.LevelStorageAccess} (see LevelStorageAccessMixin).
 * The Create World screen stores the button values on the access object of the world it just created;
 * the integrated server that is handed that same object consumes them on SERVER_STARTING.
 */
public interface PendingWorldMode {
	void echoaholic$setPending(boolean mode, int delayMinutes);

	/** Returns and clears the pending choice; null if none (existing world, dedicated server). */
	@Nullable PendingEcho echoaholic$takePending();

	/** The Create World choice: Echoaholic Mode on/off and the Echo Delay in minutes. */
	record PendingEcho(boolean mode, int delayMinutes) {}
}
