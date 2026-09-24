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
./gradlew test                           # JUnit only (pure core logic + lang file)
./gradlew runGameTest                    # server gametests only (headless)
./gradlew runClient                      # dev client
```

`build/libs/` gets `echoaholic-<version>.jar`, the jar you ship, and `echoaholic-<version>-sources.jar`.

The client gametest opens a real client and needs a display. It is not part of `build`:

```bash
timeout 300 xvfb-run -a env LIBGL_ALWAYS_SOFTWARE=1 SDL_VIDEO_FORCE_EGL=1 ./gradlew runClientGameTest   # needs libegl1 libegl-mesa0
```

## Layout

| Path | What it holds |
|---|---|
| `src/main/java/dev/echoaholic/core/` | Pure logic: `EchoConfig` (every setting and safety cap, with defaults, ranges and the `Key` metadata behind `/echoaholic config`), `EchoSchedule` (spawn/retire math), `BudgetScheduler` (per-echo and global per-tick budgets, round-robin), `VirtualInventory` (an echo's credits), `NotifySettings`. `core/action/` holds the action records (`Move`, `Pose`, `BlockBreak`, `BlockPlace`, `Attack`, `Shoot`, `UseItem`, `Dimension`, `Teleport`, `Death`, `Swing`), their binary codecs and the `ActionTypes` registry. `core/stream/` holds the segment format (`SegmentWriter`/`SegmentReader`/`DecodedSegment`), `StreamRecorder`, `MoveSampler`, `RingBuffer` and the `SegmentStore` interface. It must not import Minecraft or Fabric classes, and `CorePurityTest` checks this. |
| `src/main/java/dev/echoaholic/` | `Echoaholic` (entrypoint, registration order), `EchoServer` (per-server holder and the `clear`/`setConfig` facade), `EchoLifecycle` (server, tick, save and join/leave events) and `Feedback` (joined/faded notices). |
| `src/main/java/dev/echoaholic/mode/` | `EchoBootstrap` (Create World handoff, else stored data, else OFF), `PendingWorldMode` and the `/echoaholic` command (`EchoCommand`). |
| `src/main/java/dev/echoaholic/record/` | Capture: `Recorder` (per-player `PlayerRecording`, gating, keyframes, sealing and eviction), `EchoCapture` (the facade the mixins call) and `RecordHooks` (Fabric events for breaks and deaths). |
| `src/main/java/dev/echoaholic/storage/` | `EchoWorldData` (SavedData `echoaholic:world`: config, `PlayerStream` per player, `EchoState` per echo, `OwnerProfile`), `StreamStore` (segment files, ring buffers, decoded-segment cache, the `Echoaholic-IO` thread), `SegmentFiles` (paths, atomic writes, `index.bin`) and `EchoTuning` (test overrides). |
| `src/main/java/dev/echoaholic/replay/` | `EchoManager` (the tick loop: spawn, retire, cursors, budget, movement, cheap mode, list/clear), `ReplayHandlers` (one handler per `ActionType`, checked complete at server start), `MetaHandlers` (pose, teleport, dimension, death, swing) and `handler/` (break, place, attack, shoot, use item, `Hazards`). |
| `src/main/java/dev/echoaholic/entity/` | `EchoEntity` (a subclass of the vanilla `Mannequin`, so vanilla clients see a mannequin: steering, pose, collapse, never saved, no portals or riding), `EchoTargeting` (monsters target echoes), `SwingCompat` (arm swing on 26.2 and 26.3) and `EchoVisuals`. |
| `src/main/java/dev/echoaholic/net/` | `EchoNoticePayload` (`echoaholic:notice`, joined/faded) and `EchoTrailPayload` (the next 5 s of an echo's path), both server-to-client and only sent to clients that have the mod. |
| `src/main/java/dev/echoaholic/util/` | `Ids`: registry id conversions and a cached block-state parser, shared by capture and replay. |
| `src/main/java/dev/echoaholic/mixin/` | Common mixins: the capture hooks (`BlockItemMixin`, `BucketItemMixin`, `PlayerMixin`, `ProjectileMixin`, `ServerPlayerGameModeMixin`), the `LevelStorageAccess` duck for the Create World handoff, and accessors/invokers. |
| `src/client/` | The Create World buttons (`GameTabMixin` adds **Echoaholic Mode** and **Echo Delay** below Difficulty, `CreateWorldScreenMixin` hands them to the new world), the echo tint (`EchoRenderTint`, `AvatarRendererMixin`, `LivingEntityRendererMixin`), the trail (`EchoTrailClient`) and the notification settings: `NotifyConfig` (loads/saves `config/echoaholic.json` via the pure `core/NotifySettings`), `NotifyClient` (payload receiver), `NotifySettingsScreen`, `ModMenuIntegration` (Mod Menu entrypoint only) and `NotifyCommand` (`/echoaholic-notify`). |
| `src/main/resources/assets/echoaholic/lang/` | `en_us.json` and `ru_ru.json` (same keys; see [Languages](#languages)). |
| `src/test/` | JUnit tests: the core (codecs, segments, ring buffer, schedule, budget, config, storage size) and `LangFileTest`. |
| `src/gametest/` | Server and client gametests. This is a separate test mod, `echoaholic-gametest`, and it is never packaged. |

## Behavior summary

- Echoaholic Mode and the Echo Delay are stored per world in `data/echoaholic/world.dat` (SavedData `echoaholic:world`), together with the other `EchoConfig` values. The Create World → Game buttons start ON with a 5 minute delay; worlds created elsewhere (dedicated servers) start OFF. Set the mode with that button or `/echoaholic on|off|status` (op level 2). There is no game rule.
- **Recording.** For each player in Survival or Adventure, `Recorder` feeds one `StreamRecorder`: sampled movement (a sample when the player moved more than 0.05 blocks or turned more than 2°, and at least once a second), pose changes, and the captured actions. The stream time T counts recorded ticks; it stands still while the player is offline, in Creative or Spectator, or while the mode is OFF. Every segment starts with a `Dimension` keyframe.
- **Spawning.** Echo #k is due when T ≥ k × delay (`EchoSchedule.plan`, at most one spawn per tick). It starts at the oldest retained tick (normally 0), so its lag is k × delay, and then owns its cursor, which advances one stream tick per server tick. A delay change only affects echoes that haven't spawned yet. At the cap (`maxEchoes`, 1–64) the smallest number is retired first. An echo whose cursor falls behind the ring buffer is retired. A killed echo is removed and its number is never reused.
- **Replay.** Each tick the manager checks that the echo reached the recorded position, runs that tick's actions through `ReplayHandlers`, and advances the cursor. A handler returns DONE, SKIPPED (a precondition failed: the action is consumed) or WAIT (budget refused: the cursor stalls and retries next tick). Break needs the same block state; place needs a replaceable, unobstructed spot and a credit (or TNT with `freeTnt`); attack hits the nearest `LivingEntity` within 2 blocks of the recorded target with a mob-attack damage source; buckets, fire, shears and bone meal follow the rules in the [root README](../README.md#what-echoes-replay). A Death action lies the echo down for 60 ticks while the cursor holds.
- **Budget and cheap mode.** `BudgetScheduler` hands out per-echo block ops and entity lookups, a global block-op pool and a separate global hazard pool (explosions, fire, fluids; a hazard op also costs a block op), in round-robin order. Echoes farther than `cheapModeDistance` from every player in their level snap along the path without physics and skip swings, held items, poses, sounds and the trail, but still act.
- **Freezing.** Owner offline, Creative or Spectator, `/echoaholic pause` or an unloaded chunk freezes an echo in place (after 200 unloaded ticks the entity is removed and later respawned from its state). While paused, T keeps running but no echo spawns. Mode OFF removes the entities and keeps their state; ON respawns them.
- **Dimensions.** Echoes never use portals. A recorded `Dimension` action removes the entity and spawns a new one in the target level.
- **Storage.** Segments are 1 minute (1200 ticks), Deflate-compressed, in `data/echoaholic/streams/<uuid>/<seq>.seg` with `index.bin`, written and deleted on the `Echoaholic-IO` thread. Echo entities are never saved to chunks. Measured by `SegmentSizeTest`: about 389 KiB per dense hour, 2.2 KiB per idle hour, 2.3 MiB for the default 6 h buffer.
- **Commands.** `/echoaholic` (status), `on|off|status`, `delay <1..120>`, `max <1..64>`, `list [player]`, `clear [player]` (removes the echoes and wipes the recording: T = 0, next echo #1), `pause|resume` and `config [<key> [<value>]]`. Everything needs op level 2 except `list` for your own echoes.

### Safety caps

All caps and defaults are in `core/EchoConfig` (javadoc per field), are saved per world and can be changed with `/echoaholic config <key> <value>`. The player-facing table with every key, default and range is in the [root README](../README.md#safety-caps). To add a cap, add the field, its constants, a `withX` method and a `Key` entry. SavedData stores the config by `Key` id, so old worlds get the default for a new key.

### Languages

- English (`en_us`) and Russian (`ru_ru`), in `src/main/resources/assets/echoaholic/lang/`. Every player-facing string (Create World buttons and tooltips, `/echoaholic` and `/echoaholic-notify` feedback, the list activities, the echo nametag, the joined/faded messages, the settings screen, the Mod Menu summary) is a translation key. Vanilla terms (ON/OFF on buttons, Done) come from the game's own translation.
- Server-side strings use `translatableWithFallback` with the English text, for players on vanilla clients, who have no mod lang files. The client recognises an echo by its nametag key `echoaholic.echo.name`.
- `LangFileTest` checks that `ru_ru.json` has exactly the keys of `en_us.json`, the same `%s`/`%1$s` placeholders per key and no empty values. Add a key to both files.

### Notification settings

- Each new echo shows an actionbar message (`👥 Echo #7 has joined you`) and plays a soft chime, and modded clients draw a faint cyan trail along the path each nearby echo will walk over the next 5 seconds. Each player can turn off any of the three. The settings are client-side and per player, stored in `config/echoaholic.json`: `{"notifySound": true, "notifyMessage": true, "showTrail": true}`. A missing or broken file means all are on; a broken file is left alone until the next change replaces it.
- With [Mod Menu](https://modrinth.com/mod/modmenu) installed (optional, `suggests`), Mods → Echoaholic → the config button opens the settings screen (**Echo sound** / **Echo message** / **Echo trail**, saved on every click).
- Without Mod Menu, use the client command `/echoaholic-notify status`, `/echoaholic-notify sound|message|trail` (switches it) or `/echoaholic-notify sound|message|trail on|off`. The root is `echoaholic-notify`, not `echoaholic notify`, so it cannot clash with the server's `/echoaholic` command.
- This needs the mod on both client and server: when the client has the mod, the server sends only the `echoaholic:notice` payload and the client shows/plays according to its settings. When the oldest echo fades at the cap, the payload carries a faded notice (message only, no sound). Trail paths come in `echoaholic:trail` payloads every 10 ticks for non-cheap echoes within 48 blocks. Players who join a server-only install (vanilla client) always get the default actionbar + sound, and no trail or tint.
