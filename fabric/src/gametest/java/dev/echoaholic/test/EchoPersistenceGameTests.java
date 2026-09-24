package dev.echoaholic.test;

import static dev.echoaholic.test.TestSupport.config;
import static dev.echoaholic.test.TestSupport.echoWorld;
import static dev.echoaholic.test.TestSupport.es;
import static dev.echoaholic.test.TestSupport.restore;
import static dev.echoaholic.test.TestSupport.segFiles;
import static dev.echoaholic.test.TestSupport.withConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import dev.echoaholic.EchoServer;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.stream.DecodedSegment;
import dev.echoaholic.core.stream.RingBuffer;
import dev.echoaholic.core.stream.SealedSegment;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.SegmentReader;
import dev.echoaholic.mixin.MinecraftServerAccessor;
import dev.echoaholic.mode.EchoBootstrap;
import dev.echoaholic.mode.PendingWorldMode;
import dev.echoaholic.storage.EchoState;
import dev.echoaholic.storage.EchoWorldData;
import dev.echoaholic.storage.OwnerProfile;
import dev.echoaholic.storage.PlayerStream;
import dev.echoaholic.storage.StreamStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** World data codec, per-world settings on disk, the segment store, the Create World handoff and crash recovery. */
public class EchoPersistenceGameTests {
	private static final String NS = "echoaholic-gametest:";

	private static EchoWorldData roundTrip(EchoWorldData d) {
		Tag t = EchoWorldData.CODEC.encodeStart(NbtOps.INSTANCE, d).getOrThrow();
		return EchoWorldData.CODEC.parse(NbtOps.INSTANCE, t).getOrThrow();
	}

	// ---------------------------------------------------------------------------------------------- EchoWorldData

	/** Every persisted field survives an encode/decode: config keys, player meta, profile, echoes, inventory. */
	@GameTest
	public void worldDataCodecRoundTrip(GameTestHelper h) {
		EchoWorldData d = EchoWorldData.TYPE.constructor().get();
		h.assertValueEqual(d.config(), EchoConfig.DEFAULT, "fresh data = DEFAULT (mode OFF)");
		EchoConfig cfg = EchoConfig.DEFAULT.withEnabled(true).withDelayMinutes(7).withMaxEchoes(9).withBufferHours(3)
				.withEchoBlockOpsPerTick(6).withEchoEntityLookupsPerTick(3).withGlobalBlockOpsPerTick(200)
				.withGlobalHazardOpsPerTick(5).withCheapModeDistance(300).withTriggerBlocks(true).withFreeTnt(false).withPaused(true);
		d.setConfig(cfg);
		UUID u = UUID.randomUUID();
		PlayerStream ps = d.player(u);
		ps.streamTick = 123_456;
		ps.nextSeq = 17;
		ps.nextEchoIndex = 5;
		ps.profile = new OwnerProfile("Steve", "dGV4dHVyZXM=", "c2ln");
		EchoState s = new EchoState(UUID.randomUUID(), 4, 999);
		s.actionsDone = 2;
		s.health = 13.5f;
		s.inventory.add("minecraft:dirt", 12);
		s.inventory.add("minecraft:water_bucket", 1);
		s.dimension = "minecraft:the_nether";
		s.x = 1.5;
		s.y = 64;
		s.z = -3.25;
		s.yRot = 90f;
		s.xRot = -10f;
		s.collapseTicks = 30;
		ps.echoes.add(s);
		UUID bare = UUID.randomUUID();
		d.player(bare); // defaults only

		EchoWorldData back = roundTrip(d);
		h.assertValueEqual(back.config(), cfg, "config");
		PlayerStream bp = back.playerIfPresent(u);
		h.assertTrue(bp != null, "player entry");
		h.assertValueEqual(bp.streamTick, 123_456L, "streamTick");
		h.assertValueEqual(bp.nextSeq, 17L, "nextSeq");
		h.assertValueEqual(bp.nextEchoIndex, 5, "nextEchoIndex");
		h.assertValueEqual(bp.profile, ps.profile, "profile");
		h.assertValueEqual(bp.echoes.size(), 1, "echoes");
		EchoState bs = bp.echo(4);
		h.assertTrue(bs != null, "echo #4");
		h.assertValueEqual(bs.echoId, s.echoId, "echoId");
		h.assertValueEqual(bs.cursor, 999L, "cursor");
		h.assertValueEqual(bs.actionsDone, 2, "actionsDone");
		h.assertValueEqual(bs.health, 13.5f, "health");
		h.assertValueEqual(bs.inventory, s.inventory, "inventory");
		h.assertValueEqual(bs.dimension, "minecraft:the_nether", "dimension");
		h.assertTrue(bs.x == 1.5 && bs.y == 64 && bs.z == -3.25, "position");
		h.assertTrue(bs.yRot == 90f && bs.xRot == -10f, "rotation");
		h.assertValueEqual(bs.collapseTicks, 30, "collapseTicks");
		PlayerStream bb = back.playerIfPresent(bare);
		h.assertTrue(bb != null && bb.streamTick == 0 && bb.nextEchoIndex == 1 && bb.echoes.isEmpty(), "bare player entry");

		// decoded collections must be mutable (the server keeps working with them)
		back.player(UUID.randomUUID());
		bp.echoes.add(new EchoState(UUID.randomUUID(), 6, 0));
		h.assertValueEqual(bp.echoes.size(), 2, "mutable echoes list");
		h.succeed();
	}

