package dev.echoaholic.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The core package (and subpackages) must stay free of Minecraft, Mojang and Fabric so it can be unit tested without a game. */
class CorePurityTest {
	private static final Path CORE = Path.of("src/main/java/dev/echoaholic/core");

	@Test
	void coreHasNoMinecraftOrFabricImports() throws IOException {
		assertTrue(Files.isDirectory(CORE), "core sources not found at " + CORE.toAbsolutePath());
		List<Path> files;
		try (Stream<Path> walk = Files.walk(CORE)) {
			files = walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
		assertFalse(files.isEmpty(), "no core sources found");
		assertTrue(files.stream().anyMatch(p -> p.toString().contains("stream")), "subpackages not scanned");
		List<String> violations = new ArrayList<>();
		for (Path file : files) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).strip();
				if (line.startsWith("import net.minecraft") || line.startsWith("import net.fabricmc")
						|| line.startsWith("import static net.minecraft") || line.startsWith("import static net.fabricmc")
						|| line.startsWith("import com.mojang") || line.startsWith("import static com.mojang")) {
					violations.add(file + ":" + (i + 1) + ": " + line);
				} else if (!line.startsWith("*") && !line.startsWith("/") && (line.contains("net.minecraft.")
						|| line.contains("net.fabricmc.") || line.contains("com.mojang."))) {
					violations.add(file + ":" + (i + 1) + ": fully qualified " + line);
				}
			}
		}
		assertTrue(violations.isEmpty(), "forbidden imports in core:\n" + String.join("\n", violations));
	}
}
