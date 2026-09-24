package dev.echoaholic.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/** Callbacks from echo entities to the replay manager (server thread). */
public interface EchoListener {
	/** The echo was killed (health reached 0). Called right after vanilla {@code die}. */
	void onDied(EchoEntity echo, DamageSource source);

	/**
	 * The echo left its level for a reason the manager did not ask for ({@code /kill}, death cleanup, chunk unload
	 * paths ...). Not called after {@link EchoEntity#managedDiscard()}.
	 */
	void onRemoved(EchoEntity echo, Entity.RemovalReason reason);
}
