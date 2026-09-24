<p align="center">
  <img src="https://raw.githubusercontent.com/nezo32/echoaholic/main/docs/branding/curseforge_logo.png" alt="Echoaholic logo" width="256">
</p>

<p align="center"><em>Everything you do comes back.</em></p>

**Echoaholic** turns your past into company. Everything you do is **recorded**, and every 5 minutes a new translucent **Echo** of you joins the world and **replays your whole history**, 5 minutes behind you. Echo #2 is 10 minutes behind, Echo #3 is 15, and so on. Echoes aren't just for show: they **really mine, build and fight**, and they never leave. Dig a tunnel, and a few minutes later a line of ghosts marches down it after you, pickaxes swinging.

A **Fabric mod for Minecraft Java 26.2–26.3**.

## Features

- 👥 **A crowd of you:** a new Echo joins every 5 minutes and replays everything you did, from the very start.
- ⛏️ **They do it for real:** echoes break blocks, place blocks, fight mobs, fire arrows, pour buckets and light TNT, just like you did.
- 🧱 **Fair building:** echoes only place blocks they've collected by mining. Tools and weapons are free ghost copies.
- ⚔️ **Watch your back:** echoes hit whatever stands where your target stood, and that can be you. Monsters hunt them too.
- ⏱️ **Your delay, your crowd:** Echo Delay at world creation or `/echoaholic delay <minutes>`, and up to `/echoaholic max 64` echoes per player. The oldest echo fades when a new one joins at the cap.
- ⚙️ **Toggle anywhere:** an ON/OFF button at world creation plus an operator command (`/echoaholic`) for existing worlds and servers. `/echoaholic pause` freezes every echo in place.
- 💬 **Clear feedback:** an actionbar message and a soft chime announce each new echo, and each one wears a nametag like `Echo #7 · nezo`.
- 🔕 **Your call on noise:** turn the join sound, the actionbar message or the ghost trail off (Mod Menu or `/echoaholic-notify`).
- 🚦 **Lag-safe:** per-echo and per-server action budgets, and echoes far from every player switch to a cheap mode. Nothing is skipped for lack of budget, it just waits its turn.
- 👻 **Works for everyone:** players without the mod see echoes as player-like mannequins with your skin. With the mod, echoes glow translucent cyan and show a trail of where they're about to walk.

## What it does

When the mode is on, the mod does the following for each player:

