package dev.echoaholic.core.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of every action type. Ids are part of the file format: never change or reuse one. Adding a type = one
 * record + one registration line here (+ a replay handler on the MC side keyed by the new constant).
 */
public final class ActionTypes {
	private static final ActionType<?>[] BY_ID = new ActionType<?>[256];
	private static final Map<Class<?>, ActionType<?>> BY_CLASS = new HashMap<>();
	private static final List<ActionType<?>> ALL = new ArrayList<>();

	public static final ActionType<Pose> POSE = register(1, "pose", Pose.class, new ActionCodec<>() {
		@Override
		public void write(Pose a, SegmentOutput out) {
			out.writeByte((a.sneaking() ? 1 : 0) | (a.sprinting() ? 2 : 0) | (a.swimming() ? 4 : 0) | (a.fallFlying() ? 8 : 0));
		}

		@Override
		public Pose read(SegmentInput in) throws IOException {
			int bits = in.readByte();
			if ((bits & ~0x0F) != 0) throw new IOException("bad pose bits " + bits);
			return new Pose((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
		}
	});

	public static final ActionType<BlockBreak> BLOCK_BREAK = register(2, "block_break", BlockBreak.class, new ActionCodec<>() {
		@Override
		public void write(BlockBreak a, SegmentOutput out) {
			writePos(out, a.x(), a.y(), a.z());
			out.writeString(a.blockState());
			out.writeString(a.toolItem());
		}

		@Override
		public BlockBreak read(SegmentInput in) throws IOException {
			return new BlockBreak(in.readZigZagInt(), in.readZigZagInt(), in.readZigZagInt(), in.readString(), in.readString());
		}
	});

	public static final ActionType<BlockPlace> BLOCK_PLACE = register(3, "block_place", BlockPlace.class, new ActionCodec<>() {
		@Override
		public void write(BlockPlace a, SegmentOutput out) {
			writePos(out, a.x(), a.y(), a.z());
			out.writeString(a.blockState());
			out.writeString(a.item());
		}

		@Override
		public BlockPlace read(SegmentInput in) throws IOException {
			return new BlockPlace(in.readZigZagInt(), in.readZigZagInt(), in.readZigZagInt(), in.readString(), in.readString());
		}
	});

	public static final ActionType<Attack> ATTACK = register(4, "attack", Attack.class, new ActionCodec<>() {
		@Override
		public void write(Attack a, SegmentOutput out) {
			out.writeDouble(a.tx());
			out.writeDouble(a.ty());
			out.writeDouble(a.tz());
			out.writeFloat(a.damage());
			out.writeString(a.weaponItem());
		}

		@Override
		public Attack read(SegmentInput in) throws IOException {
			return new Attack(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readString());
		}
	});

	public static final ActionType<Shoot> SHOOT = register(5, "shoot", Shoot.class, new ActionCodec<>() {
		@Override
		public void write(Shoot a, SegmentOutput out) {
			out.writeString(a.entityType());
			out.writeDouble(a.x());
			out.writeDouble(a.y());
			out.writeDouble(a.z());
			out.writeDouble(a.vx());
			out.writeDouble(a.vy());
			out.writeDouble(a.vz());
			out.writeString(a.item());
		}

		@Override
		public Shoot read(SegmentInput in) throws IOException {
			return new Shoot(in.readString(), in.readDouble(), in.readDouble(), in.readDouble(),
					in.readDouble(), in.readDouble(), in.readDouble(), in.readString());
		}
	});

	public static final ActionType<UseItem> USE_ITEM = register(6, "use_item", UseItem.class, new ActionCodec<>() {
		@Override
		public void write(UseItem a, SegmentOutput out) {
			out.writeByte(a.kind().id());
			writePos(out, a.x(), a.y(), a.z());
			out.writeZigZagInt(a.face());
			out.writeString(a.item());
			out.writeString(a.extra());
		}

		@Override
		public UseItem read(SegmentInput in) throws IOException {
			int kindId = in.readByte();
			UseItem.Kind kind = UseItem.Kind.byId(kindId).orElseThrow(() -> new IOException("unknown use kind " + kindId));
			return new UseItem(kind, in.readZigZagInt(), in.readZigZagInt(), in.readZigZagInt(), in.readZigZagInt(),
					in.readString(), in.readString());
		}
	});

	public static final ActionType<Dimension> DIMENSION = register(7, "dimension", Dimension.class, new ActionCodec<>() {
		@Override
		public void write(Dimension a, SegmentOutput out) {
			out.writeString(a.dimensionId());
			out.writeDouble(a.x());
			out.writeDouble(a.y());
			out.writeDouble(a.z());
		}

		@Override
		public Dimension read(SegmentInput in) throws IOException {
			return new Dimension(in.readString(), in.readDouble(), in.readDouble(), in.readDouble());
		}
	});

	public static final ActionType<Teleport> TELEPORT = register(8, "teleport", Teleport.class, new ActionCodec<>() {
		@Override
		public void write(Teleport a, SegmentOutput out) {
			out.writeDouble(a.x());
			out.writeDouble(a.y());
			out.writeDouble(a.z());
		}

		@Override
		public Teleport read(SegmentInput in) throws IOException {
			return new Teleport(in.readDouble(), in.readDouble(), in.readDouble());
		}
	});

	public static final ActionType<Death> DEATH = register(9, "death", Death.class, new ActionCodec<>() {
		@Override
		public void write(Death a, SegmentOutput out) {}

		@Override
		public Death read(SegmentInput in) {
			return Death.INSTANCE;
		}
	});

	public static final ActionType<Swing> SWING = register(10, "swing", Swing.class, new ActionCodec<>() {
		@Override
		public void write(Swing a, SegmentOutput out) {}

		@Override
		public Swing read(SegmentInput in) {
			return Swing.INSTANCE;
		}
	});

	private ActionTypes() {}

	private static <A extends Action> ActionType<A> register(int id, String name, Class<A> cls, ActionCodec<A> codec) {
		ActionType<A> type = new ActionType<>(id, name, cls, codec);
		if (BY_ID[id] != null) throw new IllegalStateException("duplicate action id " + id);
		if (BY_CLASS.containsKey(cls)) throw new IllegalStateException("duplicate action class " + cls.getName());
		BY_ID[id] = type;
		BY_CLASS.put(cls, type);
		ALL.add(type);
		return type;
	}

	private static void writePos(SegmentOutput out, int x, int y, int z) {
		out.writeZigZagInt(x);
		out.writeZigZagInt(y);
		out.writeZigZagInt(z);
	}

	/** All types in id order. */
	public static List<ActionType<?>> all() {
		return Collections.unmodifiableList(ALL);
	}

	public static Optional<ActionType<?>> byId(int id) {
		if (id < 0 || id >= BY_ID.length) return Optional.empty();
		return Optional.ofNullable(BY_ID[id]);
	}

	/** The registered type of {@code action}; unregistered classes -> IllegalArgumentException. */
	public static ActionType<?> of(Action action) {
		ActionType<?> type = BY_CLASS.get(action.getClass());
		if (type == null) throw new IllegalArgumentException("unregistered action " + action.getClass().getName());
		return type;
	}

	/** Type id byte + payload. */
	public static void write(Action action, SegmentOutput out) {
		ActionType<?> type = of(action);
		out.writeByte(type.id());
		type.write(action, out);
	}

	/** Reads one action written by {@link #write}. Unknown id -> IOException (the segment is corrupt). */
	public static Action read(SegmentInput in) throws IOException {
		int id = in.readByte();
		ActionType<?> type = BY_ID[id];
		if (type == null) throw new IOException("unknown action id " + id);
		return type.codec().read(in);
	}
}
