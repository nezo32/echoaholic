package dev.echoaholic.mixin;

import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.record.EchoCapture;
import dev.echoaholic.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World item uses on a block (flint and steel, fire charge, shears, bone meal), covering both the "block reacts to the
 * item" path (TNT, pumpkin) and {@code Item.useOn}. HEAD snapshots the held item (a fire charge or the last bone meal is
 * gone by RETURN) and the block states at the clicked and the adjacent position; RETURN records only what really
 * changed, because some paths report success without doing anything (e.g. TNT in adventure mode).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Unique
	private @Nullable Item echoaholic$item;
	@Unique
	private int echoaholic$count;
	@Unique
	private @Nullable BlockState echoaholic$before;
	@Unique
	private @Nullable BlockState echoaholic$beforeRel;

	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void echoaholic$useBefore(ServerPlayer player, Level level, ItemStack itemStack, InteractionHand hand,
			BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		echoaholic$item = null;
		if (!(itemStack.is(Items.FLINT_AND_STEEL) || itemStack.is(Items.FIRE_CHARGE) || itemStack.is(Items.SHEARS)
				|| itemStack.is(Items.BONE_MEAL)) || !EchoCapture.recording(player)) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		echoaholic$item = itemStack.getItem();
		echoaholic$count = itemStack.getCount();
		echoaholic$before = level.getBlockState(pos);
		echoaholic$beforeRel = level.getBlockState(pos.relative(hit.getDirection()));
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void echoaholic$useAfter(ServerPlayer player, Level level, ItemStack itemStack, InteractionHand hand,
			BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Item item = echoaholic$item;
		echoaholic$item = null;
		if (item == null || !cir.getReturnValue().consumesAction()) return;
		BlockState before = echoaholic$before, beforeRel = echoaholic$beforeRel;
		echoaholic$before = null;
		echoaholic$beforeRel = null;
		Direction face = hit.getDirection();
		BlockPos pos = hit.getBlockPos();
		BlockPos rel = pos.relative(face);
		BlockState after = level.getBlockState(pos);
		String itemId = Ids.item(item);
		UseItem use = null;
		if (item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE) {
			BlockState afterRel = level.getBlockState(rel);
			if (before.is(Blocks.TNT) && !after.is(Blocks.TNT)) {
				use = at(UseItem.Kind.TNT_IGNITE, pos, face, itemId, "");
			} else if (lit(before) == Boolean.FALSE && lit(after) == Boolean.TRUE) {
				use = at(UseItem.Kind.IGNITE, pos, face, itemId, "light");
			} else if (afterRel != beforeRel
					&& (afterRel.getBlock() instanceof BaseFireBlock || afterRel.is(Blocks.NETHER_PORTAL))) {
				use = at(UseItem.Kind.IGNITE, rel, face, itemId, "fire");
			}
		} else if (item == Items.SHEARS) {
			if (before.is(Blocks.PUMPKIN) && after.is(Blocks.CARVED_PUMPKIN)) {
				use = at(UseItem.Kind.SHEAR_BLOCK, pos, face, itemId, Ids.block(Blocks.PUMPKIN));
			} else if (before.getBlock() instanceof GrowingPlantHeadBlock && after != before && after.is(before.getBlock())) {
				use = at(UseItem.Kind.SHEAR_BLOCK, pos, face, itemId, Ids.block(before.getBlock()));
			}
		} else if (item == Items.BONE_MEAL) {
			// growCrop / growWaterPlant consume one only when they applied
			if (itemStack.isEmpty() || itemStack.getCount() < echoaholic$count) {
				use = at(UseItem.Kind.BONE_MEAL, pos, face, itemId, "");
			}
		}
		if (use != null) EchoCapture.capture(player, use);
	}

	@Unique
	private static @Nullable Boolean lit(BlockState state) {
		return state.hasProperty(BlockStateProperties.LIT) ? state.getValue(BlockStateProperties.LIT) : null;
	}

	@Unique
	private static UseItem at(UseItem.Kind kind, BlockPos pos, Direction face, String item, String extra) {
		return new UseItem(kind, pos.getX(), pos.getY(), pos.getZ(), face.ordinal(), item, extra);
	}
}
