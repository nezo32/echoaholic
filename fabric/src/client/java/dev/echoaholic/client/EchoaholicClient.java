package dev.echoaholic.client;

import net.fabricmc.api.ClientModInitializer;

public final class EchoaholicClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NotifyConfig.load();
		NotifyClient.register();
		NotifyCommand.register();
		EchoTrailClient.register();
	}
}
