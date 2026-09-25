package dev.echoaholic.core.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.echoaholic.core.EchoConfig;
import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.Attack;
import dev.echoaholic.core.action.BlockBreak;
import dev.echoaholic.core.action.BlockPlace;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.Pose;
import dev.echoaholic.core.action.Shoot;
import dev.echoaholic.core.action.Swing;
import dev.echoaholic.core.action.UseItem;

/** Storage cost of one recorded hour (the README quotes the printed numbers). */
class SegmentSizeTest {
	private static final int HOUR = EchoConfig.TICKS_PER_HOUR;
	private static final long LIMIT = 1_500_000;

	private static final String[] BREAK_STATES = {"minecraft:stone", "minecraft:deepslate", "minecraft:dirt",
			"minecraft:grass_block[snowy=false]", "minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]",
			"minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gravel", "minecraft:andesite", "minecraft:short_grass",
			"minecraft:birch_log[axis=y]"};
	private static final String[] TOOLS = {"minecraft:diamond_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel", ""};
	private static final String[] PLACE_STATES = {"minecraft:cobblestone", "minecraft:oak_planks", "minecraft:torch",
			"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", "minecraft:crafting_table"};

	private record Result(long bytes, int segments, long actions) {}

	private static Result recordHour(boolean dense, long seed) throws IOException {
		Random rnd = new Random(seed);
		StreamRecorder recorder = new StreamRecorder(0, 0);
		long bytes = 0;
		int segments = 0;
		long actions = 0;
		double x = 1523.7, y = 64, z = -8801.2, vy = 0;
		float yaw = 12f, pitch = 5f;
		double heading = 0;
		Pose pose = Pose.STANDING;
		for (int t = 0; t < HOUR; t++) {
			List<Action> list = new ArrayList<>();
			if (dense) {
				// walking/sprinting every tick with small heading changes, looking around, jumping now and then
				heading += rnd.nextGaussian() * 0.08;
				double speed = pose.sprinting() ? 0.28 : 0.21;
				x += Math.cos(heading) * speed;
				z += Math.sin(heading) * speed;
				if (vy == 0 && rnd.nextInt(40) == 0) vy = 0.42;
				if (vy != 0 || y > 64) {
					y += vy;
					vy -= 0.08;
					if (y <= 64) {
						y = 64;
						vy = 0;
					}
				}
				yaw = (float) Math.toDegrees(heading) - 90f + (float) rnd.nextGaussian() * 3f;
				pitch = (float) Math.max(-90, Math.min(90, pitch + rnd.nextGaussian() * 2));
				if (rnd.nextInt(300) == 0) pose = new Pose(rnd.nextInt(4) == 0, rnd.nextBoolean(), false, false);
				int bx = (int) Math.floor(x) + rnd.nextInt(5) - 2, by = 63 + rnd.nextInt(4), bz = (int) Math.floor(z) + rnd.nextInt(5) - 2;
				if (t % 10 == 0) {
					list.add(new BlockBreak(bx, by, bz, BREAK_STATES[rnd.nextInt(BREAK_STATES.length)], TOOLS[rnd.nextInt(TOOLS.length)]));
				}
				if (t % 40 == 5) {
					String state = PLACE_STATES[rnd.nextInt(PLACE_STATES.length)];
					list.add(new BlockPlace(bx, by, bz, state, state.replaceFirst("\\[.*", "")));
				}
				if (t % 100 == 50) {
					list.add(new Attack(x + rnd.nextDouble() * 2, y, z + rnd.nextDouble() * 2, 4f + rnd.nextInt(6), "minecraft:iron_sword"));
				}
				if (t % 25 == 7) list.add(Swing.INSTANCE);
				if (t % 600 == 300) {
					list.add(new Shoot("minecraft:arrow", x, y + 1.5, z, rnd.nextGaussian(), 0.3, rnd.nextGaussian(), "minecraft:bow"));
				}
				if (t % 1200 == 900) {
					list.add(new UseItem(UseItem.Kind.BUCKET_EMPTY, bx, by, bz, 1, "minecraft:water_bucket", "minecraft:water"));
				}
			}
			actions += list.size();
			var sealed = recorder.record(new Move(x, y, z, yaw, pitch), pose, list);
			if (sealed.isPresent()) {
				bytes += sealed.get().bytes().length;
				segments++;
				SegmentReader.decode(sealed.get().bytes()); // stays decodable
			}
		}
		return new Result(bytes, segments, actions);
	}

	@Test
	void denseHourFitsTheBudget() throws IOException {
		Result r = recordHour(true, 2026);
		assertEquals(60, r.segments());
		System.out.printf("[echoaholic] dense hour: %,d bytes/hour (%.1f KiB, %d segments, %d actions, %.0f bytes/segment); 6 h buffer: %.2f MiB%n",
				r.bytes(), r.bytes() / 1024.0, r.segments(), r.actions(), r.bytes() / (double) r.segments(), 6 * r.bytes() / 1048576.0);
		assertTrue(r.bytes() < LIMIT, "dense hour " + r.bytes() + " bytes >= " + LIMIT);
	}

	@Test
	void idleHourIsTiny() throws IOException {
		Result r = recordHour(false, 1);
		assertEquals(60, r.segments());
		System.out.printf("[echoaholic] idle hour: %,d bytes/hour (%.1f KiB, %d segments, %.0f bytes/segment)%n",
				r.bytes(), r.bytes() / 1024.0, r.segments(), r.bytes() / (double) r.segments());
		assertTrue(r.bytes() < 20_000, "idle hour " + r.bytes() + " bytes");
	}
}
