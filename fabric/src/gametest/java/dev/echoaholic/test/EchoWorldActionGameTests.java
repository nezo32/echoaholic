package dev.echoaholic.test;

import static dev.echoaholic.test.EchoReplayGameTests.at;
import static dev.echoaholic.test.TestSupport.around;
import static dev.echoaholic.test.TestSupport.cleanup;
import static dev.echoaholic.test.TestSupport.discardAll;
import static dev.echoaholic.test.TestSupport.echoWorld;
import static dev.echoaholic.test.TestSupport.entities;
import static dev.echoaholic.test.TestSupport.floor;
import static dev.echoaholic.test.TestSupport.manager;
import static dev.echoaholic.test.TestSupport.player;
import static dev.echoaholic.test.TestSupport.recordedEntries;

import java.util.List;
import java.util.UUID;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.Attack;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.Death;
import dev.echoaholic.core.action.Shoot;
import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.core.stream.TickEntry;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.test.SyntheticStreams.Replay;
import dev.echoaholic.test.SyntheticStreams.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Item uses that change the world (research hooks §12, UseItem field table in ARCH §3.2), projectiles, and what the
 * recorder captures (and leaves out).
 */
public class EchoWorldActionGameTests {
	private static final int UP = Direction.UP.get3DDataValue();

	private static UseItem use(UseItem.Kind kind, BlockPos abs, int face, String item, String extra) {
		return new UseItem(kind, abs.getX(), abs.getY(), abs.getZ(), face, item, extra);
	}

	// ---------------------------------------------------------------------------------------------- buckets

