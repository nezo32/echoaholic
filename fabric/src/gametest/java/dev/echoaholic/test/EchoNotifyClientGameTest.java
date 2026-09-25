package dev.echoaholic.test;

import java.lang.reflect.Field;

import dev.echoaholic.EchoServer;
import dev.echoaholic.client.EchoRenderTint;
import dev.echoaholic.client.EchoTrailClient;
import dev.echoaholic.client.NotifyClient;
import dev.echoaholic.client.NotifyConfig;
import dev.echoaholic.client.NotifySettingsScreen;
import dev.echoaholic.core.NotifySettings;
import dev.echoaholic.net.EchoNoticePayload;
import dev.echoaholic.net.EchoTrailPayload;
import dev.echoaholic.storage.EchoTuning;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

/**
 * Client gametest of the notification settings, the notice payload, the echo tint and the trail (run with
 * {@code ./gradlew runClientGameTest} under Xvfb).
 * <ol>
 * <li>The settings screen: each ON/OFF click updates {@link NotifyConfig} and writes {@code config/echoaholic.json}.</li>
 * <li>/echoaholic-notify sound|message|trail on|off changes the same settings.</li>
 * <li>In a world: both channels are negotiated; {@link NotifyClient#handle} honours "message" (the sound branch
 *     mirrors it); a real server notice shows the overlay.</li>
 * <li>With test tuning (echo delay 60 ticks) the host's echo #1 joins: the client recognises it as an echo
 *     ({@link EchoRenderTint#tintFor}) and receives its trail payloads.</li>
 * </ol>
 */
