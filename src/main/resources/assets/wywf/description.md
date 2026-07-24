# What you Want to Find (WyWF)

A client-side Fabric mod for Minecraft 26.x that turns the **Seed** field in the
world-creation screen into a natural-language search bar. Describe the world you
want, press Create, and the mod searches offline for a matching seed.

> Type what you want your world to be like — get a seed that matches.

## How it works

Type a description into the Seed field, for example:

```
village near warm ocean
деревня возле теплого океана
mansion dark forest
desert temple in desert
near deep dark
```

When you press **Create New World**, the mod parses the text and, if it contains
recognized keywords, opens a search screen. It scans seeds (around the origin
and/or approximate world spawn), locates structures from placement math and
samples biomes with a fast, vanilla-exact climate sampler. When a seed matches,
the world is created with it.

Keywords are recognized in **Russian and English**, with many synonyms.

## Modifiers

| Modifier (EN / RU)                          | Meaning                          |
|---------------------------------------------|----------------------------------|
| *(none)*                                    | present within the default radius|
| `near` / рядом, возле…                     | within ~200 blocks               |
| `in` / в, на…                              | within ~64 blocks                |
| `far` / далеко, вдали…                     | far away (~500–1000 blocks)      |
| `some N` / несколько, много…               | N or more structures nearby      |
| `only` / только, лишь, одна…               | exactly one structure nearby     |
| `between 500 to 800` / между, от…          | structure within a distance range|
| `under` / под, снизу                       | the surface biome at that spot   |
| `no`, `not`, `without` / нет, не, без      | must NOT be present              |

## What works

- **Biomes**: surface biomes (ocean, desert, forest, …) and cave biomes
  (`deep dark`, `lush caves`, `dripstone caves`, `sulfur_caves` — searchable
  with `near`).
- **Structures**: village, desert/pyramid, jungle, swamp, igloo, mansion,
  monument, shipwreck, outpost, mineshaft, ocean ruins, buried treasure,
  trail ruins, ancient city, trial chambers, ruined portal, stronghold, and more.
- **Compound terms**: `plains village` → `village_plains`, `desert temple` →
  `desert_pyramid`, `snowy village` → `village_snowy`, etc.
- **Spawn block**: find seeds by the block you stand on at the origin —
  `spawn on sand`, `on the stone block`, `на блоке песок`, or any solid block.
- **Search center**: check at origin `(0, 0)`, approximate world spawn, or both.

For slow queries the search stops early (after collecting a few candidates) so
you get a result faster.

## Limitations

- **Objects** (`tree`, `water`, `lava`) are recognized but not searched yet.
- Requires **Minecraft 26.x** and **Java 25**.

## License

Apache License 2.0.
