package dev.echoaholic.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dev.echoaholic.net.EchoTrailPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The optional path trail ({@code showTrail}): the server sends each nearby echo's recorded path for the next
 * 5 seconds ({@link EchoTrailPayload}, every 10 ticks). The latest path per entity id is kept for 40 ticks (or until
 * the entity is gone) and drawn as cyan dust every 2 client ticks: each point plus one midpoint between neighbours.
 * Render thread only.
 */
public final class EchoTrailClient {
	public static final int EXPIRE_TICKS = 40;
	public static final int SPAWN_EVERY = 2;
	public static final int PARTICLE_LIFETIME = 10;
	/** Only the trails of this many echoes nearest the camera are drawn per cycle. */
	public static final int MAX_DRAWN = 8;
	public static final float DUST_SCALE = 0.8f;
	private static final DustParticleOptions DUST = new DustParticleOptions(EchoRenderTint.RGB, DUST_SCALE);

	private record Trail(float[] xyz, long receivedAt) {}

	private record Candidate(float[] xyz, double distSqr) {}

	private static final Map<Integer, Trail> TRAILS = new HashMap<>();
	private static long clientTicks;

	private EchoTrailClient() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(EchoTrailPayload.TYPE,
				(payload, ctx) -> accept(payload.entityId(), payload.xyz()));
		ClientTickEvents.END_CLIENT_TICK.register(EchoTrailClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TRAILS.clear());
	}

	/** Stores the newest path of this entity (a copy of whole xyz triples). Public for the client gametest. */
	public static void accept(int entityId, float[] xyz) {
		int n = xyz.length / 3 * 3;
		if (n == 0) {
			TRAILS.remove(entityId);
			return;
		}
		float[] copy = new float[n];
		System.arraycopy(xyz, 0, copy, 0, n);
		TRAILS.put(entityId, new Trail(copy, clientTicks));
	}

	/** Number of stored (unexpired) trails. For tests. */
	public static int trailCount() {
		return TRAILS.size();
	}

	private static void tick(Minecraft mc) {
		clientTicks++;
		ClientLevel level = mc.level;
		if (level == null) {
			TRAILS.clear();
			return;
		}
		if (TRAILS.isEmpty() || mc.isPaused()) return;
		boolean draw = NotifyConfig.get().trail() && clientTicks % SPAWN_EVERY == 0;
		List<Candidate> candidates = draw ? new ArrayList<>() : null;
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		for (Iterator<Map.Entry<Integer, Trail>> it = TRAILS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Trail> e = it.next();
			Trail trail = e.getValue();
			Entity entity = level.getEntity(e.getKey());
			if (clientTicks - trail.receivedAt() > EXPIRE_TICKS || entity == null || entity.isRemoved()) {
				it.remove();
				continue;
			}
			if (draw) candidates.add(new Candidate(trail.xyz(), entity.distanceToSqr(cam)));
		}
		if (!draw || candidates.isEmpty()) return;
		if (candidates.size() > MAX_DRAWN) {
			candidates.sort(Comparator.comparingDouble(Candidate::distSqr));
		}
		for (int i = 0; i < Math.min(MAX_DRAWN, candidates.size()); i++) {
			spawn(mc, candidates.get(i).xyz());
		}
	}

	private static void spawn(Minecraft mc, float[] p) {
		for (int i = 0; i < p.length; i += 3) {
			dust(mc, p[i], p[i + 1], p[i + 2]);
			if (i + 5 < p.length) {
				dust(mc, (p[i] + p[i + 3]) * 0.5, (p[i + 1] + p[i + 4]) * 0.5, (p[i + 2] + p[i + 5]) * 0.5);
			}
		}
	}

	private static void dust(Minecraft mc, double x, double y, double z) {
		Particle particle = mc.particleEngine.createParticle(DUST, x, y + 0.05, z, 0, 0, 0);
		if (particle != null) particle.setLifetime(PARTICLE_LIFETIME);
	}
}
