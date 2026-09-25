package dev.echoaholic.replay.handler;

import dev.echoaholic.core.BudgetScheduler;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.Shoot;
import dev.echoaholic.core.action.UseItem;

/**
 * Which replayed actions count against the global hazard budget (research hooks §13). A hazard op is a block op plus
 * one op of the small global hazard pool, so a crowd of echoes can't set off many explosions, fires or floods in the
 * same tick.
 * <ul>
 * <li>explosion: TNT placement and ignition, wind charges</li>
 * <li>fire: flint and steel fire, anything done with a fire charge, lava emptied from a bucket (also a fluid)</li>
 * <li>fluid: water emptied from a bucket (fish buckets included), any bucket fill</li>
 * </ul>
 * Everything else is a plain block op. Classification uses the recorded ids only, so it is decided before the world
 * is touched.
 */
public final class Hazards {
	/** Hazard class of an action. */
	public enum Kind {
		NONE, EXPLOSION, FIRE, FLUID;

		public boolean isHazard() {
			return this != NONE;
		}
	}

	static final String TNT = "minecraft:tnt";
	static final String WIND_CHARGE = "minecraft:wind_charge";
	static final String FIRE_CHARGE = "minecraft:fire_charge";
	static final String LAVA = "minecraft:lava";
	static final String LAVA_BUCKET = "minecraft:lava_bucket";
	static final String POWDER_SNOW_BUCKET = "minecraft:powder_snow_bucket";
	static final String IGNITE_FIRE = "fire";

	private Hazards() {}

	public static Kind of(BlockPlace a) {
		return isTnt(a) ? Kind.EXPLOSION : Kind.NONE;
	}

	public static Kind of(Shoot a) {
		return WIND_CHARGE.equals(a.entityType()) ? Kind.EXPLOSION : Kind.NONE;
	}

	public static Kind of(UseItem a) {
		return switch (a.kind()) {
			case TNT_IGNITE -> Kind.EXPLOSION;
			case IGNITE -> IGNITE_FIRE.equals(a.extra()) || FIRE_CHARGE.equals(a.item()) ? Kind.FIRE : Kind.NONE;
			case BUCKET_FILL -> Kind.FLUID;
			case BUCKET_EMPTY -> {
				if (LAVA.equals(a.extra()) || LAVA_BUCKET.equals(a.item())) yield Kind.FIRE;
				if (a.extra().isEmpty() || POWDER_SNOW_BUCKET.equals(a.item())) yield Kind.NONE;
				yield Kind.FLUID;
			}
			case SHEAR_BLOCK, SHEAR_ENTITY, BONE_MEAL -> Kind.NONE;
		};
	}

	/** A recorded TNT placement (by placed state or by item). */
	public static boolean isTnt(BlockPlace a) {
		return TNT.equals(a.item()) || a.blockState().equals(TNT) || a.blockState().startsWith(TNT + "[");
	}

	/** Takes a hazard op (block op + global hazard op) for hazards, else a block op; false = the action must wait. */
	public static boolean take(BudgetScheduler.EchoBudget budget, Kind kind) {
		return kind.isHazard() ? budget.tryHazardOp() : budget.tryBlockOp();
	}
}
