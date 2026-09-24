<p align="center">
  <img src="docs/branding/curseforge_logo.png" alt="Echoaholic logo" width="256">
</p>

<h1 align="center">Echoaholic</h1>

<p align="center"><em>Everything you do comes back.</em></p>

Echoaholic is a Minecraft mode that records everything you do. Every 5 minutes a translucent **Echo** of you joins the
world and replays your history from the start, for real: it mines, builds and fights. Echo #1 is 5 minutes behind you,
Echo #2 is 10 minutes behind, and so on, and they never leave.

It is a Fabric mod for Minecraft Java 26.2–26.3 (in [`fabric/`](fabric/)).

## What it does

When the mode is on, the mod does the following for each player:

1. **Recording:** while you play in Survival or Adventure, the mod records your movement (walking, sneaking, sprinting,
   swimming, elytra), blocks you break and place, melee hits, projectiles you fire, buckets, flint and steel and fire
   charges, shears, bone meal, TNT, dimension changes, teleports, respawns and deaths. Chat, your inventory, menus and
   eating are never recorded. Creative, Spectator and logging off pause the recording, and so does turning the mode
   off.
2. **A new echo:** after every **Echo Delay** (default 5 minutes) of recorded play, a new echo joins. Echo #k joins
   when you have k × delay of recorded play, and it starts at the beginning of your history, so it is k × delay behind
   you. Echo numbers keep going up and are never reused.
