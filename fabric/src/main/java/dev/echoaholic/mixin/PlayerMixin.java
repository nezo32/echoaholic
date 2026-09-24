package dev.echoaholic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.echoaholic.core.action.Attack;
import dev.echoaholic.core.action.UseItem;
import dev.echoaholic.record.EchoCapture;
import dev.echoaholic.util.Ids;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Melee capture (the damage call inside {@code Player.attack}, which also sees killing blows) and entity shearing
 * ({@code interactOn}: the target must be ready for shearing before and not after, because a sheep that is not ready
 * still consumes the click).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Unique
	private @Nullable Entity echoaholic$shearTarget;
	@Unique
	private @Nullable EntityType<?> echoaholic$shearType;
	@Unique
	private @Nullable Vec3 echoaholic$shearPos;

	@WrapOperation(method = "attack", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
	private boolean echoaholic$recordAttack(Entity target, DamageSource source, float damage, Operation<Boolean> original) {
		Player self = (Player) (Object) this;
		if (!(self instanceof ServerPlayer player) || !(target instanceof LivingEntity) || !EchoCapture.recording(player)) {
			return original.call(target, source, damage);
		}
		Vec3 pos = target.position();
		String weapon = Ids.heldItem(player.getWeaponItem());
		boolean hurt = original.call(target, source, damage);
		if (hurt) EchoCapture.capture(player, new Attack(pos.x, pos.y, pos.z, damage, weapon));
		return hurt;
	}

	@Inject(method = "interactOn", at = @At("HEAD"))
	private void echoaholic$shearBefore(Entity entity, InteractionHand hand, Vec3 location,
			CallbackInfoReturnable<InteractionResult> cir) {
		echoaholic$shearTarget = null;
		Player self = (Player) (Object) this;
		if (entity instanceof Shearable shearable && self.getItemInHand(hand).is(Items.SHEARS) && EchoCapture.recording(self)
				&& shearable.readyForShearing()) {
			echoaholic$shearTarget = entity;
			echoaholic$shearType = entity.getType();
			echoaholic$shearPos = entity.position();
		}
	}

	@Inject(method = "interactOn", at = @At("RETURN"))
	private void echoaholic$shearAfter(Entity entity, InteractionHand hand, Vec3 location,
			CallbackInfoReturnable<InteractionResult> cir) {
		Entity target = echoaholic$shearTarget;
		echoaholic$shearTarget = null;
		if (target != entity || target == null || !cir.getReturnValue().consumesAction()) return;
		// a mooshroom converts (removed); everything else is simply no longer ready
		boolean sheared = target.isRemoved() || !(target instanceof Shearable s) || !s.readyForShearing();
		if (!sheared || !((Object) this instanceof ServerPlayer player)) return;
		Vec3 p = echoaholic$shearPos;
		EchoCapture.capture(player, new UseItem(UseItem.Kind.SHEAR_ENTITY, Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z), -1,
				Ids.item(Items.SHEARS), Ids.entityType(echoaholic$shearType)));
	}
}
