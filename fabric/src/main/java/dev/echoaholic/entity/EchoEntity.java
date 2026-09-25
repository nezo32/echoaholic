package dev.echoaholic.entity;

import dev.echoaholic.mixin.MannequinInvoker;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * An echo: a server-side vanilla {@link Mannequin} (no custom entity type, so vanilla clients see a plain player-like
 * mannequin with the owner's skin). The replay manager drives it: {@link #steer} gives the next recorded position and
 * vanilla physics (collision, step-up, gravity, water, ladders, footsteps) moves it there.
 *
 * <p>Never saved to chunks: the echo's state lives in world data and the entity is re-created from it. It cannot use
 * portals, ride anything or be teleported to another dimension by vanilla code (the manager respawns it there instead),
 * drops no loot and ignores pressure plates and tripwires unless told otherwise.
 */
public class EchoEntity extends Mannequin {
	/** Translation key of the name tag; the modded client recognises echoes by it. */
	public static final String NAME_KEY = "echoaholic.echo.name";
	/** Echo cyan: name tag colour and client tint. */
	public static final int TINT_RGB = 0x7FE8FF;

	/** Largest horizontal (and free-flight) step toward the target per tick, in blocks. */
	private static final double MAX_STEP = 4.0;
	/** Largest vertical step per tick when swimming or climbing (elytra flight uses {@link #MAX_STEP}). */
	private static final double MAX_FLY_STEP_Y = 0.5;
	/** Less horizontal movement than this in a tick, while colliding, counts as blocked. */
	private static final double MIN_PROGRESS = 0.05;
	/** Knockback is not overwritten by steering while hurtTime is above this (the first 2 of 10 ticks). */
	private static final int KNOCKBACK_TICKS = 8;
	private static final int AMBIENT_MIN_TICKS = 300;
	private static final int AMBIENT_MAX_TICKS = 600;
	private static final float AMBIENT_VOLUME = 0.15F;
	private static final float AMBIENT_PITCH = 1.4F;

	private static volatile @Nullable EchoListener listener;

	private final UUID echoId;
	private final UUID ownerId;
	private final int echoIndex;

	private @Nullable Vec3 steerTarget;
	private boolean steerFreeFlight;
	private boolean steerBlocked;
	private boolean flying;
	private boolean cheap;
	private boolean ignoreBlockTriggers = true;
	private dev.echoaholic.core.action.Pose lastPose = dev.echoaholic.core.action.Pose.STANDING;
	private int collapseTicks;
	private int ambientCountdown;
	private boolean managedRemoval;
	private boolean deathReported;

	public EchoEntity(ServerLevel level, UUID echoId, UUID owner, int index, ResolvableProfile profile, String ownerName) {
		super(EntityTypes.MANNEQUIN, level);
		this.echoId = echoId;
		this.ownerId = owner;
		this.echoIndex = index;
		this.entityData.set(DATA_PROFILE, profile);
		((MannequinInvoker) this).echoaholic$setHideDescription(true);
		// "Echo #7" in echo cyan, "· nezo" gray; the fallback serves vanilla clients
		setCustomName(Component.translatableWithFallback(NAME_KEY, "Echo #%s · %s", index,
						Component.literal(ownerName).withStyle(ChatFormatting.GRAY))
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(TINT_RGB))));
		setCustomNameVisible(true);
		this.ambientCountdown = nextAmbientDelay();
	}

	/** Set by the replay manager when it starts, cleared when the server stops. */
	public static void setListener(@Nullable EchoListener l) {
		listener = l;
	}

	public UUID echoId() {
		return echoId;
	}

	public UUID ownerId() {
		return ownerId;
	}

	public int echoIndex() {
		return echoIndex;
	}

	// ------------------------------------------------------------------ steering

	/**
	 * Walks toward {@code target} from the next entity tick on ({@code null} = stand still). The rotation is applied at
	 * once. {@code freeFlight} (elytra, swimming) moves vertically without gravity.
	 */
	public void steer(@Nullable Vec3 target, float yRot, float xRot, boolean freeFlight) {
		this.steerTarget = target;
		this.steerFreeFlight = freeFlight;
		if (target == null) steerBlocked = false;
		setYRot(yRot);
		setYHeadRot(yRot);
		setYBodyRot(yRot);
		setXRot(xRot);
	}

	/** Turns body, head and pitch without changing the steering target (the echo looks where the recording looked). */
	public void look(float yRot, float xRot) {
		setYRot(yRot);
		setYHeadRot(yRot);
		setYBodyRot(yRot);
		setXRot(xRot);
	}

	/** True when the last movement tick hit a wall and made (almost) no progress toward the target. */
	public boolean steerBlocked() {
		return steerBlocked;
	}

	@Override
	public void travel(Vec3 input) {
		if (cheap) {
			// the manager snaps the position every tick: no physics, no footsteps
			steerBlocked = false;
			return;
		}
		Vec3 target = steerTarget;
		if (target == null || isDeadOrDying() || collapseTicks > 0 || hurtTime > KNOCKBACK_TICKS) {
			setFlying(false);
			steerBlocked = false;
			super.travel(Vec3.ZERO);
			return;
		}
		double startX = getX();
		double startZ = getZ();
		double dx = Mth.clamp(target.x - startX, -MAX_STEP, MAX_STEP);
		double dy = target.y - getY();
		double dz = Mth.clamp(target.z - startZ, -MAX_STEP, MAX_STEP);
		boolean fly = steerFreeFlight || isInWater() || isInLava() || onClimbable();
		setFlying(fly);
		// elytra flight dives and climbs as fast as it moves horizontally; swimming/climbing stays gentle
		double maxDy = steerFreeFlight && lastPose.fallFlying() ? MAX_STEP : MAX_FLY_STEP_Y;
		double vy = fly ? Mth.clamp(dy, -maxDy, maxDy) : getDeltaMovement().y;
		setDeltaMovement(dx, vy, dz);
		if (!fly && dy > 0.5 && onGround() && (horizontalCollision || dy > 0.6)) {
			jumpFromGround();
		}
		super.travel(Vec3.ZERO);
		double moved = Math.sqrt(Mth.square(getX() - startX) + Mth.square(getZ() - startZ));
		double wanted = Math.sqrt(dx * dx + dz * dz);
		steerBlocked = horizontalCollision && wanted > MIN_PROGRESS && moved < MIN_PROGRESS;
	}

	private void setFlying(boolean fly) {
		if (fly == flying) return;
		flying = fly;
		setNoGravity(fly);
	}

	/** Cheap mode: silent, no physics (the manager snaps the position), no pose or held-item updates, no swings. */
	public void setCheap(boolean cheap) {
		if (this.cheap == cheap) return;
		this.cheap = cheap;
		setSilent(cheap);
		if (cheap) {
			setFlying(false);
			steerBlocked = false;
		} else {
			applyPose(lastPose);
		}
	}

	public boolean isCheap() {
		return cheap;
	}

	public void setIgnoreBlockTriggers(boolean ignore) {
		this.ignoreBlockTriggers = ignore;
	}

	// ------------------------------------------------------------------ pose, collapse, hands

	/**
	 * Recorded body pose: sneaking crouches, swimming and elytra flight lie flat (the swimming pose, which vanilla
	 * clients draw horizontally), sprinting sets the sprint flag. Remembered and applied later during a collapse or in
	 * cheap mode.
	 */
	public void applyPose(dev.echoaholic.core.action.Pose p) {
		lastPose = p;
		if (cheap || collapseTicks > 0 || isDeadOrDying()) return;
		setShiftKeyDown(p.sneaking());
		setSprinting(p.sprinting());
		if (p.swimming() || p.fallFlying()) {
			setPose(Pose.SWIMMING);
		} else if (p.sneaking()) {
			setPose(Pose.CROUCHING);
		} else {
			setPose(Pose.STANDING);
		}
	}

	/** Lies down for {@code ticks} (the owner's death replay), then stands up again in the last recorded pose. */
	public void startCollapse(int ticks) {
		collapseTicks = Math.max(1, ticks);
		steerTarget = null;
		steerBlocked = false;
		setShiftKeyDown(false);
		setSprinting(false);
		setPose(Pose.SLEEPING);
	}

	public boolean collapsed() {
		return collapseTicks > 0;
	}

	/** Holds a ghost copy of the recorded item in the main hand (only when the item changes). */
	public void showItem(ItemStack ghostCopy) {
		if (cheap) return;
		ItemStack current = getMainHandItem();
		if (current.isEmpty() && ghostCopy.isEmpty()) return;
		if (!current.isEmpty() && !ghostCopy.isEmpty() && current.getItem() == ghostCopy.getItem()) return;
		setItemSlot(EquipmentSlot.MAINHAND, ghostCopy.copy());
	}

	public void swingArm() {
		if (cheap) return;
		SwingCompat.mainHand(this);
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide() || isRemoved()) return;
		if (collapseTicks > 0 && --collapseTicks == 0 && !isDeadOrDying()) {
			setPose(Pose.STANDING);
			applyPose(lastPose);
		}
		if (--ambientCountdown <= 0) {
			ambientCountdown = nextAmbientDelay();
			if (!cheap && !isSilent() && isAlive()) {
				playSound(SoundEvents.SOUL_ESCAPE.value(), AMBIENT_VOLUME, AMBIENT_PITCH);
			}
		}
	}

	private int nextAmbientDelay() {
		return AMBIENT_MIN_TICKS + random.nextInt(AMBIENT_MAX_TICKS - AMBIENT_MIN_TICKS + 1);
	}

	// ------------------------------------------------------------------ lifecycle

	/** Removes the entity on the manager's request: {@link EchoListener#onRemoved} is not called for it. */
	public void managedDiscard() {
		managedRemoval = true;
		discard();
	}

	/** Whether the entity was removed through {@link #managedDiscard()}. */
	public boolean wasManagedRemoval() {
		return managedRemoval;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		EchoListener l = listener;
		if (!deathReported && l != null && !level().isClientSide()) {
			deathReported = true;
			l.onDied(this, source);
		}
	}

	@Override
	public void onRemoval(Entity.RemovalReason reason) {
		super.onRemoval(reason);
		EchoListener l = listener;
		if (!managedRemoval && l != null && !level().isClientSide()) l.onRemoved(this, reason);
	}

	// ------------------------------------------------------------------ vanilla behaviour we switch off

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean canUsePortal(boolean ignorePassenger) {
		return false;
	}

	/** Same-level teleports only: a vanilla cross-dimension teleport would leave a plain, saved mannequin behind. */
	@Override
	public @Nullable Entity teleport(TeleportTransition transition) {
		if (transition.newLevel() != level()) return null;
		return super.teleport(transition);
	}

	@Override
	public boolean startRiding(Entity vehicle, boolean force, boolean sendEventAndTriggers) {
		return false;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return ignoreBlockTriggers;
	}

	@Override
	protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.PLAYER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.PLAYER_DEATH;
	}
}
