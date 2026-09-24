package dev.echoaholic.core.action;

import java.util.Objects;
import java.util.Optional;

/**
 * A world-changing item use at block (x, y, z).
 *
 * @param face clicked face as a direction ordinal (0..5), or -1 when not applicable
 * @param item item id used, e.g. "minecraft:water_bucket"
 * @param extra fluid / block state / entity type as the kind needs it, "" when unused
 */
public record UseItem(Kind kind, int x, int y, int z, int face, String item, String extra) implements Action {
	public UseItem {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(item, "item");
		Objects.requireNonNull(extra, "extra");
	}

	/** What the use did. The ids are part of the file format: never change or reuse one. */
	public enum Kind {
		BUCKET_FILL(0),
		BUCKET_EMPTY(1),
		IGNITE(2),
		SHEAR_BLOCK(3),
		SHEAR_ENTITY(4),
		BONE_MEAL(5),
		TNT_IGNITE(6);

		private final int id;

		Kind(int id) {
			this.id = id;
		}

		public int id() {
			return id;
		}

		public static Optional<Kind> byId(int id) {
			for (Kind k : values()) {
				if (k.id == id) return Optional.of(k);
			}
			return Optional.empty();
		}
	}
}
