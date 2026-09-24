package dev.echoaholic.replay.handler;

import dev.echoaholic.core.action.Shoot;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.ReplayContext;
import dev.echoaholic.replay.ReplayHandler;
import dev.echoaholic.util.Ids;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fires the recorded projectile again from the recorded position with the recorded velocity, owned by the echo.
 * Projectiles are free (a ghost copy of the launcher) and can never be picked up: arrows and tridents get pickup
 * DISALLOWED, and a trident is built from a plain trident (no loyalty, riptide or channeling).
 *
 * <p>Types that would duplicate things or need a player are never replayed: eggs (chicks), ender pearls (teleport),
 * experience bottles (XP), fishing bobbers and firework rockets. A wind charge takes a hazard op (explosion).
 */
final class ShootHandler implements ReplayHandler<Shoot> {
	/** Never replayed; the recorder filters them too. */
	static final Set<String> EXCLUDED = Set.of("minecraft:egg", "minecraft:ender_pearl", "minecraft:experience_bottle",
			"minecraft:fishing_bobber", "minecraft:firework_rocket", "minecraft:eye_of_ender");
	/** Fastest believable projectile speed (blocks per tick); anything above is clamped. */
	static final double MAX_SPEED = 10.0;

	@Override
	public Result apply(ReplayContext ctx, Shoot a) {
		if (EXCLUDED.contains(a.entityType())) return Result.SKIPPED;
		EntityType<?> type = Ids.entityTypeOf(a.entityType());
		if (type == null || !isFinite(a)) return Result.SKIPPED;

		if (!Hazards.take(ctx.budget(), Hazards.of(a))) return Result.WAIT;

		ServerLevel level = ctx.level();
		int bar = a.item().indexOf('|');
		String itemId = bar < 0 ? a.item() : a.item().substring(0, bar);
		String potionId = bar < 0 ? "" : a.item().substring(bar + 1);
		Vec3 velocity = new Vec3(a.vx(), a.vy(), a.vz());
		if (velocity.length() > MAX_SPEED) velocity = velocity.normalize().scale(MAX_SPEED);

		Projectile projectile = create(level, type, a, potionId, velocity);
		if (projectile == null) return Result.SKIPPED;
		projectile.setOwner(ctx.echo());
		projectile.setPos(a.x(), a.y(), a.z());
		projectile.setDeltaMovement(velocity);
		double horizontal = velocity.horizontalDistance();
		projectile.setYRot((float) (Mth.atan2(velocity.x, velocity.z) * Mth.RAD_TO_DEG));
		projectile.setXRot((float) (Mth.atan2(velocity.y, horizontal) * Mth.RAD_TO_DEG));
		// after setOwner: never let anyone pick up an echo's arrow or trident
		if (projectile instanceof AbstractArrow arrow) arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		level.addFreshEntity(projectile);

		WorldHandlers.finish(ctx, WorldHandlers.ghost(itemId), Activity.FIGHTING);
		return Result.DONE;
	}

	private static @Nullable Projectile create(ServerLevel level, EntityType<?> type, Shoot a, String potionId, Vec3 velocity) {
		double x = a.x();
		double y = a.y();
		double z = a.z();
		Holder<Potion> potion = potion(potionId);
		if (type == EntityTypes.ARROW) {
			ItemStack stack = potion != null ? PotionContents.createItemStack(Items.TIPPED_ARROW, potion) : new ItemStack(Items.ARROW);
			return new Arrow(level, x, y, z, stack, null);
		}
		if (type == EntityTypes.SPECTRAL_ARROW) return new SpectralArrow(level, x, y, z, new ItemStack(Items.SPECTRAL_ARROW), null);
		if (type == EntityTypes.TRIDENT) return new ThrownTrident(level, x, y, z, new ItemStack(Items.TRIDENT));
		if (type == EntityTypes.SNOWBALL) return new Snowball(level, x, y, z, new ItemStack(Items.SNOWBALL));
		if (type == EntityTypes.SPLASH_POTION) {
			return new ThrownSplashPotion(level, x, y, z, potionStack(Items.SPLASH_POTION.getDefaultInstance(), potion));
		}
		if (type == EntityTypes.LINGERING_POTION) {
			return new ThrownLingeringPotion(level, x, y, z, potionStack(Items.LINGERING_POTION.getDefaultInstance(), potion));
		}
		if (type == EntityTypes.WIND_CHARGE) return new WindCharge(level, x, y, z, velocity);
		// anything else (modded projectiles): plain entity of the recorded type
		Entity entity = type.create(level, EntitySpawnReason.TRIGGERED);
		if (entity instanceof Projectile projectile) return projectile;
		if (entity != null) entity.discard();
		return null;
	}

	private static ItemStack potionStack(ItemStack plain, @Nullable Holder<Potion> potion) {
		return potion == null ? plain : PotionContents.createItemStack(plain.getItem(), potion);
	}

	private static @Nullable Holder<Potion> potion(String id) {
		if (id.isEmpty()) return null;
		Identifier key = Identifier.tryParse(id);
		if (key == null) return null;
		Optional<Holder.Reference<Potion>> holder = BuiltInRegistries.POTION.get(key);
		return holder.isPresent() ? holder.get() : null;
	}

	private static boolean isFinite(Shoot a) {
		return Double.isFinite(a.x()) && Double.isFinite(a.y()) && Double.isFinite(a.z())
				&& Double.isFinite(a.vx()) && Double.isFinite(a.vy()) && Double.isFinite(a.vz());
	}
}