	/** Stored config keys: unknown ones are ignored, missing ones get defaults, out-of-range values are clamped. */
	@GameTest
	public void unknownAndMissingConfigKeys(GameTestHelper h) {
		EchoWorldData d = EchoWorldData.TYPE.constructor().get();
		d.setConfig(EchoConfig.DEFAULT.withEnabled(true).withDelayMinutes(9).withMaxEchoes(3).withFreeTnt(false));
		CompoundTag root = (CompoundTag) EchoWorldData.CODEC.encodeStart(NbtOps.INSTANCE, d).getOrThrow();
		CompoundTag config = root.getCompoundOrEmpty("config");
		h.assertTrue(config.contains("delayMinutes") && config.contains("freeTnt"), "config stored by key id: " + config);
		config.remove("maxEchoes");
		config.putInt("someFutureKey", 5);
		config.putInt("cheapModeDistance", 99_999);
		root.put("config", config);
		EchoWorldData back = EchoWorldData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
		h.assertTrue(back.config().enabled(), "enabled kept");
		h.assertValueEqual(back.config().delayMinutes(), 9, "delay kept");
		h.assertTrue(!back.config().freeTnt(), "freeTnt kept");
		h.assertValueEqual(back.config().maxEchoes(), EchoConfig.DEFAULT_MAX_ECHOES, "missing key -> default");
		h.assertValueEqual(back.config().cheapModeDistance(), EchoConfig.MAX_CHEAP_MODE_DISTANCE, "out of range -> clamped");

		// an empty file (older / foreign) decodes to DEFAULT
		EchoWorldData empty = EchoWorldData.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
		h.assertValueEqual(empty.config(), EchoConfig.DEFAULT, "empty tag");
		h.succeed();
	}

