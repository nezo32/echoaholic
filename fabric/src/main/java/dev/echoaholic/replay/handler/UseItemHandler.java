package dev.echoaholic.replay.handler;

import dev.echoaholic.core.VirtualInventory;
import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandler;
import dev.echoaholic.util.Ids;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * World item uses (research hooks §12), replayed with direct world calls because the echo is not a player.
 * Tools (buckets, flint and steel, shears) are free ghost copies; consumables need a virtual-inventory credit, which
 * is spent only when the use worked:
 * <ul>
 * <li>bucket fill: only from a fluid source of the recorded kind (or the recorded block, e.g. powder snow); removes it
 * and credits the filled bucket;</li>
 * <li>bucket empty: needs a filled-bucket credit; fish/axolotl/tadpole buckets empty plain water (no mob copies);</li>
 * <li>ignite: fire or lighting a campfire/candle; a fire charge needs a credit;</li>
 * <li>TNT ignite: primes the TNT still at the position (respects the tntExplodes game rule);</li>
 * <li>shears: pumpkin carving (4 seeds, credited unless doTileDrops is off) and growing-plant capping; entity shearing of a ready mob of the
 * recorded type within 2 blocks;</li>
 * <li>bone meal: needs a credit.</li>
 * </ul>
 */
final class UseItemHandler implements ReplayHandler<UseItem> {
	static final double SHEAR_REACH = 2.0;
	static final int PUMPKIN_SEEDS = 4;
	/** {@code LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH}, as BoneMealItem uses it. */
	static final int BONE_MEAL_EVENT = 1505;

	private static final String WATER = "minecraft:water";

	@Override
	public Result apply(ReplayContext ctx, UseItem a) {
		return switch (a.kind()) {
			case BUCKET_FILL -> fill(ctx, a);
			case BUCKET_EMPTY -> empty(ctx, a);
			case IGNITE -> ignite(ctx, a);
			case TNT_IGNITE -> tnt(ctx, a);
			case SHEAR_BLOCK -> shearBlock(ctx, a);
			case SHEAR_ENTITY -> shearEntity(ctx, a);
			case BONE_MEAL -> boneMeal(ctx, a);
		};
	}

