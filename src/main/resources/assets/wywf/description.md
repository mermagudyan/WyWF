# What you Want to Find (WyWF)

A client-side Fabric mod for Minecraft that turns the **Seed** field in the
world-creation screen into a natural-language search bar. Describe the world you
want, press Create, and the mod searches offline for a matching seed.

> Type what you want your world to be like — get a seed that matches.

## Version compatibility

| Minecraft version | 1.3.x+ | 1.2.x | 1.1.x | 1.0.x |
|-------------------|--------|-------|-------|-------|
| 1.16.5            | ❌      | ❌    | ❌    | ❌    |
| 1.17.1            | ❌      | ❌    | ❌    | ❌    |
| 1.18.2            | ❌      | ❌    | ❌    | ❌    |
| 1.19.2            | ❌      | ❌    | ❌    | ❌    |
| 1.20.1            | ❌      | ❌    | ❌    | ❌    |
| 1.20.4            | ❌      | ❌    | ❌    | ❌    |
| 1.21.1            | ✅      | ❌    | ❌    | ❌    |
| 1.21.5            | ✅      | ❌    | ❌    | ❌    |
| 1.21.11           | ✅      | ❌    | ❌    | ❌    |
| 26.1              | ✅      | ✅    | ✅    | ✅    |
| 26.1.2            | ✅      | ✅    | ✅    | ✅    |
| 26.2              | ✅      | ✅    | ✅    | ✅    |

## How it works

Type a description into the Seed field, for example:

```
village near warm ocean       mansion dark forest
desert temple in desert       near deep dark
spawn on sand                 some 4 village
never plains village          only desert pyramid
between 500 to 800 village   spawn on any solid
```

When you press **Create New World**, the mod parses the text and, if it contains
recognized keywords, opens a search screen. It scans seeds around your
chosen center (spawn, origin, or both), locates structures from placement math
and samples biomes with a fast native library when available (with a pure-Java
fallback). When a seed matches, the world is created with it.

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
| `no`, `not`, `without`, `never` / нет, не, без | must NOT be present          |

## What works

- **Biomes**: surface biomes (ocean, desert, forest, …) and cave biomes
  (`deep dark`, `lush caves`, `dripstone caves` — with `near` or `under`).
- **Structures**: village (and `village_plains`/`desert`/… variants), desert
  pyramid, jungle temple, swamp hut, igloo, mansion, monument, shipwreck,
  outpost, mineshaft, ocean ruins, buried treasure, trail ruins, ancient city,
  trial chambers, ruined portal, stronghold, **nether fortress/bastion**, and
  more. Nether checks use a separate nether generator.
- **Compound terms**: `plains village` → `village_plains`, `desert temple` →
  `desert_pyramid`, `snowy village` → `village_snowy`, etc.
- **Spawn block**: find seeds by the block you stand on —
  `spawn on sand`, `on the stone block`, `на блоке песок`, or any solid block.
- **Search center**: check at spawn (default), origin `(0, 0)`, or both.

For slow queries the search stops early (after collecting a few candidates) so
you get a result faster. Every candidate is re-checked exhaustively before
it is shown.

## Limitations

- **Objects** (`tree`, `water`, `lava`) are recognized but not searched yet.
- Requires **Minecraft 26.x** and **Java 25**.

## License

Apache License 2.0.
