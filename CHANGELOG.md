# Changelog

## 1.2.0

### Added
- **Externalized all query synonyms to lang files** (`assets/wywf/lang/en_us.json`,
  `ru_ru.json`). Modifiers, spawn triggers, biomes, structures, objects and spawn
  blocks are now data-driven and no longer hardcoded in `QueryParser` /
  `VanillaStructureChecker`. AUTO merges EN + RU (`queryLanguage` EN / RU / AUTO).
- **New spawn blocks** for `spawn on …` / `на …`: `clay`, `terracotta`,
  `red_sand`, `netherrack`, `soul_sand`, `basalt`, `blackstone`, `end_stone`,
  `obsidian`, `ice`, `packed_ice`, `cobblestone`, `moss`, `rooted_dirt`
  (RU synonyms included).
- **Compound biome+structure terms** resolve to the specific structure variant:
  `plains village` → `village_plains`, `desert temple` → `desert_pyramid`,
  `jungle temple` → `jungle_pyramid`, `snowy village` → `village_snowy`, etc.
  RU phrases supported (`равнинная деревня`, …).
- **Query validation / ignored words:** unknown words are no longer silently dropped —
  they are collected and logged (WARN) so the player knows part of the query was
  not understood.
- **Config persistence:** `SearchConfig` (incl. `queryLanguage`) is saved to
  `~/.minecraft/config/wywf.json` and reloaded on the Create-World screen.
- **Query language is now a mod-level setting (Mod Menu), not a per-search
  button.** The in-search `Language:` button was removed. Only **EN** is
  selectable in 1.2.0 — **RU and AUTO are NOT available yet** (the `ru_ru.json`
  lexicon exists but is not wired to a UI control); the default and fallback is
  EN. RU/AUTO will be exposed in a later release.
- **Search-result reporting:** each found seed now lists the matched structures
  and biomes (coordinates stay DEEP v1) plus the reason the search stopped
  (`collected N candidates`, `search complete`, …).
- **Unknown-word feedback:** if part of the query was not recognized, the search
  screen prints `Unknown words ignored: …` so the player knows what was dropped.

### Changed
- `KeywordDictionary` now loads from lang JSON; `VanillaStructureChecker.EXPANSIONS`
  moved to `wywf.variant.*` lang entries; hardcoded `buildModifiers()` /
  `buildSpawnTriggers()` removed from `QueryParser`.
- **Structure presence cache:** `SeedValidator` caches per-(seed, structure,
  radius) placement results so a revisited seed does not recompute `firstPosition`
  (internal optimization; no player-visible behaviour change).

## 1.1.1

### Fixed
- **Structure search parity (all placement types):** rewrote
  `VanillaStructureChecker` to no longer rely on
  `ChunkGeneratorStructureState.isStructureChunk(state, …)`, which both failed
  to compile and threw `UnsupportedOperationException` at construction (offline /
  test `RegistryAccess` lacks the datapack `has_structure` biome tags). It now
  re-implements the placement check standalone:
  - `RandomSpreadStructurePlacement` via its public
    `getPotentialStructureChunk(seed, x, z)` (covers mineshaft, villages,
    temples, monuments, bastions, fortresses, end cities, ruined portals, …).
  - `ConcentricRingsStructurePlacement` via recomputed ring positions
    (`BiomeSource.findBiomeHorizontal`, cached per `(placement, seed)`,
    with an "any biome" fallback when the preferred-biome tag is unbound).
  - `FrequencyReduction` is now honored through the public
    `applyAdditionalChunkRestrictions`.
  - Per-structure biome gate now calls
    `VanillaBiomeChecker.quartYForSurfaceMatches`, unifying surface/cave quart-Y
    with the biome checker.
- **`WorldContext` build API:** removed the broken `structureState` field and
  unused holders; now carries `HolderLookup.Provider` + `noiseSettingsKey`.
- **`MinecraftWorldContextFactory`:** updated to construct `WorldContext` with
  the registry provider + noise-settings key.

### Known feature
- `ExclusionZone` interactions between structure sets are not yet enforced
  (accessor is `protected`; no vanilla structure of interest uses one).

## 1.1.0

### Added
- **Search by spawn block** — find seeds by the block you stand on at the origin:
  `spawn on sand`, `on the stone block`, `на блоке песок`, or `any solid`
  (predicted from the origin biome via `SpawnBlockPredictor`; approximate).
- **`ruined_portal_nether`** is now included in portal search.
- **`sulfur_caves`** is now searchable (only with the `near` modifier).
- **Adaptive candidate count** — for slow queries the required candidate target
  ramps down from `8` to `3` after `10 s` (`SearchConfig` + `SearchWorker` +
  `SeedSearcher` monitor). New `stopAtFirstCandidate` option stops at the first
  match for the fastest possible result.
- **Faster biome search**:
  - `near` uses an early-exit presence check instead of scanning the whole grid.
  - The sampled biome grid is cached per seed and reused across all biome terms
    (`BiomeField`), so a multi-biome query pays the sampling cost once.
  - Query terms are reordered so cheap checks (structures, spawn, positive biome
    terms) run before expensive ones (`far` / `never`).

### Changed
- **Removed the `ON` modifier** (merged into `in`, ~64 blocks). `on` / `на` now
  belongs to spawn-block search when followed by a block name.
- Removed the unused spawn-offset code in `PendingWorldCreation`.

### Metadata
- `fabric.mod.json` now lists `issues` and `homepage` (CurseForge) contact links.