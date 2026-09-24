package dev.echoaholic.core.action;

import java.util.Objects;

/**
 * A projectile left the player.
 *
 * @param entityType projectile entity type id, e.g. "minecraft:arrow"
 * @param x spawn position x
 * @param vx initial velocity x (blocks per tick)
 * @param item item id of the launcher (bow, crossbow, trident, snowball ...)
 */
public record Shoot(String entityType, double x, double y, double z, double vx, double vy, double vz, String item)
		implements Action {
	public Shoot {
		Objects.requireNonNull(entityType, "entityType");
		Objects.requireNonNull(item, "item");
	}
}
