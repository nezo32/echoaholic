package dev.echoaholic.replay.handler;

import dev.echoaholic.core.action.ActionTypes;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandlers;
import dev.echoaholic.util.Ids;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Registers the replay handlers that change the world: block break and place, melee attack, projectile shot and
 * world item use.
 *
 * <p>Every handler follows the same contract:
 * <ol>
 * <li>check the cheap preconditions first; a failed one returns SKIPPED (the action is consumed, no budget used);</li>
 * <li>then take the budget (block op, hazard op via {@link Hazards} or entity lookup); refused = WAIT (the cursor
 * stalls and the action is retried next tick, nothing is dropped);</li>
 * <li>only then change the world (and the echo's virtual inventory);</li>
 * <li>outside cheap mode show a ghost copy of the recorded item and swing; set the activity.</li>
 * </ol>
 */
public final class WorldHandlers {
	private static final Direction[] DIRECTIONS = Direction.values();

	private WorldHandlers() {}

	public static void register() {
		ReplayHandlers.register(ActionTypes.BLOCK_BREAK, new BlockBreakHandler());
		ReplayHandlers.register(ActionTypes.BLOCK_PLACE, new BlockPlaceHandler());
		ReplayHandlers.register(ActionTypes.ATTACK, new AttackHandler());
		ReplayHandlers.register(ActionTypes.SHOOT, new ShootHandler());
		ReplayHandlers.register(ActionTypes.USE_ITEM, new UseItemHandler());
	}

	/** A plain copy (default components, no enchantments) of the recorded item; empty for "" or unknown ids. */
	static ItemStack ghost(String itemId) {
		Item item = itemOrAir(itemId);
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	static Item itemOrAir(String itemId) {
		return itemId.isEmpty() ? Items.AIR : Ids.itemOf(itemId);
	}

	/** Recorded face ordinal (0..5 = DOWN, UP, NORTH, SOUTH, WEST, EAST) to a direction; null when not applicable. */
	static @Nullable Direction direction(int face) {
		return face >= 0 && face < DIRECTIONS.length ? DIRECTIONS[face] : null;
	}

	/** Step 4 of the contract: ghost item + swing (not in cheap mode), then the activity. */
	static void finish(ReplayContext ctx, ItemStack ghost, Activity activity) {
		if (!ctx.cheap()) {
			EchoEntity echo = ctx.echo();
			echo.showItem(ghost);
			echo.swingArm();
		}
		ctx.activity(activity);
	}
}
