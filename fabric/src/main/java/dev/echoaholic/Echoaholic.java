package dev.echoaholic;

import dev.echoaholic.entity.EchoTargeting;
import dev.echoaholic.mode.EchoCommand;
import dev.echoaholic.net.EchoNoticePayload;
import dev.echoaholic.net.EchoTrailPayload;
import dev.echoaholic.record.RecordHooks;
import dev.echoaholic.replay.MetaHandlers;
import dev.echoaholic.replay.handler.WorldHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Echoaholic implements ModInitializer {
	public static final String MOD_ID = "echoaholic";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		EchoNoticePayload.register();
		EchoTrailPayload.register();
		MetaHandlers.register();
		WorldHandlers.register();
		RecordHooks.register();
		EchoTargeting.register();
		EchoLifecycle.register(); // SERVER_STARTING checks that every action type has a replay handler
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> EchoCommand.register(dispatcher));
	}
}
