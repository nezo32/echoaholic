package dev.echoaholic.test;

import static dev.echoaholic.test.EchoReplayGameTests.at;
import static dev.echoaholic.test.TestSupport.awaitEcho;
import static dev.echoaholic.test.TestSupport.cleanup;
import static dev.echoaholic.test.TestSupport.config;
import static dev.echoaholic.test.TestSupport.echoWorld;
import static dev.echoaholic.test.TestSupport.floor;
import static dev.echoaholic.test.TestSupport.manager;
import static dev.echoaholic.test.TestSupport.nameOf;
import static dev.echoaholic.test.TestSupport.op;
import static dev.echoaholic.test.TestSupport.restore;
import static dev.echoaholic.test.TestSupport.run;
import static dev.echoaholic.test.TestSupport.withConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.echoaholic.Feedback;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.net.EchoNoticePayload;
import dev.echoaholic.net.EchoTrailPayload;
import dev.echoaholic.test.SyntheticStreams.Replay;
import dev.echoaholic.test.SyntheticStreams.Stream;
import dev.echoaholic.test.TestSupport.Capture;
import dev.echoaholic.test.TestSupport.Mock;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** /echoaholic, notifications (vanilla vs modded clients) and the trail payload. */
public class EchoCommandGameTests {
	private static final String NS = "echoaholic-gametest:";

	// ---------------------------------------------------------------------------------------------- settings commands

	/** on/off/status/delay/max/pause/resume/config, with ranges, clamping, unknown keys and invalid values. */
	@GameTest(environment = NS + "solo_commands")
	public void settingsCommands(GameTestHelper h) throws CommandSyntaxException {
		EchoConfig before = withConfig(h, c -> c);
		Capture out = new Capture();
		CommandSourceStack op = op(h, out);
		try {
			h.assertValueEqual(run(h, op, "echoaholic off"), 0, "off result");
			h.assertTrue(!config(h).enabled(), "off stored");
			h.assertTrue(out.has("echoaholic.command.off"), "off message: " + out);
			h.assertValueEqual(run(h, op, "echoaholic status"), 0, "status off");
			h.assertTrue(out.has("echoaholic.command.status.off"), "status off message");
			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic delay 7"), 7, "delay result");
			h.assertValueEqual(config(h).delayMinutes(), 7, "delay stored");
			h.assertTrue(out.has("echoaholic.command.delay.set") && out.has("echoaholic.command.modeOffHint"),
					"delay message + mode-off hint: " + out);
			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic on"), 1, "on result");
			h.assertTrue(config(h).enabled(), "on stored");
			h.assertValueEqual(run(h, op, "echoaholic"), 1, "bare command = status for ops");
			h.assertTrue(out.has("echoaholic.command.status.on") && out.has("echoaholic.command.status.detail"), "status on: " + out);
			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic max 10"), 10, "max result");
			h.assertValueEqual(config(h).maxEchoes(), 10, "max stored");
			h.assertTrue(out.has("echoaholic.command.max.set") && !out.has("echoaholic.command.modeOffHint"), "max message, no hint when ON");
			for (String bad : new String[] {"echoaholic delay 0", "echoaholic delay 121", "echoaholic max 0", "echoaholic max 65",
					"echoaholic delay soon", "echoaholic bogus"}) {
				try {
					run(h, op, bad);
					h.fail("/" + bad + " accepted");
				} catch (CommandSyntaxException expected) {
					// out of range / not a number / unknown subcommand
				}
			}
			h.assertValueEqual(config(h).delayMinutes(), 7, "delay unchanged by bad input");
			h.assertValueEqual(config(h).maxEchoes(), 10, "max unchanged by bad input");

			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic config"), EchoConfig.Key.values().length, "config lists every key");
			h.assertValueEqual(out.withKey("echoaholic.command.config.value").size(), EchoConfig.Key.values().length, "config lines");
			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic config freeTnt"), 1, "config get (freeTnt default true)");
			h.assertValueEqual(run(h, op, "echoaholic config freeTnt false"), 1, "config set");
			h.assertTrue(!config(h).freeTnt(), "freeTnt stored");
			out.clear();
			run(h, op, "echoaholic config maxEchoes 999");
			h.assertValueEqual(config(h).maxEchoes(), EchoConfig.MAX_ECHOES_CAP, "clamped to the cap");
			List<TranslatableContents> set = out.withKey("echoaholic.command.config.set");
			h.assertTrue(!set.isEmpty() && arg(set.get(0), 1).equals(String.valueOf(EchoConfig.MAX_ECHOES_CAP)),
					"reply shows the stored (clamped) value: " + out);
			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic config bogusKey 1"), 0, "unknown key result");
			h.assertTrue(out.has("echoaholic.command.config.unknown"), "unknown key message: " + out);
			out.clear();
			h.assertValueEqual(run(h, op, "echoaholic config freeTnt maybe"), 0, "invalid value result");
			h.assertTrue(out.has("echoaholic.command.config.invalid"), "invalid value message: " + out);
			h.assertTrue(!config(h).freeTnt(), "invalid value changes nothing");
			run(h, op, "echoaholic config cheapModeDistance 64");
			h.assertValueEqual(config(h).cheapModeDistance(), 64, "int key set");

			out.clear();
			run(h, op, "echoaholic pause");
			h.assertTrue(config(h).paused(), "paused");
			h.assertTrue(out.has("echoaholic.command.paused"), "pause message");
			run(h, op, "echoaholic resume");
			h.assertTrue(!config(h).paused(), "resumed");
			h.assertTrue(TestSupport.es(h).data().isDirty(), "settings marked dirty for saving");
		} finally {
			restore(h, before);
		}
		h.succeed();
	}