	/** Bucket fill: the water source disappears and the echo gains a water_bucket credit. */
	@GameTest(maxTicks = 200)
	public void bucketFillRemovesSourceAndCredits(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos rel = new BlockPos(4, 0, 4); // a pit in the floor: the source cannot spread
		BlockPos abs = h.absolutePos(rel);
		h.setBlock(rel, Blocks.WATER);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long t = s.now();
		s.tick(use(UseItem.Kind.BUCKET_FILL, abs, -1, "minecraft:water_bucket", "minecraft:water[level=0]")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > t + 2, "echo past the fill");
			h.assertTrue(h.getLevel().getBlockState(abs).isAir(), "water source removed: " + h.getLevel().getBlockState(abs));
			h.assertValueEqual(r.echo().inventory.count("minecraft:water_bucket"), 1, "water_bucket credit");
			cleanup(h, r.owner());
		});
	}

	/** Bucket empty needs a filled-bucket credit: none -> nothing placed; one -> water placed and the credit spent. */
	@GameTest(maxTicks = 200)
	public void bucketEmptyNeedsCredit(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos relA = new BlockPos(4, 0, 4);
		BlockPos relB = new BlockPos(6, 0, 6);
		h.setBlock(relA, Blocks.AIR);
		h.setBlock(relB, Blocks.AIR);
		BlockPos a = h.absolutePos(relA), b = h.absolutePos(relB);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long tA = s.now();
		s.tick(use(UseItem.Kind.BUCKET_EMPTY, a, UP, "minecraft:water_bucket", "minecraft:water")).idle(24);
		long tB = s.now();
		s.tick(use(UseItem.Kind.BUCKET_EMPTY, b, UP, "minecraft:water_bucket", "minecraft:water")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tA, "echo past empty A"))
				.thenExecute(() -> {
					h.assertTrue(h.getLevel().getBlockState(a).isAir(), "no credit: nothing placed at A");
					r.echo().inventory.add("minecraft:water_bucket", 1);
				})
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tB, "echo past empty B"))
				.thenExecute(() -> {
					h.assertTrue(h.getLevel().getBlockState(b).is(Blocks.WATER), "water placed at B: " + h.getLevel().getBlockState(b));
					h.assertValueEqual(r.echo().inventory.count("minecraft:water_bucket"), 0, "water_bucket credit spent");
					h.setBlock(relB, Blocks.STONE);
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------------------------------------- fire / TNT

	/** Flint and steel: fire appears on the recorded position; TNT_IGNITE turns the TNT block into primed TNT. */
	@GameTest(maxTicks = 200)
	public void flintIgnitesFireAndTnt(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		h.setBlock(new BlockPos(4, 0, 4), Blocks.NETHERRACK);
		BlockPos fireRel = new BlockPos(4, 1, 4);
		BlockPos tntRel = new BlockPos(6, 1, 6);
		h.setBlock(tntRel, Blocks.TNT);
		BlockPos fire = h.absolutePos(fireRel), tnt = h.absolutePos(tntRel);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		s.tick(use(UseItem.Kind.IGNITE, fire, UP, "minecraft:flint_and_steel", "fire")).idle(5);
		long tTnt = s.now();
		s.tick(use(UseItem.Kind.TNT_IGNITE, tnt, UP, "minecraft:flint_and_steel", "")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		boolean[] sawFire = {false};
		boolean[] sawPrimed = {false};
		h.onEachTick(() -> {
			if (h.getLevel().getBlockState(fire).is(Blocks.FIRE)) sawFire[0] = true;
			List<PrimedTnt> primed = entities(h, PrimedTnt.class, around(h, 2));
			if (!primed.isEmpty()) {
				sawPrimed[0] = true;
				primed.forEach(PrimedTnt::discard); // never let it explode in the test grid
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > tTnt + 2, "echo past the TNT ignite");
			h.assertTrue(sawFire[0], "fire lit by the echo");
			h.assertTrue(sawPrimed[0], "TNT primed by the echo");
			h.assertBlockNotPresent(Blocks.TNT, tntRel);
			h.setBlock(fireRel, Blocks.AIR);
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- bone meal

	/** Bone meal is a consumable: without a credit the crop stays; with one it grows and the credit is spent. */
	@GameTest(maxTicks = 200)
	public void boneMealNeedsCredit(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos cropRel = new BlockPos(4, 1, 4);
		h.setBlock(new BlockPos(4, 0, 4), Blocks.FARMLAND);
		h.setBlock(cropRel, Blocks.WHEAT);
		BlockPos crop = h.absolutePos(cropRel);
		Stream s = new Stream(Level.OVERWORLD, at(h, 2, 2)).idle(5);
		long tA = s.now();
		s.tick(use(UseItem.Kind.BONE_MEAL, crop, UP, "minecraft:bone_meal", "")).idle(24);
		long tB = s.now();
		s.tick(use(UseItem.Kind.BONE_MEAL, crop, UP, "minecraft:bone_meal", "")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tA, "echo past bone meal A"))
				.thenExecute(() -> {
					h.assertValueEqual(age(h, crop), 0, "no credit: crop unchanged");
					r.echo().inventory.add("minecraft:bone_meal", 1);
				})
				.thenWaitUntil(() -> h.assertTrue(r.cursor() > tB, "echo past bone meal B"))
				.thenExecute(() -> {
					h.assertTrue(age(h, crop) > 0, "crop grew with a credit");
					h.assertValueEqual(r.echo().inventory.count("minecraft:bone_meal"), 0, "bone_meal credit spent");
					cleanup(h, r.owner());
				})
				.thenSucceed();
	}

	private static int age(GameTestHelper h, BlockPos abs) {
		var state = h.getLevel().getBlockState(abs);
		h.assertTrue(state.is(Blocks.WHEAT), "wheat still there: " + state);
		return state.getValue(BlockStateProperties.AGE_7);
	}

	// ---------------------------------------------------------------------------------------------- shears

	/** Shears on a sheep: sheared, wool dropped. */
	@GameTest(maxTicks = 200)
	public void shearsShearSheep(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Sheep sheep = h.spawnWithNoFreeWill(EntityTypes.SHEEP, new BlockPos(5, 1, 5));
		BlockPos at = sheep.blockPosition();
		Stream s = new Stream(Level.OVERWORLD, at(h, 3, 3)).idle(5);
		long t = s.now();
		s.tick(use(UseItem.Kind.SHEAR_ENTITY, at, -1, "minecraft:shears", "minecraft:sheep")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > t + 2, "echo past the shear");
			h.assertTrue(sheep.isSheared(), "sheep sheared");
			h.assertTrue(!entities(h, ItemEntity.class, sheep.getBoundingBox().inflate(3)).isEmpty(), "wool dropped");
			sheep.discard();
			discardAll(h, ItemEntity.class, around(h, 4));
			cleanup(h, r.owner());
		});
	}

	/** Shears on a pumpkin carve it (and drop seeds). */
	@GameTest(maxTicks = 200)
	public void pumpkinCarve(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		BlockPos rel = new BlockPos(5, 1, 5);
		h.setBlock(rel, Blocks.PUMPKIN);
		BlockPos abs = h.absolutePos(rel);
		Stream s = new Stream(Level.OVERWORLD, at(h, 5, 3)).idle(5);
		long t = s.now();
		s.tick(use(UseItem.Kind.SHEAR_BLOCK, abs, Direction.NORTH.get3DDataValue(), "minecraft:shears", "minecraft:pumpkin")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > t + 2, "echo past the carve");
			h.assertBlockPresent(Blocks.CARVED_PUMPKIN, rel);
			h.assertItemEntityPresent(Items.PUMPKIN_SEEDS, rel, 3.0);
			discardAll(h, ItemEntity.class, around(h, 4));
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- projectiles

	/** A recorded bow shot flies from the echo (owned by it) and can never be picked up. */
	@GameTest(maxTicks = 200)
	public void arrowShotFliesNoPickup(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		Vec3 from = at(h, 1, 4).add(0, 1.5, 0);
		Stream s = new Stream(Level.OVERWORLD, at(h, 1, 4)).idle(5);
		long t = s.now();
		s.tick(new Shoot("minecraft:arrow", from.x, from.y, from.z, 1.2, 0.1, 0.0, "minecraft:bow")).idle(10);
		Replay r = SyntheticStreams.replay(h, s);
		h.succeedWhen(() -> {
			h.assertTrue(r.cursor() > t, "echo past the shot");
			List<AbstractArrow> arrows = entities(h, AbstractArrow.class, around(h, 4));
			h.assertValueEqual(arrows.size(), 1, "arrows");
			AbstractArrow arrow = arrows.get(0);
			h.assertValueEqual(arrow.pickup, AbstractArrow.Pickup.DISALLOWED, "pickup");
			EchoEntity e = manager(h).entity(r.owner(), 1);
			h.assertTrue(e != null && arrow.getOwner() == e, "arrow owned by the echo: " + arrow.getOwner());
			arrow.discard();
			cleanup(h, r.owner());
		});
	}

	// ---------------------------------------------------------------------------------------------- capture

	/**
	 * Capture: a thrown snowball is recorded as a Shoot; an egg (excluded: it spawns chickens) is not recorded at all.
	 */
	@GameTest(maxTicks = 100)
	public void excludedProjectileNotRecorded(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> {
					p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EGG, 4));
					Items.EGG.use(h.getLevel(), p, InteractionHand.MAIN_HAND);
					p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SNOWBALL, 4));
					Items.SNOWBALL.use(h.getLevel(), p, InteractionHand.MAIN_HAND);
					h.assertTrue(!entities(h, ThrownEgg.class, around(h, 8)).isEmpty(), "egg thrown");
					h.assertTrue(!entities(h, Snowball.class, around(h, 8)).isEmpty(), "snowball thrown");
				})
				.thenIdle(2)
				.thenExecute(() -> {
					h.getLevel().getServer().getPlayerList().remove(p); // seals the open segment
					List<Shoot> shots = actions(h, u, Shoot.class);
					h.assertTrue(shots.stream().anyMatch(a -> a.entityType().equals("minecraft:snowball")), "snowball recorded: " + shots);
					h.assertTrue(shots.stream().noneMatch(a -> a.entityType().equals("minecraft:egg")), "egg must not be recorded: " + shots);
					discardAll(h, ThrownEgg.class, around(h, 16));
					discardAll(h, Snowball.class, around(h, 16));
					cleanup(h, p);
				})
				.thenSucceed();
	}

	/** Capture: a block place, a melee hit, a flint-and-steel ignite and the owner's death all land in the stream. */
	@GameTest(maxTicks = 100)
	public void recordingCapturesActions(GameTestHelper h) {
		echoWorld(h);
		floor(h);
		h.setBlock(new BlockPos(6, 0, 2), Blocks.NETHERRACK);
		ServerPlayer p = player(h, new Vec3(2.5, 1, 2.5));
		UUID u = p.getUUID();
		Pig pig = h.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 1, 2));
		h.startSequence()
				.thenIdle(3)
				.thenExecute(() -> {
					ItemStack stone = new ItemStack(Items.STONE, 4);
					p.setItemInHand(InteractionHand.MAIN_HAND, stone);
					h.placeAt(p, stone, new BlockPos(2, 0, 4), Direction.UP);
				})
				.thenIdle(1)
				.thenExecute(() -> {
					p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
					p.attack(pig);
				})
				.thenIdle(1)
				.thenExecute(() -> {
					ItemStack flint = new ItemStack(Items.FLINT_AND_STEEL);
					p.setItemInHand(InteractionHand.MAIN_HAND, flint);
					BlockPos rack = h.absolutePos(new BlockPos(6, 0, 2));
					p.gameMode.useItemOn(p, h.getLevel(), flint, InteractionHand.MAIN_HAND,
							new BlockHitResult(Vec3.atCenterOf(rack).add(0, 0.5, 0), Direction.UP, rack, false));
				})
				.thenIdle(1)
				.thenExecute(() -> {
					p.kill(h.getLevel());
					h.assertTrue(p.isDeadOrDying(), "owner died (health " + p.getHealth() + ")");
				})
				.thenIdle(2)
				.thenExecute(() -> {
					h.getLevel().getServer().getPlayerList().remove(p);
					List<BlockPlace> places = actions(h, u, BlockPlace.class);
					h.assertTrue(places.stream().anyMatch(a -> a.blockState().startsWith("minecraft:stone") && a.item().equals("minecraft:stone")),
							"stone place recorded: " + places);
					List<Attack> attacks = actions(h, u, Attack.class);
					h.assertTrue(attacks.stream().anyMatch(a -> a.weaponItem().equals("minecraft:iron_sword") && a.damage() > 0),
							"attack recorded: " + attacks);
					List<UseItem> uses = actions(h, u, UseItem.class);
					h.assertTrue(uses.stream().anyMatch(a -> a.kind() == UseItem.Kind.IGNITE && a.item().equals("minecraft:flint_and_steel")),
							"ignite recorded: " + uses);
					h.assertTrue(!actions(h, u, Death.class).isEmpty(), "owner death recorded; stream=" + recordedEntries(h, u).stream()
							.filter(e -> !e.actions().isEmpty()).map(e -> e.tick() + ":" + e.actions()).toList());
					h.setBlock(new BlockPos(6, 1, 2), Blocks.AIR);
					pig.discard();
					discardAll(h, ItemEntity.class, around(h, 4));
					cleanup(h, p);
				})
				.thenSucceed();
	}

	static <A extends Action> List<A> actions(GameTestHelper h, UUID owner, Class<A> type) {
		return recordedEntries(h, owner).stream()
				.map(TickEntry::actions)
				.flatMap(List::stream)
				.filter(type::isInstance)
				.map(type::cast)
				.toList();
	}
}
