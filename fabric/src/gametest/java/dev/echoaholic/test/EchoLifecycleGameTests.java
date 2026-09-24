package dev.echoaholic.test;

import static dev.echoaholic.test.EchoReplayGameTests.at;
import static dev.echoaholic.test.TestSupport.DELAY;
import static dev.echoaholic.test.TestSupport.awaitEcho;
import static dev.echoaholic.test.TestSupport.cleanup;
import static dev.echoaholic.test.TestSupport.echoWorld;
import static dev.echoaholic.test.TestSupport.es;
import static dev.echoaholic.test.TestSupport.floor;
import static dev.echoaholic.test.TestSupport.info;
import static dev.echoaholic.test.TestSupport.manager;
import static dev.echoaholic.test.TestSupport.player;
import static dev.echoaholic.test.TestSupport.segFiles;
import static dev.echoaholic.test.TestSupport.state;
import static dev.echoaholic.test.TestSupport.stream;
import static dev.echoaholic.test.TestSupport.streamDir;

import java.nio.file.Path;
import java.util.UUID;

import dev.echoaholic.core.action.Death;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.entity.EchoTargetGoal;
import dev.echoaholic.entity.SwingCompat;
import dev.echoaholic.mixin.MobAccessor;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.storage.PlayerStream;
import dev.echoaholic.test.SyntheticStreams.Replay;
import dev.echoaholic.test.SyntheticStreams.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Schedule, death, collapse, creative/offline freezing, dimensions, unloaded chunks, targeting, clear. */
public class EchoLifecycleGameTests {
	/** Far away from every other test: its chunk is only loaded while this test forces it. */
	private static final int FAR = 3000;

	// ---------------------------------------------------------------------------------------------- schedule

