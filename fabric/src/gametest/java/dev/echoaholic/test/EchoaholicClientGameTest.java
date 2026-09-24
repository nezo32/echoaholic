package dev.echoaholic.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.echoaholic.client.CreateWorldEchoHolder;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.storage.EchoWorldData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Client gametest of the Create World → Game tab rows (run with {@code ./gradlew runClientGameTest} under Xvfb).
 * <ol>
 * <li>The two rows sit directly below Difficulty (toggle, then delay); defaults ON / 5 min; the delay row is inactive
 *     while the mode is OFF; the delay cycles 5→10→15→20→30→60→1→2→3→5.</li>
 * <li>World 1 (ON, 10 min, cheats): the server data says ON / 10 and data/echoaholic/world.dat exists;
 *     /echoaholic off and /echoaholic delay 3 as the host work.</li>
 * <li>Cancel after changing both: a fresh screen is back at ON / 5.</li>
 * <li>World 2 with the mode OFF: OFF in the data.</li>
 * <li>World 3, Hardcore, mode ON, 15 min: the world is hardcore on Hard and the mode touched neither.</li>
 * </ol>
 */
public class EchoaholicClientGameTest implements FabricClientGameTest {
	private static final String TOGGLE = "echoaholic.createWorld.toggle";
	private static final String DELAY = "echoaholic.createWorld.delay";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		// 1. layout + defaults + cycling
		openCreateWorld(ctx);
		List<String> order = widgetTexts(ctx);
		int difficulty = indexStartingWith(order, I18n.get("options.difficulty"));
		if (difficulty < 0) throw new AssertionError("no Difficulty widget in " + order);
		if (difficulty + 2 >= order.size() || !order.get(difficulty + 1).startsWith(I18n.get(TOGGLE))
				|| !order.get(difficulty + 2).startsWith(I18n.get(DELAY))) {
			throw new AssertionError("expected Difficulty, Echoaholic Mode, Echo Delay in a row; widgets: " + order);
		}
		if (!uiMode(ctx) || uiDelay(ctx) != 5) throw new AssertionError("defaults: mode " + uiMode(ctx) + ", delay " + uiDelay(ctx));
		ctx.takeScreenshot("echoaholic_create_world_game_tab");
		ctx.clickScreenButton(TOGGLE);
		if (uiMode(ctx)) throw new AssertionError("toggle did not turn the mode OFF");
		if (delayActive(ctx)) throw new AssertionError("delay row active while the mode is OFF");
		ctx.takeScreenshot("echoaholic_create_world_mode_off");
		ctx.clickScreenButton(TOGGLE);
		if (!uiMode(ctx) || !delayActive(ctx)) throw new AssertionError("toggle did not turn the mode back ON (active delay)");
		int[] expected = {10, 15, 20, 30, 60, 1, 2, 3, 5};
		List<Integer> seen = new ArrayList<>();
		for (int m : expected) {
			ctx.clickScreenButton(DELAY);
			seen.add(uiDelay(ctx));
		}
		if (!seen.equals(java.util.Arrays.stream(expected).boxed().toList())) throw new AssertionError("delay cycle " + seen);
		ctx.clickScreenButton(DELAY); // 10
		ctx.runOnClient(mc -> ((CreateWorldScreen) mc.gui.screen()).getUiState().setAllowCommands(true));
		Path world1 = createWorld(ctx);
		assertConfig(ctx, true, 10, "world 1 after create");
		if (!Files.isRegularFile(world1.resolve("data/echoaholic/world.dat"))) {
			throw new AssertionError("world.dat not written right after creating " + world1);
		}
		waitForCommandTree(ctx);
		ctx.runOnClient(mc -> mc.player.connection.sendCommand("echoaholic off"));
		ctx.waitFor(mc -> !config(mc).enabled(), 100);
		ctx.runOnClient(mc -> mc.player.connection.sendCommand("echoaholic delay 3"));
		ctx.waitFor(mc -> config(mc).delayMinutes() == 3, 100);
		leaveWorld(ctx);

