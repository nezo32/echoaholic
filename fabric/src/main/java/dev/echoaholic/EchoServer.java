package dev.echoaholic;

import java.util.UUID;

import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.record.Recorder;
import dev.echoaholic.replay.EchoManager;
import dev.echoaholic.storage.EchoWorldData;
import dev.echoaholic.storage.PlayerStream;
import dev.echoaholic.storage.StreamStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Everything Echoaholic keeps for one running server: the world data, the segment store, the recorder and the echo
 * manager. Created on SERVER_STARTING ({@link #start}), dropped on SERVER_STOPPED ({@link #stop}). Server thread only,
 * except {@link #current()}, which any thread may read.
 *
 * <p>Also the facade the commands use to change settings ({@link #setConfig}) and to wipe a player ({@link #clear}),
 * so the manager always hears about mode changes.
 */
public final class EchoServer {
	private static volatile @Nullable EchoServer current;

	private final MinecraftServer server;
	private final EchoWorldData data;
	private final StreamStore store;
	private final Recorder recorder;
	private final EchoManager manager;

	private EchoServer(MinecraftServer server) {
		this.server = server;
		this.data = EchoWorldData.get(server);
		this.store = new StreamStore(server.getWorldPath(LevelResource.DATA).resolve("echoaholic").resolve("streams"));
		this.recorder = new Recorder(server, data, store);
		this.manager = new EchoManager(server, data, store, recorder);
	}

	/** The running instance, or null when no server runs (or on a client without an integrated server). */
	public static @Nullable EchoServer current() {
		return current;
	}

	/** The instance of this server. Throws when Echoaholic was not started for it. */
	public static EchoServer get(MinecraftServer server) {
		EchoServer c = current;
		if (c == null || c.server != server) {
			throw new IllegalStateException("Echoaholic is not running on this server");
		}
		return c;
	}

	/** SERVER_STARTING, after the world data is settled (EchoBootstrap). */
	public static EchoServer start(MinecraftServer server) {
		EchoServer old = current;
		if (old != null) {
			// a previous server never reached SERVER_STOPPED (crash): release its IO thread
			Echoaholic.LOGGER.warn("Echoaholic was still running for a previous server; closing it");
			old.store.close();
		}
		EchoServer started = new EchoServer(server);
		current = started;
		return started;
	}

	/** SERVER_STOPPED: forget the instance. The caller closed the store already. */
	public static void stop() {
		current = null;
	}

	public MinecraftServer server() {
		return server;
	}

	public EchoWorldData data() {
		return data;
	}

	public StreamStore store() {
		return store;
	}

	public Recorder recorder() {
		return recorder;
	}

	public EchoManager manager() {
		return manager;
	}

	public EchoConfig config() {
		return data.config();
	}

	/**
	 * {@code /echoaholic clear}: removes every echo of {@code owner} and wipes the recording (T = 0, next echo #1,
	 * all segment files deleted). Segment numbers keep counting up. Returns the number of echoes removed.
	 */
	public int clear(UUID owner) {
		int removed = manager.clearEchoes(owner);
		recorder.wipe(owner);
		store.wipe(owner);
		PlayerStream stream = data.playerIfPresent(owner);
		if (stream != null) {
			stream.streamTick = 0;
			stream.nextEchoIndex = 1;
			stream.echoes.clear();
		}
		data.setDirty();
		server.getDataStorage().scheduleSave(); // a crash before the next autosave must not resurrect the cleared echoes
		return removed;
	}

	/** Stores {@code config} (clamped) and tells the manager when Echoaholic Mode was switched. */
	public void setConfig(EchoConfig config) {
		boolean was = data.config().enabled();
		data.setConfig(config);
		boolean now = data.config().enabled();
		if (was != now) manager.onModeChanged(now);
	}
}