	/** Echo #1 joins at T = delay with cursor 0 (lag = delay); #2 at T = 2 * delay (lag = 2 * delay). */
	@GameTest(maxTicks = 300)
	public void scheduleLagIsKDelay(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		long[] seen1 = {-1, -1};
		long[] seen2 = {-1, -1};
		h.onEachTick(() -> {
			PlayerStream ps = stream(h, u);
			EchoState s1 = ps.echo(1), s2 = ps.echo(2);
			if (s1 != null && seen1[0] < 0) {
				seen1[0] = ps.streamTick;
				seen1[1] = s1.cursor;
			}
			if (s2 != null && seen2[0] < 0) {
				seen2[0] = ps.streamTick;
				seen2[1] = s2.cursor;
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(seen2[0] >= 0, "echo #2 not spawned yet");
			h.assertValueEqual(seen1[0], DELAY, "T when #1 joined");
			h.assertTrue(seen1[1] <= 1, "#1 started at cursor 0 (seen " + seen1[1] + ")");
			h.assertValueEqual(seen2[0], 2 * DELAY, "T when #2 joined");
			h.assertTrue(seen2[1] <= 1, "#2 started at cursor 0 (seen " + seen2[1] + ")");
			long t = stream(h, u).streamTick;
			long lag1 = t - state(h, u, 1).cursor, lag2 = t - state(h, u, 2).cursor;
			h.assertTrue(Math.abs(lag1 - DELAY) <= 1, "lag #1 = delay, got " + lag1);
			h.assertTrue(Math.abs(lag2 - 2 * DELAY) <= 1, "lag #2 = 2*delay, got " + lag2);
			h.assertValueEqual(info(h, u, 2).lagTicks(), lag2, "EchoInfo lag");
			cleanup(h, p);
		});
	}

	// ---------------------------------------------------------------------------------------------- death

	/** A killed echo is gone for good: its state is removed and the next echo gets the next number (never reused). */
	@GameTest(maxTicks = 200)
	public void echoDeathFreesSlot(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(100); // T = 100 after the injection
		Replay r = SyntheticStreams.replay(h, s);
		stream(h, r.owner()).nextEchoIndex = 2; // #2 is due at T = 120
		h.startSequence()
				.thenWaitUntil(() -> awaitEcho(h, r.owner(), 1))
				.thenExecute(() -> {
					EchoEntity e = awaitEcho(h, r.owner(), 1);
					e.kill(h.getLevel());
					h.assertTrue(stream(h, r.owner()).echo(1) == null, "state of the dead echo removed");
					h.assertTrue(manager(h).list(r.owner()).isEmpty(), "list empty after the death");
				})
				.thenWaitUntil(() -> awaitEcho(h, r.owner(), 2))
				.thenExecute(() -> {
					h.assertTrue(stream(h, r.owner()).echo(1) == null, "#1 never comes back");
					h.assertValueEqual(manager(h).list(r.owner()).size(), 1, "one echo");
					h.assertValueEqual(manager(h).list(r.owner()).get(0).index(), 2, "the next echo is #2");
					h.assertValueEqual(stream(h, r.owner()).nextEchoIndex, 3, "next index");
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	/** The owner's recorded death: the echo lies down (SLEEPING) for 60 ticks with its cursor held, then stands up. */
	@GameTest(maxTicks = 300)
	public void collapseOnOwnerDeath(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(10);
		long tDeath = s.now();
		s.tick(Death.INSTANCE).idle(20).walkTo(at(h, 6, 3), 0.2).idle(5);
		Replay r = SyntheticStreams.replay(h, s);
		long[] sleepStart = {-1}, sleepEnd = {-1};
		long[] heldCursor = {-1};
		h.onEachTick(() -> {
			EchoEntity e = manager(h).entity(r.owner(), 1);
			if (e == null) return;
			boolean sleeping = e.getPose() == Pose.SLEEPING;
			if (sleeping && sleepStart[0] < 0) {
				sleepStart[0] = h.getTick();
				heldCursor[0] = r.cursor();
			}
			if (sleeping && sleepEnd[0] < 0) {
				h.assertTrue(r.cursor() <= heldCursor[0] + 1, "cursor held while collapsed (" + heldCursor[0] + " -> " + r.cursor() + ")");
				h.assertTrue(info(h, r.owner(), 1).activity() == Activity.COLLAPSED || h.getTick() == sleepStart[0],
						"activity COLLAPSED while lying");
			}
			if (!sleeping && sleepStart[0] >= 0 && sleepEnd[0] < 0) sleepEnd[0] = h.getTick();
		});
		h.succeedWhen(() -> {
			h.assertTrue(sleepEnd[0] >= 0, "echo collapsed and stood up again");
			long lying = sleepEnd[0] - sleepStart[0];
			h.assertTrue(lying >= Death.COLLAPSE_TICKS - 3 && lying <= Death.COLLAPSE_TICKS + 3, "lying ticks " + lying);
			h.assertTrue(heldCursor[0] >= tDeath && heldCursor[0] <= tDeath + 1, "collapse at the death tick");
			h.assertTrue(r.cursor() > tDeath + 5, "cursor moves again after the collapse");
			EchoEntity e = awaitEcho(h, r.owner(), 1);
			h.assertValueEqual(e.getPose(), Pose.STANDING, "pose after the collapse");
			h.assertTrue(e.isAlive(), "echo alive (a collapse is not a death)");
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- creative / offline

	/** Creative from the start: nothing is recorded (T stays 0), no echo ever spawns, breaks are not captured. */
	@GameTest(maxTicks = 200)
	public void creativeNotRecordedNoSpawns(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		ServerPlayer creative = player(h, new Vec3(2.5, 1, 2.5));
		ServerPlayer spectator = player(h, new Vec3(5.5, 1, 5.5));
		creative.setGameMode(GameType.CREATIVE);
		spectator.setGameMode(GameType.SPECTATOR);
		h.setBlock(new BlockPos(4, 1, 4), Blocks.OAK_LOG);
		h.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					creative.gameMode.destroyBlock(h.absolutePos(new BlockPos(4, 1, 4)));
					h.assertTrue(!es(h).recorder().isRecording(creative), "creative is not recording");
					h.assertTrue(!es(h).recorder().isRecording(spectator), "spectator is not recording");
				})
				.thenIdle((int) (2 * DELAY))
				.thenExecute(() -> {
					for (ServerPlayer p : new ServerPlayer[] {creative, spectator}) {
						UUID u = p.getUUID();
						h.assertValueEqual(stream(h, u).streamTick, 0L, "T of a " + p.gameMode() + " player");
						h.assertTrue(manager(h).list(u).isEmpty(), "no echoes for a " + p.gameMode() + " player");
						h.assertTrue(es(h).store().ring(u).isEmpty(), "no segments for a " + p.gameMode() + " player");
						h.assertTrue(!es(h).recorder().isStreaming(u), "not streaming");
					}
					cleanup(h, creative, spectator);
				})
				.thenSucceed();
	}

	/** Switching the owner to creative pauses the stream and freezes the echo (still visible); survival resumes both. */
	@GameTest(maxTicks = 300)
	public void creativePausesStreamAndEchoes(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 1, 1)).idle(5).walkTo(at(h, 6, 1), 0.05).walkTo(at(h, 6, 6), 0.05);
		Replay r = SyntheticStreams.replay(h, s);
		long[] t0 = {-1}, c0 = {-1};
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > 5, "echo replaying"))
				.thenExecute(() -> {
					r.player().setGameMode(GameType.CREATIVE);
					t0[0] = stream(h, r.owner()).streamTick;
					c0[0] = r.cursor();
				})
				.thenIdle(30)
				.thenExecute(() -> {
					h.assertValueEqual(stream(h, r.owner()).streamTick, t0[0], "T frozen in creative");
					h.assertTrue(r.cursor() <= c0[0] + 1, "cursor frozen in creative (" + c0[0] + " -> " + r.cursor() + ")");
					EchoEntity e = awaitEcho(h, r.owner(), 1);
					h.assertTrue(e.isAlive(), "frozen echo stays visible");
					h.assertValueEqual(info(h, r.owner(), 1).activity(), Activity.PAUSED, "activity");
					r.player().setGameMode(GameType.SURVIVAL);
				})
				.thenIdle(10)
				.thenExecute(() -> {
					h.assertTrue(stream(h, r.owner()).streamTick >= t0[0] + 8, "T moves again in survival");
					h.assertTrue(r.cursor() >= c0[0] + 8, "cursor moves again in survival");
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	/** Owner offline: the echo stays in the world, frozen; back online it continues. */
	@GameTest(maxTicks = 300)
	public void ownerOfflineFreezes(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 1, 1)).idle(5).walkTo(at(h, 6, 1), 0.05).walkTo(at(h, 6, 6), 0.05);
		Replay r = SyntheticStreams.replay(h, s);
		long[] c0 = {-1};
		ServerPlayer[] back = {null};
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > 5, "echo replaying"))
				.thenExecute(() -> {
					h.getLevel().getServer().getPlayerList().remove(r.player());
					c0[0] = r.cursor();
				})
				.thenIdle(30)
				.thenExecute(() -> {
					EchoEntity e = awaitEcho(h, r.owner(), 1);
					h.assertTrue(e.isAlive(), "echo of an offline owner stays");
					h.assertTrue(r.cursor() <= c0[0] + 1, "cursor frozen while offline (" + c0[0] + " -> " + r.cursor() + ")");
					h.assertTrue(!es(h).recorder().isStreaming(r.owner()), "offline owner not streaming");
					back[0] = TestSupport.survivalPlayer(h, r.owner());
					TestSupport.teleport(h, back[0], new Vec3(1.5, 40, 1.5));
				})
				.thenIdle(10)
				.thenExecute(() -> {
					h.assertTrue(r.cursor() >= c0[0] + 8, "cursor moves again after the owner rejoined");
					cleanup(h, back[0]);
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- dimensions / chunks

	/** A recorded dimension change moves the echo to the Nether (a new entity there, the old one gone). */
	@GameTest(maxTicks = 300)
	public void dimensionChangeRespawns(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
		h.assertTrue(nether != null, "the test server has a Nether");
		BlockPos base = h.absolutePos(BlockPos.ZERO).atY(100);
		int cx = base.getX() >> 4, cz = base.getZ() >> 4;
		nether.setChunkForced(cx, cz, true);
		Vec3 target = Vec3.atBottomCenterOf(base.above());
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(10);
		long tDim = s.now();
		s.changeDimension(Level.NETHER, target).idle(30);
		EchoEntity[] overworldEcho = {null};
		Replay[] r = {null};
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(nether.isPositionEntityTicking(base), "forced Nether chunk ticking"))
				.thenExecute(() -> {
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) nether.setBlock(base.offset(dx, 0, dz), Blocks.OBSIDIAN.defaultBlockState(), 2);
					}
					for (int y = 1; y <= 3; y++) nether.setBlock(base.above(y), Blocks.AIR.defaultBlockState(), 2);
					r[0] = SyntheticStreams.replay(h, s);
				})
				.thenWaitUntil(() -> overworldEcho[0] = awaitEcho(h, r[0].owner(), 1))
				.thenWaitUntil(() -> {
					h.assertTrue(r[0].cursor() > tDim + 2, "echo past the dimension change");
					EchoEntity e = awaitEcho(h, r[0].owner(), 1);
					h.assertTrue(e.level() == nether, "echo is in the Nether: " + e.level().dimension());
				})
				.thenExecute(() -> {
					EchoEntity e = awaitEcho(h, r[0].owner(), 1);
					h.assertTrue(e != overworldEcho[0], "a new entity in the Nether");
					h.assertTrue(overworldEcho[0].isRemoved(), "the Overworld entity is gone");
					h.assertTrue(e.distanceToSqr(target) < 4.0, "at the recorded Nether position: " + e.position());
					h.assertValueEqual(r[0].echo().dimension, "minecraft:the_nether", "state dimension");
					cleanup(h, r[0].owner());
					nether.setChunkForced(cx, cz, false);
				})
				.thenSucceed();
	}

