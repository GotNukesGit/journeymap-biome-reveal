# Biome Reveal

A small companion mod for **JourneyMap 5.2.19 FairPlay (GTNH 1.7.10)** that shows the
**biome name + BiomeDictionary tags** on the fullscreen map for **any chunk you've
discovered** — not just the loaded chunks around you.

Hover anywhere on the map that you've previously explored and the block-info label reads:

```
1234, -560  Birch Forest [FOREST, HILLS, MAGICAL]
```

Tags come straight from Forge's `BiomeDictionary` at runtime — the authoritative
source, no wiki lookups. (`BiomeReveal.describe(BiomeGenBase)` is reusable if you
ever want to dump the whole biome→tags table.)

**Singleplayer backfill:** on a local/singleplayer world, the mod also reads
biomes straight from the save's region files on disk (a one-time scan when the
world first loads). That means your *entire already-explored map* works
immediately — not just chunks you load after installing. This is local-only: on
a remote multiplayer server the region files live on the server, so there's
nothing on the client to backfill from, and it's limited to chunks you load
after installing.

## Why a companion mod and not an addon?

This JourneyMap build has **no client plugin API** (no `journeymap.client.api`,
no `IClientAPI`), so there's no official extension point. It also keeps **no
persistent biome data**: its `ChunkMD` cache is `expireAfterAccess(30s)` and the
map tiles on disk are rendered PNGs, not biome IDs. And JourneyMap deliberately
**won't** show a biome for an unloaded chunk, because on the client
`World.getBiomeGenForCoords` falls back to a *seedless* `WorldChunkManager` and
returns the wrong biome.

So Biome Reveal does two things:

1. **Records biomes as you explore** into its own tiny persistent index.
2. **Rewrites the hover label** (via one Mixin) to read from that index, so
   discovered-but-unloaded chunks show their real biome + tags.

## How it works

- **`BiomeIndex`** — persistent store, `byte[256]` (one biome id per column) per
  chunk, grouped into 32×32-chunk regions, one gzip file per region at
  `<.minecraft>/biomereveal/<worldId>/DIM<dim>/r.<rx>.<rz>.bz`. Writes are
  deduped and diffed so re-recording the same chunk is free.

- **`BiomeReveal`** (`@Mod`) — records biomes on a **periodic sweep of loaded
  chunks around the player** (radius 10, every ~1s), *not* on `ChunkEvent.Load`.
  This is deliberate: on the **client**, `ChunkEvent.Load` fires *before*
  `fillChunk` populates the biome array, so the load event would capture empty
  data. A loaded (non-`EmptyChunk`) client chunk is always fully populated, so
  the sweep always reads real biomes. Saves every ~30s and on world unload.

- **`MixinBlockInfoLayer`** — a single `@Redirect` on the
  `BlockInfoLayer$BlockInfoStep.update(String,double,double)` call inside
  `onMouseMove`. Both the loaded and unloaded branches of `onMouseMove` route
  through that one call, so one redirect covers every case. It rebuilds the
  label from the hovered coord (index first, live biome as a fallback for
  currently-loaded chunks) and forwards it. Rebuilding from the coord — instead
  of appending to the incoming text — keeps the "coord unchanged" branch
  idempotent, so tags never double-append.

- **`BlockInfoStepInvoker`** — an `@Invoker` mixin that exposes the
  package-private inner `update(...)` so the redirect can forward to it without
  reflection or an access transformer.

- **`BiomeRevealCore`** — a minimal `IFMLLoadingPlugin` so the jar is a
  recognized coremod and its `MixinConfigs` manifest attribute is picked up by
  the GTNH mixin loader (UniMixins) — the same way JourneyMap's own jar loads
  its mixins.

## Requirements

- Minecraft 1.7.10, Forge (GTNH build)
- **JourneyMap 5.2.19 FairPlay** (the exact build these targets were read from)
- **UniMixins** (GTNH's SpongePowered Mixin `0.8.5-GTNH` loader) — already
  present in GTNH 2.9

## Build

This project is fully configured and verified — plugin version, GTNH Maven repo,
UniMixins dependency, JDK toolchains, jar manifest, and the Gradle wrapper are
all in place. The one heavy step (decompiling Minecraft once) needs a normal
multi-core machine; it won't finish on a tiny single-core box.

There are two ways to get the jar. If you don't have a dev environment, use
Option A — it needs nothing installed on your computer.

### Option A — Build in the cloud with GitHub Actions (no local setup)

1. Put your JourneyMap jar in `libs/`, named exactly
   `journeymap-1_7_10-5_2_19-fairplay.jar`.
2. Create a new GitHub repository (private is fine) and upload this whole
   folder to it — including `libs/` with the JourneyMap jar and the
   `.github/` folder.
3. GitHub automatically runs the build (see the **Actions** tab). It takes a
   few minutes. When it finishes with a green check, open that run and download
   the **BiomeReveal-jar** artifact at the bottom.
4. Unzip it and drop `BiomeReveal-0.1.0.jar` into `.minecraft/mods/` next to
   JourneyMap.

That's it — GitHub installs Java and runs everything; you never touch a
terminal. The included `.github/workflows/build.yml` handles it.

### Option B — Build locally

Requirements: install **Adoptium Temurin 21** (https://adoptium.net). Your
Java 25 can stay your default — the build just needs a 21 (the 1.7.10 toolchain
doesn't support 25). The build auto-fetches the JDK 8 + 17 it uses internally.

1. Put your JourneyMap jar in `libs/` as above.
2. From this folder, run the wrapper pointed at Java 21:

   **Windows (PowerShell):**
   ```
   $env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot"
   .\gradlew.bat build
   ```
   **Linux/macOS:**
   ```
   JAVA_HOME=/path/to/jdk-21 ./gradlew build
   ```

3. The first build downloads Minecraft/Forge and decompiles Minecraft once (a
   few minutes; cached after). The jar lands in `build/libs/` — take the plain
   `BiomeReveal-0.1.0.jar` (not `-dev` or `-sources`) and drop it into
   `.minecraft/mods/`.

### Required jar manifest attributes

`build.gradle` sets these (mirrors JourneyMap's own manifest):

```
FMLCorePlugin: com.pronghorn.biomereveal.BiomeRevealCore
FMLCorePluginContainsFMLMod: true
MixinConfigs: mixins.biomereveal.json
ForceLoadAsMod: true
```

## Notes / tuning

- `SWEEP_RADIUS`, `SWEEP_TICKS`, `SAVE_TICKS` in `BiomeReveal` control how far/
  how often chunks are recorded and saved. Bigger radius = more coverage as you
  fly, at a small per-second cost.
- The label format is `"x, z  Biome [TAGS]"`. If you'd rather keep JourneyMap's
  richer coordinate/Y formatting for loaded chunks, change the return in
  `BiomeReveal.enrich` — the tags append cleanly onto any prefix.
- `remap = false` is correct on both mixins: `onMouseMove` and `update` are
  JourneyMap methods (not obfuscated), and their descriptors reference
  deobfuscated Minecraft class names that are identical at runtime, so no refmap
  remapping is needed for the targets. The shipped `refmap.json` is empty for
  that reason; the Mixin annotation processor will regenerate it on build.