public class EchoNotifyClientGameTest implements FabricClientGameTest {
	private static final String SOUND = "echoaholic.settings.notifySound";
	private static final String MESSAGE = "echoaholic.settings.notifyMessage";
	private static final String TRAIL = "echoaholic.settings.showTrail";
	private static final String SENTINEL = "sentinel";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		NotifySettings original = NotifyConfig.get();
		try {
			settingsScreen(ctx);
			inWorld(ctx);
		} finally {
			NotifyConfig.set(original);
			EchoTuning.reset();
		}
		System.out.println("ECHOAHOLIC_NOTIFY_CLIENT_TEST_OK");
	}

	private static void settingsScreen(ClientGameTestContext ctx) {
		NotifyConfig.set(NotifySettings.DEFAULT);
		ctx.setScreen(() -> new NotifySettingsScreen(new TitleScreen()));
		ctx.waitForScreen(NotifySettingsScreen.class);
		ctx.clickScreenButton(SOUND);
		expectSaved(new NotifySettings(false, true, true), "after sound click");
		ctx.clickScreenButton(MESSAGE);
		expectSaved(new NotifySettings(false, false, true), "after message click");
		ctx.clickScreenButton(TRAIL);
		expectSaved(new NotifySettings(false, false, false), "after trail click");
		ctx.takeScreenshot("echoaholic_notify_settings");
		ctx.clickScreenButton(SOUND);
		ctx.clickScreenButton(MESSAGE);
		ctx.clickScreenButton(TRAIL);
		expectSaved(NotifySettings.DEFAULT, "after clicking all again");
		ctx.clickScreenButton("gui.done");
		ctx.waitForScreen(TitleScreen.class);
	}

	private static void expectSaved(NotifySettings expected, String what) {
		if (!NotifyConfig.get().equals(expected)) throw new AssertionError(what + ": memory " + NotifyConfig.get());
		NotifySettings onDisk = NotifySettings.load(NotifyConfig.path());
		if (!onDisk.equals(expected)) throw new AssertionError(what + ": file " + onDisk + ", expected " + expected);
	}

	private static void inWorld(ClientGameTestContext ctx) {
		// test tuning before the world exists: the host's recording starts with 20-tick segments, echo #1 at T = 60
		EchoTuning.segmentTicksOverride = TestSupport.SEGMENT;
		EchoTuning.delayTicksOverride = TestSupport.DELAY;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.waitFor(mc -> mc.player != null, 20 * 60);
			boolean negotiated = false;
			for (int i = 0; i < 20 * 10 && !negotiated; i++) {
				negotiated = sp.getServer().computeOnServer(EchoNotifyClientGameTest::canSend);
				if (!negotiated) ctx.waitTick();
			}
			if (!negotiated) throw new AssertionError("server cannot send echoaholic:notice / echoaholic:trail to the modded client");

			notifyCommand(ctx);

			// message OFF: the overlay is untouched
			NotifyConfig.set(new NotifySettings(true, false, true));
			setSentinel(ctx);
			ctx.runOnClient(mc -> NotifyClient.handle(EchoNoticePayload.JOINED, 5, mc.player));
			ctx.waitTicks(3);
			String off = ctx.computeOnClient(EchoNotifyClientGameTest::overlay);
			if (!SENTINEL.equals(off)) throw new AssertionError("message OFF but the overlay changed to: " + off);

			// message ON: handled locally, and through a real server notice
			NotifyConfig.set(NotifySettings.DEFAULT);
			setSentinel(ctx);
			ctx.runOnClient(mc -> NotifyClient.handle(EchoNoticePayload.FADED, 4, mc.player));
			String faded = ctx.computeOnClient(EchoNotifyClientGameTest::overlay);
			if (faded == null || !faded.contains("4")) throw new AssertionError("faded overlay: " + faded);
			setSentinel(ctx);
			sp.getServer().runOnServer(s -> dev.echoaholic.Feedback.joined(s.getPlayerList().getPlayers().get(0), 6));
			ctx.waitFor(mc -> !SENTINEL.equals(overlay(mc)), 20 * 5);
			String on = ctx.computeOnClient(EchoNotifyClientGameTest::overlay);
			if (!on.contains("Echo #6")) throw new AssertionError("unexpected overlay: " + on);

			// the host's own echo #1: mode ON (Create World default), survival, stand still
			sp.getServer().runOnServer(s -> {
				EchoServer es = EchoServer.get(s);
				if (!es.config().enabled()) es.setConfig(es.config().withEnabled(true));
				s.getPlayerList().getPlayers().get(0).setGameMode(GameType.SURVIVAL);
			});
			ctx.waitFor(mc -> findEcho(mc) != null, 20 * 30);
			int[] idx = ctx.computeOnClient(mc -> {
				Entity e = findEcho(mc);
				Integer tint = EchoRenderTint.tintFor(e);
				return new int[] {EchoRenderTint.echoIndex(e), tint == null ? 0 : tint};
			});
			if (idx[0] != 1) throw new AssertionError("client sees echo number " + idx[0]);
			if (idx[1] != EchoRenderTint.argb(1)) throw new AssertionError("tint " + Integer.toHexString(idx[1]));
			boolean playerTinted = ctx.computeOnClient(mc -> EchoRenderTint.tintFor(mc.player) != null);
			if (playerTinted) throw new AssertionError("the player is tinted as an echo");
			ctx.waitFor(mc -> EchoTrailClient.trailCount() > 0, 20 * 10);
			ctx.takeScreenshot("echoaholic_echo_in_world");
		}
	}

	private static void notifyCommand(ClientGameTestContext ctx) {
		NotifyConfig.set(NotifySettings.DEFAULT);
		command(ctx, "echoaholic-notify sound off");
		command(ctx, "echoaholic-notify message off");
		command(ctx, "echoaholic-notify trail off");
		expectSaved(new NotifySettings(false, false, false), "after /echoaholic-notify ... off");
		command(ctx, "echoaholic-notify sound on");
		command(ctx, "echoaholic-notify message on");
		command(ctx, "echoaholic-notify trail on");
		expectSaved(NotifySettings.DEFAULT, "after /echoaholic-notify ... on");
		command(ctx, "echoaholic-notify status");
	}

	private static void command(ClientGameTestContext ctx, String cmd) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(cmd));
		ctx.waitTicks(2);
	}

	private static Entity findEcho(Minecraft mc) {
		for (Entity e : mc.level.entitiesForRendering()) {
			if (EchoRenderTint.echoIndex(e) > 0) return e;
		}
		return null;
	}

	private static boolean canSend(MinecraftServer s) {
		var players = s.getPlayerList().getPlayers();
		return !players.isEmpty() && ServerPlayNetworking.canSend(players.get(0), EchoNoticePayload.TYPE)
				&& ServerPlayNetworking.canSend(players.get(0), EchoTrailPayload.TYPE);
	}

	private static void setSentinel(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.hud.setOverlayMessage(Component.literal(SENTINEL), false));
	}

	/** The private Hud.overlayMessageString (Mojang names at runtime on 26.x). */
	private static String overlay(Minecraft mc) {
		try {
			Field f = Hud.class.getDeclaredField("overlayMessageString");
			f.setAccessible(true);
			Component c = (Component) f.get(mc.gui.hud);
			return c == null ? null : c.getString();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("cannot read Hud.overlayMessageString", e);
		}
	}
}