	/** An echo whose chunk stops ticking pauses (cursor held); ticking again, it continues. */
	@GameTest(maxTicks = 400)
	public void unloadedChunkPauses(GameTestHelper h) {
		echoWorld(h);
		ServerLevel level = h.getLevel();
		BlockPos base = h.absolutePos(BlockPos.ZERO).offset(FAR, 0, FAR + 200);
		int cx = base.getX() >> 4, cz = base.getZ() >> 4;
		BlockPos chunkMid = new BlockPos((cx << 4) + 8, base.getY(), (cz << 4) + 8);
		level.setChunkForced(cx, cz, true);
		Replay[] r = {null};
		long[] c0 = {-1};
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(level.isPositionEntityTicking(chunkMid), "forced chunk ticking"))
				.thenExecute(() -> {
					for (int dx = -6; dx <= 6; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							level.setBlock(chunkMid.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
							for (int y = 0; y <= 2; y++) level.setBlock(chunkMid.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), 2);
						}
					}
					Vec3 a = Vec3.atBottomCenterOf(chunkMid.west(5)), b = Vec3.atBottomCenterOf(chunkMid.east(5));
					Stream s = new Stream(Level.OVERWORLD, a).idle(5);
					for (int i = 0; i < 6; i++) s.walkTo(b, 0.1).walkTo(a, 0.1);
					r[0] = SyntheticStreams.replay(h, s);
				})
				.thenWaitUntil(() -> {
					awaitEcho(h, r[0].owner(), 1);
					h.assertTrue(r[0].cursor() > 10, "echo replaying in the forced chunk");
				})
				.thenExecute(() -> level.setChunkForced(cx, cz, false))
				.thenWaitUntil(() -> h.assertTrue(!level.isPositionEntityTicking(chunkMid), "chunk no longer ticking"))
				.thenExecute(() -> c0[0] = r[0].cursor())
				.thenIdle(20)
				.thenExecute(() -> {
					h.assertTrue(r[0].cursor() <= c0[0] + 1, "cursor held while unloaded (" + c0[0] + " -> " + r[0].cursor() + ")");
					h.assertValueEqual(info(h, r[0].owner(), 1).activity(), Activity.PAUSED, "activity while unloaded");
					level.setChunkForced(cx, cz, true);
				})
				.thenWaitUntil(() -> h.assertTrue(r[0].cursor() > c0[0] + 5, "cursor moves again when ticking"))
				.thenExecute(() -> {
					cleanup(h, r[0].owner());
					level.setChunkForced(cx, cz, false);
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- entity rules

	/** Zombies get the echo target goal and go for an echo (the owner is far out of their range). */
	@GameTest(maxTicks = 200)
	public void monstersTargetEchoes(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 1, 1)).idle(150);
		Replay r = SyntheticStreams.replay(h, s, new Vec3(1.5, 60, 1.5));
		Zombie[] zombie = {null};
		h.startSequence()
				.thenWaitUntil(() -> awaitEcho(h, r.owner(), 1))
				.thenExecute(() -> {
					zombie[0] = h.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 6));
					zombie[0].setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // no burning in daylight
					zombie[0].setPersistenceRequired();
				})
				.thenWaitUntil(() -> {
					boolean hasGoal = ((MobAccessor) zombie[0]).echoaholic$targetSelector().getAvailableGoals().stream()
							.anyMatch(w -> w.getGoal() instanceof EchoTargetGoal);
					h.assertTrue(hasGoal, "zombie has the echo target goal");
					h.assertTrue(zombie[0].getTarget() instanceof EchoEntity, "zombie targets the echo: " + zombie[0].getTarget());
				})
				.thenExecute(() -> {
					zombie[0].discard();
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	/** Echoes never ride, never use portals and refuse cross-dimension teleports. */
	@GameTest(maxTicks = 100)
	public void noRidingNoPortal(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(80);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			EchoEntity e = awaitEcho(h, r.owner(), 1);
			Boat boat = h.spawn(EntityTypes.OAK_BOAT, new BlockPos(5, 1, 5));
			h.assertTrue(!e.startRiding(boat, true, true), "startRiding refused");
			h.assertTrue(e.getVehicle() == null, "not riding");
			boat.discard();
			h.assertTrue(!e.canUsePortal(false) && !e.canUsePortal(true), "no portals");
			ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
			h.assertTrue(e.teleport(new TeleportTransition(nether, e.position(), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING)) == null,
					"cross-dimension teleport refused");
			h.assertTrue(e.level() == h.getLevel() && !e.isRemoved(), "echo still here");
			cleanup(h, r.owner());
		});
	}

	/** The swing method handle resolves on this Minecraft version and swinging does not throw. */
	@GameTest
	public void swingCompatResolves(GameTestHelper h) {
		h.assertTrue(SwingCompat.available(), "SwingCompat resolved a swing method");
		Pig pig = h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(2, 1, 2));
		SwingCompat.mainHand(pig);
		pig.discard();
		h.succeed();
	}

	// ---------------------------------------------------------------------------------------------- clear

	/** /echoaholic clear semantics: echoes and their entities gone, T = 0, next echo #1, segment files deleted. */
	@GameTest(maxTicks = 300)
	public void clearWipes(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		Path dir = streamDir(h, u);
		EchoEntity[] echo = {null};
		h.startSequence()
				.thenWaitUntil(() -> echo[0] = awaitEcho(h, u, 1))
				.thenWaitUntil(() -> h.assertTrue(segFiles(dir) > 0, "segment files written to " + dir))
				.thenExecute(() -> {
					long seqBefore = stream(h, u).nextSeq;
					h.getLevel().getServer().getPlayerList().remove(p);
					int removed = es(h).clear(u);
					h.assertValueEqual(removed, 1, "echoes removed");
					PlayerStream ps = stream(h, u);
					h.assertValueEqual(ps.streamTick, 0L, "T");
					h.assertValueEqual(ps.nextEchoIndex, 1, "next echo index");
					h.assertTrue(ps.nextSeq >= seqBefore, "segment numbers never reused (" + seqBefore + " -> " + ps.nextSeq + ")");
					h.assertTrue(ps.echoes.isEmpty(), "states gone");
					h.assertTrue(manager(h).entity(u, 1) == null, "no entity");
					h.assertTrue(echo[0].isRemoved(), "entity removed from the world");
					h.assertTrue(es(h).store().ring(u).isEmpty(), "ring empty");
				})
				.thenWaitUntil(() -> h.assertValueEqual(segFiles(dir), 0L, ".seg files left in " + dir))
				.thenExecute(() -> cleanup(h, u))
				.thenSucceed();
	}

	/** clear while the owner is online: the stream restarts at T = 0 and the first echo is #1 again. */
	@GameTest(maxTicks = 300)
	public void clearOnlineRestartsAtZero(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		h.startSequence()
				.thenWaitUntil(() -> awaitEcho(h, u, 1))
				.thenExecute(() -> {
					h.assertValueEqual(es(h).clear(u), 1, "echoes removed");
					h.assertValueEqual(stream(h, u).streamTick, 0L, "T right after clear");
				})
				.thenIdle(5)
				.thenExecute(() -> {
					long t = stream(h, u).streamTick;
					h.assertTrue(t >= 3 && t <= 6, "stream restarted from 0 (T=" + t + ")");
				})
				.thenWaitUntil(() -> awaitEcho(h, u, 1))
				.thenExecute(() -> {
					h.assertValueEqual(stream(h, u).streamTick, DELAY, "new #1 at T = delay");
					cleanup(h, p);
				})
				.thenSucceed();
	}
}
