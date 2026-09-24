# Echoaholic for Fabric (Java Edition)

This is the Fabric mod for Minecraft Java **26.3** (the default) and **26.2**. For what the mod does and how to install it, see the [root README](../README.md).

## Requirements

- JDK 21 or newer to run Gradle. The build itself compiles with a **Java 25 toolchain**, which Gradle downloads automatically through the foojay resolver. To use a JDK you already have, pass `-Porg.gradle.java.installations.paths=/path/to/jdk-25`.
- Everything else comes from the Gradle wrapper (Gradle 9.5.1, Fabric Loom 1.17, Loader 0.19.5, Fabric API 0.161.0).
- Mod Menu (optional, 21.0.0 for 26.3 / 20.0.2 for 26.2) to open the settings screen. The build only compiles against it (`clientCompileOnly`, from the TerraformersMC maven); it is not bundled and not on the dev/gametest runtime classpath. To try it in `runClient`, drop the matching `modmenu-*.jar` into `run/mods`.

## Build and test

```bash
./gradlew build                          # 26.3: compile + JUnit + server gametests
./gradlew build -Pmod_version=1.2.3      # set the version (default 0.0.0)
./gradlew clean build -Pmc=26.2          # build and test against 26.2 instead
./gradlew test                           # JUnit only
./gradlew runGameTest                    # server gametests only (headless)
./gradlew runClient                      # dev client
```

`build/libs/` gets `echoaholic-<version>.jar`, the jar you ship, and `echoaholic-<version>-sources.jar`.

The client gametest opens a real client and needs a display. It is not part of `build`:

```bash
timeout 300 xvfb-run -a env LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1 ./gradlew runClientGameTest   # needs libegl1 libegl-mesa0
```

## Layout

<!-- TODO(docs): table of source paths (core/, record/, storage/, replay/, entity/, net/, mixin/, src/client/,
     lang/, src/test/, src/gametest/ = the `echoaholic-gametest` test mod, never packaged). -->

## Behavior summary

<!-- TODO(docs): Echoaholic Mode and Echo Delay per world, recording, echo spawning/retiring, replayed actions,
     budget and cheap mode, storage (bytes/hour), commands. -->

### Languages

<!-- TODO(docs): en_us / ru_ru, translatableWithFallback, LangFileTest. -->

### Notification settings

<!-- TODO(docs): config/echoaholic.json, Mod Menu screen, /echoaholic-notify, payload vs vanilla clients. -->
