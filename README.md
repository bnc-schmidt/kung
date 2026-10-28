# Kung

A client-side Fabric mod with passive helpers for Hypixel SkyBlock: dungeon and boss
maps, run splits and statistics, slayer and garden helpers, hitboxes, HUD overlays and
quality-of-life chat tools.

Owner: [bnc-schmidt](https://github.com/bnc-schmidt)  
Official Discord Server: [KUNG](https://discord.com/invite/EDD4ujq3NM)

## Requirements

- Minecraft **26.1.2** or **26.2** with **Java 25**
- Fabric Loader 0.19.2 or newer, and Fabric API
- Optional: OneConfig and Mod Menu. Kung shows the same settings natively in both when
  they are installed, and works the same without them.

## Install

Drop the jar for your Minecraft version (`kung-26.1.2-<version>.jar` or
`kung-26.2-<version>.jar`) into your `mods` folder. Every feature ships switched
off, so a fresh install changes nothing until you turn something on.

## Using it

- `/kung` opens the settings, grouped into Dungeon, Garden, Hunting, Slayer, Util and Debug.
- `/kung hud` moves and scales every overlay that is switched on.
- `/kung log save` writes a diagnostic trace and prints its path, which is what to attach
  to a bug report.

## What it does

**Dungeon** — live dungeon map with room and secret state, boss maps, run splits with
personal bests, run and player reports, Wither Dragons helper for M7, Blood Rush and
Ice Spray highlights, colored F7/M7 pillars, mimic hints, crypt and death messages, and a
chat filter.

**Slayer, Garden and Hunting** — Tarantula egg sac prediction, 6th visitor alarm, feast
progress and Critter Safari uniques.

**Util** — hitboxes for any entity, SkyBlock mob or held item, sack tracker with goals,
performance HUD with TPS, FPS and ping plus 30 second graphs, bow draw indicator, Frozen
Blaze aura timer, Ragnarock Axe tick timer, lobby hop helper, superpairs helper, loadout auto close, chat commands,
chat emotes and custom sounds.

Everything stays client side and passive: Kung reads chat, packets and entities, and never
plays for you.

## Development

One source tree in `versions/mc26_1_2/src` builds for both Minecraft versions; each
`versions/mcX/src/version/java` only holds the API that differs. Build both jars from the
project root:

```powershell
./gradlew.bat build --console=plain
```

`build-and-copy-jar_26.1.2.bat` builds and copies the jar straight into a Modrinth profile.
Tests run with `./gradlew.bat :versions:mc26_1_2:test --console=plain`.

Contributor notes, the feature-to-code map and the per-feature documentation live in the
working copy under `docs/` and are not published here.