		// 2. cancel does not leak
		ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen())));
		ctx.waitForScreen(CreateWorldScreen.class);
		ctx.clickScreenButton(DELAY);
		ctx.clickScreenButton(TOGGLE);
		ctx.clickScreenButton("gui.cancel");
		ctx.waitForScreen(TitleScreen.class);
		openCreateWorld(ctx);
		if (!uiMode(ctx) || uiDelay(ctx) != 5) throw new AssertionError("cancel leaked: mode " + uiMode(ctx) + ", delay " + uiDelay(ctx));

		// 3. mode OFF
		ctx.clickScreenButton(TOGGLE);
		Path world2 = createWorld(ctx);
		assertConfig(ctx, false, 5, "world 2 (mode OFF)");
		leaveWorld(ctx);

		// 4. hardcore + mode ON + 15 min
		openCreateWorld(ctx);
		ctx.runOnClient(mc -> ((CreateWorldScreen) mc.gui.screen()).getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.HARDCORE));
		ctx.waitTicks(2);
		if (!uiMode(ctx)) throw new AssertionError("hardcore turned the mode button OFF");
		ctx.clickScreenButton(DELAY); // 10
		ctx.clickScreenButton(DELAY); // 15
		ctx.takeScreenshot("echoaholic_create_world_hardcore");
		Path world3 = createWorld(ctx);
		assertConfig(ctx, true, 15, "world 3 (hardcore)");
		boolean hardcore = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().isHardcore());
		Difficulty diff = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty());
		if (!hardcore || diff != Difficulty.HARD) throw new AssertionError("hardcore world: hardcore=" + hardcore + " difficulty=" + diff);
		leaveWorld(ctx);

		ctx.setScreen(TitleScreen::new);
		System.out.println("ECHOAHOLIC_CLIENT_TEST_OK world1=" + world1.getFileName() + " world2=" + world2.getFileName()
				+ " world3=" + world3.getFileName());
	}

	private static List<String> widgetTexts(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			List<String> out = new ArrayList<>();
			for (GuiEventListener c : mc.gui.screen().children()) {
				if (c instanceof AbstractWidget w) out.add(w.getMessage().getString());
			}
			return out;
		});
	}

	private static int indexStartingWith(List<String> texts, String prefix) {
		for (int i = 0; i < texts.size(); i++) if (texts.get(i).startsWith(prefix)) return i;
		return -1;
	}

	private static boolean delayActive(ClientGameTestContext ctx) {
		String prefix = I18n.get(DELAY);
		return ctx.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().startsWith(prefix))
				.map(c -> ((AbstractWidget) c).active)
				.findFirst().orElseThrow(() -> new AssertionError("no delay widget")));
	}

	private static void openCreateWorld(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> CreateWorldScreen.openFresh(mc, () -> {}));
		ctx.waitForScreen(CreateWorldScreen.class);
	}

	private static Path createWorld(ClientGameTestContext ctx) {
		ctx.clickScreenButton("selectWorld.create");
		ctx.waitFor(mc -> mc.getSingleplayerServer() != null && mc.player != null, 20 * 60);
		return ctx.computeOnClient(mc -> mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
	}

	// not server.submit(..).join(): the client-gametest framework holds the server thread while the test thread runs
	private static EchoConfig config(net.minecraft.client.Minecraft mc) {
		return EchoWorldData.get(mc.getSingleplayerServer()).config();
	}

	private static void assertConfig(ClientGameTestContext ctx, boolean enabled, int delay, String what) {
		EchoConfig c = ctx.computeOnClient(EchoaholicClientGameTest::config);
		if (c.enabled() != enabled || c.delayMinutes() != delay) {
			throw new AssertionError(what + ": " + c + ", expected enabled=" + enabled + " delay=" + delay);
		}
	}

	private static void waitForCommandTree(ClientGameTestContext ctx) {
		ctx.waitFor(mc -> mc.player.connection.getCommands().getRoot().getChild("help") != null, 20 * 10);
	}

	private static void leaveWorld(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.level.disconnect(Component.translatable("menu.savingLevel"));
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
		});
		ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 20 * 60);
		ctx.setScreen(TitleScreen::new);
		ctx.waitTicks(20);
	}

	private static boolean uiMode(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> ((CreateWorldEchoHolder) mc.gui.screen()).echoaholic$isModeEnabled());
	}

	private static int uiDelay(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> ((CreateWorldEchoHolder) mc.gui.screen()).echoaholic$getDelayMinutes());
	}
}
