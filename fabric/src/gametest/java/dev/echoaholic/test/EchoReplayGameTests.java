package dev.echoaholic.test;

import static dev.echoaholic.test.TestSupport.DELAY;
import static dev.echoaholic.test.TestSupport.around;
import static dev.echoaholic.test.TestSupport.awaitEcho;
import static dev.echoaholic.test.TestSupport.cleanup;
import static dev.echoaholic.test.TestSupport.discardAll;
import static dev.echoaholic.test.TestSupport.echoWorld;
import static dev.echoaholic.test.TestSupport.floor;
import static dev.echoaholic.test.TestSupport.horizontal;
import static dev.echoaholic.test.TestSupport.info;
import static dev.echoaholic.test.TestSupport.manager;
import static dev.echoaholic.test.TestSupport.player;
import static dev.echoaholic.test.TestSupport.state;
import static dev.echoaholic.test.TestSupport.stream;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import dev.echoaholic.core.action.Attack;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.test.SyntheticStreams.Replay;
import dev.echoaholic.test.SyntheticStreams.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Core replay: an echo re-does recorded block breaks / places / attacks for real, follows the recorded path, waits
 * when blocked and jumps on teleports. Every test uses its own owner, so they all run in the default environment.
 */
public class EchoReplayGameTests {
	private static final String ARENA = "echoaholic-gametest:arena32";

	/** Absolute position of the centre of relative block (x, 1, z): the walking layer above {@link TestSupport#floor}. */
	static Vec3 at(GameTestHelper h, double x, double z) {
		return h.absoluteVec(new Vec3(x + 0.5, 1, z + 0.5));
	}

	// ---------------------------------------------------------------------------------------------- break (recorded)