	private static String arg(TranslatableContents tc, int i) {
		Object a = tc.getArgs()[i];
		return a instanceof Component c ? c.getString() : String.valueOf(a);
	}

	/** Everything except list is for operators only: non-ops cannot even see it. */
	@GameTest
	public void nonOpCannotChangeSettings(GameTestHelper h) {
		echoWorld(h);
		EchoConfig before = config(h);
		MinecraftServer server = h.getLevel().getServer();
		var p = TestSupport.survivalPlayer(h);
		CommandSourceStack nobody = server.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS);
		CommandSourceStack player = p.createCommandSourceStack();
		try {
			for (CommandSourceStack source : new CommandSourceStack[] {nobody, player}) {
				for (String cmd : new String[] {"echoaholic off", "echoaholic status", "echoaholic delay 3", "echoaholic max 2",
						"echoaholic pause", "echoaholic resume", "echoaholic config", "echoaholic config freeTnt false",
						"echoaholic clear", "echoaholic clear " + nameOf(p.getUUID())}) {
					try {
						run(h, source, cmd);
						h.fail("non-op could run /" + cmd);
					} catch (CommandSyntaxException expected) {
						// requires() hides it
					}
				}
			}
			h.assertValueEqual(config(h), before, "config unchanged");
		} finally {
			cleanup(h, p);
		}
		h.succeed();
	}

	// ---------------------------------------------------------------------------------------------- list / clear

	/** A non-op lists their own echoes (also via the bare command); listing somebody else is an operator command. */
	@GameTest(maxTicks = 100)
	public void listSelfNonOp(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Replay r = SyntheticStreams.replay(h, new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(60));
		var other = TestSupport.survivalPlayer(h);
		h.startSequence().thenWaitUntil(() -> {
			awaitEcho(h, r.owner(), 1);
		}).thenExecute(() -> {
			try {
				Capture out = new Capture();
				CommandSourceStack self = r.player().createCommandSourceStack().withSource(out);
				h.assertValueEqual(run(h, self, "echoaholic list"), 1, "own list size");
				h.assertTrue(out.has("echoaholic.command.list.header"), "header: " + out);
				List<TranslatableContents> entries = out.withKey("echoaholic.command.list.entry");
				h.assertValueEqual(entries.size(), 1, "entries");
				TranslatableContents e = entries.get(0);
				h.assertValueEqual(e.getArgs().length, 5, "entry args (k, activity, lag, pos, health)");
				h.assertValueEqual(arg(e, 0), "1", "echo number");
				h.assertValueEqual(arg(e, 4), "20", "health");
				out.clear();
				h.assertValueEqual(run(h, self, "echoaholic"), 1, "bare command lists own echoes for non-ops");
				h.assertTrue(out.has("echoaholic.command.list.entry"), "bare command output: " + out);

				Capture denied = new Capture();
				CommandSourceStack stranger = other.createCommandSourceStack().withSource(denied);
				try {
					run(h, stranger, "echoaholic list " + nameOf(r.owner()));
					h.fail("a non-op could list another player's echoes");
				} catch (CommandSyntaxException expected) {
					// list <player> is for operators only: hidden from non-ops
				}
				h.assertTrue(!denied.has("echoaholic.command.list.entry"), "no entries leaked: " + denied);
				denied.clear();
				h.assertValueEqual(run(h, stranger, "echoaholic list"), 0, "stranger's own list is empty");
				h.assertTrue(denied.has("echoaholic.command.list.empty"), "empty message: " + denied);

				Capture opOut = new Capture();
				h.assertValueEqual(run(h, op(h, opOut), "echoaholic list " + nameOf(r.owner())), 1, "op lists another player");
				h.assertTrue(opOut.has("echoaholic.command.list.entry"), "op sees the entry");
			} catch (CommandSyntaxException ex) {
				h.fail("list command failed to parse: " + ex.getMessage());
			} finally {
				cleanup(h, r.owner());
				cleanup(h, other);
			}
		}).thenSucceed();
	}

	/** /echoaholic clear <player> removes every echo of that player and reports the count. */
	@GameTest(maxTicks = 100)
	public void clearCount(GameTestHelper h) throws CommandSyntaxException {
		echoWorld(h);
		floor(h);
		Replay r = SyntheticStreams.replay(h, new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(60));
		manager(h).spawnEcho(r.owner(), 2, 10);
		Capture out = new Capture();
		h.assertValueEqual(run(h, op(h, out), "echoaholic clear " + nameOf(r.owner())), 2, "cleared count");
		List<TranslatableContents> msg = out.withKey("echoaholic.command.cleared");
		h.assertTrue(!msg.isEmpty() && arg(msg.get(0), 0).equals("2"), "cleared message: " + out);
		h.assertTrue(manager(h).list(r.owner()).isEmpty(), "no echoes left");
		h.assertValueEqual(TestSupport.stream(h, r.owner()).nextEchoIndex, 1, "numbering restarts");
		cleanup(h, r.owner());
		h.succeed();
	}

	// ---------------------------------------------------------------------------------------------- notifications

	private static boolean overlay(Object msg, Component expected) {
		return msg instanceof ClientboundSystemChatPacket p && p.overlay() && p.content().equals(expected);
	}

	private static <T> List<T> payloads(List<Object> out, Class<T> type) {
		List<T> list = new ArrayList<>();
		for (Object m : out) {
			if (m instanceof ClientboundCustomPayloadPacket p && type.isInstance(p.payload())) list.add(type.cast(p.payload()));
		}
		return list;
	}

	/** Vanilla client: "joined" = overlay + sound packet; "faded" = overlay only; no payloads. */
	@GameTest
	public void vanillaClientGetsOverlayAndSound(GameTestHelper h) {
		Mock m = TestSupport.mock(h, UUID.randomUUID());
		try {
			h.assertTrue(!ServerPlayNetworking.canSend(m.player(), EchoNoticePayload.TYPE), "mock player has no echoaholic channel");
			Feedback.send(m.player(), EchoNoticePayload.JOINED, 7, false);
			List<Object> out = m.drain();
			h.assertValueEqual(out.stream().filter(x -> overlay(x, Feedback.joinedMessage(7))).count(), 1L, "joined overlay; " + out);
			h.assertValueEqual(out.stream().filter(x -> x instanceof ClientboundSoundPacket).count(), 1L, "sound; " + out);
			h.assertTrue(payloads(out, EchoNoticePayload.class).isEmpty(), "no payload");
			ClientboundSoundPacket sound = (ClientboundSoundPacket) out.stream().filter(x -> x instanceof ClientboundSoundPacket).findFirst().get();
			h.assertValueEqual(sound.getVolume(), Feedback.VOLUME, "volume");
			h.assertValueEqual(sound.getPitch(), Feedback.PITCH, "pitch");

			Feedback.send(m.player(), EchoNoticePayload.FADED, 3, false);
			out = m.drain();
			h.assertValueEqual(out.stream().filter(x -> overlay(x, Feedback.fadedMessage(3))).count(), 1L, "faded overlay; " + out);
			h.assertTrue(out.stream().noneMatch(x -> x instanceof ClientboundSoundPacket), "faded has no sound");

			TranslatableContents tc = (TranslatableContents) Feedback.joinedMessage(7).getContents();
			h.assertValueEqual(tc.getKey(), "echoaholic.message.joined", "joined key");
			h.assertTrue(tc.getFallback() != null && tc.getFallback().contains("Echo #"), "fallback for vanilla clients");
		} finally {
			cleanup(h, m.player());
		}
		h.succeed();
	}

	/** Modded client: only the echoaholic:notice payload; the client applies its own settings. */
	@GameTest
	public void moddedClientGetsPayloadOnly(GameTestHelper h) {
		Mock m = TestSupport.mock(h, UUID.randomUUID());
		try {
			TestSupport.makeModded(m.player());
			h.assertTrue(ServerPlayNetworking.canSend(m.player(), EchoNoticePayload.TYPE), "registered channel");
			Feedback.joined(m.player(), 4);
			List<Object> out = m.drain();
			List<EchoNoticePayload> p = payloads(out, EchoNoticePayload.class);
			h.assertValueEqual(p, List.of(new EchoNoticePayload(EchoNoticePayload.JOINED, 4)), "payloads; " + out);
			h.assertTrue(out.stream().noneMatch(x -> x instanceof ClientboundSystemChatPacket c && c.overlay()), "no overlay");
			h.assertTrue(out.stream().noneMatch(x -> x instanceof ClientboundSoundPacket), "no sound");
		} finally {
			cleanup(h, m.player());
		}
		h.succeed();
	}

	/** End to end: when the schedule spawns echo #1, the (vanilla) owner hears the chime and reads the overlay. */
	@GameTest(maxTicks = 200)
	public void joinedNoticeOnScheduledSpawn(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Mock m = TestSupport.mock(h, UUID.randomUUID());
		TestSupport.teleport(h, m.player(), new Vec3(2.5, 1, 2.5));
		List<Object> seen = new ArrayList<>();
		h.onEachTick(() -> seen.addAll(m.drain()));
		h.succeedWhen(() -> {
			awaitEcho(h, m.player().getUUID(), 1);
			seen.addAll(m.drain());
			h.assertTrue(seen.stream().anyMatch(x -> overlay(x, Feedback.joinedMessage(1))), "joined overlay for #1");
			h.assertTrue(seen.stream().anyMatch(x -> x instanceof ClientboundSoundPacket), "chime");
			cleanup(h, m.player());
		});
	}

	@GameTest
	public void payloadCodecRoundTrip(GameTestHelper h) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
		try {
			EchoNoticePayload.CODEC.encode(buf, new EchoNoticePayload(EchoNoticePayload.FADED, 12));
			h.assertValueEqual(EchoNoticePayload.CODEC.decode(buf), new EchoNoticePayload(EchoNoticePayload.FADED, 12), "notice");
			h.assertValueEqual(buf.readableBytes(), 0, "bytes left");

			float[] xyz = {1.5f, 64f, -2.25f, 2.5f, 64.5f, -3f};
			EchoTrailPayload.CODEC.encode(buf, new EchoTrailPayload(42, xyz));
			EchoTrailPayload t = EchoTrailPayload.CODEC.decode(buf);
			h.assertValueEqual(t.entityId(), 42, "trail entity");
			h.assertTrue(java.util.Arrays.equals(t.xyz(), xyz), "trail points");
			h.assertValueEqual(buf.readableBytes(), 0, "bytes left");

			buf.writeVarInt(7);
			buf.writeVarInt(EchoTrailPayload.MAX_POINTS + 1);
			try {
				EchoTrailPayload.CODEC.decode(buf);
				h.fail("a trail with more than 20 points decoded");
			} catch (RuntimeException expected) {
				// rejected
			}
		} finally {
			buf.release();
		}
		h.assertValueEqual(EchoNoticePayload.TYPE.id().toString(), "echoaholic:notice", "notice channel");
		h.assertValueEqual(EchoTrailPayload.TYPE.id().toString(), "echoaholic:trail", "trail channel");
		h.assertValueEqual(new EchoTrailPayload(1, new float[3 * 30]).points(), EchoTrailPayload.MAX_POINTS, "at most 20 points");
		h.succeed();
	}

	// ---------------------------------------------------------------------------------------------- trail (lead amendment)

	/**
	 * A modded player near a (non-cheap) replaying echo receives EchoTrailPayloads with the echo's upcoming recorded
	 * positions (every 5 stream ticks after the cursor); a vanilla player next to it receives none.
	 */
	@GameTest(maxTicks = 200)
	public void trailPayloadToModdedNearby(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 1, 1)).idle(5);
		for (int i = 0; i < 3; i++) s.walkTo(at(h, 6, 1), 0.1).walkTo(at(h, 6, 6), 0.1).walkTo(at(h, 1, 1), 0.1);
		List<Vec3> path = s.positions();
		// the owner's past = the synthetic stream; the owner is online with a modded client, 3 blocks above the path
		UUID u = UUID.randomUUID();
		SyntheticStreams.inject(TestSupport.es(h), u, s.finish());
		Mock back = TestSupport.mock(h, u);
		TestSupport.makeModded(back.player());
		TestSupport.teleport(h, back.player(), new Vec3(3.5, 6, 3.5));
		Mock vanilla = TestSupport.mock(h, UUID.randomUUID());
		TestSupport.teleport(h, vanilla.player(), new Vec3(4.5, 6, 4.5));
		var echo = manager(h).spawnEcho(u, 1, 0);
		List<EchoTrailPayload> trails = new ArrayList<>();
		long[] cursorAtFirst = {-1};
		List<Object> vanillaSeen = new ArrayList<>();
		h.onEachTick(() -> {
			for (EchoTrailPayload t : payloads(back.drain(), EchoTrailPayload.class)) {
				if (trails.isEmpty()) cursorAtFirst[0] = echo.cursor;
				trails.add(t);
			}
			vanillaSeen.addAll(vanilla.drain());
		});
		h.startSequence().thenWaitUntil(() -> {
			h.assertTrue(trails.size() >= 2, "trail payloads received: " + trails.size());
		}).thenExecute(() -> {
			try {
				var e = awaitEcho(h, u, 1);
				EchoTrailPayload t = trails.get(0);
				h.assertValueEqual(t.entityId(), e.getId(), "trail entity id");
				h.assertTrue(t.points() >= 1 && t.points() <= EchoTrailPayload.MAX_POINTS, "points " + t.points());
				h.assertTrue(matchesUpcoming(t, path, cursorAtFirst[0]), "trail points are the upcoming recorded positions; cursor "
						+ cursorAtFirst[0] + ", first point " + t.xyz()[0] + "," + t.xyz()[1] + "," + t.xyz()[2]);
				h.assertTrue(payloads(vanillaSeen, EchoTrailPayload.class).isEmpty(), "vanilla player got a trail payload");
			} finally {
				cleanup(h, u);
				cleanup(h, vanilla.player());
			}
		}).thenSucceed();
	}

	/** True when, for some cursor c near {@code cursor}, point i == recorded position at c + 5 * (i + 1). */
	private static boolean matchesUpcoming(EchoTrailPayload t, List<Vec3> path, long cursor) {
		for (long c = Math.max(0, cursor - 12); c <= cursor + 2; c++) {
			boolean all = true;
			for (int i = 0; i < t.points() && all; i++) {
				long tick = c + 5L * (i + 1);
				if (tick >= path.size()) {
					all = false;
					break;
				}
				Vec3 p = path.get((int) tick);
				float[] xyz = t.xyz();
				double d = Math.abs(xyz[i * 3] - p.x) + Math.abs(xyz[i * 3 + 1] - p.y) + Math.abs(xyz[i * 3 + 2] - p.z);
				if (d > 0.1) all = false;
			}
			if (all) return true;
		}
		return false;
	}
}