1. **Recording:** everything you do is recorded while you play in Survival or Adventure: walking, sneaking and sprinting, blocks you break and place, hits and shots, buckets, flint and steel, shears, bone meal, TNT, dimension changes, teleports and deaths. Chat, your inventory, menus and eating are never recorded. Creative, Spectator and logging off pause the recording.
2. **A new echo:** every 5 minutes of recorded play (the **Echo Delay**), a new Echo joins the world. Echo #1 replays your history from the very beginning, so it's always 5 minutes behind you. Echo #2 joins 5 minutes later and is 10 minutes behind, and so on.
3. **Replay, for real:** each echo walks your path and repeats what you did:
   - **Breaking:** only if the block is still the one you broke. Drops fall on the ground (as if mined with your tool, without its enchantments), and the echo gets a credit for every item that dropped.
   - **Placing:** only if the spot is free **and** the echo has collected that block. If it hasn't, it skips that placement. TNT is free by default.
   - **Fighting:** it hits the nearest living thing standing where your target stood, with the damage you dealt. That can be you, or another echo.
   - **Arrows, buckets, fire and TNT:** projectiles really fly (arrows can't be picked up), buckets really fill and pour, flint and steel really lights things.
4. **Life of an echo:** 20 health. Echoes can die to mobs, lava, TNT, the void or players, and a dead echo is gone for good. They never pick up items, sleep or eat, and they don't trigger pressure plates or tripwires. When you died, your echo lies down for 3 seconds, then carries on.
5. **The cap:** up to 32 echoes per player (operators can raise it to 64). When a new echo joins at the cap, the oldest one fades.
6. **Feedback:** the actionbar shows `👥 Echo #7 has joined you` with a soft chime, and each echo wears a nametag like `Echo #7 · nezo`. Each player can turn off the sound, the message, or the trail (see [Notification settings](#notification-settings)).

If the world has changed, echoes cope: they skip blocks that are no longer there, walk around what they can and wait where they're blocked. When the owner logs off, their echoes freeze in place until they're back.

## Notification settings

Every new echo shows an actionbar message and plays a soft chime, and with the mod installed each echo shows a faint cyan trail of the path it will walk over the next 5 seconds. Each player can turn off any of them:

- With [Mod Menu](https://modrinth.com/mod/modmenu) installed, open Mods → Echoaholic → the config button, and switch **Echo sound** / **Echo message** / **Echo trail**.
- Without Mod Menu, use the client command `/echoaholic-notify sound off`, `/echoaholic-notify message off`, `/echoaholic-notify trail off`, or `/echoaholic-notify status`.

Settings are stored on your computer in `config/echoaholic.json` and apply on any server that runs Echoaholic. Players who join without the mod on their client always get the default message and sound (and no trail).

## Commands

| Command | Who | What it does |
|---|---|---|
| `/echoaholic on\|off\|status` | operators | Turn the mode on or off for this world, or show it |
| `/echoaholic delay <minutes>` | operators | Set the Echo Delay, 1–120 (default 5) |
| `/echoaholic max <n>` | operators | Max echoes per player, 1–64 (default 32) |
| `/echoaholic list [player]` | anyone for themselves, operators for others | List echoes with what they're doing, how far behind they are, where they are and their health |
| `/echoaholic clear [player]` | operators | Remove a player's echoes and wipe their recording: the next echo is #1 again |
| `/echoaholic pause\|resume` | operators | Freeze or unfreeze every echo (no new ones join meanwhile). Recording continues |
| `/echoaholic config <key> [value]` | operators | Read or change the safety caps below |
| `/echoaholic-notify <sound\|message\|trail\|status> [on\|off]` | anyone (client, needs the mod) | Personal notification settings |

## Safety caps

All saved per world. Change them with `/echoaholic config <key> <value>`.

| Key | Default | Range | What it does |
|---|---|---|---|
| `delayMinutes` | 5 | 1–120 | Time between echoes, and how far behind each one is |
| `maxEchoes` | 32 | 1–64 | The oldest echo fades when a new one joins at the cap |
| `bufferHours` | 6 | 1–24 | Hours of recording kept. No echo can be further behind than this, and an echo that falls off the end fades |
| `echoBlockOpsPerTick` | 4 | 1–64 | Block changes one echo may make per tick |
| `echoEntityLookupsPerTick` | 2 | 1–32 | Attack and shear target searches one echo may make per tick |
| `globalBlockOpsPerTick` | 128 | 1–4096 | Block changes all echoes together may make per tick |
| `globalHazardOpsPerTick` | 2 | 1–64 | Explosions, fire and fluid placements of all echoes together per tick |
| `cheapModeDistance` | 128 | 16–1024 | Echoes farther than this from every player only move and act: no swings, sounds or trail |
| `triggerBlocks` | off | on/off | Whether echoes press pressure plates and trip tripwires |
| `freeTnt` | on | on/off | Whether echoes place TNT without having collected it |

Over budget, an action waits its turn: it's never dropped, the echo just falls a little further behind.

History is saved with the world, compressed, under `data/echoaholic/`: about 390 KiB per hour of busy play, so about 2.3 MB per player for the default 6 hours. Echoes don't count as entities in the world save: they're rebuilt from that data when the world loads.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2 or 26.3, and run the game on Java 25.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `echoaholic-<version>.jar` in your `mods/` folder.
   Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen. The mod is required on the server; on the client it adds the ghostly look, the trail and the settings.
3. Turn the mode on in one of two ways:
   - **New world:** Create World → Game tab → **Echoaholic Mode** is **ON** by default, with **Echo Delay** right under it (switch it off there for a normal world).
   - **Existing world or dedicated server:** an operator runs `/echoaholic on` (`/echoaholic status` shows the current state). Worlds made without the button (dedicated servers, other launchers) start with it off. In single-player this needs cheats: Allow Commands on, or Open to LAN with Allow Cheats on.

## Known quirks

- Echoes change your world for real. An echo can mine out the base you built after it passed by, or blow up the TNT you placed 10 minutes ago, again.
- Echoes can hurt you and each other: stand where you hit a zombie 5 minutes ago and you'll find out.
- An echo that's missing a block just skips that placement and carries on, so rebuilt structures may have gaps.
- Echoes don't use portals: when you changed dimension, your echo reappears on the other side. An echo that walks a bridge that's gone falls, into the void in the End.
- Echoes lag a bit further behind while they wait on the action budget, in unloaded chunks, behind a wall, or lying down for your deaths. Their number is how far behind they started, not an exact clock.
- Eggs, ender pearls, fireworks, bottles o' enchanting and fishing aren't replayed. A bucket of fish pours out as plain water, and cauldrons and beehives are left alone.
- Players without the mod see echoes as plain player-like mannequins (with your skin), without the cyan glow or trail.
- Lots of echoes in one place (dozens, all mining) is a lot of work for the server, even with budgets. Lower `/echoaholic max` on busy servers.

## Source

Source code, issues and releases: [github.com/nezo32/echoaholic](https://github.com/nezo32/echoaholic). MIT license.
