package dev.echoaholic.entity;

import dev.echoaholic.Echoaholic;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * Arm swing that works on both supported versions. The swing API has no common signature:
 * <ul>
 * <li>26.3: {@code boolean swing(InteractionHand, SwingAnimation, boolean)}</li>
 * <li>26.2: {@code void swing(InteractionHand, boolean)}</li>
 * </ul>
 * Both are resolved once by name (26.x runs with Mojang names) and the one that exists is called. Vanilla's own
 * {@code swing} updates the server swing state and notifies every tracking client, vanilla clients included.
 */
public final class SwingCompat {
	private static final @Nullable MethodHandle SWING_263;
	private static final @Nullable MethodHandle SWING_262;
	private static boolean warned;

	static {
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		MethodHandle modern = null;
		MethodHandle legacy = null;
		try {
			modern = lookup.findVirtual(LivingEntity.class, "swing",
					MethodType.methodType(boolean.class, InteractionHand.class, SwingAnimation.class, boolean.class));
		} catch (ReflectiveOperationException ignored) {
			// not 26.3
		}
		try {
			legacy = lookup.findVirtual(LivingEntity.class, "swing",
					MethodType.methodType(void.class, InteractionHand.class, boolean.class));
		} catch (ReflectiveOperationException ignored) {
			// not 26.2
		}
		SWING_263 = modern;
		SWING_262 = legacy;
	}

	private SwingCompat() {}

	/** Whether a swing method was found on this Minecraft version (asserted by a gametest). */
	public static boolean available() {
		return SWING_263 != null || SWING_262 != null;
	}

	/** Swings the main hand of {@code entity}, as seen by every tracking player. Never throws. */
	public static void mainHand(LivingEntity entity) {
		try {
			if (SWING_263 != null) {
				boolean ignored = (boolean) SWING_263.invokeExact(entity, InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			} else if (SWING_262 != null) {
				SWING_262.invokeExact(entity, InteractionHand.MAIN_HAND, false);
			}
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				Echoaholic.LOGGER.warn("Echo arm swing failed; swings are disabled", t);
			}
		}
	}
}
