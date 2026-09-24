package dev.echoaholic.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.echoaholic.Echoaholic;
import dev.echoaholic.core.stream.DecodedSegment;
import dev.echoaholic.core.stream.RingBuffer;
import dev.echoaholic.core.stream.SealedSegment;
import dev.echoaholic.core.stream.SegmentMeta;
import dev.echoaholic.core.stream.SegmentReader;
import org.jspecify.annotations.Nullable;

/**
 * Disk storage of every player's recorded stream for one server: per player a {@link RingBuffer} of sealed segments in
 * {@code <streamsDir>/<uuid>/<seq>.seg} plus {@code index.bin} (layout in {@link SegmentFiles}), a small cache of
 * decoded segments for the replay loop, and one background IO thread.
 *
 * <p><b>Threads.</b> Every public method runs on the server thread, which alone owns the rings, the cache and the
 * load table. The single daemon thread {@value #IO_THREAD_NAME} only reads, writes and deletes files and decodes
 * segments; it hands results back through {@link CompletableFuture}s, which the replay loop polls with
 * {@link CompletableFuture#isDone()} and never blocks on. Work runs in submission order, so a segment is always on disk
 * before the index that lists it, and a deletion always follows the writes queued before it.
 *
 * <p><b>Freshly sealed segments</b> are readable without a disk read: {@link #append} keeps the bytes of each player's
 * last {@value #RECENT_PER_OWNER} segments in a pending table, also after the IO thread has written them, until a
 * {@link #load} of them has been decoded into the LRU cache (or they are evicted, wiped, or pushed out by newer
 * segments). {@link #load} decodes such bytes on the IO thread (no disk read, no decode on the server thread: all
 * echoes of a player cross a segment boundary on the same tick); the replay loop's prefetch calls it ahead of time.
 * An echo that needs a segment at once may use {@link #decodeNowIfResident}: at most one server-thread decode per player
 * and {@value #MAX_SYNC_DECODES_PER_TICK} in total per tick ({@link #beginTick()}). {@link #forgetResident} releases a
 * player's in-memory bytes (on logout) as soon as they are on disk.
 *
 * <p><b>Failures</b> never reach the game: a failed write drops the pending bytes (a later load of that segment fails,
 * and the replay loop skips the gap), a failed read fails the future, and each player's first problem is logged as a
 * warning, later ones at debug level. A damaged or missing index is rebuilt from the segment headers
 * ({@link SegmentFiles#recover}).
 */
public final class StreamStore implements AutoCloseable {
	/** Name of the background IO thread. */
	public static final String IO_THREAD_NAME = "Echoaholic-IO";
	/** Decoded segments kept in memory (LRU, all players together). */
	public static final int CACHE_CAPACITY = 128;
	/** Longest wait for the IO thread in {@link #flush(boolean) flush(true)} and {@link #close()}. */
	private static final long JOIN_TIMEOUT_SECONDS = 60;
	/** Finished loads nobody asked for again are moved into the cache once this many loads are tracked. */
	private static final int LOADING_SWEEP_THRESHOLD = 64;
	/** Newest segments per player whose bytes stay in memory until their first load. */
	public static final int RECENT_PER_OWNER = 4;
	/** {@link #decodeNowIfResident} decodes per player per server tick (see {@link #beginTick()}). */
	public static final int SYNC_DECODES_PER_OWNER_PER_TICK = 1;
	/** Safety cap on {@link #decodeNowIfResident} decodes of all players together per server tick. */
	public static final int MAX_SYNC_DECODES_PER_TICK = 16;

	private record Key(UUID owner, long seq) {}

	/** Latest index content waiting for the IO thread; {@code epoch} changes when the owner's stream is wiped. */
	private record IndexSnapshot(int epoch, List<SegmentMeta> segments) {}

