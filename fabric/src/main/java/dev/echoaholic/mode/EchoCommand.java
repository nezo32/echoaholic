package dev.echoaholic.mode;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.echoaholic.EchoServer;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.replay.Activity;
import dev.echoaholic.replay.EchoInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * {@code /echoaholic}. Everything needs permission level 2 (gamemasters) like /gamerule, except {@code list} for
 * one's own echoes, which every player may use:
 * <ul>
 * <li>{@code on | off | status} (no argument = status)</li>
 * <li>{@code delay <1..120>} minutes, {@code max <1..64>} echoes per player</li>
 * <li>{@code list [player]}: own echoes for anyone, other players' echoes for operators</li>
 * <li>{@code clear [player]}: removes the echoes and wipes the recording</li>
 * <li>{@code pause | resume}: freezes replay and spawns (recording continues)</li>
 * <li>{@code config [key [value]]}: every setting of {@link EchoConfig.Key}; out-of-range values are clamped</li>
 * </ul>
 * Every reply is translatable with an English fallback (vanilla clients have no mod lang).
 */
public final class EchoCommand {
	public static final String ROOT = "echoaholic";

	private EchoCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(ROOT)
				// root: status for operators, the own echo list for everybody else
				.executes(c -> isOp(c.getSource()) ? status(c.getSource()) : listSelf(c.getSource()))
				.then(op("on").executes(c -> setEnabled(c.getSource(), true)))
				.then(op("off").executes(c -> setEnabled(c.getSource(), false)))
				.then(op("status").executes(c -> status(c.getSource())))
				.then(op("delay").then(Commands.argument("minutes",
								IntegerArgumentType.integer(EchoConfig.MIN_DELAY_MINUTES, EchoConfig.MAX_DELAY_MINUTES))
						.executes(c -> delay(c.getSource(), IntegerArgumentType.getInteger(c, "minutes")))))
				.then(op("max").then(Commands.argument("echoes",
								IntegerArgumentType.integer(EchoConfig.MIN_MAX_ECHOES, EchoConfig.MAX_ECHOES_CAP))
						.executes(c -> max(c.getSource(), IntegerArgumentType.getInteger(c, "echoes")))))
				.then(Commands.literal("list")
						.executes(c -> listSelf(c.getSource()))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(c -> list(c.getSource(), GameProfileArgument.getGameProfiles(c, "player")))))
				.then(op("clear")
						.executes(c -> clearSelf(c.getSource()))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(c -> clear(c.getSource(), GameProfileArgument.getGameProfiles(c, "player")))))
				.then(op("pause").executes(c -> setPaused(c.getSource(), true)))
				.then(op("resume").executes(c -> setPaused(c.getSource(), false)))
				.then(op("config")
						.executes(c -> configList(c.getSource()))
						.then(Commands.argument("key", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(
										Arrays.stream(EchoConfig.Key.values()).map(EchoConfig.Key::id), b))
								.executes(c -> configGet(c.getSource(), StringArgumentType.getString(c, "key")))
								.then(Commands.argument("value", StringArgumentType.word())
										.suggests((c, b) -> SharedSuggestionProvider.suggest(valueSuggestions(c), b))
										.executes(c -> configSet(c.getSource(), StringArgumentType.getString(c, "key"),
												StringArgumentType.getString(c, "value")))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> op(String name) {
		return Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
	}

	static boolean isOp(CommandSourceStack source) {
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions());
	}

	private static EchoServer echo(CommandSourceStack source) {
		return EchoServer.get(source.getServer());
	}

	private static MutableComponent tr(String key, String fallback, Object... args) {
		return Component.translatableWithFallback(key, fallback, args);
	}

	/** "5 min"; lag: one decimal under 10 minutes, else a whole number. */
	static Component minutes(Object value) {
		return tr("echoaholic.minutes", "%s min", value);
	}

	static String formatLag(long lagTicks) {
		double m = Math.max(0, lagTicks) / (double) EchoConfig.TICKS_PER_MINUTE;
		return m < 10 ? String.format(Locale.ROOT, "%.1f", m) : String.valueOf(Math.round(m));
	}

	private static Component modeOffHint() {
		return tr("echoaholic.command.modeOffHint",
				"(Echoaholic Mode is OFF, so nothing is recorded until you turn it on with /echoaholic on)")
				.withStyle(ChatFormatting.GRAY);
	}

	/** Appends the mode-OFF hint when the mode is off. */
	private static Component withHint(EchoServer echo, MutableComponent message) {
		return echo.config().enabled() ? message : message.append(CommonComponents.SPACE).append(modeOffHint());
	}

	private static int update(CommandSourceStack source, UnaryOperator<EchoConfig> change) {
		EchoServer echo = echo(source);
		echo.setConfig(change.apply(echo.config()));
		return 1;
	}

	// ---- on / off / status

	private static int setEnabled(CommandSourceStack source, boolean value) {
		update(source, c -> c.withEnabled(value));
		source.sendSuccess(() -> value
				? tr("echoaholic.command.on", "Echoaholic Mode is now ON for this world")
				: tr("echoaholic.command.off", "Echoaholic Mode is now OFF for this world"), true);
		return value ? 1 : 0;
	}

	private static int status(CommandSourceStack source) {
		EchoConfig config = echo(source).config();
		boolean on = config.enabled();
		source.sendSuccess(() -> on
				? tr("echoaholic.command.status.on", "Echoaholic Mode is ON in this world")
				: tr("echoaholic.command.status.off", "Echoaholic Mode is OFF in this world"), false);
		source.sendSuccess(() -> tr("echoaholic.command.status.detail", "Echo Delay: %1$s, max %2$s echoes per player",
				minutes(config.delayMinutes()), config.maxEchoes()).withStyle(ChatFormatting.GRAY), false);
		if (config.paused()) source.sendSuccess(EchoCommand::pausedMessage, false);
		return on ? 1 : 0;
	}

	// ---- delay / max

	private static int delay(CommandSourceStack source, int value) {
		update(source, c -> c.withDelayMinutes(value));
		EchoServer echo = echo(source);
		int stored = echo.config().delayMinutes();
		source.sendSuccess(() -> withHint(echo, tr("echoaholic.command.delay.set", "Echo Delay is now %s for this world",
				minutes(stored))), true);
		return stored;
	}

	private static int max(CommandSourceStack source, int value) {
		update(source, c -> c.withMaxEchoes(value));
		EchoServer echo = echo(source);
		int stored = echo.config().maxEchoes();
		source.sendSuccess(() -> withHint(echo, tr("echoaholic.command.max.set", "Max echoes per player is now %s for this world",
				stored)), true);
		return stored;
	}

	// ---- pause / resume

	private static Component pausedMessage() {
		return tr("echoaholic.command.paused", "Echoes are paused: they stand still until /echoaholic resume");
	}

	private static int setPaused(CommandSourceStack source, boolean value) {
		update(source, c -> c.withPaused(value));
		source.sendSuccess(() -> value ? pausedMessage() : tr("echoaholic.command.resumed", "Echoes are moving again"), true);
		return 1;
	}

	// ---- list

	private static int listSelf(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer self = source.getPlayerOrException();
		return listOne(source, self.getUUID(), self.getGameProfile().name());
	}

	private static int list(CommandSourceStack source, Collection<NameAndId> targets) {
		ServerPlayer self = source.getPlayer();
		boolean op = isOp(source);
		int total = 0;
		for (NameAndId target : targets) {
			if (!op && (self == null || !self.getUUID().equals(target.id()))) {
				source.sendFailure(tr("echoaholic.command.list.denied", "You can only list your own echoes"));
				continue;
			}
			total += listOne(source, target.id(), target.name());
		}
		return total;
	}

	private static int listOne(CommandSourceStack source, UUID owner, String name) {
		EchoServer echo = echo(source);
		List<EchoInfo> echoes = echo.manager().list(owner);
		Component who = Component.literal(name).withStyle(ChatFormatting.AQUA);
		if (echoes.isEmpty()) {
			source.sendSuccess(() -> withHint(echo, tr("echoaholic.command.list.empty", "%s has no echoes yet", who)), false);
			return 0;
		}
		int max = echo.config().maxEchoes();
		source.sendSuccess(() -> tr("echoaholic.command.list.header", "Echoes of %1$s (%2$s/%3$s):",
				who, echoes.size(), max), false);
		for (EchoInfo info : echoes) {
			Component line = entry(source, info);
			source.sendSuccess(() -> line, false);
		}
		return echoes.size();
	}

	private static Component entry(CommandSourceStack source, EchoInfo info) {
		Activity activity = info.activity();
		Component doing = tr(activity.langKey(), activity.name().toLowerCase(Locale.ROOT));
		BlockPos p = info.pos();
		String where = p.getX() + " " + p.getY() + " " + p.getZ();
		if (!info.dimension().equals(source.getLevel().dimension())) {
			where += " (" + info.dimension().identifier() + ")";
		}
		return tr("echoaholic.command.list.entry", "#%1$s · %2$s · %3$s behind · %4$s · ❤ %5$s",
				Component.literal(String.valueOf(info.index())).withStyle(ChatFormatting.AQUA),
				doing,
				minutes(formatLag(info.lagTicks())),
				where,
				String.format(Locale.ROOT, "%.0f", info.health()));
	}

	// ---- clear

	private static int clearSelf(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer self = source.getPlayerOrException();
		return clearOne(source, self.getUUID(), self.getGameProfile().name());
	}

	private static int clear(CommandSourceStack source, Collection<NameAndId> targets) {
		int total = 0;
		for (NameAndId target : targets) {
			total += clearOne(source, target.id(), target.name());
		}
		return total;
	}

	private static int clearOne(CommandSourceStack source, UUID owner, String name) {
		int removed = echo(source).clear(owner);
		Component who = Component.literal(name).withStyle(ChatFormatting.AQUA);
		source.sendSuccess(() -> tr("echoaholic.command.cleared", "Removed %1$s echoes of %2$s", removed, who), true);
		return removed;
	}

	// ---- config

	private static Component configValue(EchoConfig.Key key, EchoConfig config) {
		return tr("echoaholic.command.config.value", "%1$s = %2$s", key.id(),
				Component.literal(key.format(config)).withStyle(ChatFormatting.AQUA));
	}

	private static int configList(CommandSourceStack source) {
		EchoConfig config = echo(source).config();
		for (EchoConfig.Key key : EchoConfig.Key.values()) {
			Component line = configValue(key, config);
			source.sendSuccess(() -> line, false);
		}
		return EchoConfig.Key.values().length;
	}

	private static Optional<EchoConfig.Key> key(CommandSourceStack source, String id) {
		Optional<EchoConfig.Key> key = EchoConfig.Key.byId(id);
		if (key.isEmpty()) source.sendFailure(tr("echoaholic.command.config.unknown", "Unknown setting: %s", id));
		return key;
	}

	private static int configGet(CommandSourceStack source, String id) {
		Optional<EchoConfig.Key> key = key(source, id);
		if (key.isEmpty()) return 0;
		EchoConfig config = echo(source).config();
		source.sendSuccess(() -> configValue(key.get(), config), false);
		return key.get().get(config);
	}

	private static int configSet(CommandSourceStack source, String id, String raw) {
		Optional<EchoConfig.Key> found = key(source, id);
		if (found.isEmpty()) return 0;
		EchoConfig.Key key = found.get();
		OptionalInt value = key.parse(raw);
		if (value.isEmpty()) {
			boolean bool = key.kind() == EchoConfig.Kind.BOOL;
			source.sendFailure(tr("echoaholic.command.config.invalid", "Invalid value for %1$s: %2$s (allowed %3$s..%4$s)",
					key.id(), raw, bool ? "false" : String.valueOf(key.min()), bool ? "true" : String.valueOf(key.max())));
			return 0;
		}
		EchoServer echo = echo(source);
		echo.setConfig(key.set(echo.config(), value.getAsInt()));
		EchoConfig stored = echo.config();
		source.sendSuccess(() -> tr("echoaholic.command.config.set", "%1$s is now %2$s for this world", key.id(),
				Component.literal(key.format(stored)).withStyle(ChatFormatting.AQUA)), true);
		return 1;
	}

	private static List<String> valueSuggestions(CommandContext<CommandSourceStack> c) {
		Optional<EchoConfig.Key> key = EchoConfig.Key.byId(StringArgumentType.getString(c, "key"));
		if (key.isEmpty()) return List.of();
		if (key.get().kind() == EchoConfig.Kind.BOOL) return List.of("true", "false");
		EchoConfig.Key k = key.get();
		return List.of(String.valueOf(k.defaultValue()), String.valueOf(k.min()), String.valueOf(k.max()));
	}
}
