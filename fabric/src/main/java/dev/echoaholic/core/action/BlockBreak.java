package dev.echoaholic.core.action;

import java.util.Objects;

/**
 * The player broke the block at (x, y, z).
 *
 * @param blockState full state string, e.g. "minecraft:oak_log[axis=y]"
 * @param toolItem item id of the tool in hand, "" for an empty hand
 */
public record BlockBreak(int x, int y, int z, String blockState, String toolItem) implements Action {
	public BlockBreak {
		Objects.requireNonNull(blockState, "blockState");
		Objects.requireNonNull(toolItem, "toolItem");
	}
}
