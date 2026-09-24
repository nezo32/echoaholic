package dev.echoaholic.core;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;

/**
 * Every per-world setting and safety cap of Echoaholic, with its default and allowed range. The single place where
 * caps live: commands, SavedData and the replay loop all read them from here. Pure (no Minecraft / Fabric).
 *
 * <p>The canonical constructor stores values as given; {@link #clamped()} and every {@code withX} return clamped copies.
 *
 * @param enabled Echoaholic Mode for this world. Worlds made with the Create World toggle on start enabled; worlds made
 *        elsewhere start disabled ({@link #DEFAULT}).
 * @param delayMinutes Echo Delay: echo #k joins after k * delay minutes of recorded play. Default 5, range 1..120.
 * @param maxEchoes most echoes alive per player; when a new one joins at the cap the oldest (smallest #) is retired.
 *        Default 32, range 1..64.
 * @param bufferHours how many hours of each player's recording are kept on disk (ring buffer). Default 6, range 1..24.
 * @param echoBlockOpsPerTick block changes one echo may make per tick; extra actions wait. Default 4, range 1..64.
 * @param echoEntityLookupsPerTick entity searches (attack targets, shear targets) one echo may make per tick. Default 2,
 *        range 1..32.
 * @param globalBlockOpsPerTick block changes all echoes of the server together may make per tick. Default 128, range
 *        1..4096.
 * @param globalHazardOpsPerTick explosions, fire and fluid placements of all echoes together per tick. Default 2, range
 *        1..64.
 * @param cheapModeDistance echoes farther than this many blocks from every player only move and change blocks (no
 *        swings, poses, sounds, trail). Default 128, range 16..1024.
 * @param triggerBlocks whether echoes press pressure plates and trip tripwires. Default false.
 * @param freeTnt whether echoes place recorded TNT without owning it. Default true.
 * @param paused replay frozen for everybody (recording continues). Default false.
 */
