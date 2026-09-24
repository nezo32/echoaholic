package dev.echoaholic.client.mixin;

import java.util.List;

import com.llamalad7.mixinextras.sugar.Local;
import dev.echoaholic.client.CreateWorldEchoHolder;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Echoaholic Mode: ON/OFF" and "Echo Delay: 5 min" to the "Game" tab of the Create World screen, directly
 * below "Difficulty" (right after the Difficulty listener, addListener #2). The values live on the screen
 * (CreateWorldScreenMixin), because the tab is rebuilt on every init(). The delay row is inactive while the mode is OFF.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class GameTabMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;addListener(Ljava/util/function/Consumer;)V",
			ordinal = 2, shift = At.Shift.AFTER))
	private void echoaholic$addRows(CreateWorldScreen screen, CallbackInfo ci,
			@Local GridLayout.RowHelper helper, @Local LayoutSettings buttonLayoutSettings) {
		CreateWorldEchoHolder holder = (CreateWorldEchoHolder) screen;
		CycleButton<Integer> delay = CycleButton.<Integer>builder(
						m -> Component.translatable("echoaholic.minutes", m), Integer.valueOf(holder.echoaholic$getDelayMinutes()))
				.withValues(List.of(1, 2, 3, 5, 10, 15, 20, 30, 60))
				.withTooltip(value -> Tooltip.create(Component.translatable("echoaholic.createWorld.delay.tooltip")))
				.create(0, 0, 210, 20, Component.translatable("echoaholic.createWorld.delay"),
						(b, value) -> holder.echoaholic$setDelayMinutes(value));
		delay.active = holder.echoaholic$isModeEnabled();
		helper.addChild(CycleButton.onOffBuilder(holder.echoaholic$isModeEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("echoaholic.createWorld.toggle.tooltip")))
				.create(0, 0, 210, 20, Component.translatable("echoaholic.createWorld.toggle"),
						(b, value) -> {
							holder.echoaholic$setModeEnabled(value);
							delay.active = value;
						}), buttonLayoutSettings);
		helper.addChild(delay, buttonLayoutSettings);
	}
}
