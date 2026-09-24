package dev.echoaholic.core.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import dev.echoaholic.core.action.Move;

/**
 * Binary layout of a sealed segment, shared by {@link SegmentWriter} and {@link SegmentReader}.
 *
 * <p>Sealed bytes = zlib(Deflate level 6) of: magic "ECHO", version byte, startTick (varlong), tickCount (varint), body.
 * Body = for each tick with content: tickDelta (varint, &gt;= 1, relative to the previous entry or startTick - 1),
 * flags byte, move fields, then if {@link #ACTIONS}: action count (varint &gt;= 1) and actions (type id byte + codec).
 * A keyframe move stores absolute fixed-point x/y/z (zigzag varlongs) and yaw/pitch bytes; a delta move stores only the
 * axes flagged {@link #DX}/{@link #DY}/{@link #DZ} as zigzag varlong deltas and yaw/pitch bytes only when {@link #ROT}.
 */
final class SegmentFormat {
	static final byte[] MAGIC = {'E', 'C', 'H', 'O'};
	static final int VERSION = 1;
	static final int DEFLATE_LEVEL = 6;
	/** Refuse to inflate more than this (corrupt or hostile data). */
	static final int MAX_INFLATED_BYTES = 32 * 1024 * 1024;
	/** Axis delta (fixed-point units) beyond which a move is stored as a keyframe: 8 blocks. */
	static final long KEYFRAME_DELTA = 8L * Move.POS_SCALE;

	static final int MOVE = 0x01;
	static final int KEYFRAME = 0x02;
	static final int ROT = 0x04;
	static final int ACTIONS = 0x08;
	static final int DX = 0x10;
	static final int DY = 0x20;
	static final int DZ = 0x40;
	static final int KNOWN_FLAGS = 0x7F;

	private SegmentFormat() {}

	static byte[] deflate(byte[] raw) {
		Deflater deflater = new Deflater(DEFLATE_LEVEL);
		try {
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
			byte[] chunk = new byte[8192];
			while (!deflater.finished()) {
				int n = deflater.deflate(chunk);
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
		} finally {
			deflater.end();
		}
	}

	static byte[] inflate(byte[] sealed) throws IOException {
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(sealed);
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, sealed.length * 3));
			byte[] chunk = new byte[8192];
			while (!inflater.finished()) {
				int n = inflater.inflate(chunk);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new IOException("truncated segment");
				}
				out.write(chunk, 0, n);
				if (out.size() > MAX_INFLATED_BYTES) throw new IOException("segment too large");
			}
			if (inflater.getRemaining() > 0) throw new IOException("trailing bytes after segment");
			return out.toByteArray();
		} catch (DataFormatException e) {
			throw new IOException("corrupt segment: " + e.getMessage(), e);
		} finally {
			inflater.end();
		}
	}
}
