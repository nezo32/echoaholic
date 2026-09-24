package dev.echoaholic.replay;

import dev.echoaholic.core.action.ActionTypes;
import dev.echoaholic.core.action.Death;
import dev.echoaholic.util.Ids;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Handlers for the actions that only affect the echo itself: pose, teleport, dimension, death, swing. */
public final class MetaHandlers {
	private MetaHandlers() {}

	public static void register() {
		ReplayHandlers.register(ActionTypes.POSE, (ctx, pose) -> {
			ctx.runtime().pose = pose;
			ctx.echo().applyPose(pose); // remembered by the entity; not shown while cheap or collapsed
			return ReplayHandler.Result.DONE;
		});
		ReplayHandlers.register(ActionTypes.TELEPORT, (ctx, tp) -> {
			ctx.teleport(tp.x(), tp.y(), tp.z());
			return ReplayHandler.Result.DONE;
		});
		ReplayHandlers.register(ActionTypes.DIMENSION, (ctx, dim) -> {
			ResourceKey<Level> key = Ids.dimensionOf(dim.dimensionId());
			if (key == null) return ReplayHandler.Result.SKIPPED;
			ctx.changeDimension(key, dim.x(), dim.y(), dim.z());
			return ReplayHandler.Result.DONE;
		});
		ReplayHandlers.register(ActionTypes.DEATH, (ctx, death) -> {
			ctx.collapse(Death.COLLAPSE_TICKS);
			return ReplayHandler.Result.DONE;
		});
		ReplayHandlers.register(ActionTypes.SWING, (ctx, swing) -> {
			if (!ctx.cheap()) ctx.echo().swingArm();
			return ReplayHandler.Result.DONE;
		});
	}
}
