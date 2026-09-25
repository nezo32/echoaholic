package dev.echoaholic.core.action;

import java.util.Objects;

/**
 * The player placed a block at (x, y, z).
 *
 * @param blockState the placed state string, e.g. "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]"
 * @param item item id that was consumed, e.g. "minecraft:oak_stairs"
 */
public record BlockPlace(int x, int y, int z, String blockState, String item) implements Action {
	public BlockPlace {
		Objects.requireNonNull(blockState, "blockState");
		Objects.requireNonNull(item, "item");
	}
}
