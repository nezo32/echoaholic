package dev.echoaholic.replay;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One row of {@code /echoaholic list}.
 *
 * @param index echo number k
 * @param lagTicks how far the echo is behind its owner's live stream (T - cursor)
 * @param health current health (entity if present, else the stored value)
 * @param activity what it is doing
 * @param dimension where it is
 * @param pos block position
 * @param cheap whether it runs in cheap mode (far from every player)
 * @param hasEntity whether an entity currently exists in the world
 */
public record EchoInfo(int index, long lagTicks, float health, Activity activity, ResourceKey<Level> dimension, BlockPos pos,
		boolean cheap, boolean hasEntity) {}
