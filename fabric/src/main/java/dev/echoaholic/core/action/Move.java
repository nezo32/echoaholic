package dev.echoaholic.core.action;

/**
 * A sampled player position and rotation (degrees, Minecraft convention: yRot = yaw, xRot = pitch).
 *
 * <p>In segments positions are stored in fixed point ({@link #POS_SCALE} units per block, error &lt;= 1/128 block) and
 * angles as 1/256 of a turn (error &lt;= 0.71 degrees), so decoded moves equal {@link #quantized()} of the recorded one.
 * Decoded angles are normalized to [-180, 180).
 */
public record Move(double x, double y, double z, float yRot, float xRot) {
	/** Fixed-point units per block. */
	public static final int POS_SCALE = 64;
	/** Largest absolute coordinate a Move may carry (far beyond the world border). */
	public static final double MAX_ABS_COORD = 1.0e9;

	public Move {
		requireCoord(x, "x");
		requireCoord(y, "y");
		requireCoord(z, "z");
		if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) throw new IllegalArgumentException("angles must be finite");
	}

	private static void requireCoord(double v, String name) {
		if (!Double.isFinite(v) || Math.abs(v) > MAX_ABS_COORD) {
			throw new IllegalArgumentException(name + " out of range: " + v);
		}
	}

	/** Fixed-point form of a coordinate. */
	public static long quantize(double v) {
		return Math.round(v * POS_SCALE);
	}

	public static double dequantize(long q) {
		return q / (double) POS_SCALE;
	}

	/** An angle in degrees as 1/256 turns (0..255). */
	public static int angleByte(float degrees) {
		return (int) (Math.round(degrees * (256.0 / 360.0)) & 0xFF);
	}

	/** Inverse of {@link #angleByte}, in [-180, 180). */
	public static float angleFromByte(int b) {
		return (byte) b * (360f / 256f);
	}

	/** Signed shortest difference b - a in degrees, in (-180, 180]. */
	public static float angleDelta(float a, float b) {
		float d = (float) ((b - (double) a) % 360.0);
		if (d > 180f) d -= 360f;
		else if (d <= -180f) d += 360f;
		return d;
	}

	/** This move as a segment will return it after encoding. */
	public Move quantized() {
		return new Move(dequantize(quantize(x)), dequantize(quantize(y)), dequantize(quantize(z)),
				angleFromByte(angleByte(yRot)), angleFromByte(angleByte(xRot)));
	}

	/** Euclidean distance between the positions. */
	public double distanceTo(Move o) {
		double dx = x - o.x, dy = y - o.y, dz = z - o.z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