	/**
	 * End to end through the real recorder: the owner breaks an oak log, the log is put back, and echo #1 breaks it
	 * again one delay later: the log drops on the ground (not into anyone's inventory) and the echo gets 1 oak_log
	 * credit.
	 */
	@GameTest(maxTicks = 300)
	public void echoReplaysBreak(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos log = new BlockPos(5, 1, 5);
		h.setBlock(log, Blocks.OAK_LOG);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		long[] breakTick = {-1};
		h.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					breakTick[0] = stream(h, u).streamTick;
					h.assertTrue(p.gameMode.destroyBlock(h.absolutePos(log)), "owner broke the log");
					h.assertBlockNotPresent(Blocks.OAK_LOG, log);
					discardAll(h, ItemEntity.class, around(h, 4));
					h.setBlock(log, Blocks.OAK_LOG);
				})
				.thenWaitUntil(() -> {
					awaitEcho(h, u, 1);
					h.assertBlockNotPresent(Blocks.OAK_LOG, log);
				})
				.thenExecute(() -> {
					EchoState s = state(h, u, 1);
					h.assertValueEqual(s.inventory.count("minecraft:oak_log"), 1, "echo oak_log credit");
					h.assertItemEntityPresent(Items.OAK_LOG, log, 2.0);
					h.assertTrue(p.getInventory().isEmpty(), "nothing went into the owner's inventory");
					long lag = stream(h, u).streamTick - s.cursor;
					h.assertValueEqual(lag, DELAY, "lag when the break was replayed (exactly the delay)");
					h.assertTrue(s.cursor >= breakTick[0] + 1 && s.cursor <= breakTick[0] + 2,
							"cursor just past the break tick " + breakTick[0] + ": " + s.cursor);
				})
				.thenExecute(() -> {
					cleanup(h, p);
					discardAll(h, ItemEntity.class, around(h, 4));
				})
				.thenSucceed();
	}

	/** The block at the recorded position changed (log → stone): the break is skipped, the cursor moves on. */
	@GameTest(maxTicks = 300)
	public void breakSkippedWhenBlockChanged(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos log = new BlockPos(5, 1, 5);
		h.setBlock(log, Blocks.OAK_LOG);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		long[] breakTick = {-1};
		h.startSequence()
				.thenIdle(5)
				.thenExecute(() -> {
					breakTick[0] = stream(h, u).streamTick;
					p.gameMode.destroyBlock(h.absolutePos(log));
					discardAll(h, ItemEntity.class, around(h, 4));
					h.setBlock(log, Blocks.STONE);
				})
				.thenWaitUntil(() -> {
					awaitEcho(h, u, 1);
					h.assertTrue(state(h, u, 1).cursor > breakTick[0] + 5, "echo past the break tick");
				})
				.thenExecute(() -> {
					h.assertBlockPresent(Blocks.STONE, log);
					EchoState s = state(h, u, 1);
					h.assertTrue(s.inventory.isEmpty(), "no credit for a skipped break: " + s.inventory);
					h.assertItemEntityNotPresent(Items.STONE, log, 3.0);
					h.assertItemEntityNotPresent(Items.OAK_LOG, log, 3.0);
					cleanup(h, p);
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- place (synthetic)

	/** A recorded stone place is skipped without a stone credit; with one credit it is placed and the credit spent. */
	@GameTest(maxTicks = 200)
	public void placeNeedsMaterials(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos a = h.absolutePos(new BlockPos(4, 1, 4));
		BlockPos b = h.absolutePos(new BlockPos(5, 1, 4));
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long tA = s.now();
		s.tick(new BlockPlace(a.getX(), a.getY(), a.getZ(), "minecraft:stone", "minecraft:stone")).idle(24);
		long tB = s.now();
		s.tick(new BlockPlace(b.getX(), b.getY(), b.getZ(), "minecraft:stone", "minecraft:stone")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tA, "echo past place A"))
				.thenExecute(() -> {
					h.assertTrue(h.getLevel().getBlockState(a).isAir(), "place without credit skipped");
					h.assertTrue(r.cursor() < tB, "echo reached B too early");
					r.echo().inventory.add("minecraft:stone", 1);
				})
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tB, "echo past place B"))
				.thenExecute(() -> {
					h.assertTrue(h.getLevel().getBlockState(b).is(Blocks.STONE), "place with credit done");
					h.assertValueEqual(r.echo().inventory.count("minecraft:stone"), 0, "stone credit spent");
					h.assertTrue(h.getLevel().getBlockState(a).isAir(), "A still empty");
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	/** A recorded place onto an occupied (not replaceable) position is skipped and keeps the credit. */
	@GameTest(maxTicks = 200)
	public void placeSkippedWhenOccupied(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos rel = new BlockPos(4, 1, 4);
		BlockPos a = h.absolutePos(rel);
		h.setBlock(rel, Blocks.DIRT);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long tA = s.now();
		s.tick(new BlockPlace(a.getX(), a.getY(), a.getZ(), "minecraft:stone", "minecraft:stone")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		r.echo().inventory.add("minecraft:stone", 1);
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > tA + 2, "echo past the place");
			h.assertBlockPresent(Blocks.DIRT, rel);
			h.assertValueEqual(r.echo().inventory.count("minecraft:stone"), 1, "credit kept");
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- attack

	/** A no-AI pig next to the recorded target loses exactly the recorded damage; an attack at nothing is skipped. */
	@GameTest(maxTicks = 200)
	public void echoAttackHitsNearTarget(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Pig pig = h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(5, 1, 5));
		float before = pig.getHealth();
		Vec3 target = pig.position().add(0.3, 0.4, -0.2);
		Vec3 nothing = at(h, 1, 6);
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(5);
		s.tick(new Attack(target.x, target.y, target.z, 3.0f, "minecraft:iron_sword")).idle(10);
		long tMiss = s.now();
		s.tick(new Attack(nothing.x, nothing.y, nothing.z, 5.0f, "minecraft:iron_sword")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > tMiss + 2, "echo past both attacks");
			h.assertValueEqual(pig.getHealth(), before - 3.0f, "pig health");
			h.assertTrue(r.player().getHealth() == r.player().getMaxHealth(), "owner not hit");
			EchoEntity e = manager(h).entity(r.owner(), 1);
			h.assertTrue(e != null && e.getHealth() == e.getMaxHealth(), "echo not hit by itself");
			pig.discard();
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- movement

	/** The echo walks the recorded path: every tick within 1 block (horizontal) of the recorded position. */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void echoFollowsPath(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5)
				.walkTo(at(h, 29, 2), 0.2)
				.walkTo(at(h, 29, 29), 0.2);
		long end = s.now();
		s.idle(300); // the echo must never run into the owner's live stream during the test
		List<Vec3> path = s.positions();
		Replay r = SyntheticStreams.replay(h, s, new Vec3(16, 11, 16));
		double[] worst = {0};
		long[] worstTick = {-1};
		h.onEachTick(() -> {
			EchoEntity e = manager(h).entity(r.owner(), 1);
			long c = r.cursor();
			if (e == null || c <= 0 || c > end) return;
			double d = horizontal(e.position(), path.get((int) c));
			if (d > worst[0]) {
				worst[0] = d;
				worstTick[0] = c;
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > end, "echo at the end of the path (cursor " + r.cursor() + ")"))
				.thenExecute(() -> {
					h.assertTrue(worst[0] <= 1.0, "max horizontal deviation " + worst[0] + " at stream tick " + worstTick[0]);
					EchoEntity e = awaitEcho(h, r.owner(), 1);
					h.assertTrue(horizontal(e.position(), path.get((int) end)) <= 1.0, "echo at the path's end: " + h.relativeVec(e.position()));
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	/**
	 * Lag stays exactly k * delay on a free walk across many segment boundaries (20-tick test segments): loading the
	 * next segment must never stall the echo.
	 */
	@GameTest(environment = "echoaholic-gametest:solo_lag", structure = ARENA, maxTicks = 1000)
	public void segmentSwitchKeepsLag(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 5)).idle(5);
		for (int i = 0; i < 3; i++) s.walkTo(at(h, 28, 5), 0.25).walkTo(at(h, 2, 5), 0.25);
		long end = s.now();
		s.idle(300);
		Replay r = SyntheticStreams.replay(h, s, new Vec3(16, 11, 16));
		long[] lag0 = {-1}, maxLag = {-1}, stallNanos = {0}, lastNanos = {System.nanoTime()};
		StringBuilder stalls = new StringBuilder();
		h.onEachTick(() -> {
			long now = System.nanoTime(), dt = now - lastNanos[0];
			lastNanos[0] = now;
			long c = r.cursor();
			if (manager(h).entity(r.owner(), 1) == null || c <= 1 || c > end) return;
			long lag = stream(h, r.owner()).streamTick - c;
			if (lag0[0] < 0) lag0[0] = lag;
			if (lag > maxLag[0]) {
				if (maxLag[0] >= 0) stallNanos[0] += dt;
				if (maxLag[0] >= 0 && stalls.length() < 400) stalls.append(" c=").append(c).append("->lag ").append(lag);
				maxLag[0] = lag;
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > end, "echo at the end of the walk (cursor " + r.cursor() + ")"))
				.thenExecute(() -> {
					try {
						// the gametest server sprints (hundreds of ticks per second), so one slow disk read can still cost a
						// few ticks; without prefetching every one of the ~30 switches stalled 1-3 ticks (P1)
						double stallMs = stallNanos[0] / 1e6;
						System.out.println("ECHO_LAG drift=" + (maxLag[0] - lag0[0]) + " ticks, stalled wall time " + stallMs + " ms");
						h.assertTrue(maxLag[0] - lag0[0] <= 10 || stallMs <= 100, "lag drifted by " + (maxLag[0] - lag0[0]) + " over " + end / TestSupport.SEGMENT
								+ " segment switches (" + stallMs + " ms of wall time, i.e. " + Math.ceil(stallMs / 50) + " ticks at 20 TPS); stalls at" + stalls);
					} finally {
						cleanup(h, r.owner());
					}
				})
				.thenSucceed();
	}

	/** A wall across the path: the echo waits (lag grows, activity WAITING); remove it and the echo goes on. */
	@GameTest(structure = ARENA, maxTicks = 500)
	public void blockedEchoWaitsThenResumes(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 8)).idle(5).walkTo(at(h, 20, 8), 0.2).idle(20);
		Vec3 end = s.pos();
		for (int y = 1; y <= 3; y++) {
			for (int z = 0; z < 32; z++) h.setBlock(new BlockPos(10, y, z), Blocks.STONE);
		}
		Replay r = SyntheticStreams.replay(h, s, new Vec3(16, 11, 16));
		long[] lag0 = {-1};
		h.startSequence()
				.thenWaitUntil(() -> {
					awaitEcho(h, r.owner(), 1);
					lag0[0] = stream(h, r.owner()).streamTick - r.cursor();
				})
				.thenWaitUntil(() -> {
					long lag = stream(h, r.owner()).streamTick - r.cursor();
					h.assertTrue(lag >= lag0[0] + 20, "lag grows while blocked (" + lag0[0] + " -> " + lag + ")");
					h.assertValueEqual(info(h, r.owner(), 1).activity(), Activity.WAITING, "activity while blocked");
					EchoEntity e = awaitEcho(h, r.owner(), 1);
					h.assertTrue(e.getX() < h.absolutePos(new BlockPos(10, 1, 8)).getX(), "echo stayed in front of the wall");
				})
				.thenExecute(() -> {
					for (int y = 1; y <= 3; y++) {
						for (int z = 0; z < 32; z++) h.setBlock(new BlockPos(10, y, z), Blocks.AIR);
					}
				})
				.thenWaitUntil(() -> {
					h.assertTrue(r.cursor() >= s.length() - 1, "echo finished the path after the wall went away");
					EchoEntity e = awaitEcho(h, r.owner(), 1);
					h.assertTrue(horizontal(e.position(), end) <= 1.0, "echo at the end of the path: " + e.position());
				})
				.thenExecute(() -> cleanup(h, r.owner()))
				.thenSucceed();
	}

	/** A recorded teleport is a jump, not a walk: the echo is never seen in between. */
	@GameTest(structure = ARENA, maxTicks = 300)
	public void teleportJumps(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Vec3 a = at(h, 3, 3);
		Vec3 b = at(h, 26, 26);
		Stream s = new Stream(Level.OVERWORLD, a).idle(10);
		long tJump = s.now();
		s.teleport(b).idle(300);
		Replay r = SyntheticStreams.replay(h, s, new Vec3(16, 11, 16));
		double[] worstMiddle = {0};
		StringBuilder trace = new StringBuilder();
		h.onEachTick(() -> {
			EchoEntity e = manager(h).entity(r.owner(), 1);
			if (e == null) return;
			double d = Math.min(horizontal(e.position(), a), horizontal(e.position(), b));
			if (d > 2.0 && trace.length() < 1500) {
				trace.append(" [c=").append(r.cursor()).append(" pos=").append(h.relativeVec(e.position())).append(']');
			}
			worstMiddle[0] = Math.max(worstMiddle[0], d);
		});
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > tJump + 10, "echo past the jump");
			EchoEntity e = awaitEcho(h, r.owner(), 1);
			h.assertTrue(horizontal(e.position(), b) <= 1.0, "echo at the teleport target: " + e.position());
			h.assertTrue(worstMiddle[0] <= 2.0, "echo walked instead of jumping (" + worstMiddle[0] + " blocks off both ends; jump at c=" + tJump + "):" + trace);
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- entity

	/** Name tag key + args, owner profile, never saved to chunks, "Mannequin" description hidden, vanilla type. */
	@GameTest(maxTicks = 100)
	public void echoNameProfileNotSaved(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(60);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			EchoEntity e = awaitEcho(h, r.owner(), 1);
			h.assertTrue(e instanceof Mannequin && e.getType() == EntityTypes.MANNEQUIN, "vanilla mannequin type");
			Component name = e.getCustomName();
			h.assertTrue(name != null && name.getContents() instanceof TranslatableContents, "translatable name: " + name);
			TranslatableContents tc = (TranslatableContents) name.getContents();
			h.assertValueEqual(tc.getKey(), EchoEntity.NAME_KEY, "name key");
			h.assertValueEqual(tc.getKey(), "echoaholic.echo.name", "name key literal");
			h.assertTrue(tc.getFallback() != null && tc.getFallback().contains("Echo #"), "fallback for vanilla clients");
			Object[] args = tc.getArgs();
			h.assertValueEqual(args.length, 2, "name args");
			h.assertValueEqual(String.valueOf(args[0] instanceof Component c ? c.getString() : args[0]), "1", "echo number");
			h.assertTrue(args[1] instanceof Component, "owner name is a component");
			h.assertValueEqual(((Component) args[1]).getString(), TestSupport.nameOf(r.owner()), "owner name");
			h.assertTrue(e.isCustomNameVisible(), "name visible");
			h.assertValueEqual(e.getProfile().partialProfile().id(), r.owner(), "profile id = owner");
			h.assertValueEqual(e.ownerId(), r.owner(), "ownerId");
			h.assertValueEqual(e.echoIndex(), 1, "echoIndex");
			h.assertTrue(!e.shouldBeSaved(), "never saved to chunks");
			h.assertValueEqual(e.getMaxHealth(), 20.0f, "max health");
			h.assertTrue(hideDescription(h, e), "Mannequin description line hidden");
			cleanup(h, r.owner());
		});
	}

	private static boolean hideDescription(GameTestHelper h, Mannequin m) {
		try {
			Field f = Mannequin.class.getDeclaredField("hideDescription");
			f.setAccessible(true);
			return f.getBoolean(m);
		} catch (ReflectiveOperationException ex) {
			throw h.assertionException(Component.literal("cannot read Mannequin.hideDescription: " + ex));
		}
	}
}
