# Changelog

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