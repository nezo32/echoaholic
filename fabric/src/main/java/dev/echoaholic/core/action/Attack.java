package dev.echoaholic.core.action;

import java.util.Objects;

/**
 * A melee hit.
 *
 * @param tx target position x (entity position when hit)
 * @param damage damage actually dealt
 * @param weaponItem item id in the main hand, "" for an empty hand
 */
public record Attack(double tx, double ty, double tz, float damage, String weaponItem) implements Action {
	public Attack {
		Objects.requireNonNull(weaponItem, "weaponItem");
	}
}
