package dev.echoaholic.test;

import static dev.echoaholic.test.EchoReplayGameTests.at;
import static dev.echoaholic.test.TestSupport.DELAY;
import static dev.echoaholic.test.TestSupport.around;
import static dev.echoaholic.test.TestSupport.awaitEcho;
import static dev.echoaholic.test.TestSupport.cleanup;
import static dev.echoaholic.test.TestSupport.discardAll;
import static dev.echoaholic.test.TestSupport.es;
import static dev.echoaholic.test.TestSupport.floor;
import static dev.echoaholic.test.TestSupport.info;
import static dev.echoaholic.test.TestSupport.manager;
import static dev.echoaholic.test.TestSupport.restore;
import static dev.echoaholic.test.TestSupport.state;
import static dev.echoaholic.test.TestSupport.stream;
import static dev.echoaholic.test.TestSupport.withConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.echoaholic.Feedback;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.EchoInfo;
import dev.echoaholic.replay.TickStats;
import dev.echoaholic.storage.EchoTuning;
import dev.echoaholic.test.SyntheticStreams.Replay;
import dev.echoaholic.test.SyntheticStreams.Stream;
import dev.echoaholic.test.TestSupport.Mock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Tests that change world-global settings. Each has an environment of its own, so it runs in a batch of its own
 * (nothing in parallel), and restores the settings in {@code finally} / at its end.
 */
public class EchoSoloGameTests {
	private static final String NS = "echoaholic-gametest:";

	// ---------------------------------------------------------------------------------------------- cap

