# What You Want To Find (WYWTF)

A Fabric mod for Minecraft 26.x (Loader 0.19.x+, Java 25) that turns the
**Seed** field in the world creation menu into a natural-language search bar.

## What it does

Instead of typing a numeric seed, you can write:

```
village near warm ocean
деревня возле теплого океана
spawn me next to a blacksmith by the ocean
mansion dark forest
jungle temple
ancient city
```

The mod recognizes keywords in **Russian and English**, runs a multi-threaded
seed search, then creates the world with the found seed and shifts the spawn
point as close as possible to the discovered location.

If the Seed field contains a plain numeric seed or a string with no recognized
keywords, the mod does not intervene.

## Features

- Natural-language seed search
- Bilingual dictionary (RU + EN) with synonyms
- Multi-threaded search via `ExecutorService`
- Stride+offset distribution — threads never overlap
  (`seed = threadIndex + i * threadCount`)
- Atomic progress counters — no GC pressure in the hot loop
- Daemon threads with reduced priority (don't starve the render thread)
- Live search GUI with metrics:
  - Query text
  - Checked seeds count
  - Discarded seeds count
  - Active thread count
  - Elapsed time
  - Instant + average speed (seeds/sec)
  - CPU usage estimate
  - Progress bar
  - Current best candidate
  - Cancel button
- Extensible dictionary — add biomes/structures/synonyms without touching the parser
- Interface-driven architecture (BiomeChecker, StructureChecker, WorldContextFactory)

## Architecture

```
com.wywtf/
├── WYWTFClient                — entry point (ClientModInitializer)
├── core/                      — domain model, no Minecraft dependency
│   ├── KeywordDictionary      — keyword dictionary + synonyms (RU+EN)
│   ├── QueryParser            — natural-language parser → ParsedQuery
│   ├── ParsedQuery            — immutable parse result
│   ├── SearchConfig           — search settings (thread mode, radius, limit)
│   ├── SearchResult           — result (seed + spawn coords)
│   └── SearchProgress         — thread-safe metrics for GUI
├── search/                    — seed search
│   ├── WorldContext           — per-seed world context
│   ├── WorldContextFactory    — context factory interface
│   ├── BiomeChecker           — biome check interface
│   ├── StructureChecker       — structure check interface
│   ├── SpawnFinder            — final spawn point selector
│   ├── SeedValidator          — atomic per-seed validation
│   ├── SearchWorker           — single thread (stride+offset iteration)
│   ├── SeedSearcher           — ExecutorService coordinator
│   ├── VanillaBiomeChecker    — impl over BiomeSource
│   ├── VanillaStructureChecker— impl over ChunkGenerator
│   └── VanillaStructurePlacementAccess — accessor stub
├── world/                     — world creation
│   ├── PendingWorldCreation   — seed/spawn mailbox between GUI and mixins
│   ├── WorldCreator           — public API for creating the world with a seed
│   └── SpawnAdjuster          — spawn shift scheduler
├── client/
│   └── SearchScreen           — search metrics GUI
└── mixin/
    ├── CreateWorldScreenMixin           — intercepts onCreateWorld
    └── WorldGenSettingsComponentMixin   — substitutes the seed
```

## Multi-threading

- Uses `Executors.newFixedThreadPool(N)`.
- Each thread iterates its own arithmetic progression:
  `seed = threadIndex + i * threadCount`, where `i = 0, 1, 2, ...`
- Threads **never** overlap.
- Thread count depends on `SearchConfig.Mode`:
  - `AUTO` — 65% of CPU (default)
  - `ECONOMY` — 25%
  - `HIGH` — 75%
  - `MAX` — 100%
- All counters use `AtomicLong` / `AtomicInteger`.
- The Minecraft main thread is never blocked.
- Worker threads run at reduced priority to avoid preempting the render thread.

## Search algorithm

For each seed:

1. Build a `WorldContext` (BiomeSource + ChunkGenerator for that seed).
2. If the query contains structures — iterate chunks within
   `searchRadiusChunks` (default 96) and for each chunk check
   `structurePlacement.isStructureChunk(seed, cx, cz)`.
   This is an O(1) hash — no chunk generation.
3. If a structure is found — verify biomes around it within
   `biomeCheckRadiusChunks` (default 16).
4. If everything matches — `SpawnFinder` picks spawn coordinates
   (center of the structure) and returns a `SearchResult`.

## GUI

After clicking "Create World", `SearchScreen` opens with metrics:

- Query
- Checked seeds count
- Discarded seeds count
- Thread count
- Elapsed time
- Instant speed (seeds/sec)
- Average speed
- CPU usage (estimate)
- Progress bar (against seed limit)
- Current candidate
- **Cancel** button

## Building

```bash
gradle wrapper --gradle-version 8.10   # generate wrapper (not shipped in source archive)
./gradlew build
```

The ready-to-use jar will appear at `build/libs/wywtf-1.0.0.jar`.

## Installation

1. Install Fabric Loader 0.19.0+ for Minecraft 26.x.
2. Install Fabric API.
3. Copy the jar into your `mods/` folder.

## Extending

### Add a new biome

```java
WYWTFClient.dictionary()
    .register(new KeywordDictionary.Entry(
        "mymod:my_biome",
        KeywordDictionary.Category.BIOME,
        "my biome"
    ), "synonym1", "synonym2");
WYWTFClient.dictionary().rebuildIndex();
```

### Add a new structure

```java
WYWTFClient.dictionary()
    .register(new KeywordDictionary.Entry(
        "mymod:my_structure",
        KeywordDictionary.Category.STRUCTURE,
        "my structure"
    ), "synonym");
WYWTFClient.dictionary().rebuildIndex();
```

The structure must be present in `Registry<Structure>`.

### Add a synonym

```java
WYWTFClient.dictionary()
    .registerSynonym("sea", "minecraft:ocean");
WYWTFClient.dictionary().rebuildIndex();
```

## Notes on 26.x

In Minecraft 26.x and Fabric Loader 0.19.x+:

- **Yarn mappings are no longer needed.** Official Mojang mappings are used
  (`loom.officialMojangMappings()` in `build.gradle`).
- **Java 25** is the minimum required version.
- Some method names may slightly differ from those mentioned in comments —
  spots marked with `// 26.x:` should be verified against the actual
  Minecraft source.

## Roadmap

- [ ] JSON synonym dictionary (`assets/wywtf/dict.json`)
- [ ] Localization (more languages)
- [ ] Public API for other mods (Fabric Networking + custom events)
- [ ] Favorite queries (saved in config)
- [ ] History of found seeds
- [ ] Real block check for objects (water/lava/tree)
- [ ] Mixin-Accessor for `StructurePlacement.isStructureChunk`
- [ ] Mixin on `ServerLevel` to shift spawn point
- [ ] Config screen (AUTO/ECONOMY/HIGH/MAX mode switch)
- [ ] Unit tests for QueryParser / KeywordDictionary

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Pull requests are welcome!

## Code of Conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](SECURITY.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This project is distributed under the Apache License, Version 2.0, which is a
permissive license similar to MIT but includes an explicit grant of patent
rights from contributors to users. See the full text in [LICENSE](LICENSE)
for the terms and conditions. A human-readable summary is available at
https://choosealicense.com/licenses/apache-2.0/.
