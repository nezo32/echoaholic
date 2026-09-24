package dev.echoaholic.replay.handler;

import dev.echoaholic.core.VirtualInventory;
import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandler;
import dev.echoaholic.util.Ids;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks the recorded block again, but only if the block at the position is still the recorded block. Drops are
 * computed as if mined with a plain copy of the recorded tool (no enchantments: no fortune, no silk touch), fall on
 * the ground, and every dropped item is credited to the echo's virtual inventory. No experience orbs.
 */
final class BlockBreakHandler implements ReplayHandler<BlockBreak> {
	@Override
	public Result apply(ReplayContext ctx, BlockBreak a) {
		ServerLevel level = ctx.level();
		BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
		BlockState recorded = Ids.parseState(level.registryAccess(), a.blockState());
		if (recorded == null) return Result.SKIPPED;
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.is(recorded.getBlock()) || state.getDestroySpeed(level, pos) < 0) return Result.SKIPPED;

		if (!ctx.budget().tryBlockOp()) return Result.WAIT;

		ItemStack tool = WorldHandlers.ghost(a.toolItem());
		BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		boolean drops = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		if (drops) {
			List<ItemStack> stacks = Block.getDrops(state, level, pos, blockEntity, ctx.echo(), tool);
			VirtualInventory inventory = ctx.inventory();
			for (ItemStack stack : stacks) {
				if (stack.isEmpty()) continue;
				// read before popping: the item entity may merge and change the stack
				inventory.add(Ids.item(stack), stack.getCount());
				Block.popResource(level, pos, stack);
			}
			state.spawnAfterBreak(level, pos, tool, false);
		}
		// particles + sound, fluid-aware removal, BLOCK_DESTROY game event; false = no second (tool-less) drop
		level.destroyBlock(pos, false, ctx.echo());

		WorldHandlers.finish(ctx, tool, Activity.MINING);
		return Result.DONE;
	}
}
