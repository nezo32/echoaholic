package dev.echoaholic.mode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import dev.echoaholic.Echoaholic;
import dev.echoaholic.EchoServer;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.mixin.MinecraftServerAccessor;
import dev.echoaholic.replay.ReplayHandlers;
import dev.echoaholic.storage.EchoWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * ServerLifecycleEvents.SERVER_STARTING: settle the world's Echoaholic settings before levels load or anyone joins,
 * then start {@link EchoServer}.
 *
 * <p>Order of authority: the Create World choice (pending on this world's storage access), else the stored world
 * data, else mode OFF (worlds made elsewhere: other launchers, dedicated servers, pre-Echoaholic worlds).
 */
public final class EchoBootstrap {
	private EchoBootstrap() {}

	public static void onServerStarting(MinecraftServer server) {
		// always taken, so a choice never outlives this start
		PendingWorldMode access = (PendingWorldMode) ((MinecraftServerAccessor) server).echoaholic$getStorageSource();
		apply(server, access.echoaholic$takePending());
		ReplayHandlers.verifyComplete(); // a missing handler is a programming error: fail the start loudly
		EchoServer.start(server);
	}

	/** Applies a Create World choice (or none) to the world data. Public for gametests. */
	public static void apply(MinecraftServer server, PendingWorldMode.@Nullable PendingEcho pending) {
		boolean existed = EchoWorldData.exists(server);
		EchoWorldData data = EchoWorldData.get(server);
		if (pending != null) {
			// 1. new world from the Create World screen
			set(server, data, data.config().withEnabled(pending.mode()).withDelayMinutes(pending.delayMinutes()));
			server.getDataStorage().scheduleSave(); // persist now: a crash before the first autosave must not lose the choice
			Echoaholic.LOGGER.info("Echoaholic Mode {}, Echo Delay {} min for new world",
					pending.mode() ? "ON" : "OFF", data.config().delayMinutes());
			return;
		}
		if (existed) {
			// 2. existing world with Echoaholic data: it is authoritative
			Echoaholic.LOGGER.debug("Echoaholic Mode loaded: {}", data.config().enabled());
			return;
		}
		Path file = server.getWorldPath(LevelResource.DATA).resolve("echoaholic").resolve("world.dat");
		if (Files.exists(file)) {
			// 3a. the file is there but vanilla could not read it (it logged the error and cached "absent"). Do NOT
			//     persist DEFAULT over it: keep the in-memory defaults (mode OFF) without setDirty/scheduleSave, so the
			//     damaged file stays for inspection or repair. A later save that marks the data dirty (a command, the
			//     save hook) would replace it, so a backup copy is kept next to it as well.
			Path backup = file.resolveSibling("world.dat.damaged-" + System.currentTimeMillis());
			try {
				Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException | RuntimeException e) {
				backup = null;
			}
			Echoaholic.LOGGER.error("Unreadable Echoaholic world data {}; Echoaholic Mode stays OFF for this session and the file is not"
					+ " overwritten now{}", file, backup != null ? " (backup: " + backup.getFileName() + ")" : "");
			return;
		}
		// 3b. no data yet: OFF, written once so this runs once per world
		set(server, data, EchoConfig.DEFAULT);
		server.getDataStorage().scheduleSave();
	}

	/** Through the running EchoServer when there is one (gametests), so the manager hears about mode changes. */
	private static void set(MinecraftServer server, EchoWorldData data, EchoConfig config) {
		EchoServer running = EchoServer.current();
		if (running != null && running.server() == server) {
			running.setConfig(config);
		} else {
			data.setConfig(config);
		}
	}
}
