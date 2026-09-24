package dev.echoaholic.client;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import org.jspecify.annotations.Nullable;

/**
 * The ghost look on modded clients. An echo is a vanilla Mannequin whose custom name uses the translation key
 * {@link #NAME_KEY} (first argument = echo number), so it is recognised with no extra packet, as soon as it is tracked.
 * AvatarRendererMixin stores the tint in the render state; LivingEntityRendererMixin returns it from getModelTint.
 * PlayerModel already renders entity-translucent, so the alpha of the tint makes the echo see-through.
 */
public final class EchoRenderTint {
	/** Must equal EchoEntity.NAME_KEY (main source set). */
	public static final String NAME_KEY = "echoaholic.echo.name";
	/** Echo cyan #7FE8FF. */
	public static final int RGB = 0x7FE8FF;
	/** 55 % alpha for Echo #1 ... */
	public static final int ALPHA_NEWEST = 0x8C;
	/** ... fading linearly to 35 % from Echo #8 on. */
	public static final int ALPHA_OLDEST = 0x59;
	public static final int FADE_STEPS = 7;

	/** ARGB model tint of the echo in this render state; null (absent) = not an echo. */
	public static final RenderStateDataKey<Integer> TINT = RenderStateDataKey.create(() -> "echoaholic:echo_tint");

	private EchoRenderTint() {}

	/** ARGB tint for this entity, or null if it is not an echo. */
	public static @Nullable Integer tintFor(Entity e) {
		int index = echoIndex(e);
		return index > 0 ? argb(index) : null;
	}

	/** Echo number from the name tag; 0 if the entity is not an echo. Unreadable numbers count as #1. */
	public static int echoIndex(Entity e) {
		if (!(e instanceof Mannequin)) return 0;
		Component name = e.getCustomName();
		if (name == null || !(name.getContents() instanceof TranslatableContents tc) || !NAME_KEY.equals(tc.getKey())) return 0;
		Object[] args = tc.getArgs();
		if (args.length == 0) return 1;
		Object a = args[0];
		String text;
		if (a instanceof Number n) return Math.max(1, n.intValue());
		else if (a instanceof Component c) text = c.getString();
		else text = String.valueOf(a);
		try {
			return Math.max(1, Integer.parseInt(text.trim()));
		} catch (NumberFormatException ex) {
			return 1;
		}
	}

	/** Alpha 0x8C (55 %) at #1, linear down to 0x59 (35 %) from #8 on, over echo cyan. */
	public static int argb(int index) {
		int step = Math.clamp(index - 1, 0, FADE_STEPS);
		int alpha = ALPHA_NEWEST - Math.round((ALPHA_NEWEST - ALPHA_OLDEST) * step / (float) FADE_STEPS);
		return alpha << 24 | RGB;
	}
}
