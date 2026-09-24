package dev.echoaholic.storage;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.echoaholic.Echoaholic;
import dev.echoaholic.core.EchoConfig;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Per-world Echoaholic state, saved as {@code <world>/data/echoaholic/world.dat} in the server-wide
 * {@link net.minecraft.world.level.storage.SavedDataStorage} (MinecraftServer#getDataStorage): the world's
 * {@link EchoConfig} plus, per recorded player, the stream counters and the live echo states ({@link PlayerStream}).
 * Absent file = {@link EchoConfig#DEFAULT} (mode off), like worlds created without the Create World toggle.
 *
 * <p>Deliberately small: vanilla encodes dirty SavedData on the server thread at every autosave, so the recorded
 * segments are kept in their own files ({@link StreamStore}) and never here.
 *
 * <p>Robust decoding: the config is stored as {@code {key id: int}} through {@link EchoConfig.Key}, so unknown keys are
 * ignored and missing ones get their defaults; a damaged player or echo entry is skipped instead of failing the whole
 * file (a failed file would silently turn the mode off).
 *
 * <p>Server thread only.
 */
public final class EchoWorldData extends SavedData {
	public static final Codec<EchoWorldData> CODEC = RecordCodecBuilder.create(i -> i.group(
			PlayerStream.lenientMap(Codec.STRING, Codec.INT).lenientOptionalFieldOf("config", Map.of()).forGetter(d -> encodeConfig(d.config)),
			PlayerStream.lenientMap(UUIDUtil.STRING_CODEC, PlayerStream.Body.CODEC).lenientOptionalFieldOf("players", Map.of())
					.forGetter(EchoWorldData::bodies)
	).apply(i, EchoWorldData::new));

	/** null DataFixTypes: no vanilla fixer applies; Fabric's SavedDataStorageMixin skips datafixing for null. */
	public static final SavedDataType<EchoWorldData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(Echoaholic.MOD_ID, "world"), EchoWorldData::new, CODEC, null);

	private EchoConfig config;
	private final Map<UUID, PlayerStream> players;

	/** Fresh data: {@link EchoConfig#DEFAULT} (mode off), no players. */
	public EchoWorldData() {
		this.config = EchoConfig.DEFAULT;
		this.players = new HashMap<>();
	}

	private EchoWorldData(Map<String, Integer> config, Map<UUID, PlayerStream.Body> players) {
		this.config = decodeConfig(config);
		this.players = new HashMap<>();
		players.forEach((owner, body) -> this.players.put(owner, PlayerStream.fromBody(owner, body)));
	}

	/** The world's data, created (mode off) when the file does not exist. */
	public static EchoWorldData get(MinecraftServer s) {
		return s.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Whether the world already has Echoaholic data (loaded from disk or created earlier this session). */
	public static boolean exists(MinecraftServer s) {
		return s.getDataStorage().get(TYPE) != null;
	}

	/** Current settings (always clamped). */
	public EchoConfig config() {
		return config;
	}

	/** Stores {@code c.clamped()} and marks the data dirty. */
	public void setConfig(EchoConfig c) {
		config = c.clamped();
		setDirty();
	}

	/** Echo delay in ticks: the {@link EchoTuning} override when set, else the configured minutes. */
	public long delayTicks() {
		long override = EchoTuning.delayTicksOverride;
		return override > 0 ? override : config.delayTicks();
	}

	/** Ring buffer retention in ticks: the {@link EchoTuning} override when set, else the configured hours. */
	public long bufferTicks() {
		long override = EchoTuning.bufferTicksOverride;
		return override > 0 ? override : config.bufferTicks();
	}

	/** The player's entry, created (and the data marked dirty) on first use. */
	public PlayerStream player(UUID owner) {
		PlayerStream p = players.get(owner);
		if (p == null) {
			p = new PlayerStream(owner);
			players.put(owner, p);
			setDirty();
		}
		return p;
	}

	/** The player's entry, or null when the player was never recorded in this world. */
	public @Nullable PlayerStream playerIfPresent(UUID owner) {
		return players.get(owner);
	}

	/** Every known player (read-only view). */
	public Collection<PlayerStream> players() {
		return Collections.unmodifiableCollection(players.values());
	}

	private Map<UUID, PlayerStream.Body> bodies() {
		Map<UUID, PlayerStream.Body> out = new HashMap<>();
		players.forEach((owner, p) -> out.put(owner, p.body()));
		return out;
	}

	private static Map<String, Integer> encodeConfig(EchoConfig c) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (EchoConfig.Key key : EchoConfig.Key.values()) out.put(key.id(), key.get(c));
		return out;
	}

	private static EchoConfig decodeConfig(Map<String, Integer> stored) {
		EchoConfig c = EchoConfig.DEFAULT;
		for (EchoConfig.Key key : EchoConfig.Key.values()) {
			Integer v = stored.get(key.id());
			if (v != null) c = key.set(c, v);
		}
		return c.clamped();
	}
}
