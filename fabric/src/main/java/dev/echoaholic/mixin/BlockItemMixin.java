package dev.echoaholic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.record.EchoCapture;
import dev.echoaholic.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block place capture. The BLOCK_PLACE game event in {@code BlockItem.place} is reached only after the block was really
 * placed, and carries the final position (after {@code updatePlacementContext}) and the placed state. The powder snow
 * bucket is a {@link BlockItem} too; it is recorded as a bucket empty instead of a place.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@WrapOperation(method = "place", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V"))
	private void echoaholic$recordPlace(Level level, Holder<GameEvent> event, BlockPos pos, GameEvent.Context context,
			Operation<Void> original, @Local(argsOnly = true) BlockPlaceContext placeContext) {
		original.call(level, event, pos, context);
		if (!(context.sourceEntity() instanceof ServerPlayer player) || !EchoCapture.recording(player)) return;
		Object self = this;
		if (self instanceof SolidBucketItem bucket) {
			EchoCapture.capture(player, new UseItem(UseItem.Kind.BUCKET_EMPTY, pos.getX(), pos.getY(), pos.getZ(),
					placeContext.getClickedFace().ordinal(), Ids.item(bucket), ""));
		} else if (context.affectedState() != null) {
			EchoCapture.capture(player, new BlockPlace(pos.getX(), pos.getY(), pos.getZ(), Ids.state(context.affectedState()),
					Ids.item((BlockItem) self)));
		}
	}
}