3. **Replay, for real:** each echo walks your path and repeats what you did at the moment you did it:
   - **Breaking:** only if the block is still the one you broke. The drops fall on the ground as if the block was mined
     with your tool without its enchantments. For each dropped item the echo gets one credit of that item.
   - **Placing:** only if the spot is free **and** the echo has a credit for that item, which the placement uses up.
     Without one, the placement is skipped. TNT is free by default (`freeTnt`).
   - **Fighting:** it hits the nearest living thing within 2 blocks of where your target stood, with the damage you
     dealt. That can be you or another echo.
   - **Projectiles, buckets, fire, shears, bone meal, TNT:** they happen for real (see
     [What echoes replay](#what-echoes-replay)).
4. **Life of an echo:** 20 health. Echoes can die to mobs, lava, TNT, the void or players, and monsters hunt them. A dead
   echo is gone for good and frees its slot. Echoes never pick up items, sleep, eat or earn advancements, and they
   don't press pressure plates or trip tripwires (`triggerBlocks`). When you died, your echo lies down for 3 seconds
   and then carries on.
5. **The cap:** at most 32 echoes per player (`/echoaholic max`, up to 64). When a new echo joins at the cap, the
   oldest one (smallest number) fades.
6. **Feedback:** the actionbar shows `👥 Echo #7 has joined you` with a soft chime, and each echo wears a nametag like
   `Echo #7 · nezo`. Each player can turn off the sound, the message or the trail (see
   [Notification settings](#notification-settings)).

Difficulty and hardcore settings are never changed. When the owner is offline, their echoes freeze in place until they
are back.

## Notification settings

Every new echo shows an actionbar message and plays a soft chime. With the mod on the client, echoes also show a faint
cyan trail of the path they are about to walk over the next 5 seconds. Each player can turn off any of the three:

- With [Mod Menu](https://modrinth.com/mod/modmenu) installed, open Mods → Echoaholic → the config button, and switch
  **Echo sound** / **Echo message** / **Echo trail**.
- Without Mod Menu, use the client command `/echoaholic-notify sound off`, `/echoaholic-notify message off`,
  `/echoaholic-notify trail off`, or `/echoaholic-notify status`.

Settings are stored on your computer in `config/echoaholic.json` and apply on any server that runs Echoaholic. Players
who join without the mod on their client always get the default message and sound, and no trail. When the oldest echo
fades at the cap, the owner gets an `Echo #1 has faded` message without a sound.

## At a glance

| | Echoaholic (Fabric) |
|---|---|
| Game versions | Minecraft Java 26.2–26.3 (one jar), Java 25 |
| Where it runs | Required on the server (or in single-player). Optional on the client, where it adds the tint, the trail and the settings |
| Turning it on | **Echoaholic Mode** ON/OFF button on the Create World → Game tab, right below Difficulty (saved with the world), or `/echoaholic on` |
| Default | On for new worlds (Create World button); off for worlds made without it, e.g. dedicated servers |
| Echo Delay | **Echo Delay** button right under the mode (1, 2, 3, 5, 10, 15, 20, 30 or 60 min, default 5), or `/echoaholic delay <1–120>` |
| Echoes per player | 32 by default, `/echoaholic max <1–64>` |
| Toggling later | `/echoaholic [on\|off\|status]`, operators only (permission level 2, like /gamerule) |
| Turning it off | Echoes disappear and recording stops. Their state is kept: `/echoaholic on` brings them back where they were |
| Pausing | `/echoaholic pause` freezes every echo in place and holds back new ones. Recording continues, so the echoes just fall further behind |
| What echoes look like | Modded clients: your skin tinted translucent cyan (older echoes are fainter) plus the trail. Vanilla clients: a plain player-like mannequin with your skin. Everyone sees the nametag |
| Notification settings (per player) | Mod Menu → Echoaholic → config screen, or the client command `/echoaholic-notify <sound\|message\|trail\|status> [on\|off]` (needs the mod on the client; saved in `config/echoaholic.json`) |
| Achievements | Unaffected |

## What echoes replay

"Credit" means the echo's own materials, which it earns by breaking blocks and filling buckets. Tools and weapons are
free ghost copies: the echo holds a copy of the item you used and never runs out.

| You did | The echo does | Needs |
|---|---|---|
| Walk, sneak, sprint, swim, glide | Walks your path with normal collision, jumps up blocks, swims and glides freely | – |
| Break a block | Breaks it only if it is still the same block (state included). Drops fall on the ground as if mined with your tool without enchantments | Nothing. Gives 1 credit per dropped item |
| Place a block | Places it only if the spot is replaceable, free of entities and the block can stay there | 1 credit of the item (TNT is free while `freeTnt` is on) |
| Hit a mob or player | Hits the nearest living thing (other than itself) within 2 blocks of where your target stood, with the damage you dealt | – |
| Shoot or throw (bows, crossbows, tridents, snowballs, potions, wind charges, …) | Fires the same projectile with the same velocity. Arrows can't be picked up | – |
| Fill a bucket | Removes the source block | Gives 1 credit of the filled bucket |
| Empty a bucket (water, lava, powder snow) | Places the fluid | 1 credit of that filled bucket |
| Flint and steel / fire charge | Lights fire, campfires, candles and so on, or ignites TNT | Fire charge: 1 credit |
| Shears | Carves pumpkins and shears blocks and sheep (and other shearable mobs) | – |
| Bone meal | Uses bone meal on the block | 1 credit |
| Change dimension | Reappears in the new dimension | – |
| Teleport or respawn | Jumps to the new position | – |
| Die | Lies down for 3 seconds, then carries on | – |

Not recorded, so never replayed: chat, inventory and menus, eating, eggs, ender pearls, bottles o' enchanting,
fireworks, fishing, cauldrons, beehive shearing and the mob in a fish (or axolotl, tadpole) bucket.

## Safety caps

Every setting and cap lives in one class, [`core/EchoConfig`](fabric/src/main/java/dev/echoaholic/core/EchoConfig.java),
and is saved per world. Operators read and change them with `/echoaholic config <key> <value>` (`/echoaholic config`
lists them all, `/echoaholic config <key>` shows one). Values outside the range are clamped, and switches accept
`true`/`false`, `on`/`off` or `1`/`0`.

| Key | Default | Range | What it does |
|---|---|---|---|
| `enabled` | on for Create World worlds, off otherwise | on/off | Echoaholic Mode for this world (also `/echoaholic on\|off`) |
| `delayMinutes` | 5 | 1–120 | Echo Delay: echo #k joins after k × delay minutes of recorded play (also `/echoaholic delay`) |
| `maxEchoes` | 32 | 1–64 | Most echoes alive per player. When a new one joins at the cap, the oldest (smallest number) fades (also `/echoaholic max`) |
| `bufferHours` | 6 | 1–24 | Hours of each player's recording kept on disk. No echo can be further behind than this |
| `echoBlockOpsPerTick` | 4 | 1–64 | Block changes one echo may make per tick. Extra actions wait |
| `echoEntityLookupsPerTick` | 2 | 1–32 | Entity searches (attack and shear targets) one echo may make per tick |
| `globalBlockOpsPerTick` | 128 | 1–4096 | Block changes all echoes of the server together may make per tick |
| `globalHazardOpsPerTick` | 2 | 1–64 | Explosions, fire and fluid placements of all echoes together per tick |
| `cheapModeDistance` | 128 | 16–1024 | Echoes farther than this many blocks from every player only move and act: no swings, poses, held items, sounds or trail |
| `triggerBlocks` | false | on/off | Whether echoes press pressure plates and trip tripwires |
| `freeTnt` | true | on/off | Whether echoes place recorded TNT without having a credit for it |
| `paused` | false | on/off | Replay and new echoes frozen for everybody; recording continues (also `/echoaholic pause\|resume`) |

When an action is over budget, it waits: the echo's replay stops at that action and tries again next tick, and the
echoes take turns so that none of them starves. Nothing is dropped; the echo just falls a little further behind.

Measured server cost (`EchoPerfGameTests`: 32 echoes replaying a dense mining session, 200 ticks, GitHub-class CI runner): the whole replay loop takes **about 1.5–2.5 ms per tick on average** (p95 3–4.3 ms, worst tick under 15 ms) on both 26.2 and 26.3, with at most 17 of the 128 global block operations used per tick and nothing deferred. The test fails if the average goes above 3 ms or any tick above 20 ms.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/echoaholic` or `/echoaholic status` | operators | Show whether the mode is on, the Echo Delay and the cap |
| `/echoaholic on\|off` | operators | Turn the mode on or off for this world |
| `/echoaholic delay <minutes>` | operators | Set the Echo Delay, 1–120 (default 5). Only echoes that haven't joined yet use the new delay |
| `/echoaholic max <n>` | operators | Max echoes per player, 1–64 (default 32). Lowering it retires the oldest echoes |
| `/echoaholic list` | anyone | List your own echoes: number, what each is doing, how far behind it is, where it is and its health |
| `/echoaholic list <player>` | operators | The same for another player |
| `/echoaholic clear [player]` | operators | Remove a player's echoes **and** wipe their recording. The next echo is #1 again, one delay later |
| `/echoaholic pause\|resume` | operators | Freeze or unfreeze every echo. New echoes wait too. Recording continues |
| `/echoaholic config [<key> [<value>]]` | operators | List, read or change the [safety caps](#safety-caps) |
| `/echoaholic-notify <sound\|message\|trail\|status> [on\|off]` | anyone (client, needs the mod) | Personal notification settings. Without `on\|off` it switches the setting |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2 or 26.3, and run the game on Java 25.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `echoaholic-<version>.jar` in your `mods/` folder. Get the
   jar from [GitHub releases](https://github.com/nezo32/echoaholic/releases) or CurseForge.
   Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen. The mod is required on the server;
   on the client it adds the ghostly look, the trail and the settings.
3. Turn the mode on in one of two ways:
   - **New world:** Create World → Game tab → **Echoaholic Mode** is **ON** by default, with **Echo Delay** right under
     it (switch the mode off there for a normal world).
   - **Existing world or dedicated server:** an operator runs `/echoaholic on` (`/echoaholic status` shows the current
     state). Worlds made without the button (dedicated servers, other launchers) start with it off. In single-player
     this needs cheats: Allow Commands on, or Open to LAN with Allow Cheats on.

## Known quirks

- **Redstone and pressure plates:** echoes walk over pressure plates and through tripwires without triggering them
  (turn `triggerBlocks` on to change that). Buttons, levers, doors, trapdoors and gates you used are not replayed, so
  an echo can be stopped by a door you opened and closed behind you.
- **The End:** echoes can't use portals. End portals, end gateways, nether portals: when your recording changes
  dimension, the echo disappears and reappears in the new dimension, at the spot where you arrived. An echo that follows
  a bridge that is gone falls into the void and is gone for good. The dragon and endermen can kill echoes like anything
  else, and a dragon fight replays poorly, because the dragon is somewhere else by then.
- **Changed terrain:** echoes deal with the world as it is now.
  - A break is skipped when the block is gone or is a different block.
  - A placement is skipped when the spot is occupied or the echo has no credit for the item, so rebuilt structures
    can have gaps.
  - A blocked echo stops and waits (`waiting` in `/echoaholic list`), and walks on once the way is clear. If it has
    been stuck for 5 seconds and the spot it should be at is free, it teleports there.
- **Lag grows:** budget waits, blocked paths, loading segments and paused echoes all add lag. An echo's number tells
  you how far behind it started, not an exact clock. Each of your deaths adds 3 seconds (the echo lies down while the
  replay waits).
- **Cheap mode:** echoes farther than 128 blocks from every player snap along your path without physics, are silent,
  and don't swing, change pose or hold items. They still break, place and hit.
- **Unloaded chunks:** an echo in an unloaded chunk is frozen. After 10 seconds its entity is removed, and it comes back
  where it was when the chunk loads again.
- **The 6 hour horizon:** only the last `bufferHours` (6 h) of each recording are kept. No echo can be further behind
  than that: once k × delay is more than 6 hours (for example 64 echoes at a 10 minute delay), a new echo starts about
  6 hours behind you instead of k × delay, and an echo whose part of the recording has been dropped fades. With the
  default 5 minutes and 32 echoes, the oldest echo is 2 h 40 min behind, well inside the horizon.
- **Your own death:** replayed as a 3 second collapse that adds 3 seconds of lag. The echo doesn't lose anything.
- **TNT:** echoes place recorded TNT for free by default (`freeTnt`), so the TNT you set off 10 minutes ago goes off
  again. Turn `freeTnt` off to make them need a TNT credit, like any other block.
- **Buckets:** a bucket of fish (or axolotl, tadpole) replays as a plain water bucket. Filling and emptying cauldrons
  and shearing beehives are not replayed.
- **Not replayed:** eggs, ender pearls, fireworks, bottles o' enchanting and fishing.
- **Vanilla clients** see echoes as plain player-like mannequins with the owner's skin and the nametag, without the
  cyan tint or the trail.
- **Hardcore:** unchanged. Echoes don't change difficulty or hardcore rules, and they can still hurt you.

## Storage

Echoaholic keeps everything in the world folder, not in player data:

| Path (in the world folder) | Contents |
|---|---|
| `data/echoaholic/world.dat` | Settings (the [safety caps](#safety-caps)), per-player recording state (recorded time, next echo number) and every echo: position, health, replay position, credits, and the owner's name and skin so echoes look right while the owner is offline |
| `data/echoaholic/streams/<uuid>/<seq>.seg` | One file per minute of recorded play, compact binary, Deflate-compressed, written as soon as the minute is over |
| `data/echoaholic/streams/<uuid>/index.bin` | The index of that player's segments |

Echoes are never saved in chunks: they are rebuilt from `world.dat` when the world loads. Files are written on a
background thread, and segments older than `bufferHours` are deleted. `/echoaholic clear` deletes that player's
files.

Measured size (the core `SegmentSizeTest`, a synthetic worst case): about **389 KiB per hour** of dense play (walking or sprinting every tick,
a block broken every half second, a block placed every 2 seconds, a hit every 5 seconds, a shot every 30 seconds),
about **2.2 KiB per idle hour**, so about **2.3 MiB per player** for the default 6 hour buffer. Recorded in-game streams are smaller: the gametests
measured about 82 KiB per hour of dense mining and about 42 KiB per hour of ordinary walking with a block action every
~10 s.

## Repository layout

| Path | Contents |
|---|---|
| `fabric/` | The Fabric mod (Gradle), see [fabric/README.md](fabric/README.md) |
| `.github/workflows/` | CI (`ci.yml`), release (`release.yml`) and the reusable `reusable-*.yml` workflows |
| `scripts/` | CurseForge upload script and its tests |
| `docs/ci/` | Release runbook and reusable pipeline docs |
| `docs/branding/` | Logo, palette, player-facing strings, CurseForge description |

## Development

The mod needs JDK 25. Gradle can also run on Java 21 and download a JDK 25 toolchain.

```bash
cd fabric
./gradlew build          # Minecraft 26.3: compile, JUnit, server GameTests; jars in build/libs/
./gradlew clean build -Pmc=26.2   # the same against 26.2
```

One jar runs on both 26.2 and 26.3. The Create World buttons and the notification settings are covered by client
GameTests (`./gradlew runClientGameTest`). They need a display (for example Xvfb), so `build` and CI don't run them.

Branch names, PR rules and the full list of local checks are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Releasing

To release, push an annotated `vX.Y.Z` tag on a commit of `main` (pre-releases use `-alpha.N`, `-beta.N` or `-rc.N`).
`release.yml` then does the rest:

1. Builds and tests the jar, stamping the tag's version into it.
2. Creates the GitHub release with notes generated from PR titles and labels.
3. Uploads the jar (with the sources jar) to CurseForge.

Don't edit the version in `fabric/gradle.properties` by hand. The tag sets the version.

The CurseForge upload needs the repository secret `CURSEFORGE_TOKEN` and the repository variable
`CURSEFORGE_PROJECT_ID` (when it is empty, the CurseForge step is skipped). Optional variables:
`CURSEFORGE_GAME_VERSIONS` (default `26.2,26.3,Fabric,Java 25,Client,Server`) and `CURSEFORGE_ENVIRONMENT` (default
`Echoaholic`).

- Maintainer runbook: [docs/ci/RELEASING.md](docs/ci/RELEASING.md)
- How the reusable pipeline works and how other projects can use it:
  [docs/ci/REUSABLE_RELEASE_PIPELINE.md](docs/ci/REUSABLE_RELEASE_PIPELINE.md)

## License

[MIT](LICENSE) © 2026 nezo
