# What you Want to Find (WyWF)

A client-side Fabric mod for Minecraft 1.21.x that turns the **Seed** field in the world-creation screen into a
natural-language search bar. Instead of a number, you describe the world you want
and the mod searches for a seed that matches — fully offline, without generating
chunks.

## Version compatibility

<table>
<tr><th rowspan="2">Minecraft version</th><th colspan="4" style="text-align:center">Version of mod</th></tr>
<tr><th>1.3.x</th><th>1.2.x</th><th>1.1.x</th><th>1.0.x</th></tr>
<tr><td>1.16.5</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.17.1</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.18.2</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.19.2</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.20.1</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.20.4</td><td>❌</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.21.1</td><td>✅</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.21.5</td><td>✅</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>1.21.11</td><td>✅</td><td>❌</td><td>❌</td><td>❌</td></tr>
<tr><td>26.1</td><td>✅</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>26.1.2</td><td>✅</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>26.2</td><td>✅</td><td>✅</td><td>✅</td><td>✅</td></tr>
</table>

Each MC version has its own jar — pick the one matching your Minecraft version.

## What it does

Type a description into the Seed field, for example:

```
village near warm ocean       mansion dark forest
desert temple in desert       near deep dark
spawn on sand                 some 4 village
never plains village          only desert pyramid
between 500 to 800 village   spawn on any solid
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

| Modifier (EN / RU)                            | Meaning                                                                                                                                 |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| *(none)*                                      | present within the default radius                                                                                                       |
| `near` / рядом, около, возле…                 | within ~200 blocks                                                                                                                      |
| `in` / в, на…                                 | within ~64 blocks (right where you spawn)                                                                                               |
| `far` / далеко, вдали…                        | far away (~500–1000 blocks)                                                                                                             |
| `some N` / несколько, много…                  | N or more structures nearby (if the number of structures is not specified (i.e., N structures), it searches for 2 structures by default |
| `only` / только, лишь, одна…                  | exactly one structure nearby (~500 blocks)                                                                                              |
| `between x1 to x2 (x1..x2)` / между, от…      | structure within a distance range                                                                                                       |
| `under` / под, снизу                          | the surface biome at that spot                                                                                                          |
| `no`, `not`, `without` `never` / нет, не, без | must **not** be present (~500-1000 blocks)                                                                                              |

Keywords fall into four categories:

- **Biomes** — `warm ocean`, `desert`, `dark forest`, `deep dark`, `lush caves`,
  `sulfur_caves` (searchable only with `near`, since it sits deep underground)…
- **Structures** — `village`, `mansion`, `desert temple`, `monument`,
  `ruined_portal` (incl. `ruined_portal_nether`)…
- **Spawn blocks** — `grass`, `dirt`, `sand`, `stone`, `snow`, `podzol`,
  `mycelium`, `gravel`, or `any solid` block, prefixed by a trigger such as
  `spawn on`, `on the … block`, `на блоке …` (see below).
- **Objects** — `tree`, `water`, `lava` (recognized, but not searchable yet — ignored)

The dictionary lives in `KeywordDictionary` and holds many synonyms per keyword.
Multi-word keywords win over shorter ones (`dark forest` is matched as the
dark-forest biome, not `forest`).

### Spawn block

You can ask for the block the player ends up standing on at the world origin.
The query must contain a *trigger* (`spawn`, `on`, `на`, `блок`, `block`,
`onto`, `встань`, `стоять`) immediately followed by a block name:

```
spawn on sand
on the stone block
на блоке песок
на любых твёрдых блоках     (any solid block)
```

- `on` / `на` here mean *spawn on*, **not** the (removed) `ON` modifier.
- If the requested block can never be a world-origin surface block (e.g.
  `spawn on lava`), the term is ignored rather than failing the search.
- `any solid` always matches.
- The block is predicted from the biome at the origin chunk (the mod cannot run
  the vanilla voxel spawn finder offline on the client), so the prediction is
  approximate — beaches, rivers and snow layers may differ. It is accurate
  enough to reliably find e.g. desert (sand) or snowy (snow) spawns.

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
- Candidate count: collects up to `8` matching seeds, then stops. For slow /
  rare queries the target **ramps down to `3` after 10 s** of searching, so a
  result appears sooner instead of waiting for a full set
  (`minCandidates`, `candidateRampDownSeconds`).
- Start position: randomized across the 48-bit space by default
  (`randomizeStart`), so a re-run over the same query explores different seeds.
- Search center: `ORIGIN` (default), `SPAWN`, or `BOTH` — structures/biomes
  are checked at origin `(0, 0)`, approximate world spawn, or both (first
  match wins).

## Example: a seed found by WyWF

To show the mod in action, here is a real seed it discovered while running a
natural-language search (not a typed number), verified on 1.1.0:

> **Seed:** `3033784457675282057`
>
> - Query: `in village near mushroom`
> - Seeds checked: `1 665 924`
> - Search time: `62 552 ms` (~1 minute)
> - Candidates found: `3` (the search ramped down to 3 after 10 s, then stopped)

What you'll find in this world:
- You spawn on a **snowy beach**, right next to a **snowy village**.
- A **mushroom biome** lies in the middle of a `deep frozen ocean`, stretching
  from about `(-181, -159)` to `(-586, -862)`. It is made of **2 large islands**
  and **2–3 smaller ones** between them.


Copy-paste the seed:

```
3033784457675282057
```

## Limitations

- **Objects** (`tree`, `water`, `lava`) are recognized but not searched.
- Matches are found around the origin `(0, 0)`; the world spawn is not moved.

## Building

### Prerequisites

- **JDK 25.** The mod is compiled against Java 25 (`sourceCompatibility` /
  `targetCompatibility = VERSION_25`, `options.release = 25`). Any JDK 25 works
  (e.g. Eclipse Temurin 25, Oracle, GraalVM). Set `JAVA_HOME` to it before
  building so Gradle uses the right compiler:

  ```bash
  # Linux / macOS
  export JAVA_HOME=/path/to/jdk-25

  # Windows (PowerShell)
  $env:JAVA_HOME = "C:\path\to\jdk-25"
  ```

- **No manual Gradle install needed.** The project ships the Gradle Wrapper
  (`gradlew` / `gradlew.bat`), which downloads the correct Gradle version
  automatically.
- **Internet access on the first build.** Fabric Loom downloads Minecraft
  1.21.11, the Yarn mappings, Fabric Loader and Fabric API and caches them under
  `~/.gradle` / `.gradle`. Later builds are offline-friendly.

### Commands

Run the wrapper from the project root. On Windows use `gradlew.bat` instead of
`./gradlew`.

```bash
# Build the mod jar (compiles main + tests, runs the test suite)
./gradlew build

# Run the unit tests only (JUnit 5)
./gradlew test

# Launch a Minecraft client with the mod loaded (dev run)
./gradlew runClient

# List all available tasks
./gradlew tasks

# Wipe the build output and caches for a clean rebuild
./gradlew clean
```

`runClient` uses the `client` run configuration defined in `build.gradle`
(Loom) and needs Fabric API, which is already declared as an `implementation`
dependency, so it works out of the box.

### Output

The built mod jar is written to:

```
build/libs/wywf-1.3.0+1.21.x.jar
```

(the name is `<archives_base_name>-<mod_version>.jar`, taken from
`gradle.properties`). Drop that jar into your `mods/` folder to install.

### Troubleshooting

- **`invalid source release: 25` / `release version 25 not supported`** — your
  `JAVA_HOME` points at an older JDK. Point it at a JDK 25.
- **First build is slow / downloads a lot** — that is Loom fetching Minecraft
  and the mappings once; subsequent builds are much faster.
- **Outdated mappings / dependency caches** — run `./gradlew clean` and rebuild.

## Installation

1. Install Fabric Loader 0.19+ for Minecraft 1.21.x (tested on 1.21.11) and Fabric API.
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