	/** Per-world settings reach data/echoaholic/world.dat and read back identically. */
	@GameTest(environment = NS + "solo_settings")
	public void settingsPersistToDisk(GameTestHelper h) throws IOException {
		MinecraftServer server = h.getLevel().getServer();
		EchoConfig before = withConfig(h, c -> c.withDelayMinutes(7).withMaxEchoes(9).withFreeTnt(false).withCheapModeDistance(200));
		try {
			EchoConfig expected = config(h);
			h.assertTrue(es(h).data().isDirty(), "setConfig marks the data dirty");
			server.getDataStorage().saveAndJoin();
			Path file = server.getWorldPath(LevelResource.DATA).resolve("echoaholic").resolve("world.dat");
			h.assertTrue(Files.isRegularFile(file), "world.dat written at " + file);
			CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			EchoWorldData onDisk = EchoWorldData.CODEC.parse(NbtOps.INSTANCE, root.get("data")).getOrThrow();
			h.assertValueEqual(onDisk.config(), expected, "config on disk");
			h.assertTrue(EchoWorldData.exists(server), "exists() after save");
		} finally {
			restore(h, before);
		}
		h.succeed();
	}

	// ---------------------------------------------------------------------------------------------- StreamStore

	/**
	 * The store writes {@code <uuid>/<seq>.seg} + {@code index.bin}; a fresh store on the same directory has the same
	 * ring (also rebuilt from segment headers when the index is gone); eviction and wipe delete files.
	 */
	@GameTest(maxTicks = 100)
	public void streamStoreRoundTrip(GameTestHelper h) throws IOException {
		Path dir = Files.createTempDirectory("echoaholic-store");
		UUID u = UUID.randomUUID();
		Path ownerDir = dir.resolve(u.toString());
		SyntheticStreams.Stream s = new SyntheticStreams.Stream(Level.OVERWORLD, new Vec3(0.5, 64, 0.5)).idle(3);
		for (int i = 0; i < 12; i++) s.walkTo(new Vec3(i * 3 + 0.5, 64, 0.5), 0.25).idle(1);
		s.idle(100 - (int) s.length());
		List<SealedSegment> segs = s.finish();
		h.assertValueEqual(segs.size(), 5, "segments of 20 ticks");
		StreamStore store = new StreamStore(dir);
		StreamStore again = null, rebuilt = null;
		try {
			for (SealedSegment seg : segs) store.append(u, seg);
			long bytes = segs.stream().mapToLong(x -> x.bytes().length).sum();
			h.assertValueEqual(store.totalBytes(u), bytes, "totalBytes");
			DecodedSegment early = store.load(u, 2).join(); // readable before the async write finished
			h.assertValueEqual(early.entries(), SegmentReader.decode(segs.get(2).bytes()).entries(), "pending segment readable");
			store.flush(true);
			for (SealedSegment seg : segs) h.assertTrue(Files.isRegularFile(ownerDir.resolve(seg.seq() + ".seg")), seg.seq() + ".seg");
			h.assertTrue(Files.isRegularFile(ownerDir.resolve("index.bin")), "index.bin");
			List<SegmentMeta> ring = store.ring(u).segments();

			again = new StreamStore(dir);
			h.assertValueEqual(again.ring(u).segments(), ring, "ring from index.bin");
			for (SealedSegment seg : segs) {
				DecodedSegment d = again.load(u, seg.seq()).join();
				h.assertValueEqual(d.entries(), SegmentReader.decode(seg.bytes()).entries(), "segment " + seg.seq() + " from disk");
			}
			again.close();
			again = null;

			Files.delete(ownerDir.resolve("index.bin"));
			rebuilt = new StreamStore(dir);
			RingBuffer r = rebuilt.ring(u);
			h.assertValueEqual(r.segments(), ring, "ring rebuilt from segment headers");
			rebuilt.close();
			rebuilt = null;

			List<SegmentMeta> evicted = store.evict(u, 100, 40);
			h.assertValueEqual(evicted.size(), 3, "evicted (end <= 60)");
			store.flush(true);
			h.assertValueEqual(segFiles(ownerDir), 2L, ".seg files after eviction");
			h.assertValueEqual(store.ring(u).oldestRetainedTick(), 60L, "oldest retained");

			store.wipe(u);
			store.flush(true);
			h.assertValueEqual(segFiles(ownerDir), 0L, ".seg files after wipe");
			h.assertTrue(store.ring(u).isEmpty(), "ring empty after wipe");
		} finally {
			store.close();
			if (again != null) again.close();
			if (rebuilt != null) rebuilt.close();
			deleteTree(dir);
		}
		h.succeed();
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}

