package dev.echoaholic.mixin;

import java.util.Set;
import java.util.function.Consumer;

import dev.echoaholic.core.action.Shoot;
import dev.echoaholic.record.EchoCapture;
import dev.echoaholic.util.Ids;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Projectile capture. Every thrown or fired projectile of a player goes through this method (bows and crossbows incl.
 * multishot, tridents, snowballs, potions, wind charges). At RETURN the projectile is aimed and in the world. The stack
 * is the ammunition / thrown item; potions and tipped arrows append their potion id as "item|potion".
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	/** Never recorded: they duplicate mobs or XP, teleport the echo, need a player owner, or explode on elytras. */
	@Unique
	private static final Set<String> EXCLUDED = Set.of("minecraft:egg", "minecraft:ender_pearl", "minecraft:experience_bottle",
			"minecraft:fishing_bobber", "minecraft:firework_rocket");

	@Inject(method = "spawnProjectile(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Consumer;)Lnet/minecraft/world/entity/projectile/Projectile;",
			at = @At("RETURN"))
	private static <T extends Projectile> void echoaholic$recordShot(T projectile, ServerLevel level, ItemStack stack,
			Consumer<T> shoot, CallbackInfoReturnable<T> cir) {
		if (!(projectile.getOwner() instanceof ServerPlayer player) || projectile.isRemoved() || !EchoCapture.recording(player)) {
			return;
		}
		String type = Ids.entityType(projectile.getType());
		if (EXCLUDED.contains(type)) return;
		String item = Ids.item(stack);
		PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
		if (potion != null && potion.potion().isPresent()) item = item + "|" + potion.potion().get().getRegisteredName();
		Vec3 pos = projectile.position();
		Vec3 vel = projectile.getDeltaMovement();
		EchoCapture.capture(player, new Shoot(type, pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, item));
	}
}
