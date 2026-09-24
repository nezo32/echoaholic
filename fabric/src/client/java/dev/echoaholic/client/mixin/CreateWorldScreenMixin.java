package dev.echoaholic.client.mixin;

import dev.echoaholic.client.CreateWorldEchoHolder;
import dev.echoaholic.mode.PendingWorldMode;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Holds the Create World values (mode, delay) per screen instance and, when this screen creates its world,
 * hands them to that world's LevelStorageAccess, the object the integrated server is built with.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin implements CreateWorldEchoHolder {
	/** New worlds start with the mode ON; the player can switch it off on the Game tab. */
	@Unique
	private boolean echoaholic$mode = true;

	@Unique
	private int echoaholic$delayMinutes = 5;

	@Override
	public boolean echoaholic$isModeEnabled() {
		return echoaholic$mode;
	}

	@Override
	public void echoaholic$setModeEnabled(boolean enabled) {
		echoaholic$mode = enabled;
	}

	@Override
	public int echoaholic$getDelayMinutes() {
		return echoaholic$delayMinutes;
	}

	@Override
	public void echoaholic$setDelayMinutes(int minutes) {
		echoaholic$delayMinutes = minutes;
	}

	@ModifyArg(method = "createNewWorld", index = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;createLevelFromExistingSettings(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/server/ReloadableServerResources;Lnet/minecraft/core/LayeredRegistryAccess;Lnet/minecraft/world/level/storage/LevelDataAndDimensions$WorldDataAndGenSettings;Ljava/util/Optional;)V"))
	private LevelStorageSource.LevelStorageAccess echoaholic$handOffMode(LevelStorageSource.LevelStorageAccess access) {
		((PendingWorldMode) access).echoaholic$setPending(echoaholic$mode, echoaholic$delayMinutes);
		return access;
	}
}
