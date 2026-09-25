package dev.echoaholic.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per-client settings: play the "echo joined" sound, show the actionbar message, draw echo path trails.
 * Stored as {@code {"notifySound": true, "notifyMessage": true, "showTrail": true}}. Pure (no Minecraft / Fabric).
 */
public record NotifySettings(boolean sound, boolean message, boolean trail) {
	public static final NotifySettings DEFAULT = new NotifySettings(true, true, true);
	public static final String KEY_SOUND = "notifySound";
	public static final String KEY_MESSAGE = "notifyMessage";
	public static final String KEY_TRAIL = "showTrail";

	public NotifySettings withSound(boolean v) {
		return new NotifySettings(v, message, trail);
	}

	public NotifySettings withMessage(boolean v) {
		return new NotifySettings(sound, v, trail);
	}

	public NotifySettings withTrail(boolean v) {
		return new NotifySettings(sound, message, v);
	}

	/** Never throws. Non-object / malformed JSON -> DEFAULT; a missing or non-boolean key -> true for that key. */
	public static NotifySettings parse(String json) {
		if (json == null) return DEFAULT;
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		} catch (RuntimeException e) {
			return DEFAULT;
		}
		if (root == null || !root.isJsonObject()) return DEFAULT;
		JsonObject obj = root.getAsJsonObject();
		return new NotifySettings(bool(obj, KEY_SOUND), bool(obj, KEY_MESSAGE), bool(obj, KEY_TRAIL));
	}

	private static boolean bool(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
		return true;
	}

	/** Pretty JSON, 2-space indent, trailing newline: {"notifySound": true, "notifyMessage": true, "showTrail": true}. */
	public String toJson() {
		return "{\n  \"" + KEY_SOUND + "\": " + sound + ",\n  \"" + KEY_MESSAGE + "\": " + message
				+ ",\n  \"" + KEY_TRAIL + "\": " + trail + "\n}\n";
	}

	/** Missing file, unreadable file or bad content -> DEFAULT. Never throws; never writes. */
	public static NotifySettings load(Path file) {
		try {
			if (!Files.isRegularFile(file)) return DEFAULT;
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			// includes MalformedInputException for binary garbage
			return DEFAULT;
		}
	}

	/**
	 * createDirectories(parent); write "&lt;name&gt;.tmp" (UTF-8); move it over the file atomically,
	 * falling back to a plain replacing move when the file system cannot do atomic moves.
	 */
	public void save(Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) Files.createDirectories(parent);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
