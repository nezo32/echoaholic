package dev.echoaholic.test;

import static dev.echoaholic.test.TestSupport.awaitEcho;
import static dev.echoaholic.test.TestSupport.cleanup;
import static dev.echoaholic.test.TestSupport.discardAll;
import static dev.echoaholic.test.TestSupport.echoWorld;
import static dev.echoaholic.test.TestSupport.es;
import static dev.echoaholic.test.TestSupport.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import dev.echoaholic.Echoaholic;
import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.stream.SealedSegment;
import dev.echoaholic.core.stream.SegmentWriter;
import dev.echoaholic.replay.TickStats;
import dev.echoaholic.test.SyntheticStreams.Stream;
import dev.echoaholic.test.TestSupport.Mock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Performance: 32 echoes of one online owner replay a dense-mining stream (a break every 2 ticks) in full mode. Prints
 * lines starting with {@code PERF} (ms/tick of the echo manager: avg, p95, max; stream bytes per hour) for the README.
 */
public class EchoPerfGameTests {
	private static final int ECHOES = 32;
	private static final int COLUMN_TICKS = 8; // 4 breaks, one every 2 ticks, while walking one block
	private static final int STONE_TOP = 5; // stone y = 1..5, walk on y = 6, mine y = 1..4
	private static final int WARMUP = 20;
	private static final int MEASURE = 200;

	@GameTest(environment = "echoaholic-gametest:solo_perf", structure = "echoaholic-gametest:arena32", maxTicks = 600)
	public void thirtyTwoEchoesDenseMining(GameTestHelper h) {
		EchoConfig cfg = echoWorld(h).config();
		ServerLevel level = h.getLevel();
		for (int x = 0; x < 32; x++) {
			for (int z = 0; z < 32; z++) {
				for (int y = 0; y <= STONE_TOP; y++) level.setBlock(h.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 2);
			}
		}

		// serpentine over the 32x32 arena: one column per 8 ticks, mining the 4 blocks below the walker
		Stream s = new Stream(Level.OVERWORLD, col(h, 0, 0), SegmentWriter.SEGMENT_TICKS);
		for (int z = 0; z < 32; z++) {
			for (int i = 0; i < 32; i++) {
				int x = z % 2 == 0 ? i : 31 - i;
				Vec3 from = col(h, x, z);
				Vec3 to = i < 31 ? col(h, z % 2 == 0 ? x + 1 : x - 1, z) : col(h, x, Math.min(31, z + 1));
				for (int t = 0; t < COLUMN_TICKS; t++) {
					double f = (t + 1) / (double) COLUMN_TICKS;
					s.look(z % 2 == 0 ? -90f : 90f, 60f);
					Vec3 p = from.add(to.subtract(from).scale(f));
					s.moveTo(p);
					if (t % 2 == 0) {
						BlockPos b = h.absolutePos(new BlockPos(x, 4 - t / 2, z));
						s.tick(new BlockBreak(b.getX(), b.getY(), b.getZ(), "minecraft:stone", "minecraft:iron_pickaxe"));
					} else {
						s.tick();
					}
				}
			}
		}
		List<SealedSegment> segs = s.finish();
		long streamTicks = s.length();
		int stride = (int) (streamTicks / ECHOES);

		UUID owner = UUID.randomUUID();
		SyntheticStreams.inject(es(h), owner, segs);
		Mock m = TestSupport.mock(h, owner);
		TestSupport.makeModded(m.player()); // include trail payloads in the cost
		TestSupport.teleport(h, m.player(), new Vec3(16, 11, 16));
		for (int k = 1; k <= ECHOES; k++) manager(h).spawnEcho(owner, k, (long) (k - 1) * stride);

		long denseBytes = es(h).store().totalBytes(owner);
		double denseBytesPerHour = denseBytes * (EchoConfig.TICKS_PER_HOUR / (double) streamTicks);

		List<Long> nanos = new ArrayList<>();
		int[] maxOps = {0}, maxHazard = {0}, maxEchoes = {0};
		long[] deferred = {0};
		int[] start = {-1};
		h.onEachTick(() -> {
			m.drain();
			if (start[0] < 0) {
				if (manager(h).entity(owner, ECHOES) != null) start[0] = (int) h.getTick();
				return;
			}
			if (h.getTick() <= start[0] + WARMUP || nanos.size() >= MEASURE) return;
			TickStats st = manager(h).lastTickStats();
			nanos.add(st.nanos());
			maxOps[0] = Math.max(maxOps[0], st.blockOps());
			maxHazard[0] = Math.max(maxHazard[0], st.hazardOps());
			maxEchoes[0] = Math.max(maxEchoes[0], st.echoes());
			deferred[0] += st.deferred();
		});
		h.succeedWhen(() -> {
			h.assertTrue(nanos.size() >= MEASURE, "measuring: " + nanos.size() + "/" + MEASURE);
			try {
				awaitEcho(h, owner, 1);
				long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
				double avgMs = Arrays.stream(sorted).average().orElse(0) / 1e6;
				double p95Ms = sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1e6;
				double maxMs = sorted[sorted.length - 1] / 1e6;
				int mined = 0;
				for (int x = 0; x < 32; x++) {
					for (int z = 0; z < 32; z++) {
						for (int y = 1; y <= 4; y++) if (level.getBlockState(h.absolutePos(new BlockPos(x, y, z))).isAir()) mined++;
					}
				}
				double walking = walkingBytesPerHour(h);
				String line = String.format(java.util.Locale.ROOT,
						"PERF echoes=%d ticks=%d manager ms/tick avg=%.3f p95=%.3f max=%.3f | blockOps max=%d (cap %d) hazard max=%d (cap %d)"
								+ " deferred=%d | blocks mined=%d",
						maxEchoes[0], sorted.length, avgMs, p95Ms, maxMs, maxOps[0], cfg.globalBlockOpsPerTick(), maxHazard[0],
						cfg.globalHazardOpsPerTick(), deferred[0], mined);
				String bytes = String.format(java.util.Locale.ROOT,
						"PERF stream bytes/hour dense-mining=%.0f (%.1f KiB/h, %d segments, %d bytes for %d ticks) walking=%.0f (%.1f KiB/h)",
						denseBytesPerHour, denseBytesPerHour / 1024, segs.size(), denseBytes, streamTicks, walking, walking / 1024);
				Echoaholic.LOGGER.info(line);
				Echoaholic.LOGGER.info(bytes);
				System.out.println(line);
				System.out.println(bytes);
				h.assertValueEqual(maxEchoes[0], ECHOES, "echoes ticked");
				h.assertTrue(maxOps[0] <= cfg.globalBlockOpsPerTick(), "global block-op cap");
				h.assertTrue(maxOps[0] <= ECHOES * cfg.echoBlockOpsPerTick(), "per-echo caps");
				h.assertTrue(maxHazard[0] <= cfg.globalHazardOpsPerTick(), "hazard cap");
				h.assertTrue(avgMs <= 3.0, "average " + avgMs + " ms/tick > 3 ms");
				h.assertTrue(maxMs <= 20.0, "max " + maxMs + " ms/tick > 20 ms");
				// every echo mines 1 block per 2 ticks: ~(WARMUP + MEASURE) / 2 each, allow stalls at the start
				h.assertTrue(mined >= ECHOES * (MEASURE / 2) * 9 / 10, "blocks mined " + mined);
			} finally {
				cleanup(h, owner);
				discardAll(h, ItemEntity.class, TestSupport.around(h, 4));
			}
		});
	}

