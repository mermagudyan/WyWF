# Changelog

Written in plain language — every change, explained so anyone can understand.

## 1.4.0

### New
- **Much faster searches.** The mod now uses a built-in speed-up library
  when available. Biome checks become up to ~50× faster. It turns on by
  itself, and if the library is missing, the mod quietly keeps working
  the old way.
- **Nether structures finally work.** Searching for `nether fortress`
  or `bastion` now finds real seeds. Before, these searches almost
  always came back empty when the speed-up mode was active.
- **Cave biomes work with "under".** Queries like `under lush caves`
  now return results — before this version, they never did.
- **Smarter spawn fallback.** When the game's own spawn finder comes up
  empty, the mod now searches around for the nearest sensible spot
  (plains, forest, taiga, meadow, sunflower plains) within a few
  thousand blocks, instead of just guessing next to the origin.

### Changed
- **Spawn-centered search is more accurate.** Each seed now gets its own
  spawn position and terrain height (before, many seeds shared the same
  wrong height and an approximate position). When the predicted point
  lands in water, nearby dry ground is preferred.
- **Strongholds are placed correctly.** Each seed now gets its own true
  ring positions. Before, seeds that differed only in their upper bits
  shared one wrong set, giving both false finds and missed finds.
- **Renamed biomes match again.** Looking for snowy plains, windswept
  hills, old growth pine taiga, wooded badlands and friends now works
  together with the speed-up mode — before, these biomes silently
  never matched there.
- **Village phrases work again.** Combinations like `plains village`,
  `desert temple`, `snowy village` are recognized as one thing again,
  and asking for a nether fortress, bastion or end city by name works.
- **`some` counts honestly.** Asking for `some 2 stronghold`,
  `some 2 mansion` and similar now truly requires that many — before,
  one instance was enough.
- **Even faster.** Removed hidden disk writing inside the speed-up
  library that slowed down every checked seed, and sorted query parts
  once per search instead of once per seed.
- **More reliable caching.** An internal mix-up that could rarely swap
  two different search results has been eliminated.
- **Calmer logs.** Normal, successful events no longer shout like
  warnings; genuine failures now report full details.
- Small cleanup in the settings screen layout.

### Fixed
- Searches centered on the spawn point no longer reject every seed up
  front when structures are requested.
- A crash while predicting the spawn point could silently stop a whole
  search thread mid-run. It now skips just that one seed.
- Very rare race: clicking "Create World" at exactly the wrong moment
  could create a world with the wrong (or zero) seed.
- Rare freezes and corrupted results when many search threads shared
  one lookup table.
- Saved search reports no longer scramble when several threads write
  them at once.
- The speed-up library's helper objects are properly released after
  each search instead of leaking memory.

## 1.3.0

### New
- **`only` modifier** (`only`, `just`, `single`, `только`, `лишь`,
  `одна`): accept seeds with exactly one instance of the structure
  nearby. Example: `only desert pyramid` — exactly one pyramid within
  roughly 500 blocks.
- **`between` modifier** (`between`, `mid`, `middle`, `от`, `между`,
  `диапазон`): the structure must be within a specific distance range.
  Examples: `village between 500 to 800`, `500..800 village`,
  `деревня от 500 до 800`. Separators `..`, `to`, `do`, `до` all work,
  including glued forms like `500..800`.
- **`some N` count**: choose how many you need.
  Example: `some 4 village` — at least 4 villages within ~320 blocks.
  Plain `some village` still means "at least 2".
- **Modifiers shown in the search log**: you can see exactly how your
  request was understood, e.g. `some 4 minecraft:village`.
- **Settings screen via Mod Menu.** All options in a tidy categorized
  menu with sliders and toggles. Uses optional companion libraries —
  without them everything still works through the config file. Options
  include: query language, thread mode, scan radii, sample step,
  candidate count, stop-at-first, sort-by-distance.
- **"Did you mean?" suggestions.** Misspelled words get corrected using
  letter-skeleton matching: "vllg" → village, "dsert" → desert,
  "mnsn" → mansion. Shows all corrections at once, even when every
  word in the query is misspelled.
- **Exclusion zones respected.** Structures that vanilla blocks because
  another structure set sits too close are now correctly rejected.
- **Wider spawn-block scanning.** The block you will stand on is now
  predicted across a ±32 block area (matching how the game itself
  hunts for a spawn spot), not just the exact corner.
- **Sort candidates by distance.** Optional: the final seed is picked
  so the nearest structure is as close as possible, instead of random
  among candidates.
- **Time limit.** Maximum search time before stopping (default
  30 minutes, minimum 5). When reached, a dialog offers: take the best
  candidate so far, double the limits and continue, or cancel.
- **Seed limit.** Hard cap on how many seeds may be checked (default
  10 million). Same dialog when reached. Values below the minimums snap
  up to the minimum.
- **Automatic biome for structures.** If a structure lives in one
  obvious biome and your query doesn't name one, it is added for you:
  mansion → dark forest, desert pyramid → desert, witch hut → swamp,
  ocean monument → ocean, desert village → desert, plains village →
  plains, taiga village → taiga, savanna village → savanna, snowy
  village → snowy plains, and so on.
- **Village variants resolve correctly.** Named variants (desert
  village, plains village…) are matched back to the general village
  when checking placement, so requesting them no longer discards every
  seed by mistake.

