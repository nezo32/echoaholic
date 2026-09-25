package dev.echoaholic.mixin;

import dev.echoaholic.mode.PendingWorldMode;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Implements {@link PendingWorldMode} on the storage access of one world directory. One volatile reference, so the
 * pair (mode, delay) is published atomically: the client thread writes it (Create World), the integrated server
 * thread reads it (SERVER_STARTING).
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin implements PendingWorldMode {
	@Unique
	private volatile PendingWorldMode.@Nullable PendingEcho echoaholic$pending;

	@Override
	public void echoaholic$setPending(boolean mode, int delayMinutes) {
		echoaholic$pending = new PendingWorldMode.PendingEcho(mode, delayMinutes);
	}

	@Override
	public PendingWorldMode.@Nullable PendingEcho echoaholic$takePending() {
		PendingWorldMode.PendingEcho value = echoaholic$pending;
		echoaholic$pending = null;
		return value;
	}
}
