package dev.echoaholic.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jspecify.annotations.Nullable;

/**
 * Per-player stream metadata plus that player's live echoes, persisted in {@link EchoWorldData}. The recorded actions
 * themselves live in segment files ({@link StreamStore}); only the counters needed to continue the stream are here.
 *
 * <p>Server thread only. Public mutable fields on purpose (the recorder and the replay loop update them every tick);
 * {@link EchoWorldData} is marked dirty on every save, which persists them.
 */
public final class PlayerStream {
	/** Stored body of a player entry; the owner UUID is the map key in {@link EchoWorldData}. */
	record Body(long streamTick, long nextSeq, int nextEchoIndex, Optional<OwnerProfile> profile, List<EchoState> echoes) {
		static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.optionalFieldOf("streamTick", 0L).forGetter(Body::streamTick),
				Codec.LONG.optionalFieldOf("nextSeq", 0L).forGetter(Body::nextSeq),
				Codec.INT.optionalFieldOf("nextEchoIndex", 1).forGetter(Body::nextEchoIndex),
				OwnerProfile.CODEC.optionalFieldOf("profile").forGetter(Body::profile),
				lenientList(EchoState.CODEC).lenientOptionalFieldOf("echoes", List.of()).forGetter(Body::echoes)
		).apply(i, Body::new));
	}

	/** The recorded player's UUID. */
	public final UUID owner;
	/** Stream time T: ticks this player has been recorded so far (the tick the next recorded tick gets). */
	public long streamTick;
	/** Sequence number the next sealed segment gets; only grows, never reused (even after a clear). */
	public long nextSeq;
	/** Number k of the next echo to spawn; only grows until a clear resets it to 1. */
	public int nextEchoIndex = 1;
	/** Name and skin captured at the last join, for echoes spawned while the owner is offline. */
	public @Nullable OwnerProfile profile;
	/** Live echoes of this player, in any order. */
	public final List<EchoState> echoes = new ArrayList<>();

	public PlayerStream(UUID owner) {
		if (owner == null) throw new IllegalArgumentException("owner");
		this.owner = owner;
	}

	/** The live echo with number {@code index}, or null. */
	public @Nullable EchoState echo(int index) {
		for (EchoState e : echoes) {
			if (e.index == index) return e;
		}
		return null;
	}

	Body body() {
		return new Body(streamTick, nextSeq, nextEchoIndex, Optional.ofNullable(profile), List.copyOf(echoes));
	}

	/**
	 * Rebuilds a player entry from storage, repairing what a damaged file may contain: negative counters are raised to
	 * 0 (1 for the echo index), echoes repeating an index or an id are dropped, and {@code nextEchoIndex} is kept above
	 * every live echo so an index is never handed out twice.
	 */
	static PlayerStream fromBody(UUID owner, Body body) {
		PlayerStream p = new PlayerStream(owner);
		p.streamTick = Math.max(0, body.streamTick());
		p.nextSeq = Math.max(0, body.nextSeq());
		p.profile = body.profile().orElse(null);
		int next = Math.max(1, body.nextEchoIndex());
		Set<Integer> indices = new HashSet<>();
		Set<UUID> ids = new HashSet<>();
		for (EchoState e : body.echoes()) {
			if (!indices.add(e.index) || !ids.add(e.echoId)) continue;
			p.echoes.add(e);
			if (e.index >= next) next = e.index == Integer.MAX_VALUE ? Integer.MAX_VALUE : e.index + 1;
		}
		p.nextEchoIndex = next;
		return p;
	}

	/** A list codec whose decoder skips elements that fail to decode instead of failing the whole list. */
	static <E> Codec<List<E>> lenientList(Codec<E> element) {
		Decoder<List<E>> decoder = new Decoder<>() {
			@Override
			public <T> DataResult<Pair<List<E>, T>> decode(DynamicOps<T> ops, T input) {
				return ops.getStream(input).map(stream -> Pair.of(
						stream.map(t -> element.parse(ops, t).result()).flatMap(Optional::stream).toList(), input));
			}
		};
		return Codec.of(element.listOf(), decoder);
	}

	/** An unbounded map codec whose decoder skips entries whose key or value fails to decode; the result is mutable. */
	static <K, V> Codec<Map<K, V>> lenientMap(Codec<K> keyCodec, Codec<V> valueCodec) {
		Decoder<Map<K, V>> decoder = new Decoder<>() {
			@Override
			public <T> DataResult<Pair<Map<K, V>, T>> decode(DynamicOps<T> ops, T input) {
				return ops.getMapValues(input).map(entries -> {
					Map<K, V> out = new HashMap<>();
					entries.forEach(e -> {
						Optional<K> k = keyCodec.parse(ops, e.getFirst()).result();
						Optional<V> v = valueCodec.parse(ops, e.getSecond()).result();
						if (k.isPresent() && v.isPresent()) out.putIfAbsent(k.get(), v.get());
					});
					return Pair.of(out, input);
				});
			}
		};
		return Codec.of(Codec.unboundedMap(keyCodec, valueCodec), decoder);
	}
}
