# Changelog

## 1.3.0

### Added
- **`only` modifier** (`only`, `just`, `single`, `только`, `лишь`, `одна`):
  accept seeds with exactly one instance of the structure within range.
  Example: `only desert pyramid` — exactly one pyramid within ~500 blocks.
- **`between` modifier** (`between`, `mid`, `middle`, `от`, `между`,
  `диапазон`): structure must be within a specific distance range.
  Examples: `village between 500 to 800`, `500..800 village`,
  `деревня от 500 до 800`. Supports separators `..`, `to`, `do`, `до`.
- **`some N` count**: `some` now accepts an explicit count.
  Example: `some 4 village` — at least 4 villages within ~320 blocks.
  `some village` (no number) defaults to at least 2.
- **Modifier display in search log**: Terms now show modifier details:
  `some 4 minecraft:village`, `between 500..800 minecraft:village_plains`.
- **Mod Menu + YACL config screen:** settings are now accessible via Mod Menu
  (config button). Uses YACL for a beautiful categorized UI with sliders,
  dropdowns, and toggles. Both Mod Menu and YACL are **optional** — the mod
  works without them (settings via `config/wywf.json`). Available settings:
  query language, thread mode, scan radii, sample step, candidate count,
  stop-at-first toggle, sort-by-distance toggle.
- **"Did you mean?" with consonant-skeleton matching:** when the query contains
  misspelled words, the mod suggests corrections by matching consonant
  skeletons (e.g. "mnsn" → "mansion", "vllg" → "village", "dsert" →
  "desert"). Shows ALL corrections at once. Works even when ALL words are
  misspelled.
- **ExclusionZone enforcement:** structure placements now respect vanilla
  exclusion zones — a structure whose placement is blocked by a nearby structure
  from another set is correctly rejected.
- **Spawn block prediction improvement:** spawn block scanning now covers ±2
  chunks (32 blocks) around the origin, matching vanilla's spawn search area,
  instead of only the origin chunk.
- **Sort candidates by distance:** when ON, the final candidate is chosen by
  distance to the nearest structure (closest first), rather than randomly.
  Default: OFF. Toggle in Mod Menu → Search.
- **Time limit:** maximum search time before stopping. Default: 30 minutes,
  minimum: 5 minutes. When reached, a dialog asks whether to use the best
  candidate found, double the limits and search more, or cancel.
- **Max seeds to check:** hard limit on seeds evaluated. Default: 10,000,000,
  minimum: 1,000,000. When reached, same dialog as time limit. Both limits
  enforce minimums — inputting a lower value snaps to the minimum.
- **Implicit biome for structures:** when a structure has a unique natural
  biome and the query doesn't specify one, the biome is auto-added
  (mansion → dark_forest, desert_pyramid → desert, swamp_hut → swamp,
  ocean_monument → ocean, village_desert → desert, village_plains → plains,
  village_taiga → taiga, village_savanna → savanna, village_snowy →
  snowy_plains, etc.).
- **Reverse variant mapping:** structure variants (village_desert,
  village_plains, etc.) now resolve back to their base structure (village)
  for placement checks. Without this, the prefilter couldn't find the
  structure in StructureSets and discarded ALL seeds.

### Changed
- **Distance constants tuned:**
  - `near`: 200 blocks (was default radius).
  - `in`: 64 blocks.
  - `far`: 500–1000 blocks (was ~1000–2000).
  - `some`: 320 blocks scan radius.
  - `only`: 500 blocks scan radius.
- **NEVER prefilter removed.** The structure placement prefilter for `never`
  was too aggressive for common structures (villages, etc.) — it mathematically
  could never reject within the search radius. Full validation handles `never`
  correctly without a prefilter.
- **SOME/ONLY re-scan uses biome-aware `positions()`** (not placement-only).
  BETWEEN still uses `positionsPlacementOnly()`.

### Fixed
- **NEVER false positives:** `never plains village` previously accepted seeds
  with a village within range. Reverted to `firstPositionPlacementOnly` which
  correctly resolves structure variants (e.g. `village_plains` → all village
  placements via `minecraft:village` Structure key).
- **SOME re-scan radius:** `structureScanRadiusChunks` for SOME returned the
  full `searchRadiusChunks` (40 chunks / 640 blocks) instead of
  `chunks(SOME_BLOCKS)` (20 chunks / 320 blocks). Villages at 668+ blocks
  were incorrectly shown for `some 4 village`.
- **BETWEEN parsing:** trailing `500 to 800` after a term (like
  `village 500 to 800`) was not attached. Now retroactively applied to the
  last term at end-of-loop.
- **BETWEEN re-scan display:** positions outside `[betweenMin, betweenMax]`
  were shown. Now filtered correctly.
- **`evalBiomeTermDirect` step:** ONLY/BETWEEN biome cases now use
  `effectiveStep(quartY)` for correct underground biome sampling.
- **`evalBiomeTermDirect` BETWEEN biome bug:** now uses
  `BiomeField.nearestDistanceBlocks(key, betweenMin, betweenMax)` for
  correct range matching.
- **`splitBetweenWord` helper:** handles single-word BETWEEN patterns
  (`500..800`, `500до800`, `500to800`, `500do800`).
- **Thread safety: structure cache race condition.** `SeedValidator.structureCache`
  inner maps were plain `HashMap` shared across multiple search threads — could
  cause `ConcurrentModificationException` or corrupted data during concurrent
  reads. Changed to `ConcurrentHashMap`.
- **Thread safety: `WorldContext.sampler()` non-volatile lazy init.** Multiple
  threads could see a partially-constructed `Climate.Sampler` object. Added
  `volatile`.
- **`stopReason` not reset between searches.** If search A finished with a
  stop reason (e.g. "time limit reached"), search B's completion handler
  would show the wrong reason. Now cleared in `start()`.
- **Duplicate implicit biomes.** `addImplicitBiomes` could add the same biome
  multiple times if two structures mapped to the same biome (e.g. "ocean
  monument ocean ruins" → two ocean biome terms). Now tracks already-added
  biomes.
- **Dangling modifier applied to wrong word.** "near xyz village" — the NEAR
  modifier was intended for "xyz" (which was ignored), but was incorrectly
  applied to "village". Now modifiers are reset when a word is ignored.
- **All threads named the same.** Worker threads were all named
  `"WYWF-Search-{count}"`, making them indistinguishable in thread dumps.
  Now uses an `AtomicInteger` counter.
- **Progress counter drift in linear (biome-only) search.** `SearchWorker.runLinear`
  did not increment `globalSeedCursor` on error, causing the progress display
  to undercount checked seeds. `runSplit` already did this correctly.
- **Duplicate synonym warnings.** Removed duplicate structure entries
  (`desert_pyramid`, `jungle_pyramid`, `swamp_hut`, `igloo`, `monument`) that
  shared synonyms with generic entries and caused first-wins warnings at
  startup. Unique synonyms from removed duplicates were merged into the
  generic entries. Village variant entries (`village_plains`, etc.) kept for
  compound binding.
- **`woodland` synonym conflict.** Removed "woodland" from mansion synonyms —
  it was already claimed by the forest biome entry.
- **MC lang parser warning.** Moved synonym data files from `lang/` to `data/`
  (`assets/wywf/data/en_us.json`, `ru_ru.json`) to prevent Minecraft's
  resource loader from attempting to parse them as language files (which
  expected string values, not arrays). Updated `KeywordDictionary` loader
  paths.

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