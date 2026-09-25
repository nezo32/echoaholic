package dev.echoaholic.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotifySettingsTest {
	@TempDir
	Path dir;

	private static List<NotifySettings> all() {
		List<NotifySettings> out = new ArrayList<>();
		for (boolean sound : new boolean[] {true, false}) {
			for (boolean message : new boolean[] {true, false}) {
				for (boolean trail : new boolean[] {true, false}) out.add(new NotifySettings(sound, message, trail));
			}
		}
		return out;
	}

	@Test
	void missingFileIsDefaultAndNotCreated() {
		Path file = dir.resolve("echoaholic.json");
		assertEquals(NotifySettings.DEFAULT, NotifySettings.load(file));
		assertFalse(Files.exists(file));
		assertEquals(new NotifySettings(true, true, true), NotifySettings.DEFAULT);
	}

	@Test
	void saveLoadRoundTripsAllCombinations() throws IOException {
		Path file = dir.resolve("echoaholic.json");
		for (NotifySettings s : all()) {
			s.save(file);
			assertEquals(s, NotifySettings.load(file), s.toString());
		}
		new NotifySettings(false, true, false).save(file);
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"notifySound\": false"), text);
		assertTrue(text.contains("\"notifyMessage\": true"), text);
		assertTrue(text.contains("\"showTrail\": false"), text);
	}

	@Test
	void toJsonFormat() {
		assertEquals("{\n  \"notifySound\": true,\n  \"notifyMessage\": false,\n  \"showTrail\": true\n}\n",
				new NotifySettings(true, false, true).toJson());
	}

	@Test
	void saveCreatesParentsOverwritesAndLeavesNoTmp() throws IOException {
		Path file = dir.resolve("a/b/config/echoaholic.json");
		new NotifySettings(false, false, false).save(file);
		assertEquals(new NotifySettings(false, false, false), NotifySettings.load(file));
		new NotifySettings(true, false, true).save(file);
		assertEquals(new NotifySettings(true, false, true), NotifySettings.load(file));
		try (Stream<Path> files = Files.list(file.getParent())) {
			List<String> names = files.map(p -> p.getFileName().toString()).toList();
			assertEquals(List.of("echoaholic.json"), names);
		}
	}

	@Test
	void corruptInputIsDefaultAndNotRewritten() throws IOException {
		Path file = dir.resolve("echoaholic.json");
		List<byte[]> inputs = List.of(
				"not json".getBytes(StandardCharsets.UTF_8),
				new byte[0],
				"[]".getBytes(StandardCharsets.UTF_8),
				"null".getBytes(StandardCharsets.UTF_8),
				"{\"showTrail\":".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xC3, 0x28, (byte) 0x80, 0x7B, 0x01, (byte) 0xFA});
		for (byte[] bytes : inputs) {
			Files.write(file, bytes);
			assertEquals(NotifySettings.DEFAULT, NotifySettings.load(file), "input " + new String(bytes, StandardCharsets.ISO_8859_1));
			assertArrayEquals(bytes, Files.readAllBytes(file), "load must not rewrite the file");
		}
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse(null));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("\"x\""));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("true"));
	}

	@Test
	void directoryInsteadOfFileIsDefault() throws IOException {
		Path file = dir.resolve("echoaholic.json");
		Files.createDirectories(file);
		assertEquals(NotifySettings.DEFAULT, NotifySettings.load(file));
	}

	@Test
	void partialAndWrongTypes() {
		assertEquals(new NotifySettings(false, true, true), NotifySettings.parse("{\"notifySound\":false}"));
		assertEquals(new NotifySettings(true, false, true), NotifySettings.parse("{\"notifyMessage\":false}"));
		assertEquals(new NotifySettings(true, true, false), NotifySettings.parse("{\"showTrail\":false}"));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("{\"notifySound\":\"no\",\"notifyMessage\":0,\"showTrail\":\"false\"}"));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("{\"notifySound\":null,\"notifyMessage\":{},\"showTrail\":[false]}"));
		assertEquals(new NotifySettings(false, false, false),
				NotifySettings.parse("{\"notifySound\":false,\"notifyMessage\":false,\"showTrail\":false,\"extra\":1}"));
		// a file written before the trail key existed keeps its two values and gets the trail default
		assertEquals(new NotifySettings(false, false, true), NotifySettings.parse("{\"notifySound\":false,\"notifyMessage\":false}"));
		assertEquals(NotifySettings.DEFAULT, NotifySettings.parse("{}"));
	}

	@Test
	void withersAreImmutableAndJsonRoundTrips() {
		NotifySettings base = NotifySettings.DEFAULT;
		assertEquals(new NotifySettings(false, true, true), base.withSound(false));
		assertEquals(new NotifySettings(true, false, true), base.withMessage(false));
		assertEquals(new NotifySettings(true, true, false), base.withTrail(false));
		assertEquals(new NotifySettings(false, false, false), base.withSound(false).withMessage(false).withTrail(false));
		assertEquals(new NotifySettings(true, true, true), base);
		assertNotSame(base, base.withTrail(true));
		for (NotifySettings s : all()) assertEquals(s, NotifySettings.parse(s.toJson()));
	}
}