	private static Vec3 col(GameTestHelper h, int x, int z) {
		return h.absoluteVec(new Vec3(x + 0.5, STONE_TOP + 1, z + 0.5));
	}

	/**
	 * Bytes per hour of an ordinary play session: an hour of walking around (sprinting some of the time, sneaking now
	 * and then, turning), a block broken or placed every ~10 s. Encoded with the production segment length.
	 */
	private static double walkingBytesPerHour(GameTestHelper h) {
		java.util.Random rnd = new java.util.Random(42);
		Stream s = new Stream(Level.OVERWORLD, new Vec3(0.5, 64, 0.5), SegmentWriter.SEGMENT_TICKS);
		Vec3 pos = new Vec3(0.5, 64, 0.5);
		while (s.length() < EchoConfig.TICKS_PER_HOUR) {
			Vec3 target = pos.add(rnd.nextInt(41) - 20, rnd.nextInt(3) - 1, rnd.nextInt(41) - 20);
			boolean sprint = rnd.nextInt(3) == 0;
			s.pose(new Pose(rnd.nextInt(10) == 0, sprint, false, false));
			s.walkTo(target, sprint ? 0.28 : 0.215);
			pos = target;
			BlockPos b = BlockPos.containing(pos).below();
			Action a = rnd.nextBoolean()
					? new BlockBreak(b.getX(), b.getY(), b.getZ(), "minecraft:dirt", "minecraft:iron_shovel")
					: new BlockPlace(b.getX(), b.getY() + 1, b.getZ(), "minecraft:cobblestone", "minecraft:cobblestone");
			s.tick(a).idle(rnd.nextInt(60));
		}
		long bytes = s.finish().stream().mapToLong(x -> x.bytes().length).sum();
		return bytes * (EchoConfig.TICKS_PER_HOUR / (double) s.length());
	}
}
