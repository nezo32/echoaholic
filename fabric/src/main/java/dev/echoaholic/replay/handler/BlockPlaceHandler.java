package dev.echoaholic.replay.handler;

import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandler;
import dev.echoaholic.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Places the recorded block state if the target is replaceable, nothing else is in the way, the block can survive
 * there and the echo owns the material (one virtual-inventory credit, spent on success). TNT always takes a hazard op.
 *
 * <p>TNT paid with a credit is placed as a normal TNT block. Without a credit, while the {@code freeTnt} setting is on,
 * the TNT is primed at once instead (a {@link PrimedTnt} lit by the echo, default fuse): free TNT never exists as a
 * block, so nobody can mine it into real TNT items. With the {@code tntExplodes} game rule off free TNT is skipped.
 *
 * <p>The state is re-fitted to the current world (fence/wall/stair connections) and waterlogging follows the fluid
 * actually at the position, so a recorded waterlogged block never creates water from nothing. Doors, beds and tall
 * plants need room for their other half. An echo standing in the target spot (pillaring up) is lifted onto the block.
 */
final class BlockPlaceHandler implements ReplayHandler<BlockPlace> {
	@Override
	public Result apply(ReplayContext ctx, BlockPlace a) {
		ServerLevel level = ctx.level();
		EchoEntity echo = ctx.echo();
		BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
		BlockState recorded = Ids.parseState(level.registryAccess(), a.blockState());
		if (recorded == null || recorded.isAir()) return Result.SKIPPED;
		BlockState current = level.getBlockState(pos);
		if (!current.canBeReplaced() || current.equals(recorded)) return Result.SKIPPED;

		BlockState state = Block.updateFromNeighbourShapes(recorded, level, pos);
		if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
			state = state.setValue(BlockStateProperties.WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
		}
		if (!state.canSurvive(level, pos) || !otherHalfFree(level, pos, state)) return Result.SKIPPED;
		VoxelShape shape = state.getCollisionShape(level, pos);
		if (!shape.isEmpty() && !level.isUnobstructed(echo, shape.move(pos))) return Result.SKIPPED;

		Item item = a.item().isEmpty() ? state.getBlock().asItem() : WorldHandlers.itemOrAir(a.item());
		String itemId = Ids.item(item);
		boolean paid = item != Items.AIR && ctx.inventory().has(itemId);
		boolean free = !paid && Hazards.isTnt(a) && ctx.config().freeTnt();
		if (!paid && !free) return Result.SKIPPED;
		if (free && !level.getGameRules().get(GameRules.TNT_EXPLODES)) return Result.SKIPPED;

		if (!Hazards.take(ctx.budget(), Hazards.of(a))) return Result.WAIT;

		if (free) {
			// never a minable block: prime it right away, like TntBlock.prime with the echo as igniter
			PrimedTnt primed = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, echo);
			level.addFreshEntity(primed);
			level.playSound(null, primed.getX(), primed.getY(), primed.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F,
					1.0F);
			level.gameEvent(echo, GameEvent.PRIME_FUSE, pos);
			WorldHandlers.finish(ctx, new ItemStack(Items.TNT), Activity.BUILDING);
			return Result.DONE;
		}
		if (!level.setBlock(pos, state, Block.UPDATE_ALL)) return Result.SKIPPED;
		ctx.inventory().take(itemId);
		ItemStack stack = new ItemStack(item);
		// places the other half of doors/beds/tall plants and applies block-entity data like vanilla
		state.getBlock().setPlacedBy(level, pos, state, echo, stack);
		SoundType sound = state.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
				sound.getPitch() * 0.8F);
		level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(echo, state));
		liftOnto(level, echo, pos, shape);

		WorldHandlers.finish(ctx, stack, Activity.BUILDING);
		return Result.DONE;
	}

	/** Doors and tall plants need the block above, beds the head position; vanilla would overwrite whatever is there. */
	private static boolean otherHalfFree(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
			return level.getBlockState(pos.above()).canBeReplaced();
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
				&& state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
			Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			return level.getBlockState(pos.relative(facing)).canBeReplaced();
		}
		return true;
	}

	/** Pillaring: an echo inside the new block is moved on top of it, if there is room. */
	private static void liftOnto(ServerLevel level, EchoEntity echo, BlockPos pos, VoxelShape shape) {
		if (shape.isEmpty()) return;
		AABB box = echo.getBoundingBox();
		if (!box.intersects(shape.bounds().move(pos))) return;
		double top = pos.getY() + shape.max(Direction.Axis.Y);
		double dy = top - echo.getY();
		if (dy <= 0 || dy > 1.5) return;
		if (level.noCollision(echo, box.move(0, dy, 0))) echo.teleportTo(echo.getX(), top, echo.getZ());
	}
}
