package dev.echoaholic.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * Conversion between game objects and the registry id strings stored in recorded actions. Only registry ids are stored
 * (never numeric ids, which change across versions and mod sets), and item ids carry no components.
 */
public final class Ids {
	private static final int STATE_CACHE_LIMIT = 4096;
	/** Parsed block states by string; empty remembers an unparsable string. Server thread only. */
	private static final Map<String, Optional<BlockState>> STATE_CACHE = new HashMap<>();

	private Ids() {}

	/** Registry id of the stack's item, without components; "minecraft:air" for an empty stack. */
	public static String item(ItemStack s) {
		return item(s.getItem());
	}

	public static String item(Item i) {
		return BuiltInRegistries.ITEM.getKey(i).toString();
	}

	/** Item id for "the item in hand" fields of actions: "" for an empty hand (see {@code BlockBreak.toolItem}). */
	public static String heldItem(ItemStack s) {
		return s.isEmpty() ? "" : item(s);
	}

	/** Full state string, e.g. "minecraft:oak_log[axis=y]". */
	public static String state(BlockState s) {
		return BlockStateParser.serialize(s);
	}

	public static String block(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).toString();
	}

	public static String fluid(Fluid f) {
		return BuiltInRegistries.FLUID.getKey(f).toString();
	}

	public static String entityType(EntityType<?> t) {
		return EntityType.getKey(t).toString();
	}

	public static String dimension(ResourceKey<Level> k) {
		return k.identifier().toString();
	}

	/** Parses a state string written by {@link #state}; null when the block is unknown or the string is malformed. */
	public static @Nullable BlockState parseState(HolderLookup.Provider regs, String s) {
		Optional<BlockState> cached = STATE_CACHE.get(s);
		if (cached == null) {
			BlockState parsed;
			try {
				parsed = BlockStateParser.parseForBlock(regs.lookupOrThrow(Registries.BLOCK), s, false).blockState();
			} catch (CommandSyntaxException | RuntimeException e) {
				parsed = null;
			}
			if (STATE_CACHE.size() >= STATE_CACHE_LIMIT) STATE_CACHE.clear();
			cached = Optional.ofNullable(parsed);
			STATE_CACHE.put(s, cached);
		}
		return cached.orElse(null);
	}

	/** The item with this id; AIR for "", malformed or unknown ids. */
	public static Item itemOf(String id) {
		Identifier key = id.isEmpty() ? null : Identifier.tryParse(id);
		return key == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(key);
	}

	public static @Nullable EntityType<?> entityTypeOf(String id) {
		Identifier key = id.isEmpty() ? null : Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
	}

	public static @Nullable ResourceKey<Level> dimensionOf(String id) {
		Identifier key = id.isEmpty() ? null : Identifier.tryParse(id);
		return key == null ? null : ResourceKey.create(Registries.DIMENSION, key);
	}
}
