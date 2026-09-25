package dev.echoaholic.replay;

import dev.echoaholic.core.BudgetScheduler;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.VirtualInventory;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.storage.EchoState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * What a {@link ReplayHandler} sees of one echo during one tick. Created once by {@link EchoManager} and re-pointed at
 * each echo before its actions run, so handlers must not keep it.
 */
public final class ReplayContext {
	private final EchoManager manager;
	private EchoRuntime rt;
	private EchoConfig config;
	private BudgetScheduler.EchoBudget budget;
	private long tick;

	ReplayContext(EchoManager manager) {
		this.manager = manager;
	}

	void bind(EchoRuntime rt, EchoConfig config, BudgetScheduler.EchoBudget budget, long tick) {
		this.rt = rt;
		this.config = config;
		this.budget = budget;
		this.tick = tick;
	}

	EchoRuntime runtime() {
		return rt;
	}

	/** Level the echo is in now (changes after {@link #changeDimension}). */
	public ServerLevel level() {
		return rt.level;
	}

	/**
	 * The echo entity. Never null for world handlers; meta handlers (Pose, Teleport, Dimension, Death, Swing) may see
	 * null when they run in the jump pre-pass before the entity exists.
	 */
	public EchoEntity echo() {
		return rt.entity;
	}

	public UUID owner() {
		return rt.owner;
	}

	public EchoState state() {
		return rt.state;
	}

	public EchoConfig config() {
		return config;
	}

	public VirtualInventory inventory() {
		return rt.state.inventory;
	}

	public BudgetScheduler.EchoBudget budget() {
		return budget;
	}

	/** Cheap mode: only block actions and damage, no swing / equipment / pose / sound. */
	public boolean cheap() {
		return rt.cheap;
	}

	/** Stream tick being replayed (the echo's cursor). */
	public long tick() {
		return tick;
	}

	/** Reports what the action did, for {@code /echoaholic list}. */
	public void activity(Activity a) {
		manager.noteWorldActivity(rt, a);
	}

	/** Same-level snap to (x, y, z). */
	public void teleport(double x, double y, double z) {
		manager.teleport(rt, x, y, z);
	}

	/**
	 * Moves the echo to another dimension (discard + respawn there); same level = teleport when far, else nothing. If the
	 * target position is not entity-ticking the echo has no entity until it is, and the rest of this tick waits.
	 */
	public void changeDimension(ResourceKey<Level> dim, double x, double y, double z) {
		manager.changeDimension(rt, dim, x, y, z);
	}

	/** Owner death: lie down and hold the cursor for {@code ticks}. */
	public void collapse(int ticks) {
		manager.collapse(rt, ticks);
	}
}