	/** max = 2: when #3 joins, #1 (the oldest) is retired and the owner gets the "faded" notice. */
	@GameTest(environment = NS + "solo_cap", maxTicks = 400)
	public void capRetiresOldest(GameTestHelper h) {
		EchoConfig before = withConfig(h, c -> c.withMaxEchoes(2));
		floor(h);
		Mock m = TestSupport.mock(h, UUID.randomUUID());
		TestSupport.teleport(h, m.player(), new Vec3(2.5, 1, 2.5));
		UUID u = m.player().getUUID();
		List<Object> packets = new ArrayList<>();
		EchoEntity[] first = {null};
		h.onEachTick(() -> packets.addAll(m.drain()));
		h.startSequence()
				.thenWaitUntil(() -> first[0] = awaitEcho(h, u, 1))
				.thenWaitUntil(() -> awaitEcho(h, u, 3))
				.thenExecute(() -> {
					try {
						packets.addAll(m.drain());
						List<Integer> live = manager(h).list(u).stream().map(EchoInfo::index).toList();
						h.assertValueEqual(live, List.of(2, 3), "live echoes");
						h.assertTrue(first[0].isRemoved(), "#1 entity removed");
						h.assertTrue(stream(h, u).echo(1) == null, "#1 state removed");
						long faded = packets.stream().filter(p -> p instanceof ClientboundSystemChatPacket c && c.overlay()
								&& c.content().equals(Feedback.fadedMessage(1))).count();
						h.assertValueEqual(faded, 1L, "faded notice for #1; packets=" + packets);
						long joined3 = packets.stream().filter(p -> p instanceof ClientboundSystemChatPacket c && c.overlay()
								&& c.content().equals(Feedback.joinedMessage(3))).count();
						h.assertValueEqual(joined3, 1L, "joined notice for #3");
					} finally {
						cleanup(h, m.player());
						restore(h, before);
					}
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- buffer

	/** An echo whose cursor falls behind the ring buffer (small retention) is retired, without a "faded" notice. */
	@GameTest(environment = NS + "solo_buffer", maxTicks = 300)
	public void behindBufferRetires(GameTestHelper h) {
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(200);
		Replay r = SyntheticStreams.replay(h, s);
		EchoEntity[] e = {null};
		h.startSequence()
				.thenWaitUntil(() -> e[0] = awaitEcho(h, r.owner(), 1))
				.thenExecute(() -> EchoTuning.bufferTicksOverride = 40)
				.thenWaitUntil(() -> h.assertTrue(stream(h, r.owner()).echo(1) == null, "echo retired behind the buffer"))
				.thenExecute(() -> {
					try {
						h.assertTrue(es(h).store().ring(r.owner()).oldestRetainedTick() > r.echo().cursor,
								"cursor " + r.echo().cursor + " was behind the buffer start");
						h.assertTrue(e[0].isRemoved(), "entity removed");
						h.assertTrue(manager(h).list(r.owner()).isEmpty(), "list empty");
					} finally {
						EchoTuning.bufferTicksOverride = 0;
						cleanup(h, r.owner());
					}
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- pause

	/** pause: cursors and spawns freeze while T keeps growing; resume: they continue, the late #2 still lags 2*delay. */
	@GameTest(environment = NS + "solo_pause", maxTicks = 400)
	public void pauseResume(GameTestHelper h) {
		TestSupport.echoWorld(h);
		floor(h);
		var p = TestSupport.player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		long[] t0 = {-1}, c0 = {-1}, spawn2 = {-1}, resumeT = {-1};
		EchoConfig[] before = {null};
		h.onEachTick(() -> {
			if (spawn2[0] < 0 && stream(h, u).echo(2) != null) spawn2[0] = stream(h, u).streamTick;
		});
		h.startSequence()
				.thenWaitUntil(() -> awaitEcho(h, u, 1))
				.thenExecute(() -> {
					before[0] = withConfig(h, c -> c.withPaused(true));
					t0[0] = stream(h, u).streamTick;
					c0[0] = state(h, u, 1).cursor;
				})
				.thenIdle(80)
				.thenExecute(() -> {
					try {
						h.assertTrue(stream(h, u).streamTick >= t0[0] + 75, "recording continues while paused");
						h.assertTrue(stream(h, u).streamTick >= 2 * DELAY, "#2 is due");
						h.assertTrue(state(h, u, 1).cursor <= c0[0] + 1, "cursor frozen while paused");
						h.assertTrue(stream(h, u).echo(2) == null, "no spawn while paused (#2 spawned at T=" + spawn2[0] + ", paused at T=" + t0[0]
								+ ", config " + TestSupport.config(h) + ")");
						h.assertTrue(awaitEcho(h, u, 1).isAlive(), "paused echo stays visible");
						h.assertValueEqual(info(h, u, 1).activity(), Activity.PAUSED, "activity");
					} finally {
						restore(h, before[0]);
						resumeT[0] = stream(h, u).streamTick;
					}
				})
				.thenWaitUntil(() -> awaitEcho(h, u, 2))
				.thenIdle(5)
				.thenExecute(() -> {
					h.assertTrue(state(h, u, 1).cursor >= c0[0] + 5, "#1 moves again");
					long lag2 = stream(h, u).streamTick - state(h, u, 2).cursor;
					h.assertTrue(Math.abs(lag2 - 2 * DELAY) <= 2, "late #2 still lags 2*delay: " + lag2 + " (T=" + stream(h, u).streamTick
							+ " c2=" + state(h, u, 2).cursor + " c1=" + state(h, u, 1).cursor + " spawn2 at T=" + spawn2[0] + ", resumed at T=" + resumeT[0] + ")");
					cleanup(h, p);
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- mode off

	/**
	 * Mode OFF discards the entities but keeps their states (and freezes recording); ON respawns them. Neither touches
	 * the hardcore flag or the difficulty.
	 */
	@GameTest(environment = NS + "solo_modeoff", maxTicks = 300)
	public void modeOffDespawnsOnRespawns(GameTestHelper h) {
		TestSupport.echoWorld(h);
		floor(h);
		MinecraftServer server = h.getLevel().getServer();
		boolean hardcore = server.getWorldData().isHardcore();
		Difficulty difficulty = server.getWorldData().getDifficulty();
		boolean locked = server.getWorldData().isDifficultyLocked();
		var p = TestSupport.player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		EchoEntity[] e = {null};
		long[] t0 = {-1};
		EchoConfig[] before = {null};
		h.startSequence()
				.thenWaitUntil(() -> e[0] = awaitEcho(h, u, 1))
				.thenExecute(() -> {
					before[0] = withConfig(h, c -> c.withEnabled(false));
					h.assertTrue(e[0].isRemoved(), "entity discarded at once");
					h.assertTrue(stream(h, u).echo(1) != null, "state kept");
					h.assertTrue(manager(h).entity(u, 1) == null, "no entity while OFF");
					t0[0] = stream(h, u).streamTick;
				})
				.thenIdle(20)
				.thenExecute(() -> {
					try {
						h.assertValueEqual(stream(h, u).streamTick, t0[0], "nothing recorded while OFF");
						h.assertTrue(manager(h).entity(u, 1) == null, "still no entity");
						h.assertTrue(!es(h).recorder().isRecording(p), "not recording while OFF");
					} finally {
						restore(h, before[0]);
					}
				})
				.thenWaitUntil(() -> awaitEcho(h, u, 1))
				.thenExecute(() -> {
					EchoEntity again = awaitEcho(h, u, 1);
					h.assertTrue(again != e[0], "a new entity");
					h.assertValueEqual(again.echoId(), e[0].echoId(), "same echo");
					h.assertTrue(again.distanceToSqr(e[0]) < 4.0, "respawned where it was");
					h.assertValueEqual(server.getWorldData().isHardcore(), hardcore, "hardcore flag untouched");
					h.assertValueEqual(server.getWorldData().getDifficulty(), difficulty, "difficulty untouched");
					h.assertValueEqual(server.getWorldData().isDifficultyLocked(), locked, "difficulty lock untouched");
					cleanup(h, p);
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- freeTnt

	/**
	 * freeTnt=false: TNT needs a credit like any block (and is then placed as a block); freeTnt=true: without a credit
	 * it is primed at once (never a minable block).
	 */
	@GameTest(environment = NS + "solo_freetnt", maxTicks = 300)
	public void freeTntOff(GameTestHelper h) {
		EchoConfig before = withConfig(h, c -> c.withFreeTnt(false));
		floor(h);
		BlockPos a = h.absolutePos(new BlockPos(4, 1, 4)), b = h.absolutePos(new BlockPos(5, 1, 4)), c = h.absolutePos(new BlockPos(6, 1, 4));
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long tA = s.now();
		s.tick(tnt(a)).idle(24);
		long tB = s.now();
		s.tick(tnt(b)).idle(24);
		long tC = s.now();
		s.tick(tnt(c)).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		boolean[] primedAtC = {false}, primedElsewhere = {false};
		h.onEachTick(() -> {
			for (PrimedTnt p : TestSupport.entities(h, PrimedTnt.class, around(h, 4))) {
				if (p.blockPosition().equals(c)) primedAtC[0] = true;
				else primedElsewhere[0] = true;
				p.discard(); // never explode in the test grid
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tA, "past A"))
				.thenExecute(() -> {
					h.assertTrue(h.getLevel().getBlockState(a).isAir(), "freeTnt=false, no credit: not placed");
					r.echo().inventory.add("minecraft:tnt", 1);
				})
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tB, "past B"))
				.thenExecute(() -> {
					h.assertTrue(h.getLevel().getBlockState(b).is(Blocks.TNT), "freeTnt=false with a credit: placed");
					h.assertValueEqual(r.echo().inventory.count("minecraft:tnt"), 0, "credit spent");
					es(h).setConfig(es(h).config().withFreeTnt(true));
				})
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tC, "past C"))
				.thenExecute(() -> {
					try {
						h.assertTrue(primedAtC[0], "freeTnt=true without a credit: primed at once");
						h.assertTrue(h.getLevel().getBlockState(c).isAir(), "free TNT never exists as a block: " + h.getLevel().getBlockState(c));
						h.assertTrue(!primedElsewhere[0], "no other TNT was primed (A skipped, B placed as a block)");
						h.assertValueEqual(r.echo().inventory.count("minecraft:tnt"), 0, "no credit needed");
					} finally {
						h.getLevel().setBlock(b, Blocks.AIR.defaultBlockState(), 2);
						h.getLevel().setBlock(c, Blocks.AIR.defaultBlockState(), 2);
						cleanup(h, r.owner());
						restore(h, before);
					}
				})
				.thenSucceed();
	}

	private static BlockPlace tnt(BlockPos abs) {
		return new BlockPlace(abs.getX(), abs.getY(), abs.getZ(), "minecraft:tnt[unstable=false]", "minecraft:tnt");
	}

	// ---------------------------------------------------------------------------------------------- triggerBlocks

	/** A stone pressure plate under an echo stays up by default and goes down with triggerBlocks=true. */
	@GameTest(environment = NS + "solo_trigger", maxTicks = 400)
	public void triggerBlocks(GameTestHelper h) {
		EchoConfig before = withConfig(h, c -> c.withTriggerBlocks(false));
		floor(h);
		BlockPos plate = new BlockPos(5, 1, 4);
		h.setBlock(plate, Blocks.STONE_PRESSURE_PLATE);
		Vec3 home = at(h, 2, 4), onPlate = at(h, 5, 4);
		Stream s = new Stream(Level.OVERWORLD, home).idle(5).walkTo(onPlate, 0.1);
		long w1 = s.now();
		s.idle(30);
		long w1End = s.now();
		s.walkTo(home, 0.1).idle(30).walkTo(onPlate, 0.1);
		long w2 = s.now();
		s.idle(30);
		long w2End = s.now();
		s.walkTo(home, 0.1).idle(5);
		Replay r = SyntheticStreams.replay(h, s);
		boolean[] pressedEarly = {false}, pressedLate = {false}, switched = {false};
		h.onEachTick(() -> {
			long c = r.cursor();
			boolean powered = h.getBlockState(plate).getValue(PressurePlateBlock.POWERED);
			if (c > w1 + 5 && c < w1End && powered) pressedEarly[0] = true;
			if (c >= w1End && !switched[0]) {
				switched[0] = true;
				es(h).setConfig(es(h).config().withTriggerBlocks(true));
			}
			if (c > w2 + 5 && c < w2End + 5 && powered) pressedLate[0] = true;
		});
		h.startSequence().thenWaitUntil(() -> {
			h.assertTrue(r.cursor() >= w2End + 5, "echo past the second stand");
		}).thenExecute(() -> {
			try {
				h.assertTrue(!pressedEarly[0], "plate pressed although triggerBlocks=false");
				h.assertTrue(pressedLate[0], "plate not pressed with triggerBlocks=true");
			} finally {
				cleanup(h, r.owner());
				restore(h, before.withTriggerBlocks(false));
			}
		}).thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- cheap mode

	/** An echo more than cheapModeDistance from every player: cheap (silent, no held item) but still breaks blocks. */
	@GameTest(environment = NS + "solo_cheap", maxTicks = 3000)
	public void cheapModeFar(GameTestHelper h) {
		TestSupport.echoWorld(h);
		ServerLevel level = h.getLevel();
		BlockPos mid = h.absolutePos(BlockPos.ZERO).offset(2000, 0, -2000);
		int cx = mid.getX() >> 4, cz = mid.getZ() >> 4;
		BlockPos base = new BlockPos((cx << 4) + 8, mid.getY(), (cz << 4) + 8);
		level.setChunkForced(cx, cz, true);
		Replay[] r = {null};
		long[] tBreak2 = {-1};
		BlockPos b1 = base.offset(2, 0, 0), b2 = base.offset(2, 1, 0);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(level.isPositionEntityTicking(base), "far chunk ticking"))
				.thenExecute(() -> {
					for (int dx = -3; dx <= 3; dx++) {
						for (int dz = -3; dz <= 3; dz++) {
							level.setBlock(base.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
							for (int y = 0; y <= 2; y++) level.setBlock(base.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), 2);
						}
					}
					level.setBlock(b1, Blocks.STONE.defaultBlockState(), 2);
					level.setBlock(b2, Blocks.STONE.defaultBlockState(), 2);
					Stream s = new Stream(Level.OVERWORLD, Vec3.atBottomCenterOf(base)).idle(60);
					s.tick(SyntheticStreams.breakOf(b1, "minecraft:stone", "minecraft:iron_pickaxe")).idle(9);
					tBreak2[0] = s.now();
					s.tick(SyntheticStreams.breakOf(b2, "minecraft:stone", "minecraft:iron_pickaxe")).idle(20);
					r[0] = SyntheticStreams.replay(h, s);
				})
				.thenWaitUntil(() -> h.assertTrue(r[0].cursor() > tBreak2[0] + 2, "echo past the breaks"))
				.thenExecute(() -> {
					try {
						EchoEntity e = awaitEcho(h, r[0].owner(), 1);
						h.assertTrue(e.isCheap(), "far echo is cheap");
						h.assertTrue(e.isSilent(), "cheap echo is silent");
						h.assertTrue(e.getMainHandItem().isEmpty(), "cheap echo shows no item: " + e.getMainHandItem());
						h.assertTrue(info(h, r[0].owner(), 1).cheap(), "EchoInfo.cheap");
						h.assertTrue(level.getBlockState(b1).isAir() && level.getBlockState(b2).isAir(), "cheap echo still breaks blocks");
						h.assertValueEqual(r[0].echo().inventory.count("minecraft:cobblestone"), 2, "credits for the drops");
					} finally {
						discardAll(h, ItemEntity.class, new net.minecraft.world.phys.AABB(base).inflate(8));
						cleanup(h, r[0].owner());
						level.setChunkForced(cx, cz, false);
					}
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- budget

	/**
	 * 3 echoes each replay 10 breaks recorded on one tick, per-echo cap 4, global cap 5: no tick exceeds the caps, the
	 * extra breaks wait (never dropped), and all 30 blocks end up broken.
	 */
	@GameTest(environment = NS + "solo_budget", maxTicks = 300)
	public void budgetStallNotDrop(GameTestHelper h) {
		EchoConfig before = withConfig(h, c -> c.withEchoBlockOpsPerTick(4).withGlobalBlockOpsPerTick(5));
		floor(h);
		List<List<BlockPos>> rows = new ArrayList<>();
		List<Replay> replays = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			int z = 1 + 2 * i;
			List<BlockPos> row = new ArrayList<>();
			List<Action> breaks = new ArrayList<>();
			for (int x = 1; x <= 5; x++) {
				for (int y = 1; y <= 2; y++) {
					BlockPos rel = new BlockPos(x, y, z);
					h.setBlock(rel, Blocks.STONE);
					row.add(h.absolutePos(rel));
					breaks.add(SyntheticStreams.breakOf(h.absolutePos(rel), "minecraft:stone", "minecraft:iron_pickaxe"));
				}
			}
			rows.add(row);
			Stream s = new Stream(Level.OVERWORLD, at(h, 7, z)).idle(10);
			s.tick(breaks.toArray(Action[]::new)).idle(30);
			replays.add(SyntheticStreams.replay(h, s));
		}
		int[] last = {10, 10, 10};
		int[] maxTotal = {0};
		int[] maxRow = {0};
		int[] maxStats = {0};
		boolean[] deferred = {false};
		h.onEachTick(() -> {
			int total = 0;
			for (int i = 0; i < 3; i++) {
				int left = (int) rows.get(i).stream().filter(p -> h.getLevel().getBlockState(p).is(Blocks.STONE)).count();
				int broke = last[i] - left;
				last[i] = left;
				maxRow[0] = Math.max(maxRow[0], broke);
				total += broke;
			}
			maxTotal[0] = Math.max(maxTotal[0], total);
			TickStats st = manager(h).lastTickStats();
			maxStats[0] = Math.max(maxStats[0], st.blockOps());
			if (st.deferred() > 0) deferred[0] = true;
		});
		h.startSequence().thenWaitUntil(() -> {
			for (int i = 0; i < 3; i++) h.assertValueEqual(last[i], 0, "stone left in row " + i);
		}).thenExecute(() -> {
			try {
				h.assertTrue(maxRow[0] <= 4, "per-echo cap: " + maxRow[0] + " breaks by one echo in one tick");
				h.assertTrue(maxTotal[0] <= 5, "global cap: " + maxTotal[0] + " breaks in one tick");
				h.assertTrue(maxStats[0] <= 5, "TickStats.blockOps " + maxStats[0]);
				h.assertTrue(deferred[0], "some breaks were deferred");
				for (Replay r : replays) h.assertValueEqual(r.echo().inventory.count("minecraft:cobblestone"), 10, "credits of each echo");
			} finally {
				discardAll(h, ItemEntity.class, around(h, 4));
				for (Replay r : replays) cleanup(h, r.owner());
				restore(h, before);
			}
		}).thenSucceed();
	}

	/** Two fires recorded on one tick with a global hazard budget of 1 are lit on two different ticks. */
	@GameTest(environment = NS + "solo_hazard", maxTicks = 200)
	public void hazardBudget(GameTestHelper h) {
		EchoConfig before = withConfig(h, c -> c.withGlobalHazardOpsPerTick(1));
		floor(h);
		BlockPos relA = new BlockPos(4, 1, 4), relB = new BlockPos(6, 1, 4);
		h.setBlock(relA.below(), Blocks.NETHERRACK);
		h.setBlock(relB.below(), Blocks.NETHERRACK);
		BlockPos a = h.absolutePos(relA), b = h.absolutePos(relB);
		int up = Direction.UP.get3DDataValue();
		Stream s = new Stream(Level.OVERWORLD, at(h, 5, 2)).idle(10);
		s.tick(new UseItem(UseItem.Kind.IGNITE, a.getX(), a.getY(), a.getZ(), up, "minecraft:flint_and_steel", "fire"),
				new UseItem(UseItem.Kind.IGNITE, b.getX(), b.getY(), b.getZ(), up, "minecraft:flint_and_steel", "fire")).idle(20);
		Replay r = SyntheticStreams.replay(h, s);
		long[] litA = {-1}, litB = {-1};
		int[] maxHazard = {0};
		h.onEachTick(() -> {
			if (litA[0] < 0 && h.getLevel().getBlockState(a).is(Blocks.FIRE)) litA[0] = h.getTick();
			if (litB[0] < 0 && h.getLevel().getBlockState(b).is(Blocks.FIRE)) litB[0] = h.getTick();
			maxHazard[0] = Math.max(maxHazard[0], manager(h).lastTickStats().hazardOps());
		});
		h.startSequence().thenWaitUntil(() -> {
			h.assertTrue(litA[0] >= 0 && litB[0] >= 0, "both fires lit");
		}).thenExecute(() -> {
			try {
				h.assertTrue(litA[0] != litB[0], "fires lit on different ticks (" + litA[0] + ", " + litB[0] + ")");
				h.assertTrue(maxHazard[0] <= 1, "hazard ops per tick " + maxHazard[0]);
			} finally {
				h.setBlock(relA, Blocks.AIR);
				h.setBlock(relB, Blocks.AIR);
				cleanup(h, r.owner());
				restore(h, before);
			}
		}).thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- block_drops

	/** With the block_drops game rule off an echo's break drops nothing and earns no credit. */
	@GameTest(environment = NS + "solo_drops", maxTicks = 200)
	public void noCreditsWithoutBlockDrops(GameTestHelper h) {
		TestSupport.echoWorld(h);
		floor(h);
		MinecraftServer server = h.getLevel().getServer();
		boolean drops = h.getLevel().getGameRules().get(GameRules.BLOCK_DROPS);
		BlockPos rel = new BlockPos(4, 1, 4);
		h.setBlock(rel, Blocks.STONE);
		BlockPos abs = h.absolutePos(rel);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long t = s.now();
		s.tick(SyntheticStreams.breakOf(abs, "minecraft:stone", "minecraft:iron_pickaxe")).idle(200);
		h.getLevel().getGameRules().set(GameRules.BLOCK_DROPS, false, server);
		Replay r = SyntheticStreams.replay(h, s);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > t + 2, "echo past the break"))
				.thenExecute(() -> {
					try {
						h.assertTrue(h.getLevel().getBlockState(abs).isAir(), "block broken");
						h.assertItemEntityNotPresent(net.minecraft.world.item.Items.COBBLESTONE, rel, 3.0);
						h.assertTrue(r.echo().inventory.isEmpty(), "no credit without drops: " + r.echo().inventory);
					} finally {
						h.getLevel().getGameRules().set(GameRules.BLOCK_DROPS, drops, server);
						cleanup(h, r.owner());
					}
				})
				.thenSucceed();
	}
}
