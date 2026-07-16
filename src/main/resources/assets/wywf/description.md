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
recognized keywords, opens a search screen. It scans seeds (around the origin),
locates structures from placement math and samples biomes with a fast,
vanilla-exact climate sampler. When a seed matches, the world is created with it.

Keywords are recognized in **Russian and English**, with many synonyms.

## Modifiers

| Modifier (EN / RU)                | Meaning                          |
|-----------------------------------|----------------------------------|
| *(none)*                          | present within the default radius|
| `near` / рядом, возле…            | within ~200 blocks               |
| `in` / в, на…                     | within ~64 blocks                |
| `far` / далеко, вдали…            | far away (~1000–2000 blocks)     |
| `some` / несколько, много…        | several structures nearby        |
| `under` / под, снизу              | the surface biome at that spot   |
| `no`, `not`, `without` / нет, не  | must NOT be present              |

## What works

- **Biomes**: surface biomes (ocean, desert, forest, …) and cave biomes
  (`deep dark`, `lush caves`, `dripstone caves`, `sulfur_caves` — searchable
  with `near`).
- **Structures**: village, mansion, temples, monument, shipwreck, outpost,
  igloo, mineshaft, ocean ruins, buried treasure, trail ruins, ancient city,
  trial chambers, ruined portal (incl. nether variant), and more.
- **Spawn block**: find seeds by the block you stand on at the origin —
  `spawn on sand`, `on the stone block`, `на блоке песок`, or any solid block.

For slow queries the search stops early (after collecting a few candidates) so
you get a result faster.

## Limitations

- **Strongholds are not supported** yet (concentric-ring placement).
- **Objects** (`tree`, `water`, `lava`) are recognized but not searched yet.
- Matches are found around the origin `(0, 0)`; the spawn is not moved.
- Requires **Minecraft 26.x** and **Java 25**.

## License

Apache License 2.0.
