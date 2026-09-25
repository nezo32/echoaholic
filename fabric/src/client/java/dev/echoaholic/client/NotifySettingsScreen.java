package dev.echoaholic.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Echo sound / echo message / echo trail ON|OFF and Done. Every toggle is saved right away. Opened from Mod Menu. */
public class NotifySettingsScreen extends Screen {
	private static final String SOUND = "echoaholic.settings.notifySound";
	private static final String MESSAGE = "echoaholic.settings.notifyMessage";
	private static final String TRAIL = "echoaholic.settings.showTrail";

	private final @Nullable Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	public NotifySettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("echoaholic.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(this.title, this.font);
		LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(8));
		contents.addChild(CycleButton.onOffBuilder(NotifyConfig.get().sound())
				.withTooltip(v -> Tooltip.create(Component.translatable(SOUND + ".tooltip")))
				.create(0, 0, 210, 20, Component.translatable(SOUND),
						(b, v) -> NotifyConfig.set(NotifyConfig.get().withSound(v))));
		contents.addChild(CycleButton.onOffBuilder(NotifyConfig.get().message())
				.withTooltip(v -> Tooltip.create(Component.translatable(MESSAGE + ".tooltip")))
				.create(0, 0, 210, 20, Component.translatable(MESSAGE),
						(b, v) -> NotifyConfig.set(NotifyConfig.get().withMessage(v))));
		contents.addChild(CycleButton.onOffBuilder(NotifyConfig.get().trail())
				.withTooltip(v -> Tooltip.create(Component.translatable(TRAIL + ".tooltip")))
				.create(0, 0, 210, 20, Component.translatable(TRAIL),
						(b, v) -> NotifyConfig.set(NotifyConfig.get().withTrail(v))));
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