	private final Path streamsDir;
	private final ExecutorService io;
	private final Map<UUID, RingBuffer> rings = new HashMap<>();
	private final Map<UUID, Integer> epochs = new HashMap<>();
	private final Map<Key, DecodedSegment> cache = new LinkedHashMap<>(CACHE_CAPACITY * 2, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, DecodedSegment> eldest) {
			return size() > CACHE_CAPACITY;
		}
	};
	private final Map<Key, CompletableFuture<DecodedSegment>> loading = new HashMap<>();
	/**
	 * Sealed bytes of each player's newest segments (written or not yet), until their first load. Filled and trimmed by
	 * the server thread; the IO thread only removes an entry whose write failed.
	 */
	private final Map<Key, byte[]> pending = new ConcurrentHashMap<>();
	/** Segments whose write is queued but not finished. Added by the server thread, removed by the IO thread. */
	private final Set<Key> unwritten = ConcurrentHashMap.newKeySet();
	/** Owners whose in-memory bytes go away once written ({@link #forgetResident}); cleared by their next append. */
	private final Set<UUID> forgotten = ConcurrentHashMap.newKeySet();
	/** Server thread: seqs appended per player, newest last, at most {@link #RECENT_PER_OWNER}. */
	private final Map<UUID, ArrayDeque<Long>> recent = new HashMap<>();
	/** Shared with the IO thread: the next index content to write per owner. */
	private final Map<UUID, IndexSnapshot> indexSnapshots = new ConcurrentHashMap<>();
	/** Owners whose last index write failed; {@link #flush} tries again. */
	private final Set<UUID> indexRetry = ConcurrentHashMap.newKeySet();
	/** Owners (and {@link #STORE_KEY} for the store itself) already warned about. */
	private final Set<UUID> warned = ConcurrentHashMap.newKeySet();
	private static final UUID STORE_KEY = new UUID(0L, 0L);
	private boolean closed;
	/** Server thread: owners that used their synchronous decode this tick. */
	private final Set<UUID> syncDecodedOwners = new HashSet<>();

	/** A store over {@code streamsDir} ({@code <world>/data/echoaholic/streams}); nothing is read until needed. */
	public StreamStore(Path streamsDir) {
		this.streamsDir = streamsDir;
		this.io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, IO_THREAD_NAME);
			t.setDaemon(true);
			return t;
		});
	}

	/** Root folder of all streams. */
	public Path streamsDir() {
		return streamsDir;
	}

	/**
	 * The player's ring buffer. The first call per player reads {@code index.bin} (or, when it is missing, damaged or
	 * stale, rebuilds the ring from the segment headers) synchronously on the server thread; later calls are a map
	 * lookup. Never null: an unreadable folder gives an empty ring (and a warning).
	 */
	public RingBuffer ring(UUID owner) {
		RingBuffer ring = rings.get(owner);
		if (ring == null) {
			ring = loadRing(owner);
			rings.put(owner, ring);
		}
		return ring;
	}

	private RingBuffer loadRing(UUID owner) {
		Path dir = SegmentFiles.ownerDir(streamsDir, owner);
		SegmentFiles.Recovery recovery;
		try {
			recovery = SegmentFiles.recover(dir);
		} catch (IOException | RuntimeException e) {
			warn(owner, "Echoaholic could not read the recorded stream of " + owner + "; starting an empty one", e);
			return new RingBuffer();
		}
		RingBuffer ring;
		try {
			ring = new RingBuffer(recovery.segments());
		} catch (IllegalArgumentException e) {
			warn(owner, "Echoaholic found an inconsistent stream index for " + owner + "; starting an empty one", e);
			return new RingBuffer();
		}
		if (!recovery.problems().isEmpty()) {
			warn(owner, "Echoaholic repaired the recorded stream of " + owner + ": " + String.join("; ", recovery.problems()), null);
		}
		if (!recovery.obsolete().isEmpty()) {
			List<Path> obsolete = recovery.obsolete();
			submit(owner, () -> {
				for (Path f : obsolete) SegmentFiles.delete(f);
			});
		}
		if (recovery.rewriteIndex()) scheduleIndexWrite(owner, ring);
		return ring;
	}

	/**
	 * Adds a freshly sealed segment: its meta joins the ring now, its bytes are readable through {@link #load} at once,
	 * and the file plus the updated index are written on the IO thread. A segment the ring rejects (overlapping or
	 * out-of-order seq: a bug upstream) is dropped with a warning instead of breaking the server tick.
	 */
	public void append(UUID owner, SealedSegment s) {
		RingBuffer ring = ring(owner);
		try {
			ring.add(s.meta());
		} catch (IllegalArgumentException e) {
			warn(owner, "Echoaholic dropped a recorded segment of " + owner + " that does not fit the stream", e);
			return;
		}
		Key key = new Key(owner, s.seq());
		byte[] bytes = s.bytes();
		forgotten.remove(owner);
		pending.put(key, bytes);
		ArrayDeque<Long> seqs = recent.computeIfAbsent(owner, o -> new ArrayDeque<>());
		seqs.addLast(s.seq());
		// an older segment leaving memory is either on disk or queued for writing before any read of it (FIFO)
		while (seqs.size() > RECENT_PER_OWNER) pending.remove(new Key(owner, seqs.removeFirst()));
		Path file = SegmentFiles.segmentPath(SegmentFiles.ownerDir(streamsDir, owner), s.seq());
		unwritten.add(key);
		boolean queued = submit(owner, () -> {
			try {
				SegmentFiles.writeAtomic(file, bytes);
			} catch (IOException | RuntimeException e) {
				pending.remove(key, bytes); // dropped: a later load fails and the segment is a gap
				throw e;
			} finally {
				unwritten.remove(key);
				// order matters against forgetResident: it marks the owner first, then drops written entries
				if (forgotten.contains(owner)) pending.remove(key, bytes);
			}
		});
		if (!queued) {
			unwritten.remove(key);
			pending.remove(key, bytes);
		}
		scheduleIndexWrite(owner, ring);
	}

	/**
	 * Evicts the player's segments that are entirely older than {@code nowTick - retentionTicks} (the newest is always
	 * kept), forgets them in memory and deletes their files on the IO thread. Returns the evicted entries, oldest first.
	 */
	public List<SegmentMeta> evict(UUID owner, long nowTick, long retentionTicks) {
		RingBuffer ring = ring(owner);
		List<SegmentMeta> evicted = ring.evict(nowTick, retentionTicks);
		if (evicted.isEmpty()) return evicted;
		Path dir = SegmentFiles.ownerDir(streamsDir, owner);
		List<Path> files = new ArrayList<>(evicted.size());
		for (SegmentMeta m : evicted) {
			Key key = new Key(owner, m.seq());
			cache.remove(key);
			loading.remove(key);
			pending.remove(key);
			files.add(SegmentFiles.segmentPath(dir, m.seq()));
		}
		scheduleIndexWrite(owner, ring);
		submit(owner, () -> {
			for (Path f : files) SegmentFiles.delete(f);
		});
		return evicted;
	}

	/**
	 * The decoded segment if it is in the cache (or a finished {@link #load} just delivered it), else null. Never
	 * decodes or reads anything.
	 */
	public @Nullable DecodedSegment cached(UUID owner, long seq) {
		Key key = new Key(owner, seq);
		DecodedSegment hit = cache.get(key);
		if (hit != null) return hit;
		CompletableFuture<DecodedSegment> f = loading.get(key);
		return f != null ? promote(key, f) : null;
	}

	/**
	 * The decoded segment {@code seq} of the player. Already completed when it is cached; otherwise it is decoded on the
	 * IO thread, from memory for one of the player's recent segments, else from its file, and the future completes
	 * there. Never decodes on the server thread (only {@link #decodeNowIfResident} does, within its budget). Repeated calls
	 * while a load runs return the same future. A future that failed (missing or damaged file) stays failed: treat the
	 * segment as a gap.
	 */
	public CompletableFuture<DecodedSegment> load(UUID owner, long seq) {
		Key key = new Key(owner, seq);
		DecodedSegment hit = cache.get(key);
		if (hit != null) return CompletableFuture.completedFuture(hit);
		CompletableFuture<DecodedSegment> running = loading.get(key);
		if (running != null) {
			DecodedSegment done = promote(key, running);
			return done != null ? CompletableFuture.completedFuture(done) : running;
		}
		if (loading.size() >= LOADING_SWEEP_THRESHOLD) sweepLoading();
		byte[] bytes = pending.get(key); // immutable once appended: safe to hand to the IO thread
		SegmentMeta expected = findMeta(owner, seq);
		Path file = SegmentFiles.segmentPath(SegmentFiles.ownerDir(streamsDir, owner), seq);
		CompletableFuture<DecodedSegment> future;
		if (closed) {
			future = CompletableFuture.failedFuture(new IOException("stream store closed"));
		} else {
			try {
				future = bytes != null
						? CompletableFuture.supplyAsync(() -> decodeRecent(owner, file, bytes), io)
						: CompletableFuture.supplyAsync(() -> read(owner, file, expected), io);
			} catch (RejectedExecutionException e) {
				future = CompletableFuture.failedFuture(e);
			}
		}
		loading.put(key, future);
		return future;
	}

	/** Starts a server tick: refills the {@link #decodeNowIfResident} budget. Call once per tick before the replay loop. */
	public void beginTick() {
		syncDecodedOwners.clear();
	}

	/**
	 * Releases the player's in-memory segment bytes (call when the owner leaves: their stream does not grow while
	 * offline). Bytes already on disk go now; bytes whose write is still queued stay until the IO thread has written
	 * them and are released right after. Nothing recorded is lost: later loads read the files. Decoded segments in the
	 * LRU cache are unaffected. The next {@link #append} for the player keeps recent bytes again.
	 */
	public void forgetResident(UUID owner) {
		forgotten.add(owner); // before the scan: a write finishing concurrently then releases its own bytes
		pending.keySet().removeIf(k -> k.owner.equals(owner) && !unwritten.contains(k));
		ArrayDeque<Long> seqs = recent.get(owner);
		if (seqs != null) {
			seqs.removeIf(seq -> !pending.containsKey(new Key(owner, seq)));
			if (seqs.isEmpty()) recent.remove(owner);
		}
	}

	/**
	 * Bounded synchronous path for an echo that needs a segment right now (just spawned, restored): the decoded segment
	 * if it is cached, or, when its bytes are still in memory (one of the player's recent segments) and this tick's
	 * budget allows it, decoded here on the server thread and cached. Budget per tick ({@link #beginTick()}): one decode
	 * per player (all echoes of a player share the cached result, so the echo that needs a just-sealed segment is
	 * never starved by other players) and {@value #MAX_SYNC_DECODES_PER_TICK} in total. Cache hits are free. Otherwise
	 * null (use {@link #load}); never reads the disk. A decode failure fails the segment like {@link #load}.
	 */
	public @Nullable DecodedSegment decodeNowIfResident(UUID owner, long seq) {
		DecodedSegment hit = cached(owner, seq);
		if (hit != null) return hit;
		Key key = new Key(owner, seq);
		if (loading.containsKey(key) && loading.get(key).isCompletedExceptionally()) return null;
		byte[] bytes = pending.get(key);
		if (bytes == null || syncDecodedOwners.size() >= MAX_SYNC_DECODES_PER_TICK) return null;
		if (!syncDecodedOwners.add(owner)) return null; // this player's decode for the tick is used
		try {
			DecodedSegment decoded = SegmentReader.decode(bytes);
			loading.remove(key); // a running IO decode of the same bytes is simply superseded
			cache.put(key, decoded);
			pending.remove(key, bytes);
			return decoded;
		} catch (IOException e) {
			warn(owner, "Echoaholic could not decode a fresh segment " + seq + " of " + owner, e);
			loading.put(key, CompletableFuture.failedFuture(e));
			return null;
		}
	}

	/**
	 * Forgets the player's whole stream: a new empty ring right away, cached and pending data dropped, and every file of
	 * the player deleted on the IO thread (after any write queued before). Sequence numbers are not reset: the caller
	 * keeps counting from its {@code nextSeq}.
	 */
	public void wipe(UUID owner) {
		rings.put(owner, new RingBuffer());
		epochs.merge(owner, 1, Integer::sum);
		indexSnapshots.remove(owner);
		indexRetry.remove(owner);
		cache.keySet().removeIf(k -> k.owner.equals(owner));
		loading.keySet().removeIf(k -> k.owner.equals(owner));
		pending.keySet().removeIf(k -> k.owner.equals(owner));
		recent.remove(owner);
		forgotten.remove(owner);
		Path dir = SegmentFiles.ownerDir(streamsDir, owner);
		submit(owner, () -> SegmentFiles.deleteOwnerDir(dir));
	}

	/**
	 * Makes sure every index change is queued (index writes are normally queued as soon as a ring changes; this retries
	 * the ones that failed). With {@code join}, blocks until the IO thread has finished everything queued so far (at
	 * most {@value #JOIN_TIMEOUT_SECONDS} s).
	 */
	public void flush(boolean join) {
		if (closed) return;
		for (UUID owner : List.copyOf(indexRetry)) {
			indexRetry.remove(owner);
			RingBuffer ring = rings.get(owner);
			if (ring != null) scheduleIndexWrite(owner, ring);
		}
		if (!join) return;
		try {
			CompletableFuture.runAsync(() -> {}, io).get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (TimeoutException e) {
			warn(STORE_KEY, "Echoaholic stream writes did not finish within " + JOIN_TIMEOUT_SECONDS + " s", null);
		} catch (ExecutionException | RejectedExecutionException e) {
			warn(STORE_KEY, "Echoaholic could not wait for stream writes", e);
		}
	}

	/** Compressed bytes of the player's retained segments. */
	public long totalBytes(UUID owner) {
		return ring(owner).totalBytes();
	}

	/** {@code flush(true)}, then stops the IO thread. Later loads fail and later writes are dropped (with a warning). */
	@Override
	public void close() {
		if (closed) return;
		flush(true);
		closed = true;
		io.shutdown();
		try {
			if (!io.awaitTermination(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				warn(STORE_KEY, "Echoaholic IO thread did not stop in time; unfinished stream writes are abandoned", null);
				io.shutdownNow();
			}
		} catch (InterruptedException e) {
			io.shutdownNow();
			Thread.currentThread().interrupt();
		}
		cache.clear();
		loading.clear();
	}

	/** Queues an index write with the ring's current content; several changes before the write coalesce into one. */
	private void scheduleIndexWrite(UUID owner, RingBuffer ring) {
		int epoch = epochs.getOrDefault(owner, 0);
		IndexSnapshot previous = indexSnapshots.put(owner, new IndexSnapshot(epoch, ring.segments()));
		if (previous != null && previous.epoch == epoch) return; // a queued write will pick the new content up
		Path file = SegmentFiles.indexPath(SegmentFiles.ownerDir(streamsDir, owner));
		submit(owner, () -> writeIndex(owner, epoch, file));
	}

	/** IO thread: writes the latest snapshot of {@code epoch}, if it is still the latest. */
	private void writeIndex(UUID owner, int epoch, Path file) throws IOException {
		IndexSnapshot snapshot;
		do {
			snapshot = indexSnapshots.get(owner);
			if (snapshot == null || snapshot.epoch != epoch) return;
		} while (!indexSnapshots.remove(owner, snapshot));
		try {
			if (snapshot.segments.isEmpty() && !Files.isDirectory(file.getParent())) return;
			SegmentFiles.writeAtomic(file, SegmentFiles.encodeIndex(snapshot.segments));
		} catch (IOException | RuntimeException e) {
			indexRetry.add(owner);
			throw e;
		}
	}

	/** IO thread: reads and decodes one segment file, checking it against the ring entry when known. */
	private DecodedSegment read(UUID owner, Path file, @Nullable SegmentMeta expected) {
		try {
			DecodedSegment decoded = SegmentReader.decode(Files.readAllBytes(file));
			if (expected != null && (decoded.startTick() != expected.startTick() || decoded.endTick() != expected.endTick())) {
				throw new IOException("segment " + SegmentFiles.describe(file) + " covers ticks " + decoded.startTick() + ".."
						+ decoded.endTick() + ", the index says " + expected.startTick() + ".." + expected.endTick());
			}
			return decoded;
		} catch (IOException e) {
			warn(owner, "Echoaholic could not read segment " + SegmentFiles.describe(file) + "; it is skipped", e);
			throw new CompletionException(e);
		}
	}

	/** IO thread: decodes the in-memory bytes of a recent segment (no disk access). */
	private DecodedSegment decodeRecent(UUID owner, Path file, byte[] bytes) {
		try {
			return SegmentReader.decode(bytes);
		} catch (IOException e) {
			warn(owner, "Echoaholic could not decode fresh segment " + SegmentFiles.describe(file) + "; it is skipped", e);
			throw new CompletionException(e);
		}
	}

	private @Nullable SegmentMeta findMeta(UUID owner, long seq) {
		RingBuffer ring = rings.get(owner);
		if (ring == null) return null;
		for (SegmentMeta m : ring.segments()) {
			if (m.seq() == seq) return m;
		}
		return null;
	}

	/** Moves a successfully finished load into the cache and returns its segment; null while running or failed. */
	private @Nullable DecodedSegment promote(Key key, CompletableFuture<DecodedSegment> f) {
		if (!f.isDone() || f.isCompletedExceptionally()) return null;
		DecodedSegment d = f.join();
		loading.remove(key);
		cache.put(key, d);
		pending.remove(key); // decoded now; the file serves any later reload
		return d;
	}

	private void sweepLoading() {
		for (Map.Entry<Key, CompletableFuture<DecodedSegment>> e : List.copyOf(loading.entrySet())) {
			promote(e.getKey(), e.getValue());
		}
	}

	@FunctionalInterface
	private interface IoTask {
		void run() throws IOException;
	}

	/** Queues work on the IO thread; its failure is logged against {@code owner}. False when the store is closed. */
	private boolean submit(UUID owner, IoTask task) {
		if (closed) {
			warn(owner, "Echoaholic stream store is closed; a write for " + owner + " was dropped", null);
			return false;
		}
		try {
			io.execute(() -> {
				try {
					task.run();
				} catch (IOException | RuntimeException e) {
					warn(owner, "Echoaholic stream storage failed for " + owner, e);
				}
			});
			return true;
		} catch (RejectedExecutionException e) {
			warn(owner, "Echoaholic stream store rejected a write for " + owner, e);
			return false;
		}
	}

	/** Logs the first problem per owner as a warning and later ones at debug level. Any thread. */
	private void warn(UUID owner, String message, @Nullable Throwable t) {
		if (warned.add(owner)) {
			Echoaholic.LOGGER.warn("{} (further storage problems of this stream are logged at debug level)", message, t);
		} else {
			Echoaholic.LOGGER.debug(message, t);
		}
	}
}
