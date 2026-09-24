package dev.echoaholic.storage;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.echoaholic.core.VirtualInventory;
import net.minecraft.core.UUIDUtil;

/**
 * Persisted state of one echo: everything needed to re-create its entity after a restart, a mode toggle or a chunk
 * reload. The entity itself is never saved (see {@code EchoEntity#shouldBeSaved}); the manager copies the entity's
 * position, rotation and health back into this object ({@code EchoManager#snapshotAll}) before every save.
 *
 * <p>Server thread only. Public mutable fields on purpose: the replay loop touches them every tick; persistence happens
 * when {@link EchoWorldData} is marked dirty (every save).
 */
public final class EchoState {
	/** Full health of an echo (a player's 20 HP). */
	public static final float MAX_HEALTH = 20f;

	static final Codec<EchoState> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(s -> s.echoId),
			Codec.INT.fieldOf("index").forGetter(s -> s.index),
			Codec.LONG.optionalFieldOf("cursor", 0L).forGetter(s -> s.cursor),
			Codec.INT.optionalFieldOf("actionsDone", 0).forGetter(s -> s.actionsDone),
			Codec.FLOAT.optionalFieldOf("health", MAX_HEALTH).forGetter(s -> s.health),
			Codec.STRING.optionalFieldOf("inventory", "").forGetter(s -> s.inventory.encode()),
			Codec.STRING.optionalFieldOf("dimension", "").forGetter(s -> s.dimension),
			Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(s -> s.x),
			Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(s -> s.y),
			Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(s -> s.z),
			Codec.FLOAT.optionalFieldOf("yRot", 0f).forGetter(s -> s.yRot),
			Codec.FLOAT.optionalFieldOf("xRot", 0f).forGetter(s -> s.xRot),
			Codec.INT.optionalFieldOf("collapseTicks", 0).forGetter(s -> s.collapseTicks)
	).apply(i, EchoState::restore));

	/** Identity of this echo (its entity's UUID is independent). */
	public final UUID echoId;
	/** Echo number k of its owner (1, 2, ...); never reused for that owner. */
	public final int index;
	/** Next stream tick to replay. */
	public long cursor;
	/** Actions of the tick at {@link #cursor} already executed (the rest waits for budget). */
	public int actionsDone;
	/** Health carried over between entity re-creations. */
	public float health = MAX_HEALTH;
	/** Materials the echo collected (block drops, filled buckets) and may spend on placements. */
	public VirtualInventory inventory = new VirtualInventory();
	/** Dimension id (e.g. "minecraft:overworld"); "" = not positioned yet (spawn at the first recorded position). */
	public String dimension = "";
	/** Last known position. Meaningful only when {@link #dimension} is set. */
	public double x, y, z;
	/** Last known rotation. */
	public float yRot, xRot;
	/** Remaining ticks of the death collapse; 0 = standing. */
	public int collapseTicks;

	public EchoState(UUID echoId, int index, long cursor) {
		if (echoId == null) throw new IllegalArgumentException("echoId");
		if (index < 1) throw new IllegalArgumentException("echo index must be >= 1: " + index);
		this.echoId = echoId;
		this.index = index;
		this.cursor = Math.max(0, cursor);
	}

	/** Whether {@link #x}/{@link #y}/{@link #z} and {@link #dimension} hold a real position. */
	public boolean positioned() {
		return !dimension.isEmpty();
	}

	/** Codec factory: sanitizes values a damaged or hand-edited file might hold. */
	private static EchoState restore(UUID id, int index, long cursor, int actionsDone, float health, String inventory,
			String dimension, double x, double y, double z, float yRot, float xRot, int collapseTicks) {
		EchoState s = new EchoState(id, Math.max(1, index), cursor);
		s.actionsDone = Math.max(0, actionsDone);
		s.health = Float.isFinite(health) && health > 0 ? Math.min(health, MAX_HEALTH) : MAX_HEALTH;
		s.inventory = VirtualInventory.decode(inventory);
		boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
		s.dimension = finite ? dimension : "";
		if (finite) {
			s.x = x;
			s.y = y;
			s.z = z;
		}
		s.yRot = Float.isFinite(yRot) ? yRot : 0f;
		s.xRot = Float.isFinite(xRot) ? xRot : 0f;
		s.collapseTicks = Math.max(0, collapseTicks);
		return s;
	}
}
