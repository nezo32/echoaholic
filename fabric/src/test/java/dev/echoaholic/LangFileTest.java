package dev.echoaholic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * en_us.json and ru_ru.json: the same keys, the same placeholders, no blank values, every key the code asks for is
 * present, and every server-side English fallback has the placeholders of its lang value. This is the CI check that
 * fails on a missing key.
 */
class LangFileTest {
	private static JsonObject lang;
	private static JsonObject ru;

	@BeforeAll
	static void load() throws IOException {
		lang = read("en_us");
		ru = read("ru_ru");
	}

	private static JsonObject read(String code) throws IOException {
		try (InputStream in = LangFileTest.class.getResourceAsStream("/assets/echoaholic/lang/" + code + ".json")) {
			assertNotNull(in, code + ".json not on the test classpath");
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
	}

	/** Format placeholders (%s, %1$s, %d, …) of a lang value, sorted; %% is a literal percent sign. */
	static List<String> placeholders(String value) {
		Matcher m = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z%])").matcher(value);
		List<String> out = new ArrayList<>();
		int next = 1;
		while (m.find()) {
			if (m.group(2).equals("%")) continue;
			// unnumbered %s count as positional, so "%s %s" and "%2$s %1$s" compare equal
			out.add((m.group(1) != null ? m.group(1) : String.valueOf(next++)) + "$" + m.group(2));
		}
		out.sort(null);
		return out;
	}

	@Test
	void placeholderHelperSanity() {
		assertEquals(List.of("1$s", "2$s"), placeholders("Echo #%1$s · %2$s"));
		assertEquals(List.of("1$s", "2$s"), placeholders("Echo #%s · %s"));
		assertEquals(List.of("1$s", "2$s"), placeholders("%2$s a %1$s"));
		assertEquals(List.of("1$s"), placeholders("Sound: %s (100%%)"));
		assertEquals(List.of(), placeholders("none"));
	}

	@Test
	void russianHasExactlyTheEnglishKeys() {
		assertEquals(new TreeSet<>(lang.keySet()), new TreeSet<>(ru.keySet()), "ru_ru.json key set differs from en_us.json");
	}

	@Test
	void russianPlaceholdersMatchEnglish() {
		for (String key : lang.keySet()) {
			if (!ru.has(key)) continue; // reported by russianHasExactlyTheEnglishKeys
			assertEquals(placeholders(lang.get(key).getAsString()), placeholders(ru.get(key).getAsString()), "placeholders of " + key);
		}
	}

	@Test
	void noEmptyValues() {
		for (JsonObject file : new JsonObject[] {lang, ru}) {
			for (String key : file.keySet()) {
				assertFalse(file.get(key).getAsString().isBlank(), "blank " + key + (file == ru ? " (ru_ru)" : " (en_us)"));
			}
		}
	}

	/** Every player-facing key, including the ones the code builds at runtime (activities, notify settings). */
	static final String[] REQUIRED = {
			"modmenu.summaryTranslation.echoaholic",
			"modmenu.descriptionTranslation.echoaholic",
			"echoaholic.createWorld.toggle",
			"echoaholic.createWorld.toggle.tooltip",
			"echoaholic.createWorld.delay",
			"echoaholic.createWorld.delay.tooltip",
			"echoaholic.minutes",
			"echoaholic.command.on",
			"echoaholic.command.off",
			"echoaholic.command.status.on",
			"echoaholic.command.status.off",
			"echoaholic.command.status.detail",
			"echoaholic.command.delay.set",
			"echoaholic.command.max.set",
			"echoaholic.command.paused",
			"echoaholic.command.resumed",
			"echoaholic.command.cleared",
			"echoaholic.command.modeOffHint",
			"echoaholic.command.list.header",
			"echoaholic.command.list.entry",
			"echoaholic.command.list.empty",
			"echoaholic.command.list.denied",
			"echoaholic.command.config.value",
			"echoaholic.command.config.set",
			"echoaholic.command.config.unknown",
			"echoaholic.command.config.invalid",
			"echoaholic.echo.name",
			"echoaholic.message.joined",
			"echoaholic.message.faded",
			"echoaholic.activity.walking",
			"echoaholic.activity.mining",
			"echoaholic.activity.building",
			"echoaholic.activity.fighting",
			"echoaholic.activity.idle",
			"echoaholic.activity.collapsed",
			"echoaholic.activity.paused",
			"echoaholic.activity.waiting",
			"echoaholic.settings.title",
			"echoaholic.settings.notifySound",
			"echoaholic.settings.notifySound.tooltip",
			"echoaholic.settings.notifyMessage",
			"echoaholic.settings.notifyMessage.tooltip",
			"echoaholic.settings.showTrail",
			"echoaholic.settings.showTrail.tooltip",
			"echoaholic.command.notify.sound",
			"echoaholic.command.notify.message",
			"echoaholic.command.notify.trail"};

	@Test
	void requiredKeysPresent() {
		for (String key : REQUIRED) {
			assertTrue(lang.has(key), "missing " + key);
			assertTrue(ru.has(key), "ru_ru missing " + key);
		}
	}

	/** Placeholder counts the code relies on (branding table + ARCH D2/D3). */
	@Test
	void placeholderContracts() {
		assertEquals(List.of(), placeholders(lang.get("echoaholic.createWorld.delay").getAsString()),
				"createWorld.delay is a CycleButton name: no %s (the value comes from echoaholic.minutes)");
		assertEquals(List.of("1$s", "2$s", "3$s", "4$s", "5$s"), placeholders(lang.get("echoaholic.command.list.entry").getAsString()));
		assertEquals(List.of("1$s", "2$s", "3$s"), placeholders(lang.get("echoaholic.command.list.header").getAsString()));
		assertEquals(List.of("1$s", "2$s"), placeholders(lang.get("echoaholic.echo.name").getAsString()));
		assertEquals(List.of("1$s"), placeholders(lang.get("echoaholic.message.joined").getAsString()));
		assertEquals(List.of("1$s", "2$s", "3$s", "4$s"), placeholders(lang.get("echoaholic.command.config.invalid").getAsString()));
		assertTrue(lang.get("echoaholic.message.joined").getAsString().contains("👥"), "joined message has the 👥");
		assertTrue(ru.get("echoaholic.message.joined").getAsString().contains("👥"), "ru joined message has the 👥");
		assertTrue(lang.get("echoaholic.echo.name").getAsString().contains(" · "), "name uses U+00B7 with spaces");
	}

	/** ARCH D1: pause freezes replay only, recording continues; the text must not claim otherwise. */
	@Test
	void pauseTextDoesNotFreezeRecording() {
		String paused = lang.get("echoaholic.command.paused").getAsString();
		assertFalse(paused.contains("recording"), paused);
		assertTrue(paused.contains("/echoaholic resume"), paused);
		assertTrue(ru.get("echoaholic.command.paused").getAsString().contains("/echoaholic resume"));
	}

	@Test
	void russianKeepsModNameAndCommands() {
		assertEquals("Режим Echoaholic", ru.get("echoaholic.createWorld.toggle").getAsString());
		assertTrue(ru.get("echoaholic.createWorld.toggle.tooltip").getAsString().contains("/echoaholic on|off"), "ru tooltip names the command");
		assertTrue(ru.get("echoaholic.createWorld.delay.tooltip").getAsString().contains("/echoaholic delay"), "ru tooltip names the command");
		for (String key : REQUIRED) {
			if (key.startsWith("echoaholic.command.config.value")) continue; // "%1$s = %2$s" is the same everywhere
			assertFalse(lang.get(key).getAsString().equals(ru.get(key).getAsString()), "untranslated " + key);
		}
	}

	private static final Pattern FALLBACK = Pattern.compile(
			"translatableWithFallback\\(\\s*\"([^\"]+)\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern TRANSLATABLE = Pattern.compile("translatable(?:WithFallback)?\\(\\s*\"(echoaholic\\.[^\"]+)\"");

	private static List<Path> sources(String sourceSet) throws IOException {
		Path root = Path.of("src/" + sourceSet + "/java");
		if (!Files.isDirectory(root)) return List.of();
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	/** Every literal key the code translates exists; server fallbacks have the placeholders of the English value. */
	@Test
	void codeKeysExistAndFallbacksMatch() throws IOException {
		List<Path> files = new ArrayList<>(sources("main"));
		files.addAll(sources("client"));
		assertFalse(files.isEmpty(), "sources not found (working dir " + Path.of("").toAbsolutePath() + ")");
		int fallbacks = 0;
		for (Path file : files) {
			String src = Files.readString(file, StandardCharsets.UTF_8);
			Matcher t = TRANSLATABLE.matcher(src);
			while (t.find()) {
				String key = t.group(1);
				if (key.endsWith(".")) continue; // prefix of a key built at runtime (covered by REQUIRED)
				assertTrue(lang.has(key), file + " uses missing key " + key);
			}
			Matcher f = FALLBACK.matcher(src);
			while (f.find()) {
				String key = f.group(1);
				if (!key.startsWith("echoaholic.")) continue;
				fallbacks++;
				String fallback = f.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
				assertEquals(placeholders(lang.get(key).getAsString()), placeholders(fallback), file + ": fallback of " + key);
			}
		}
		assertTrue(fallbacks > 0 || sources("main").stream().noneMatch(p -> p.endsWith("EchoCommand.java")),
				"no server fallbacks found");
	}
}
