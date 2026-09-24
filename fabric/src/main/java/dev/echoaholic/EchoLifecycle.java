package dev.echoaholic;

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
	private EchoLifecycle() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(EchoBootstrap::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPING.register(EchoLifecycle::onStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(EchoLifecycle::onStopped);
		ServerLifecycleEvents.BEFORE_SAVE.register(EchoLifecycle::beforeSave);
		ServerTickEvents.END_SERVER_TICK.register(EchoLifecycle::onEndTick);
		ServerPlayerEvents.JOIN.register(player -> {
			EchoServer echo = EchoServer.current();
			if (echo == null) return;
			echo.recorder().onJoin(player);
			echo.manager().onOwnerJoin(player);
		});
		ServerPlayerEvents.LEAVE.register(player -> {
			EchoServer echo = EchoServer.current();
			if (echo == null) return;
			echo.recorder().onLeave(player); // idempotent: a no-op after SERVER_STOPPING flushed everything
			echo.manager().onOwnerLeave(player.getUUID()); // echoes freeze and stay
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
		echo.recorder().tick(); // first: the manager reads this tick's stream progress
		echo.manager().tick();
	}

	/** Autosave, /save-all and the final save. Never despawns anything. */
	private static void beforeSave(MinecraftServer server, boolean flush, boolean force) {
		EchoServer echo = of(server);
		if (echo == null) return;
		echo.manager().snapshotAll();
		echo.data().setDirty();
		echo.store().flush(flush); // join the IO thread only on a flushing save (shutdown, /save-all flush)
	}

	/** Players are still online here; the final save follows. */
	private static void onStopping(MinecraftServer server) {
		EchoServer echo = of(server);
		if (echo == null) return;
		echo.recorder().flushAll();
		echo.manager().despawnAll();
		echo.data().setDirty();
	}

	/** SavedData storage is closed by now: never touch it here. */
	private static void onStopped(MinecraftServer server) {
		EchoServer echo = of(server);
		if (echo == null) return;
		try {
			echo.store().close();
		} finally {
			EchoEntity.setListener(null);
			EchoServer.stop();
		}
	}
}