	// ---------------------------------------------------------------------------------------------- bootstrap

	/**
	 * Create World handoff: a pending choice sets mode + delay; no pending choice leaves existing data alone; the pending
	 * value is consumed once. None of it touches hardcore or the difficulty.
	 */
	@GameTest(environment = NS + "solo_bootstrap")
	public void bootstrapApply(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		EchoConfig before = withConfig(h, c -> c);
		boolean hardcore = server.getWorldData().isHardcore();
		Difficulty difficulty = server.getWorldData().getDifficulty();
		PendingWorldMode access = (PendingWorldMode) ((MinecraftServerAccessor) server).echoaholic$getStorageSource();
		try {
			h.assertTrue(access.echoaholic$takePending() == null, "nothing pending on a running server");
			access.echoaholic$setPending(true, 10);
			h.assertValueEqual(access.echoaholic$takePending(), new PendingWorldMode.PendingEcho(true, 10), "take returns the choice");
			h.assertTrue(access.echoaholic$takePending() == null, "take clears it");

			EchoBootstrap.apply(server, new PendingWorldMode.PendingEcho(true, 7));
			h.assertTrue(config(h).enabled(), "pending ON applied");
			h.assertValueEqual(config(h).delayMinutes(), 7, "pending delay applied");

			EchoBootstrap.apply(server, new PendingWorldMode.PendingEcho(false, 3));
			h.assertTrue(!config(h).enabled(), "pending OFF applied");
			h.assertValueEqual(config(h).delayMinutes(), 3, "pending delay applied (OFF)");

			EchoConfig stored = config(h);
			EchoBootstrap.apply(server, null);
			h.assertValueEqual(config(h), stored, "no pending + existing data: unchanged");

			h.assertValueEqual(server.getWorldData().isHardcore(), hardcore, "hardcore untouched");
			h.assertValueEqual(server.getWorldData().getDifficulty(), difficulty, "difficulty untouched");
		} finally {
			access.echoaholic$takePending();
			restore(h, before);
		}
		h.succeed();
	}

	// ---------------------------------------------------------------------------------------------- crash recovery

	/** Stale meta (crash before the save): on join T and nextSeq resume after the segments on disk, never reusing seq. */
	@GameTest(maxTicks = 100)
	public void crashRecoveryTakesTFromIndex(GameTestHelper h) {
		EchoServer es = echoWorld(h);
		UUID u = UUID.randomUUID();
		SyntheticStreams.Stream s = new SyntheticStreams.Stream(Level.OVERWORLD, h.absoluteVec(new Vec3(2.5, 1, 2.5))).idle(100);
		for (SealedSegment seg : s.finish()) es.store().append(u, seg);
		PlayerStream ps = es.data().player(u);
		ps.streamTick = 40; // stale
		ps.nextSeq = 2; // stale
		ps.nextEchoIndex = TestSupport.NO_SCHEDULE;
		ServerPlayer p = TestSupport.survivalPlayer(h, u);
		TestSupport.teleport(h, p, new Vec3(2.5, 1, 2.5));
		h.assertValueEqual(ps.streamTick, 100L, "T recovered from the segments");
		h.assertValueEqual(ps.nextSeq, 5L, "nextSeq recovered");
		h.succeedWhen(() -> {
			List<SegmentMeta> ring = es.store().ring(u).segments();
			h.assertTrue(ring.size() >= 6, "a new segment was sealed");
			SegmentMeta fresh = ring.get(5);
			h.assertValueEqual(fresh.seq(), 5L, "new segment seq");
			h.assertValueEqual(fresh.startTick(), 100L, "new segment continues at T=100");
			TestSupport.cleanup(h, p);
		});
	}
}
