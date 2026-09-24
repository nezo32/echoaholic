package dev.echoaholic.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An echo's materials: item id -&gt; count. Echoes earn credits from blocks they break and buckets they fill, and spend
 * them to place blocks or use consumables. Tools and weapons are free and never stored here.
 *
 * <p>Caps: each count saturates at {@link #MAX_COUNT}; at most {@link #MAX_IDS} distinct ids. Adding a new id to a full
 * inventory first drops the id with the smallest count (ties: the one added earliest), so the result is deterministic
 * and survives {@link #encode()} / {@link #decode(String)} (which keep insertion order). The new id is always kept.
 */
public final class VirtualInventory {
	public static final int MAX_COUNT = 1_000_000;
	public static final int MAX_IDS = 256;

	private final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();

	/** Adds {@code n} of {@code id} (n &lt;= 0 does nothing). Ids must be non-empty without ';', '=' or whitespace. */
	public void add(String id, int n) {
		validate(id);
		if (n <= 0) return;
		Integer current = counts.get(id);
		if (current == null) {
			if (counts.size() >= MAX_IDS) dropSmallest();
			counts.put(id, Math.min(MAX_COUNT, n));
		} else {
			counts.put(id, (int) Math.min(MAX_COUNT, (long) current + n));
		}
	}

	public boolean has(String id) {
		return count(id) > 0;
	}

	public int count(String id) {
		return counts.getOrDefault(id, 0);
	}

	/** Spends one {@code id}; false (nothing changes) when there is none. */
	public boolean take(String id) {
		Integer current = counts.get(id);
		if (current == null) return false;
		if (current <= 1) counts.remove(id);
		else counts.put(id, current - 1);
		return true;
	}

	public int distinctIds() {
		return counts.size();
	}

	public boolean isEmpty() {
		return counts.isEmpty();
	}

	/** Read-only view in insertion order. */
	public Map<String, Integer> snapshot() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
	}

	/** Rebuilds from a {@link #snapshot()}; invalid ids and non-positive counts are skipped, caps applied. */
	public static VirtualInventory restore(Map<String, Integer> snapshot) {
		VirtualInventory inv = new VirtualInventory();
		for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
			if (e.getKey() == null || e.getValue() == null || !validId(e.getKey())) continue;
			inv.add(e.getKey(), e.getValue());
		}
		return inv;
	}

	/** Compact form for SavedData: "minecraft:dirt=12;minecraft:water_bucket=1" in insertion order ("" when empty). */
	public String encode() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			if (!sb.isEmpty()) sb.append(';');
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	/** Inverse of {@link #encode()}. Never throws: malformed entries are skipped; null -&gt; empty. */
	public static VirtualInventory decode(String encoded) {
		VirtualInventory inv = new VirtualInventory();
		if (encoded == null || encoded.isEmpty()) return inv;
		for (String entry : encoded.split(";")) {
			int eq = entry.lastIndexOf('=');
			if (eq <= 0) continue;
			String id = entry.substring(0, eq);
			if (!validId(id)) continue;
			try {
				inv.add(id, Integer.parseInt(entry.substring(eq + 1)));
			} catch (NumberFormatException e) {
				// skip malformed count
			}
		}
		return inv;
	}

	private void dropSmallest() {
		String victim = null;
		int min = Integer.MAX_VALUE;
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			if (e.getValue() < min) {
				min = e.getValue();
				victim = e.getKey();
			}
		}
		counts.remove(victim);
	}

	private static void validate(String id) {
		if (id == null || !validId(id)) throw new IllegalArgumentException("invalid item id: " + id);
	}

	private static boolean validId(String id) {
		if (id.isEmpty()) return false;
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (c == ';' || c == '=' || Character.isWhitespace(c)) return false;
		}
		return true;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof VirtualInventory other && counts.equals(other.counts);
	}

	@Override
	public int hashCode() {
		return counts.hashCode();
	}

	@Override
	public String toString() {
		return "VirtualInventory[" + encode() + "]";
	}
}