	private static Result fill(ReplayContext ctx, UseItem a) {
		ServerLevel level = ctx.level();
		BlockPos pos = pos(a);
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof BucketPickup pickup)) return Result.SKIPPED;
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			// "removes the source": only a source of the fluid the player picked up
			if (!fluid.isSource() || !Ids.item(fluid.getType().getBucket()).equals(a.item())) return Result.SKIPPED;
		} else {
			BlockState recorded = Ids.parseState(level.registryAccess(), a.extra());
			if (recorded == null || !state.is(recorded.getBlock())) return Result.SKIPPED;
		}

		if (!Hazards.take(ctx.budget(), Hazards.of(a))) return Result.WAIT;

		EchoEntity echo = ctx.echo();
		ItemStack filled = pickup.pickupBlock(echo, level, pos, state);
		if (filled.isEmpty()) return Result.SKIPPED;
		pickup.getPickupSound().ifPresent(sound -> level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F));
		level.gameEvent(echo, GameEvent.FLUID_PICKUP, pos);
		ctx.inventory().add(Ids.item(filled), 1);

		WorldHandlers.finish(ctx, new ItemStack(Items.BUCKET), Activity.BUILDING);
		return Result.DONE;
	}

	private static Result empty(ReplayContext ctx, UseItem a) {
		ServerLevel level = ctx.level();
		BlockPos pos = pos(a);
		Item bucket;
		if (a.extra().isEmpty()) {
			bucket = Items.POWDER_SNOW_BUCKET;
			if (!level.isEmptyBlock(pos)) return Result.SKIPPED;
		} else if (Hazards.LAVA.equals(a.extra())) {
			bucket = Items.LAVA_BUCKET;
		} else if (WATER.equals(a.extra())) {
			// fish, axolotl and tadpole buckets too: plain water, never a copy of the mob
			bucket = Items.WATER_BUCKET;
		} else {
			return Result.SKIPPED;
		}
		String credit = Ids.item(bucket);
		if (!ctx.inventory().has(credit)) return Result.SKIPPED;

		if (!Hazards.take(ctx.budget(), Hazards.of(a))) return Result.WAIT;

		EchoEntity echo = ctx.echo();
		boolean emptied;
		if (bucket instanceof BucketItem fluidBucket) {
			emptied = fluidBucket.emptyContents(echo, level, pos, null);
		} else if (bucket instanceof SolidBucketItem solidBucket) {
			emptied = solidBucket.emptyContents(echo, level, pos, null);
		} else {
			emptied = false;
		}
		if (!emptied) return Result.SKIPPED;
		ctx.inventory().take(credit);

		WorldHandlers.finish(ctx, new ItemStack(bucket), Activity.BUILDING);
		return Result.DONE;
	}

	private static Result ignite(ReplayContext ctx, UseItem a) {
		ServerLevel level = ctx.level();
		EchoEntity echo = ctx.echo();
		BlockPos pos = pos(a);
		boolean charge = WorldHandlers.itemOrAir(a.item()) == Items.FIRE_CHARGE;
		if (charge && !ctx.inventory().has(Hazards.FIRE_CHARGE)) return Result.SKIPPED;
		boolean fire = Hazards.IGNITE_FIRE.equals(a.extra());
		BlockState state = level.getBlockState(pos);
		if (fire) {
			if (!BaseFireBlock.canBePlacedAt(level, pos, echo.getDirection())) return Result.SKIPPED;
		} else if (!CampfireBlock.canLight(state) && !CandleBlock.canLight(state) && !CandleCakeBlock.canLight(state)) {
			return Result.SKIPPED;
		}

		if (!Hazards.take(ctx.budget(), Hazards.of(a))) return Result.WAIT;

		RandomSource random = level.getRandom();
		if (charge) {
			level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F,
					(random.nextFloat() - random.nextFloat()) * 0.2F + 1.0F);
		} else {
			level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, random.nextFloat() * 0.4F + 0.8F);
		}
		if (fire) {
			level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL_IMMEDIATE);
			level.gameEvent(echo, GameEvent.BLOCK_PLACE, pos);
		} else {
			level.setBlock(pos, state.setValue(BlockStateProperties.LIT, true), Block.UPDATE_ALL_IMMEDIATE);
			level.gameEvent(echo, GameEvent.BLOCK_CHANGE, pos);
		}
		if (charge) ctx.inventory().take(Hazards.FIRE_CHARGE);

		WorldHandlers.finish(ctx, new ItemStack(charge ? Items.FIRE_CHARGE : Items.FLINT_AND_STEEL), Activity.BUILDING);
		return Result.DONE;
	}

	/** Mirrors {@code TntBlock.prime} (whose signature differs between 26.2 and 26.3) without the player checks. */
	private static Result tnt(ReplayContext ctx, UseItem a) {
		ServerLevel level = ctx.level();
		BlockPos pos = pos(a);
		boolean charge = WorldHandlers.itemOrAir(a.item()) == Items.FIRE_CHARGE;
		if (!level.getBlockState(pos).is(Blocks.TNT) || !level.getGameRules().get(GameRules.TNT_EXPLODES)) return Result.SKIPPED;
		if (charge && !ctx.inventory().has(Hazards.FIRE_CHARGE)) return Result.SKIPPED;

		if (!Hazards.take(ctx.budget(), Hazards.of(a))) return Result.WAIT;

		EchoEntity echo = ctx.echo();
		PrimedTnt primed = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, echo);
		level.addFreshEntity(primed);
		level.playSound(null, primed.getX(), primed.getY(), primed.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(echo, GameEvent.PRIME_FUSE, pos);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
		if (charge) ctx.inventory().take(Hazards.FIRE_CHARGE);

		WorldHandlers.finish(ctx, new ItemStack(charge ? Items.FIRE_CHARGE : Items.FLINT_AND_STEEL), Activity.BUILDING);
		return Result.DONE;
	}

	private static Result shearBlock(ReplayContext ctx, UseItem a) {
		ServerLevel level = ctx.level();
		EchoEntity echo = ctx.echo();
		BlockPos pos = pos(a);
		BlockState state = level.getBlockState(pos);
		BlockState recorded = Ids.parseState(level.registryAccess(), a.extra());
		if (recorded == null || !state.is(recorded.getBlock())) return Result.SKIPPED;
		boolean pumpkin = state.is(Blocks.PUMPKIN);
		GrowingPlantHeadBlock plant = state.getBlock() instanceof GrowingPlantHeadBlock head && !head.isMaxAge(state) ? head : null;
		if (!pumpkin && plant == null) return Result.SKIPPED;

		if (!ctx.budget().tryBlockOp()) return Result.WAIT;

		if (pumpkin) {
			// PumpkinBlock.useItemOn without the player: facing = clicked side, or the echo's opposite on a top/bottom click
			Direction face = WorldHandlers.direction(a.face());
			Direction facing = face == null || face.getAxis() == Direction.Axis.Y ? echo.getDirection().getOpposite() : face;
			ItemStack seeds = new ItemStack(Items.PUMPKIN_SEEDS, PUMPKIN_SEEDS);
			// credited only when the seeds really drop (doTileDrops)
			if (level.getGameRules().get(GameRules.BLOCK_DROPS)) ctx.inventory().add(Ids.item(seeds), seeds.getCount());
			Block.popResourceFromFace(level, pos, facing, seeds);
			level.playSound(null, pos, SoundEvents.PUMPKIN_CARVE, SoundSource.BLOCKS, 1.0F, 1.0F);
			level.setBlock(pos, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, facing),
					Block.UPDATE_ALL_IMMEDIATE);
			level.gameEvent(echo, GameEvent.SHEAR, pos);
		} else {
			BlockState capped = plant.getMaxAgeState(state);
			level.playSound(null, pos, SoundEvents.GROWING_PLANT_CROP, SoundSource.BLOCKS, 1.0F, 1.0F);
			level.setBlockAndUpdate(pos, capped);
			level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(echo, capped));
		}

		WorldHandlers.finish(ctx, new ItemStack(Items.SHEARS), Activity.BUILDING);
		return Result.DONE;
	}

	private static Result shearEntity(ReplayContext ctx, UseItem a) {
		EntityType<?> type = Ids.entityTypeOf(a.extra());
		if (type == null) return Result.SKIPPED;

		if (!ctx.budget().tryEntityLookup()) return Result.WAIT;

		ServerLevel level = ctx.level();
		EchoEntity echo = ctx.echo();
		double cx = a.x() + 0.5;
		double cy = a.y();
		double cz = a.z() + 0.5;
		AABB box = new AABB(cx - SHEAR_REACH, cy - SHEAR_REACH, cz - SHEAR_REACH, cx + SHEAR_REACH, cy + SHEAR_REACH, cz + SHEAR_REACH);
		List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
				e -> e.getType() == type && e.isAlive() && e instanceof Shearable s && s.readyForShearing());
		LivingEntity target = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity e : candidates) {
			double d = e.distanceToSqr(cx, cy, cz);
			if (d < best) {
				best = d;
				target = e;
			}
		}
		if (!(target instanceof Shearable shearable)) return Result.SKIPPED;

		shearable.shear(level, SoundSource.PLAYERS, new ItemStack(Items.SHEARS));
		target.gameEvent(GameEvent.SHEAR, echo);

		WorldHandlers.finish(ctx, new ItemStack(Items.SHEARS), Activity.BUILDING);
		return Result.DONE;
	}

	private static Result boneMeal(ReplayContext ctx, UseItem a) {
		VirtualInventory inventory = ctx.inventory();
		String credit = Ids.item(Items.BONE_MEAL);
		if (!inventory.has(credit)) return Result.SKIPPED;

		if (!ctx.budget().tryBlockOp()) return Result.WAIT;

		ServerLevel level = ctx.level();
		BlockPos pos = pos(a);
		// the static BoneMealItem helpers hide the BonemealableBlock signature change between 26.2 and 26.3
		ItemStack meal = new ItemStack(Items.BONE_MEAL);
		if (BoneMealItem.growCrop(meal, level, pos)) {
			level.levelEvent(BONE_MEAL_EVENT, pos, 15);
		} else {
			Direction face = WorldHandlers.direction(a.face());
			if (face == null || !level.getBlockState(pos).isFaceSturdy(level, pos, face)) return Result.SKIPPED;
			BlockPos relative = pos.relative(face);
			if (!BoneMealItem.growWaterPlant(meal, level, relative, face)) return Result.SKIPPED;
			level.levelEvent(BONE_MEAL_EVENT, relative, 15);
		}
		inventory.take(credit);

		WorldHandlers.finish(ctx, new ItemStack(Items.BONE_MEAL), Activity.BUILDING);
		return Result.DONE;
	}

	private static BlockPos pos(UseItem a) {
		return new BlockPos(a.x(), a.y(), a.z());
	}
}
