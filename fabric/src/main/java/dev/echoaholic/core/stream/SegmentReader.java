package dev.echoaholic.core.stream;

import static dev.echoaholic.core.stream.SegmentFormat.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.echoaholic.core.action.Action;
import dev.echoaholic.core.action.ActionTypes;
import dev.echoaholic.core.action.Move;
import dev.echoaholic.core.action.SegmentInput;

/** Decodes sealed segments. Any corruption (bad zlib, magic, version, flags, ids, truncation) -> IOException. */
public final class SegmentReader {
	private SegmentReader() {}

	/** Only the header (still inflates the data). */
	public static SegmentHeader header(byte[] sealed) throws IOException {
		return readHeader(new SegmentInput(inflate(sealed)));
	}

	/** Fully decodes a sealed segment. */
	public static DecodedSegment decode(byte[] sealed) throws IOException {
		SegmentInput in = new SegmentInput(inflate(sealed));
		SegmentHeader header = readHeader(in);
		long end = header.endTick();
		List<TickEntry> entries = new ArrayList<>();
		long tick = header.startTick() - 1;
		boolean hasPrev = false;
		long px = 0, py = 0, pz = 0;
		int yaw = 0, pitch = 0;
		while (in.hasRemaining()) {
			long delta = in.readVarLong();
			if (delta < 1 || delta > end - tick - 1) throw new IOException("bad tick delta " + delta);
			tick += delta;
			int flags = in.readByte();
			if ((flags & ~KNOWN_FLAGS) != 0) throw new IOException("unknown flags " + flags);
			boolean hasMove = (flags & MOVE) != 0;
			boolean hasActions = (flags & ACTIONS) != 0;
			Move move = null;
			if (hasMove) {
				if ((flags & KEYFRAME) != 0) {
					if ((flags & (DX | DY | DZ | ROT)) != 0) throw new IOException("keyframe with delta flags");
					px = in.readZigZagLong();
					py = in.readZigZagLong();
					pz = in.readZigZagLong();
					yaw = in.readByte();
					pitch = in.readByte();
				} else {
					if (!hasPrev) throw new IOException("delta move before first keyframe");
					if ((flags & DX) != 0) px += in.readZigZagLong();
					if ((flags & DY) != 0) py += in.readZigZagLong();
					if ((flags & DZ) != 0) pz += in.readZigZagLong();
					if ((flags & ROT) != 0) {
						yaw = in.readByte();
						pitch = in.readByte();
					}
				}
				hasPrev = true;
				move = toMove(px, py, pz, yaw, pitch);
			} else {
				if ((flags & (KEYFRAME | ROT | DX | DY | DZ)) != 0) throw new IOException("move flags without move");
				if (!hasActions) throw new IOException("empty tick entry");
			}
			List<Action> actions = List.of();
			if (hasActions) {
				int count = in.readVarInt();
				if (count < 1) throw new IOException("bad action count " + count);
				List<Action> list = new ArrayList<>(Math.min(count, 64));
				for (int i = 0; i < count; i++) list.add(ActionTypes.read(in));
				actions = list;
			}
			entries.add(new TickEntry(tick, move, actions));
		}
		return new DecodedSegment(header.startTick(), end, entries);
	}

	private static Move toMove(long qx, long qy, long qz, int yaw, int pitch) throws IOException {
		try {
			return new Move(Move.dequantize(qx), Move.dequantize(qy), Move.dequantize(qz),
					Move.angleFromByte(yaw), Move.angleFromByte(pitch));
		} catch (IllegalArgumentException e) {
			throw new IOException("move out of range", e);
		}
	}

	private static SegmentHeader readHeader(SegmentInput in) throws IOException {
		for (byte b : MAGIC) {
			if (in.readByte() != (b & 0xFF)) throw new IOException("not an echo segment (bad magic)");
		}
		int version = in.readByte();
		if (version != VERSION) throw new IOException("unsupported segment version " + version);
		long start = in.readVarLong();
		int count = in.readVarInt();
		if (start < 0 || count < 1 || start > Long.MAX_VALUE - count) throw new IOException("bad segment range");
		return new SegmentHeader(version, start, count);
	}
}
