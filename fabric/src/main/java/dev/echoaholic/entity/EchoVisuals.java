package dev.echoaholic.entity;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Server-side particles and sounds of the echo look (branding "The echo look"), visible to vanilla clients too. */
public final class EchoVisuals {
	/** Played at the echo when it joins (the illusioner making copies of itself). */
	public static final SoundEvent SPAWN_SOUND = SoundEvents.ILLUSIONER_MIRROR_MOVE;
	public static final float SPAWN_VOLUME = 0.5F;
	public static final float SPAWN_PITCH = 1.3F;

	private static final int BURST_RODS = 12;
	private static final double BURST_RADIUS = 0.5;
	private static final double BURST_SPEED = 0.06;
	private static final int BURST_DUST = 6;
	private static final int PUFF_SOULS = 8;

	private EchoVisuals() {}

	/** A ring of 12 end rods drifting outward at the feet, 6 cyan dust motes and the spawn sound. */
	public static void spawnBurst(ServerLevel level, Vec3 pos) {
		for (int i = 0; i < BURST_RODS; i++) {
			double angle = Math.PI * 2 * i / BURST_RODS;
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			// count 0: the offsets are the particle's direction, scaled by the speed
			level.sendParticles(ParticleTypes.END_ROD, pos.x + cos * BURST_RADIUS, pos.y + 0.1, pos.z + sin * BURST_RADIUS,
					0, cos, 0.0, sin, BURST_SPEED);
		}
		level.sendParticles(new DustParticleOptions(EchoEntity.TINT_RGB, 0.8F), pos.x, pos.y + 1.0, pos.z,
				BURST_DUST, 0.3, 0.5, 0.3, 0.0);
		level.playSound(null, pos.x, pos.y, pos.z, SPAWN_SOUND, SoundSource.PLAYERS, SPAWN_VOLUME, SPAWN_PITCH);
	}

	/** A small puff of 8 soul particles where an echo retired or died (no sound). */
	public static void retirePuff(ServerLevel level, Vec3 pos) {
		level.sendParticles(ParticleTypes.SOUL, pos.x, pos.y + 1.0, pos.z, PUFF_SOULS, 0.25, 0.5, 0.25, 0.02);
	}
}