### Changed
- **Clearer distances.** `near` = 200 blocks, `in` = 64 blocks,
  `far` = 500–1000 blocks (used to be ~1000–2000), `some` scans within
  320 blocks, `only` within 500.
- The "never" check no longer runs a pointless extra pass that could
  never help; the main check handles it fully.
- Re-counting structures for `some`/`only` now respects which biomes
  they may generate in.

### Fixed
- `never plains village` wrongly accepted seeds that DID have a village
  nearby.
- `some 4 village` counted villages much farther away than promised
  (up to 640+ blocks instead of 320).
- A trailing range like `village 500 to 800` sometimes wasn't attached
  to the village.
- Distances outside your chosen range were shown in results for
  `between` queries; now filtered.
- Cave-biome sampling for `only`/`between` used too coarse a grid and
  could miss underground biomes entirely.
- Range checks for cave biomes measured the wrong thing and could
  accept wrong seeds.
- Glued ranges written as one word (`500..800`, `500до800`, `500to800`)
  now parse everywhere.
- Several rare crashes/freezes when multiple search threads shared one
  lookup table.
- Another race where two threads could briefly see a half-built
  sampler.
- A leftover "search stopped because…" message could leak into the next
  search's summary.
- Asking for two structures that share a natural biome added that biome
  twice.
- In "near xyz village", the word `near` could stick to `village`
  instead of being dropped along with the unknown word `xyz`. Modifiers
  now reset when a word is ignored.
- All search threads were named identically, making problem reports
  harder to read. They are numbered now.
- The progress counter undercounted in one search mode when a seed
  errored out.
- Startup warnings about duplicate word definitions removed (the
  duplicates were merged into the surviving entries).
- The word "woodland" belonged to the forest biome but was also claimed
  by "mansion"; it now means forest only.
- Two data files lived in a folder where Minecraft tried to read them
  as translations and printed warnings; they moved somewhere safe.
- An empty (or fully unsupported) query froze the search screen on
  "Preparing…" forever; it now finishes cleanly with a message.
- Invisible text on Minecraft 26.x: text colors were written without
  the transparency byte, which made whole labels disappear. All UI text
  now renders.

## 1.2.0

### New
- **Every search word now lives in editable language files**
  (English and Russian). Words for modifiers, biomes, structures,
  objects and spawn blocks are loaded from data files instead of being
  welded into the code. The query language setting supports EN / RU /
  AUTO (AUTO merges both).
- **Many new spawn blocks** for `spawn on …`: clay, terracotta,
  red sand, netherrack, soul sand, basalt, blackstone, end stone,
  obsidian, ice, packed ice, cobblestone, moss, rooted dirt — Russian
  names included.
- **Combined phrases** resolve to the exact structure variant:
  `plains village` → plains village, `desert temple` → desert pyramid,
  `jungle temple`, `snowy village`, and the same in Russian.
- **Unknown words are reported.** Words the mod doesn't recognize are
  collected and shown, so you know part of your request was skipped
  instead of wondering why nothing matched.
- **Your settings persist.** Choices are saved to `config/wywf.json`
  and restored next time.
- **Richer results.** Each found seed lists the structures and biomes
  that matched, plus the reason the search stopped.

### Changed
- The word dictionary is fully driven by the language files; old
  built-in lists were removed.
- Revisiting the same seed reuses earlier structure lookups instead of
  recomputing them (no visible behavior change, just faster).

### Known limitation in this release
- Only English is selectable in the language menu yet; Russian and AUTO
  arrive in a later update.

## 1.1.1

### Fixed
- **Structure search rebuilt from scratch.** The old approach depended
  on a game system that simply cannot work outside a running world, and
  crashed on startup. The mod now computes structure positions itself,
  exactly like the game does:
  - evenly-spread structures (villages, temples, monuments, mineshafts,
    fortresses, bastions, end cities, ruined portals and more) via the
    game's own placement math;
  - stronghold rings recalculated per seed, including the land-search
    step, with a safe fallback when the game's tag data is unavailable;
  - rare "frequency reduction" restrictions honored;
  - each structure still checks that its biome fits.
- Internal plumbing modernized alongside the rebuild.

## 1.1.0

### New
- **Search by the block you land on.** Find worlds by spawn surface:
  `spawn on sand`, `on the stone block`, `на блоке песок`, or just
  `any solid`. (Predicted from the area's biome — close, though not
  voxel-perfect.)
- **Ruined portals in the nether** are included in portal searches.
- **Sulfur caves** are searchable — with `near`, since they only exist
  deep underground.
- **Stop at first find.** A new option ends the search the moment one
  matching seed appears — fastest possible result, fewer alternatives.
- **Patient searches relax automatically.** If a query is hard, the
  number of candidates the mod waits for ramps down from 8 to 3 after
  10 seconds, so rare queries still produce an answer soon.

### Changed
- **Faster biome searching:**
  - "near" checks stop at the first hit instead of scanning everything;
  - when a query names several biomes, the map of the surroundings is
    sampled once and shared between them;
  - cheap checks run before expensive ones, so most wrong seeds are
    rejected sooner.
- The old `on` modifier merged into `in` (~64 blocks); `on` now belongs
  to spawn-block search (`spawn on sand`).
- Dead code around spawn offsets removed.

### Misc
- The mod page now lists issue-tracker and homepage links.
