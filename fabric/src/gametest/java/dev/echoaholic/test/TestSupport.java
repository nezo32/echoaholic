package dev.echoaholic.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.echoaholic.EchoServer;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.stream.DecodedSegment;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.TickEntry;
import dev.echoaholic.entity.EchoEntity;
import dev.echoaholic.net.EchoNoticePayload;
import dev.echoaholic.net.EchoTrailPayload;
import dev.echoaholic.replay.EchoInfo;
import dev.echoaholic.replay.EchoManager;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.storage.EchoTuning;
import dev.echoaholic.storage.PlayerStream;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared helpers for the Echoaholic server gametests.
 *
 * <p>Concurrency rules (ARCH §7): gametests of one environment run in parallel on one server. Echoaholic Mode, the
 * caps and {@link EchoTuning} are world-global, so every test calls {@link #echoWorld} first (idempotent: mode ON, not
 * paused, test tuning) and keys everything else by its own random owner UUID. A test that changes any other setting
 * runs in its own {@code solo_*} environment (a batch of its own) and restores the setting in {@code finally}
 * ({@link #withConfig}).
 */
public final class TestSupport {
	/** Test echo delay in ticks (echo #k joins at T = k * 60). */
	public static final long DELAY = 60;
	/** Test segment length in ticks. */
	public static final int SEGMENT = 20;
	/** nextEchoIndex for synthetic owners: the schedule never spawns on its own during a test. */
	public static final int NO_SCHEDULE = 100_000;

	private TestSupport() {}

	// ------------------------------------------------------------------------------------------------ server state

	public static MinecraftServer server(GameTestHelper h) {
		return h.getLevel().getServer();
	}

	public static EchoServer es(GameTestHelper h) {
		return EchoServer.get(server(h));
	}

	public static EchoManager manager(GameTestHelper h) {
		return es(h).manager();
	}

	public static EchoConfig config(GameTestHelper h) {
		return es(h).config();
	}

	/**
	 * Test tuning (delay 60 ticks, segments of 20 ticks, default buffer) and Echoaholic Mode ON, not paused. Idempotent,
	 * so parallel tests can all call it.
	 */
	public static EchoServer echoWorld(GameTestHelper h) {
		EchoTuning.segmentTicksOverride = SEGMENT;
		EchoTuning.delayTicksOverride = DELAY;
		EchoServer es = es(h);
		EchoConfig c = es.config();
		if (!c.enabled() || c.paused()) es.setConfig(c.withEnabled(true).withPaused(false));
		return es;
	}

	/** Solo tests: applies {@code change}, returns the previous config for {@link #restore}. */
	public static EchoConfig withConfig(GameTestHelper h, UnaryOperator<EchoConfig> change) {
		EchoServer es = echoWorld(h);
		EchoConfig before = es.config();
		es.setConfig(change.apply(before));
		return before;
	}

	public static void restore(GameTestHelper h, EchoConfig before) {
		es(h).setConfig(before.withEnabled(true).withPaused(false));
	}

	// ------------------------------------------------------------------------------------------------ players

	/** A survival mock player with a random id (see {@link #survivalPlayer(GameTestHelper, UUID)}). */
	public static ServerPlayer survivalPlayer(GameTestHelper h) {
		return survivalPlayer(h, UUID.randomUUID());
	}

	/**
	 * A mock server player in the test level, in survival, with an empty inventory, named after its id so command
	 * tests can address it (reference pattern: the vanilla mock player always reports CREATIVE).
	 */
	public static ServerPlayer survivalPlayer(GameTestHelper h, UUID id) {
		return mock(h, id).player();
	}

	/** A mock player whose outbound packets can be inspected. */
	public record Mock(ServerPlayer player, EmbeddedChannel channel) {
		public List<Object> drain() {
			channel.runPendingTasks();
			List<Object> out = new ArrayList<>(channel.outboundMessages());
			channel.outboundMessages().clear();
			return out;
		}
	}

	public static Mock mock(GameTestHelper h, UUID id) {
		ServerLevel level = h.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(id, nameOf(id)), false);
		ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		Mock m = new Mock(p, channel);
		m.drain(); // join packets
		return m;
	}

	/** "e" + the first 12 hex digits of the id (unique, a valid player name of at most 16 characters). */
	public static String nameOf(UUID id) {
		return "e" + id.toString().replace("-", "").substring(0, 12);
	}

	/**
	 * Makes the mock player's client look like it has Echoaholic installed: registers our channels on its play
	 * addon, exactly what the client's minecraft:register packet does.
	 */
	public static void makeModded(ServerPlayer p) {
		ServerNetworkingImpl.getAddon(p.connection).handle(new RegistrationPayload(RegistrationPayload.REGISTER,
				List.of(EchoNoticePayload.TYPE.id(), EchoTrailPayload.TYPE.id())));
	}

	/** Moves the player to {@code rel} (relative to the test structure), keeping the level. */
	public static void teleport(GameTestHelper h, ServerPlayer p, Vec3 rel) {
		Vec3 a = h.absoluteVec(rel);
		p.teleportTo(h.getLevel(), a.x, a.y, a.z, Set.of(), 0f, 0f, true);
		p.setDeltaMovement(Vec3.ZERO);
	}

	/** A survival player at {@code rel} (relative), with a random id. */
	public static ServerPlayer player(GameTestHelper h, Vec3 rel) {
		ServerPlayer p = survivalPlayer(h);
		teleport(h, p, rel);
		return p;
	}

	/**
	 * Removes the players (their open segments are sealed on leave), then clears their echoes and recordings. Safe to
	 * call twice.
	 */
	public static void cleanup(GameTestHelper h, ServerPlayer... players) {
		for (ServerPlayer p : players) {
			if (p == null) continue;
			if (server(h).getPlayerList().getPlayer(p.getUUID()) == p) server(h).getPlayerList().remove(p);
			es(h).clear(p.getUUID());
		}
	}

	public static void cleanup(GameTestHelper h, UUID... owners) {
		for (UUID id : owners) {
			ServerPlayer p = server(h).getPlayerList().getPlayer(id);
			if (p != null) server(h).getPlayerList().remove(p);
			es(h).clear(id);
		}
	}

	// ------------------------------------------------------------------------------------------------ echoes

	public static PlayerStream stream(GameTestHelper h, UUID owner) {
		return es(h).data().player(owner);
	}

	public static EchoState state(GameTestHelper h, UUID owner, int k) {
		EchoState s = stream(h, owner).echo(k);
		if (s == null) throw h.assertionException(Component.literal("echo #" + k + " of " + owner + " has no state"));
		return s;
	}

	public static EchoInfo info(GameTestHelper h, UUID owner, int k) {
		for (EchoInfo i : manager(h).list(owner)) {
			if (i.index() == k) return i;
		}
		h.fail("echo #" + k + " not listed; list=" + manager(h).list(owner));
		throw new IllegalStateException();
	}

	/** Asserts (inside succeedWhen / thenWaitUntil) that echo #k of owner has a live entity, and returns it. */
	public static EchoEntity awaitEcho(GameTestHelper h, UUID owner, int k) {
		EchoEntity e = manager(h).entity(owner, k);
		h.assertTrue(e != null && !e.isRemoved(), "echo #" + k + " has no entity yet");
		return e;
	}

	public static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	// ------------------------------------------------------------------------------------------------ world

	/** Stone floor at relative y = 0 over the whole structure footprint (walkable layer: y = 1). */
	public static void floor(GameTestHelper h) {
		AABB b = h.getRelativeBounds();
		for (int x = (int) b.minX; x < (int) b.maxX; x++) {
			for (int z = (int) b.minZ; z < (int) b.maxZ; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
		}
	}

	public static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) level.setBlock(pos, state, 2);
	}

	public static <T extends Entity> List<T> entities(GameTestHelper h, Class<T> type, AABB absBox) {
		return h.getLevel().getEntitiesOfClass(type, absBox, e -> !e.isRemoved());
	}

	public static void discardAll(GameTestHelper h, Class<? extends Entity> type, AABB absBox) {
		h.getLevel().getEntitiesOfClass(type, absBox).forEach(Entity::discard);
	}

	/** Absolute box around the test structure (+ margin), for entity cleanup. */
	public static AABB around(GameTestHelper h, double margin) {
		return h.getBounds().inflate(margin);
	}

	// ------------------------------------------------------------------------------------------------ streams

	/** Every recorded tick entry of the owner, oldest first (loads each segment; server thread, blocking). */
	public static List<TickEntry> recordedEntries(GameTestHelper h, UUID owner) {
		List<TickEntry> out = new ArrayList<>();
		for (SegmentMeta m : es(h).store().ring(owner).segments()) {
			DecodedSegment d = es(h).store().load(owner, m.seq()).join();
			out.addAll(d.entries());
		}
		return out;
	}

	public static Path streamDir(GameTestHelper h, UUID owner) {
		return server(h).getWorldPath(LevelResource.DATA).resolve("echoaholic").resolve("streams").resolve(owner.toString());
	}

	public static long segFiles(Path dir) {
		if (!Files.isDirectory(dir)) return 0;
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().endsWith(".seg")).count();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------------------------------------ commands

	/** Collects every message sent to a command source. */
	public static final class Capture implements CommandSource {
		public final List<Component> messages = new ArrayList<>();

		@Override
		public void sendSystemMessage(Component message) {
			messages.add(message);
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}

		/** Messages that are (or contain) a translatable with this key. */
		public List<TranslatableContents> withKey(String key) {
			List<TranslatableContents> out = new ArrayList<>();
			for (Component c : messages) find(c, key, out);
			return out;
		}

		public boolean has(String key) {
			return !withKey(key).isEmpty();
		}

		private static void find(Component c, String key, List<TranslatableContents> out) {
			if (c.getContents() instanceof TranslatableContents tc && tc.getKey().equals(key)) out.add(tc);
			for (Component sibling : c.getSiblings()) find(sibling, key, out);
		}

		public void clear() {
			messages.clear();
		}

		@Override
		public String toString() {
			return messages.stream().map(Component::getString).toList().toString();
		}
	}

	public static int run(GameTestHelper h, CommandSourceStack source, String command) throws CommandSyntaxException {
		return server(h).getCommands().getDispatcher().execute(command, source);
	}

	/** The server's own (op) source with its output captured. */
	public static CommandSourceStack op(GameTestHelper h, Capture capture) {
		return server(h).createCommandSourceStack().withSource(capture);
	}
}
