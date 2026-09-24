package dev.echoaholic;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.mode.EchoBootstrap;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Server lifecycle wiring (ARCH §4): start on SERVER_STARTING, per-tick recorder then manager, player join/leave,
 * snapshots and flushes on every save, orderly shutdown.
 */
public final class EchoLifecycle {
	/** Hooks that already logged a failure at ERROR; later failures of the same hook go to DEBUG. */
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

	private EchoLifecycle() {}

	/**
	 * Runs one step of a hook; a RuntimeException is logged (first per hook at ERROR with the stack trace, later ones at
	 * DEBUG) and swallowed, so a bug in Echoaholic never stops the server.
	 */
	static void guard(String hook, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			if (FAILED.add(hook)) {
				Echoaholic.LOGGER.error("Echoaholic {} failed; further failures of it are logged at debug level", hook, e);
			} else {
				Echoaholic.LOGGER.debug("Echoaholic {} failed", hook, e);
			}
		}
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(EchoBootstrap::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPING.register(EchoLifecycle::onStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(EchoLifecycle::onStopped);
		ServerLifecycleEvents.BEFORE_SAVE.register(EchoLifecycle::beforeSave);
		ServerTickEvents.END_SERVER_TICK.register(EchoLifecycle::onEndTick);
		ServerPlayerEvents.JOIN.register(player -> {
			EchoServer echo = EchoServer.current();
			if (echo == null) return;
			guard("recorder join", () -> echo.recorder().onJoin(player));
			guard("manager join", () -> echo.manager().onOwnerJoin(player));
		});
		ServerPlayerEvents.LEAVE.register(player -> {
			EchoServer echo = EchoServer.current();
			if (echo == null) return;
			// idempotent: a no-op after SERVER_STOPPING flushed everything
			guard("recorder leave", () -> echo.recorder().onLeave(player));
			guard("manager leave", () -> echo.manager().onOwnerLeave(player.getUUID())); // echoes freeze and stay
		});
	}

	/** The instance of exactly this server, or null. */
	private static @Nullable EchoServer of(MinecraftServer server) {
		EchoServer echo = EchoServer.current();
		return echo != null && echo.server() == server ? echo : null;
	}

	private static void onEndTick(MinecraftServer server) {
		EchoServer echo = of(server);
		if (echo == null) return;
		guard("recorder tick", echo.recorder()::tick); // first: the manager reads this tick's stream progress
		guard("manager tick", echo.manager()::tick);
	}

	/** Autosave, /save-all and the final save. Never despawns anything. */
	private static void beforeSave(MinecraftServer server, boolean flush, boolean force) {
		EchoServer echo = of(server);
		if (echo == null) return;
		guard("save snapshot", echo.manager()::snapshotAll);
		echo.data().setDirty();
		guard("save flush", () -> echo.store().flush(flush)); // join the IO thread only on a flushing save (shutdown, /save-all flush)
	}

	/** Players are still online here; the final save follows. */
	private static void onStopping(MinecraftServer server) {
		EchoServer echo = of(server);
		if (echo == null) return;
		guard("stop flush", echo.recorder()::flushAll);
		guard("stop despawn", echo.manager()::despawnAll);
		echo.data().setDirty();
	}

	/** SavedData storage is closed by now: never touch it here. */
	private static void onStopped(MinecraftServer server) {
		EchoServer echo = of(server);
		if (echo == null) return;
		guard("store close", echo.store()::close);
		EchoEntity.setListener(null);
		EchoServer.stop();
	}
}
