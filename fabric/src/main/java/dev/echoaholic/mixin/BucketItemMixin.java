package dev.echoaholic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.record.EchoCapture;
import dev.echoaholic.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fluid bucket capture at the two call sites in {@code BucketItem.use} (buckets ray-trace themselves, they never pass
 * through {@code useItemOn}). The empty call site is wrapped instead of {@code emptyContents} itself, which recurses once
 * for the adjacent block. Mob buckets empty through the same call. Powder snow is handled in {@link BlockItemMixin}.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
	@WrapOperation(method = "use", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack echoaholic$recordFill(BucketPickup pickup, @Nullable LivingEntity user, LevelAccessor level, BlockPos pos,
			BlockState state, Operation<ItemStack> original) {
		ItemStack filled = original.call(pickup, user, level, pos, state);
		if (!filled.isEmpty() && user instanceof ServerPlayer player && EchoCapture.recording(player)) {
			EchoCapture.capture(player, new UseItem(UseItem.Kind.BUCKET_FILL, pos.getX(), pos.getY(), pos.getZ(), -1,
					Ids.item(filled), Ids.state(state)));
		}
		return filled;
	}

	@WrapOperation(method = "use", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/BucketItem;emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;)Z"))
	private boolean echoaholic$recordEmpty(BucketItem bucket, @Nullable LivingEntity user, Level level, BlockPos placePos,
			@Nullable BlockHitResult hit, Operation<Boolean> original) {
		boolean emptied = original.call(bucket, user, level, placePos, hit);
		if (emptied && user instanceof ServerPlayer player && EchoCapture.recording(player)) {
			Fluid content = bucket.getContent();
			BlockPos pos = placePos;
			// emptyContents falls back once to the block in front of the clicked face
			if (hit != null && !level.getFluidState(placePos).getType().isSame(content)) {
				BlockPos alt = hit.getBlockPos().relative(hit.getDirection());
				if (level.getFluidState(alt).getType().isSame(content)) pos = alt;
			}
			int face = hit == null ? -1 : hit.getDirection().ordinal();
			EchoCapture.capture(player, new UseItem(UseItem.Kind.BUCKET_EMPTY, pos.getX(), pos.getY(), pos.getZ(), face,
					Ids.item(bucket), Ids.fluid(content)));
		}
		return emptied;
	}
}