public record EchoConfig(
		boolean enabled,
		int delayMinutes,
		int maxEchoes,
		int bufferHours,
		int echoBlockOpsPerTick,
		int echoEntityLookupsPerTick,
		int globalBlockOpsPerTick,
		int globalHazardOpsPerTick,
		int cheapModeDistance,
		boolean triggerBlocks,
		boolean freeTnt,
		boolean paused) {
	public static final int TICKS_PER_MINUTE = 1200;
	public static final int TICKS_PER_HOUR = 72_000;

	public static final int DEFAULT_DELAY_MINUTES = 5;
	public static final int MIN_DELAY_MINUTES = 1;
	public static final int MAX_DELAY_MINUTES = 120;

	public static final int DEFAULT_MAX_ECHOES = 32;
	public static final int MIN_MAX_ECHOES = 1;
	/** Hard cap of echoes per player, whatever the config says. */
	public static final int MAX_ECHOES_CAP = 64;

	public static final int DEFAULT_BUFFER_HOURS = 6;
	public static final int MIN_BUFFER_HOURS = 1;
	public static final int MAX_BUFFER_HOURS = 24;

	public static final int DEFAULT_ECHO_BLOCK_OPS = 4;
	public static final int MIN_ECHO_BLOCK_OPS = 1;
	public static final int MAX_ECHO_BLOCK_OPS = 64;

	public static final int DEFAULT_ECHO_ENTITY_LOOKUPS = 2;
	public static final int MIN_ECHO_ENTITY_LOOKUPS = 1;
	public static final int MAX_ECHO_ENTITY_LOOKUPS = 32;

	public static final int DEFAULT_GLOBAL_BLOCK_OPS = 128;
	public static final int MIN_GLOBAL_BLOCK_OPS = 1;
	public static final int MAX_GLOBAL_BLOCK_OPS = 4096;

	public static final int DEFAULT_GLOBAL_HAZARD_OPS = 2;
	public static final int MIN_GLOBAL_HAZARD_OPS = 1;
	public static final int MAX_GLOBAL_HAZARD_OPS = 64;

	public static final int DEFAULT_CHEAP_MODE_DISTANCE = 128;
	public static final int MIN_CHEAP_MODE_DISTANCE = 16;
	public static final int MAX_CHEAP_MODE_DISTANCE = 1024;

	public static final boolean DEFAULT_TRIGGER_BLOCKS = false;
	public static final boolean DEFAULT_FREE_TNT = true;

	/** Defaults for a world that has no stored settings (mode OFF; Create World turns it on). */
	public static final EchoConfig DEFAULT = new EchoConfig(false, DEFAULT_DELAY_MINUTES, DEFAULT_MAX_ECHOES,
			DEFAULT_BUFFER_HOURS, DEFAULT_ECHO_BLOCK_OPS, DEFAULT_ECHO_ENTITY_LOOKUPS, DEFAULT_GLOBAL_BLOCK_OPS,
			DEFAULT_GLOBAL_HAZARD_OPS, DEFAULT_CHEAP_MODE_DISTANCE, DEFAULT_TRIGGER_BLOCKS, DEFAULT_FREE_TNT, false);

	/** A copy with every int field clamped into its range. */
	public EchoConfig clamped() {
		return new EchoConfig(enabled,
				clamp(delayMinutes, MIN_DELAY_MINUTES, MAX_DELAY_MINUTES),
				clamp(maxEchoes, MIN_MAX_ECHOES, MAX_ECHOES_CAP),
				clamp(bufferHours, MIN_BUFFER_HOURS, MAX_BUFFER_HOURS),
				clamp(echoBlockOpsPerTick, MIN_ECHO_BLOCK_OPS, MAX_ECHO_BLOCK_OPS),
				clamp(echoEntityLookupsPerTick, MIN_ECHO_ENTITY_LOOKUPS, MAX_ECHO_ENTITY_LOOKUPS),
				clamp(globalBlockOpsPerTick, MIN_GLOBAL_BLOCK_OPS, MAX_GLOBAL_BLOCK_OPS),
				clamp(globalHazardOpsPerTick, MIN_GLOBAL_HAZARD_OPS, MAX_GLOBAL_HAZARD_OPS),
				clamp(cheapModeDistance, MIN_CHEAP_MODE_DISTANCE, MAX_CHEAP_MODE_DISTANCE),
				triggerBlocks, freeTnt, paused);
	}

	/** Echo delay in ticks (delayMinutes * 1200), from the clamped value. */
	public long delayTicks() {
		return (long) clamp(delayMinutes, MIN_DELAY_MINUTES, MAX_DELAY_MINUTES) * TICKS_PER_MINUTE;
	}

	/** Ring buffer retention in ticks (bufferHours * 72000), from the clamped value. */
	public long bufferTicks() {
		return (long) clamp(bufferHours, MIN_BUFFER_HOURS, MAX_BUFFER_HOURS) * TICKS_PER_HOUR;
	}

	public EchoConfig withEnabled(boolean v) {
		return new EchoConfig(v, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withDelayMinutes(int v) {
		return new EchoConfig(enabled, v, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withMaxEchoes(int v) {
		return new EchoConfig(enabled, delayMinutes, v, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withBufferHours(int v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, v, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withEchoBlockOpsPerTick(int v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, v, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withEchoEntityLookupsPerTick(int v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, v,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withGlobalBlockOpsPerTick(int v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				v, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withGlobalHazardOpsPerTick(int v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, v, cheapModeDistance, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withCheapModeDistance(int v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, v, triggerBlocks, freeTnt, paused).clamped();
	}

	public EchoConfig withTriggerBlocks(boolean v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, v, freeTnt, paused).clamped();
	}

	public EchoConfig withFreeTnt(boolean v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, v, paused).clamped();
	}

	public EchoConfig withPaused(boolean v) {
		return new EchoConfig(enabled, delayMinutes, maxEchoes, bufferHours, echoBlockOpsPerTick, echoEntityLookupsPerTick,
				globalBlockOpsPerTick, globalHazardOpsPerTick, cheapModeDistance, triggerBlocks, freeTnt, v).clamped();
	}

	static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	/** Value type of a {@link Key}. */
	public enum Kind {
		INT, BOOL
	}

	/**
	 * Metadata for {@code /echoaholic config <key> [value]}: config name, kind, range and default. BOOL keys use 0/1 as
	 * their int value (min 0, max 1).
	 */
	public enum Key {
		ENABLED("enabled", Kind.BOOL, 0, 1, 0, c -> bit(c.enabled), (c, v) -> c.withEnabled(v != 0)),
		DELAY_MINUTES("delayMinutes", Kind.INT, MIN_DELAY_MINUTES, MAX_DELAY_MINUTES, DEFAULT_DELAY_MINUTES,
				EchoConfig::delayMinutes, EchoConfig::withDelayMinutes),
		MAX_ECHOES("maxEchoes", Kind.INT, MIN_MAX_ECHOES, MAX_ECHOES_CAP, DEFAULT_MAX_ECHOES,
				EchoConfig::maxEchoes, EchoConfig::withMaxEchoes),
		BUFFER_HOURS("bufferHours", Kind.INT, MIN_BUFFER_HOURS, MAX_BUFFER_HOURS, DEFAULT_BUFFER_HOURS,
				EchoConfig::bufferHours, EchoConfig::withBufferHours),
		ECHO_BLOCK_OPS("echoBlockOpsPerTick", Kind.INT, MIN_ECHO_BLOCK_OPS, MAX_ECHO_BLOCK_OPS, DEFAULT_ECHO_BLOCK_OPS,
				EchoConfig::echoBlockOpsPerTick, EchoConfig::withEchoBlockOpsPerTick),
		ECHO_ENTITY_LOOKUPS("echoEntityLookupsPerTick", Kind.INT, MIN_ECHO_ENTITY_LOOKUPS, MAX_ECHO_ENTITY_LOOKUPS,
				DEFAULT_ECHO_ENTITY_LOOKUPS, EchoConfig::echoEntityLookupsPerTick, EchoConfig::withEchoEntityLookupsPerTick),
		GLOBAL_BLOCK_OPS("globalBlockOpsPerTick", Kind.INT, MIN_GLOBAL_BLOCK_OPS, MAX_GLOBAL_BLOCK_OPS,
				DEFAULT_GLOBAL_BLOCK_OPS, EchoConfig::globalBlockOpsPerTick, EchoConfig::withGlobalBlockOpsPerTick),
		GLOBAL_HAZARD_OPS("globalHazardOpsPerTick", Kind.INT, MIN_GLOBAL_HAZARD_OPS, MAX_GLOBAL_HAZARD_OPS,
				DEFAULT_GLOBAL_HAZARD_OPS, EchoConfig::globalHazardOpsPerTick, EchoConfig::withGlobalHazardOpsPerTick),
		CHEAP_MODE_DISTANCE("cheapModeDistance", Kind.INT, MIN_CHEAP_MODE_DISTANCE, MAX_CHEAP_MODE_DISTANCE,
				DEFAULT_CHEAP_MODE_DISTANCE, EchoConfig::cheapModeDistance, EchoConfig::withCheapModeDistance),
		TRIGGER_BLOCKS("triggerBlocks", Kind.BOOL, 0, 1, bit(DEFAULT_TRIGGER_BLOCKS), c -> bit(c.triggerBlocks),
				(c, v) -> c.withTriggerBlocks(v != 0)),
		FREE_TNT("freeTnt", Kind.BOOL, 0, 1, bit(DEFAULT_FREE_TNT), c -> bit(c.freeTnt), (c, v) -> c.withFreeTnt(v != 0)),
		PAUSED("paused", Kind.BOOL, 0, 1, 0, c -> bit(c.paused), (c, v) -> c.withPaused(v != 0));

		private final String id;
		private final Kind kind;
		private final int min;
		private final int max;
		private final int defaultValue;
		private final ToIntFunction<EchoConfig> getter;
		private final Setter setter;

		Key(String id, Kind kind, int min, int max, int defaultValue, ToIntFunction<EchoConfig> getter, Setter setter) {
			this.id = id;
			this.kind = kind;
			this.min = min;
			this.max = max;
			this.defaultValue = defaultValue;
			this.getter = getter;
			this.setter = setter;
		}

		/** Config name as typed in the command and stored in SavedData, e.g. "bufferHours". */
		public String id() {
			return id;
		}

		public Kind kind() {
			return kind;
		}

		public int min() {
			return min;
		}

		public int max() {
			return max;
		}

		/** Default as an int (BOOL: 0/1). */
		public int defaultValue() {
			return defaultValue;
		}

		/** Current value as an int (BOOL: 0/1). */
		public int get(EchoConfig config) {
			return getter.applyAsInt(config);
		}

		/** Copy with this key set; ints are clamped, BOOL treats any non-zero as true. */
		public EchoConfig set(EchoConfig config, int value) {
			return setter.apply(config, value);
		}

		/** Human-readable current value: "true"/"false" or the number. */
		public String format(EchoConfig config) {
			int v = get(config);
			return kind == Kind.BOOL ? String.valueOf(v != 0) : String.valueOf(v);
		}

		/**
		 * Parses a raw command/storage value into this key's int form (not clamped). BOOL accepts true/false/on/off/1/0
		 * in any case; INT accepts a decimal int. Anything else -> empty.
		 */
		public OptionalInt parse(String raw) {
			if (raw == null) return OptionalInt.empty();
			String s = raw.strip().toLowerCase(Locale.ROOT);
			if (kind == Kind.BOOL) {
				return switch (s) {
					case "true", "on", "1" -> OptionalInt.of(1);
					case "false", "off", "0" -> OptionalInt.of(0);
					default -> OptionalInt.empty();
				};
			}
			try {
				return OptionalInt.of(Integer.parseInt(s));
			} catch (NumberFormatException e) {
				return OptionalInt.empty();
			}
		}

		/** Key by its config name (exact, case-sensitive), e.g. "freeTnt". */
		public static Optional<Key> byId(String id) {
			for (Key k : values()) {
				if (k.id.equals(id)) return Optional.of(k);
			}
			return Optional.empty();
		}

		private static int bit(boolean b) {
			return b ? 1 : 0;
		}

		@FunctionalInterface
		private interface Setter {
			EchoConfig apply(EchoConfig config, int value);
		}
	}
}
