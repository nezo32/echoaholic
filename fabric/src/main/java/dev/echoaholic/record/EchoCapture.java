package dev.echoaholic.record;

import dev.echoaholic.EchoServer;
import dev.echoaholic.core.action.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * What the capture mixins and events call. A no-op while no Echoaholic server runs (client-side logic, integrated server
 * not started yet). Actions are buffered by the {@link Recorder} and written at the end of the server tick.
 */
public final class EchoCapture {
	private EchoCapture() {}

	/** Records {@code a} for {@code p}; dropped unless the player is being recorded right now. */
	public static void capture(ServerPlayer p, Action a) {
		EchoServer server = EchoServer.current();
		if (server != null) server.recorder().capture(p, a);
	}

	/** True when {@code p} is a server player whose stream is being recorded (cheap pre-check for the mixins). */
	public static boolean recording(Player p) {
		if (!(p instanceof ServerPlayer sp)) return false;
		EchoServer server = EchoServer.current();
		return server != null && server.recorder().isRecording(sp);
	}
}
