# What you Want to Find (WyWF)

A client-side Fabric mod for Minecraft 26.x (tested on 26.2; Fabric Loader 0.19+,
Java 25) that turns the **Seed** field in the world-creation screen into a
natural-language search bar. Instead of a number, you describe the world you want
and the mod searches for a seed that matches — fully offline, without generating
chunks.

## What it does

Type a description into the Seed field, for example:

```
village near warm ocean
деревня возле теплого океана
mansion dark forest
desert temple in desert
near deep dark
```

When you press **Create New World**, the mod:

1. Parses the text. If it contains no recognized keywords (or is a plain number),
   nothing happens and the world is created normally.
2. Otherwise it opens a search screen and looks for a seed whose world — around
   the origin `(0, 0)` — matches your description.
3. When a seed is found, it fills the Seed field with that number and creates the
   world as usual.

Keywords are recognized in **Russian and English**.

## Query language

A query is a list of keywords, each optionally preceded by a modifier:

| Modifier (EN / RU)                     | Meaning                                   |
|----------------------------------------|-------------------------------------------|
| *(none)*                               | present within the default radius         |
| `near` / рядом, около, возле…          | within ~200 blocks                        |
| `in`, `on` / в, на…                    | within ~64 blocks (right where you spawn) |
| `far` / далеко, вдали…                 | far away (~1000–2000 blocks)              |
| `some` / несколько, много…             | several of them nearby (structures)       |
| `under` / под, снизу                   | the surface biome at that spot            |
| `no`, `not`, `without` / нет, не, без  | must **not** be present                   |

Keywords fall into three categories:

- **Biomes** — `warm ocean`, `desert`, `dark forest`, `deep dark`, `lush caves`, …
- **Structures** — `village`, `mansion`, `desert temple`, `monument`, …
- **Objects** — `tree`, `water`, `lava` (recognized, but not searchable yet — ignored)

The dictionary lives in `KeywordDictionary` and holds many synonyms per keyword.
Multi-word keywords win over shorter ones (`dark forest` is matched as the
dark-forest biome, not `forest`).

## How the search works

The search runs on a fixed thread pool (`SeedSearcher` + `SearchWorker`) and
never blocks the game thread. For every seed a `WorldContext` is built and
checked by `SeedValidator`:

- **Structures** are located from placement math only
  (`RandomSpreadStructurePlacement.getPotentialStructureChunk`) — no chunk
  generation. Where a structure requires a specific biome (e.g. a plains
  village), the biome at that spot is verified too.
- **Biomes** are read from the vanilla `MultiNoiseBiomeSource` using a climate
  sampler (see below). Cave biomes (`deep dark`, `lush caves`, `dripstone
  caves`) are sampled underground; all others at the surface.

### Two search strategies

- **With a structure term** — structure placement depends only on the low 48
  bits of the seed, so the space is split: an outer loop over the 48-bit base
  and an inner loop over the high 16 bits. Before scanning the 65 536 inner
  seeds, a cheap prefilter checks whether the required structure can be placed
  near the origin at all, and skips the whole group if not.
- **Biome-only** — a simple linear scan outward from seed `0`.

Work is divided across threads with no overlap, and progress counters use atomics.

### Fast climate sampling

Biome lookups need a `Climate.Sampler`. Building the full vanilla `RandomState`
per seed is expensive, so `ReusableClimateSampler` builds the (seed-independent)
climate density-function graph **once per thread** and, for each new seed, only
re-creates the six climate noises. The results are **bit-identical** to a real
`RandomState` (verified by tests) while being several times faster.

## Configuration

Defaults (`SearchConfig`):

- Threads: `MAX` (all CPU cores). Other modes: `HIGH` 75%, `AUTO` 65%,
  `ECONOMY` 25%.
- Structure search radius: 40 chunks.
- Biome check radius: 16 chunks, sampled every 4 chunks.
- Seed limit: unbounded.

## Links

- Source: https://github.com/mermagudyan/WyWF
- Issues: https://github.com/mermagudyan/WyWF/issues

## Project layout

```
com.wywf/
├── WYWFClient                     — client entry point
├── core/                          — parsing & model (no Minecraft dependency)
│   ├── KeywordDictionary          — keywords + synonyms (RU + EN)
│   ├── QueryParser / ParsedQuery  — text → structured query
│   ├── Modifier                   — near / in / on / some / far / under / never
│   ├── SearchConfig               — threads, radii, limits
│   ├── SearchResult               — the found seed
│   └── SearchProgress             — thread-safe metrics for the GUI
├── search/                        — the seed search
│   ├── SeedSearcher               — thread-pool coordinator
│   ├── SearchWorker               — one worker (48/16 split or linear scan)
│   ├── SeedValidator              — checks one seed against the query
│   ├── WorldContext(+Factory)     — per-seed biome source + placements
│   ├── BiomeChecker / VanillaBiomeChecker
│   ├── StructureChecker / VanillaStructureChecker
│   ├── MinecraftWorldContextFactory
│   └── ReusableClimateSampler     — fast, exact climate sampler
├── world/                         — WorldCreator, PendingWorldCreation
├── client/SearchScreen            — live search GUI (progress + cancel)
└── mixin/                         — CreateWorldScreen hook + seed-field length fix
```

## Limitations

- **Strongholds are not supported.** They use concentric-ring placement, which
  this mod does not compute yet — stronghold queries won't match.
- **Objects** (`tree`, `water`, `lava`) are recognized but not searched.
- Matches are found around the origin `(0, 0)`; the world spawn is not moved.

## Building

```bash
./gradlew build
```

Requires JDK 25. The jar is produced in `build/libs/`.

## Installation

1. Install Fabric Loader 0.19+ for Minecraft 26.x (tested on 26.2) and Fabric API.
2. Drop the jar into your `mods/` folder.

## Extending the dictionary

Add keywords/synonyms via `KeywordDictionary` and call `rebuildIndex()`:

```java
WYWFClient.dictionary().register(
    new KeywordDictionary.Entry("minecraft:my_biome",
        KeywordDictionary.Category.BIOME, "my biome"),
    "synonym one", "synonym two");
WYWFClient.dictionary().rebuildIndex();
```

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
