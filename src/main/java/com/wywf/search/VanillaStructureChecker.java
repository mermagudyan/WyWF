package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import com.wywf.core.KeywordDictionary;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VanillaStructureChecker implements StructureChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");

    private static final Map<String, Set<String>> STRUCTURE_BIOMES = Map.ofEntries(
            Map.entry("minecraft:mansion", Set.of("minecraft:dark_forest")),
            Map.entry("minecraft:village_plains", Set.of("minecraft:plains", "minecraft:meadow")),
            Map.entry("minecraft:village_desert", Set.of("minecraft:desert")),
            Map.entry("minecraft:village_savanna", Set.of("minecraft:savanna")),
            Map.entry("minecraft:village_snowy", Set.of("minecraft:snowy_plains")),
            Map.entry("minecraft:village_taiga", Set.of("minecraft:taiga")),
            Map.entry("minecraft:desert_pyramid", Set.of("minecraft:desert")),
            Map.entry("minecraft:jungle_pyramid", Set.of("minecraft:jungle", "minecraft:bamboo_jungle")),
            Map.entry("minecraft:swamp_hut", Set.of("minecraft:swamp")),
            Map.entry("minecraft:igloo", Set.of(
                    "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:snowy_slopes"))
    );

    private static final Map<String, Set<ResourceKey<Biome>>> STRUCTURE_BIOME_KEYS = buildBiomeKeys();

    private static final Map<String, Integer> CUBIOMES_STRUCT_ID = Map.ofEntries(
            Map.entry("minecraft:village", 5),
            Map.entry("minecraft:village_plains", 5),
            Map.entry("minecraft:village_desert", 5),
            Map.entry("minecraft:village_savanna", 5),
            Map.entry("minecraft:village_snowy", 5),
            Map.entry("minecraft:village_taiga", 5),
            Map.entry("minecraft:desert_pyramid", 1),
            Map.entry("minecraft:jungle_pyramid", 2),
            Map.entry("minecraft:swamp_hut", 3),
            Map.entry("minecraft:igloo", 4),
            Map.entry("minecraft:ocean_ruin", 6),
            Map.entry("minecraft:shipwreck", 7),
            Map.entry("minecraft:ocean_monument", 8),
            Map.entry("minecraft:mansion", 9),
            Map.entry("minecraft:pillager_outpost", 10),
            Map.entry("minecraft:ruined_portal", 11),
            Map.entry("minecraft:ancient_city", 13),
            Map.entry("minecraft:fortress", 18),
            Map.entry("minecraft:bastion_remnant", 19),
            Map.entry("minecraft:trail_ruins", 21),
            Map.entry("minecraft:trial_chambers", 22)
    );

    /** Cubiomes structure ids that live in the NETHER dimension — viability
     *  must be evaluated against a nether-dimension generator. */
    private static final java.util.Set<Integer> NETHER_STRUCT_IDS = Set.of(18, 19);

    /** Resolved allowed-biome sets: registry tag when readable, else the
     *  hardcoded fallback map; null = no known restriction (ungated). */
    private final Map<String, java.util.Optional<Set<ResourceKey<Biome>>>> biomeGateCache =
            new ConcurrentHashMap<>();

    private Set<ResourceKey<Biome>> allowedBiomes(WorldContext ctx, ResourceKey<Structure> key) {
        String id = key.identifier().toString();
        return biomeGateCache.computeIfAbsent(id, k -> {
            try {
                var holder = ctx.registries().lookupOrThrow(Registries.STRUCTURE).get(key);
                if (holder.isPresent()) {
                    net.minecraft.core.HolderSet<Biome> biomes = holder.get().value().biomes();
                    Set<ResourceKey<Biome>> keys = new HashSet<>();
                    for (Holder<Biome> h : biomes) {
                        var opt = h.unwrapKey();
                        if (opt.isEmpty()) return java.util.Optional.<Set<ResourceKey<Biome>>>empty();
                        keys.add(opt.get());
                    }
                    if (!keys.isEmpty()) {
                        return java.util.Optional.of(Set.copyOf(keys));
                    }
                }
            } catch (Throwable ignored) {
                // offline registry without bound tags — fall through to static map
            }
            return java.util.Optional.<Set<ResourceKey<Biome>>>ofNullable(STRUCTURE_BIOME_KEYS.get(k));
        }).orElse(null);
    }

    private static Map<String, Set<ResourceKey<Biome>>> buildBiomeKeys() {
        Map<String, Set<ResourceKey<Biome>>> out = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : STRUCTURE_BIOMES.entrySet()) {
            Set<ResourceKey<Biome>> keys = new HashSet<>();
            for (String id : e.getValue()) {
                Identifier ident = Identifier.tryParse(id);
                if (ident != null) keys.add(ResourceKey.create(Registries.BIOME, ident));
            }
            out.put(e.getKey(), Set.copyOf(keys));
        }
        return Map.copyOf(out);
    }

    private final KeywordDictionary dict;

    private final Map<String, Optional<ResourceKey<Structure>>> keyCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<ResourceKey<StructureSet>>> setKeyCache = new ConcurrentHashMap<>();

    public VanillaStructureChecker(KeywordDictionary dict) {
        this.dict = dict;
    }

    private ResourceKey<Structure> resolveKey(String realId) {
        return keyCache.computeIfAbsent(realId, rid -> {
            Identifier id = Identifier.tryParse(rid);
            if (id == null) return Optional.empty();
            return Optional.of(ResourceKey.create(Registries.STRUCTURE, id));
        }).orElse(null);
    }

    private ResourceKey<StructureSet> resolveSetKey(String canonical) {
        return setKeyCache.computeIfAbsent(canonical, cid -> {
            Identifier id = Identifier.tryParse(cid);
            if (id == null) return Optional.empty();
            return Optional.of(ResourceKey.create(Registries.STRUCTURE_SET, id));
        }).orElse(null);
    }

    /** Standalone re-implementation of {@code StructurePlacement.isStructureChunk},
     *  so we don't need a fully-tagged {@code ChunkGeneratorStructureState}
     *  (offline / test registries lack the {@code has_structure} biome tags). */
    private boolean isStructureChunk(WorldContext ctx, StructurePlacement placement,
                                     long seed, int x, int z) {
        if (!isPlacementChunk(ctx, placement, seed, x, z)) return false;
        if (!placement.applyAdditionalChunkRestrictions(x, z, seed)) return false;
        return !isExclusionZoneBlocked(ctx, placement, seed, x, z);
    }

    /**
     * Checks whether the candidate chunk is excluded by an exclusion zone.
     * An exclusion zone on a placement means: if the referenced "other" structure
     * set has a structure within {@code chunkCount} chunks, this candidate is
     * rejected. See {@code StructurePlacement.applyInteractionsWithOtherStructures}.
     */
    private boolean isExclusionZoneBlocked(WorldContext ctx, StructurePlacement placement,
                                           long seed, int cx, int cz) {
        Optional<StructurePlacement.ExclusionZone> ezOpt = placement.exclusionZone();
        if (ezOpt.isEmpty()) return false;

        StructurePlacement.ExclusionZone ez = ezOpt.get();
        int chunkCount = ez.chunkCount();
        @SuppressWarnings("unchecked")
        Holder<StructureSet> otherSetHolder = (Holder<StructureSet>) (Holder<?>) ez.otherSet();

        StructureSet otherSet = otherSetHolder.value();
        StructurePlacement otherPlacement = otherSet.placement();

        for (int ox = cx - chunkCount; ox <= cx + chunkCount; ox++) {
            for (int oz = cz - chunkCount; oz <= cz + chunkCount; oz++) {
                if (isPlacementChunk(ctx, otherPlacement, seed, ox, oz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPlacementChunk(WorldContext ctx, StructurePlacement placement,
                                     long seed, int x, int z) {
        if (placement instanceof RandomSpreadStructurePlacement rsp) {
            ChunkPos pos = rsp.getPotentialStructureChunk(seed, x, z);
            return pos.x == x && pos.z == z;
        }
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            List<ChunkPos> positions = ringPositions(ctx, rings, seed);
            return positions != null && positions.contains(new ChunkPos(x, z));
        }
        return false;
    }

    private static final int MAX_RING_CACHE = 4096;
    private final Map<Long, List<ChunkPos>> ringCache = new ConcurrentHashMap<>();

    private List<ChunkPos> ringPositions(WorldContext ctx,
                                         ConcentricRingsStructurePlacement placement,
                                         long seed) {
        // Ring positions depend on the FULL seed (RNG + biome search), not just
        // the low 48 bits — keying by low-48 mixed all high-16 variants together.
        long k = seed * 0x9E3779B97F4A7C15L ^ ((long) System.identityHashCode(placement) * 0x517CC1B727220A95L);
        List<ChunkPos> cached = ringCache.get(k);
        if (cached != null) return cached;
        List<ChunkPos> result = ringCache.computeIfAbsent(k, key -> generateRingPositions(ctx, placement, seed));
        if (ringCache.size() > MAX_RING_CACHE) {
            ringCache.clear();
        }
        return result;
    }

    private List<ChunkPos> generateRingPositions(WorldContext ctx,
                                                 ConcentricRingsStructurePlacement placement,
                                                 long seed) {
        if (placement.count() == 0) return List.of();
        int distance = placement.distance();
        int count = placement.count();
        int spread = placement.spread();
        RandomSource random = RandomSource.create();
        random.setSeed(seed);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int positionInCircle = 0;
        int circle = 0;
        List<ChunkPos> positions = new ArrayList<>();
        var preferred = safePreferredBiomes(placement);
        Climate.Sampler sampler = ctx.sampler();
        for (int i = 0; i < count; i++) {
            double dist = (double) (4 * distance + distance * circle * 6)
                    + (random.nextDouble() - 0.5) * ((double) distance * 2.5);
            int initialX = (int) Math.round(Math.cos(angle) * dist);
            int initialZ = (int) Math.round(Math.sin(angle) * dist);
            RandomSource searchRandom = random.fork();
            var found = ctx.biomeSource.findBiomeHorizontal(
                    SectionPos.sectionToBlockCoord(initialX, 8), 0,
                    SectionPos.sectionToBlockCoord(initialZ, 8), 112,
                    preferred, searchRandom, sampler);
            ChunkPos pos;
            if (found != null) {
                pos = new ChunkPos(SectionPos.blockToSectionCoord(found.getFirst().getX()),
                        SectionPos.blockToSectionCoord(found.getFirst().getZ()));
            } else {
                pos = new ChunkPos(initialX, initialZ);
            }
            positions.add(pos);
            angle += Math.PI * 2 / (double) spread;
            if (++positionInCircle != spread) continue;
            positionInCircle = 0;
            spread += 2 * spread / (++circle + 1);
            spread = Math.min(spread, count - i);
            angle += random.nextDouble() * Math.PI * 2.0;
        }
        return positions;
    }

    /** Resolve the ring's preferred biomes; falls back to "any biome" when the
     *  vanilla {@code has_structure} tag is unbound (offline registries). */
    private java.util.function.Predicate<Holder<Biome>> safePreferredBiomes(
            ConcentricRingsStructurePlacement placement) {
        try {
            HolderSet<Biome> set = placement.preferredBiomes();
            Set<ResourceKey<Biome>> keys = new HashSet<>();
            for (Holder<Biome> h : set) {
                h.unwrapKey().ifPresent(keys::add);
            }
            if (!keys.isEmpty()) {
                return h -> h.unwrapKey().map(keys::contains).orElse(false);
            }
        } catch (UnsupportedOperationException ignored) {
            // tags not bound in offline registry
        }
        return h -> true;
    }

    private boolean structureChunk(WorldContext ctx, ResourceKey<Structure> key,
                                    int cx, int cz) {
        return structureChunk(ctx, key, cx, cz, true);
    }

    private boolean structureChunk(WorldContext ctx, ResourceKey<Structure> key,
                                    int cx, int cz, boolean checkBiome) {
        long seed = ctx.seed;
        List<StructurePlacement> placements = ctx.placementsFor(key);
        if (!placements.isEmpty()) {
            return checkPlacements(ctx, key, placements, cx, cz, seed, checkBiome);
        }
        // key may be a StructureSet name (e.g. "minecraft:village") —
        // look up all structures in that set and check their placements
        String setId = key.identifier().toString();
        ResourceKey<StructureSet> setKey = resolveSetKey(setId);
        if (setKey != null) {
            var holder = ctx.structureSets().get(setKey);
            if (holder.isPresent()) {
                StructureSet set = holder.get().value();
                for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                    ResourceKey<Structure> structKey = entry.structure().unwrapKey().orElse(null);
                    if (structKey == null) continue;
                    List<StructurePlacement> structPlacements = ctx.placementsFor(structKey);
                    if (!structPlacements.isEmpty()
                            && checkPlacements(ctx, structKey, structPlacements, cx, cz, seed, checkBiome)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean checkPlacements(WorldContext ctx, ResourceKey<Structure> key,
                                     List<StructurePlacement> placements,
                                     int cx, int cz, long seed, boolean checkBiome) {
        for (StructurePlacement placement : placements) {
            if (!isStructureChunk(ctx, placement, seed, cx, cz)) continue;
            if (!checkBiome) return true;
            int bx = cx * 16 + 8, bz = cz * 16 + 8;

            if (CubiomesBridge.isActive()) {
                Integer structId = CUBIOMES_STRUCT_ID.get(key.identifier().toString());
                if (structId != null) {
                    if (NETHER_STRUCT_IDS.contains(structId)) {
                        if (!CubiomesBridge.isViableStructurePos(structId, CubiomesBridge.DIM_NETHER, ctx.seed, bx, bz)) {
                            return false;
                        }
                    } else if (!CubiomesBridge.isViableStructurePos(structId, bx, bz)) {
                        return false;
                    }
                    return true;
                }
            }

            Set<ResourceKey<Biome>> allowed = allowedBiomes(ctx, key);
            return allowed == null || VanillaBiomeChecker.quartYForSurfaceMatches(ctx, bx, bz, allowed);
        }
        return false;
    }

    /**
     * Collects candidate chunks for random-spread placements by walking the
     * REGION grid — one RNG candidate per spacing×spacing region — instead of
     * testing every chunk. Exact: produces the same verdicts as per-chunk
     * scanning, just ~spacing² times cheaper. Non-spread placements (e.g.
     * concentric rings) must be handled by the caller's chunk loop.
     */
    private void collectSpreadCandidates(WorldContext ctx,
                                         List<StructurePlacement> placements, long seed,
                                         int minX, int maxX, int minZ, int maxZ,
                                         List<int[]> out, Set<Long> seen) {
        for (StructurePlacement placement : placements) {
            if (!(placement instanceof RandomSpreadStructurePlacement rsp)) continue;
            int spacing = Math.max(1, rsp.spacing());
            int rMinX = Math.floorDiv(minX, spacing), rMaxX = Math.floorDiv(maxX, spacing);
            int rMinZ = Math.floorDiv(minZ, spacing), rMaxZ = Math.floorDiv(maxZ, spacing);
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                for (int rz = rMinZ; rz <= rMaxZ; rz++) {
                    // getPotentialStructureChunk takes CHUNK coords and floorDivs by
                    // spacing internally; rx*spacing floor-divides back to exactly rx.
                    ChunkPos p = rsp.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    int cx = p.x, cz = p.z;
                    if (cx < minX || cx > maxX || cz < minZ || cz > maxZ) continue;
                    if (!rsp.applyAdditionalChunkRestrictions(cx, cz, seed)) continue;
                    if (isExclusionZoneBlocked(ctx, placement, seed, cx, cz)) continue;
                    long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(pk)) continue;
                    out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
                }
            }
        }
    }

    /** True when every placement for this key is random-spread (region-scannable). */
    private boolean allRandomSpread(List<StructurePlacement> placements) {
        if (placements.isEmpty()) return false;
        for (StructurePlacement p : placements) {
            if (!(p instanceof RandomSpreadStructurePlacement)) return false;
        }
        return true;
    }

    /** Placement-only scan of one structure key over a chunk window. */
    private void scanKeyPlacementOnly(WorldContext ctx, ResourceKey<Structure> key,
                                      int minX, int maxX, int minZ, int maxZ,
                                      List<int[]> out, Set<Long> seen) {
        List<StructurePlacement> placements = ctx.placementsFor(key);
        if (!placements.isEmpty() && allRandomSpread(placements)) {
            collectSpreadCandidates(ctx, placements, ctx.seed, minX, maxX, minZ, maxZ, out, seen);
            return;
        }
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!structureChunk(ctx, key, cx, cz, false)) continue;
                long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                if (!seen.add(pk)) continue;
                out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
            }
        }
    }

    /** Region-scan candidates of one key, keeping only biome-viable ones. */
    private void scanKey(WorldContext ctx, ResourceKey<Structure> key,
                         int minX, int maxX, int minZ, int maxZ,
                         List<int[]> out, Set<Long> seen) {
        List<StructurePlacement> placements = ctx.placementsFor(key);
        if (!placements.isEmpty() && allRandomSpread(placements)) {
            List<int[]> cands = new ArrayList<>();
            collectSpreadCandidates(ctx, placements, ctx.seed, minX, maxX, minZ, maxZ, cands, new HashSet<>());
            for (int[] p : cands) {
                int ccx = (p[0] - 8) >> 4, ccz = (p[1] - 8) >> 4;
                if (structureChunk(ctx, key, ccx, ccz)) out.add(p);
            }
            return;
        }
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!structureChunk(ctx, key, cx, cz)) continue;
                long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                if (!seen.add(pk)) continue;
                out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
            }
        }
    }

    /** First biome-viable hit of one key over a chunk window. */
    private int[] firstKey(WorldContext ctx, ResourceKey<Structure> key,
                           int minX, int maxX, int minZ, int maxZ) {
        List<StructurePlacement> placements = ctx.placementsFor(key);
        if (!placements.isEmpty() && allRandomSpread(placements)) {
            List<int[]> cands = new ArrayList<>();
            collectSpreadCandidates(ctx, placements, ctx.seed, minX, maxX, minZ, maxZ, cands, new HashSet<>());
            for (int[] p : cands) {
                int ccx = (p[0] - 8) >> 4, ccz = (p[1] - 8) >> 4;
                if (structureChunk(ctx, key, ccx, ccz)) return p;
            }
            return null;
        }
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (structureChunk(ctx, key, cx, cz)) return new int[]{cx * 16 + 8, cz * 16 + 8};
            }
        }
        return null;
    }

    /** Placement-only first-hit scan of one structure key over a chunk window. */
    private int[] firstKeyPlacementOnly(WorldContext ctx, ResourceKey<Structure> key,
                                        int minX, int maxX, int minZ, int maxZ) {
        List<StructurePlacement> placements = ctx.placementsFor(key);
        if (!placements.isEmpty() && allRandomSpread(placements)) {
            List<int[]> one = new ArrayList<>(1);
            collectSpreadCandidates(ctx, placements, ctx.seed, minX, maxX, minZ, maxZ, one, new HashSet<>());
            return one.isEmpty() ? null : one.get(0);
        }
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (structureChunk(ctx, key, cx, cz, false)) {
                    return new int[]{cx * 16 + 8, cz * 16 + 8};
                }
            }
        }
        return null;
    }

    @Override
    public List<int[]> positions(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            scanKey(ctx, canonicalKey, minX, maxX, minZ, maxZ, out, seen);
        }

        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            scanKey(ctx, key, minX, maxX, minZ, maxZ, out, seen);
        }

        return out;
    }

    @Override
    public List<int[]> positionsPlacementOnly(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            scanKeyPlacementOnly(ctx, canonicalKey, minX, maxX, minZ, maxZ, out, seen);
        }

        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            scanKeyPlacementOnly(ctx, key, minX, maxX, minZ, maxZ, out, seen);
        }

        return out;
    }

    @Override
    public int[] firstPosition(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            int[] hit = firstKey(ctx, canonicalKey, minX, maxX, minZ, maxZ);
            if (hit != null) return hit;
        }

        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            int[] hit = firstKey(ctx, key, minX, maxX, minZ, maxZ);
            if (hit != null) return hit;
        }

        return null;
    }

    @Override
    public int[] firstPositionPlacementOnly(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            int[] hit = firstKeyPlacementOnly(ctx, canonicalKey, minX, maxX, minZ, maxZ);
            if (hit != null) return hit;
        }

        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            int[] hit = firstKeyPlacementOnly(ctx, key, minX, maxX, minZ, maxZ);
            if (hit != null) return hit;
        }

        return null;
    }

    /** Returns true if at least one structure in the set has a valid biome at (cx,cz). */
    private boolean structureSetBiomeOk(WorldContext ctx, StructureSet set, int cx, int cz) {
        int bx = cx * 16 + 8, bz = cz * 16 + 8;
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            ResourceKey<Structure> key = entry.structure().unwrapKey().orElse(null);
            if (key == null) continue;
            Set<ResourceKey<Biome>> allowed = allowedBiomes(ctx, key);
            if (allowed == null || VanillaBiomeChecker.quartYForSurfaceMatches(ctx, bx, bz, allowed)) {
                return true;
            }
        }
        return false;
    }

    private int[] firstFromStructureSet(WorldContext ctx, String canonical,
                                         int minX, int maxX, int minZ, int maxZ,
                                         boolean checkBiome) {
        ResourceKey<StructureSet> setKey = resolveSetKey(canonical);
        if (setKey == null) return null;
        var holder = ctx.structureSets().get(setKey);
        if (holder.isEmpty()) return null;
        StructureSet s = holder.get().value();
        StructurePlacement placement = s.placement();
        long seed = ctx.seed;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (isStructureChunk(ctx, placement, seed, cx, cz)) {
                    if (checkBiome && !structureSetBiomeOk(ctx, s, cx, cz)) continue;
                    return new int[]{cx * 16 + 8, cz * 16 + 8};
                }
            }
        }
        return null;
    }

    private void collectFromStructureSet(WorldContext ctx, String canonical,
                                          int minX, int maxX, int minZ, int maxZ,
                                          Set<Long> seen, List<int[]> out,
                                          boolean checkBiome) {
        ResourceKey<StructureSet> setKey = resolveSetKey(canonical);
        if (setKey == null) return;
        var holder = ctx.structureSets().get(setKey);
        if (holder.isEmpty()) return;
        StructureSet s = holder.get().value();
        StructurePlacement placement = s.placement();
        long seed = ctx.seed;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!isStructureChunk(ctx, placement, seed, cx, cz)) continue;
                if (checkBiome && !structureSetBiomeOk(ctx, s, cx, cz)) continue;
                long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                if (!seen.add(pk)) continue;
                out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
            }
        }
    }

    @Override
    public boolean hasConcentricRings(WorldContext ctx, String canonical) {
        ResourceKey<Structure> k = resolveKey(canonical);
        if (k != null && ctx.placementsFor(k).stream().anyMatch(p -> p instanceof ConcentricRingsStructurePlacement)) {
            return true;
        }
        for (String v : dict.getVariants(canonical)) {
            ResourceKey<Structure> vk = resolveKey(v);
            if (vk != null && ctx.placementsFor(vk).stream().anyMatch(p -> p instanceof ConcentricRingsStructurePlacement)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasAnyPlacementWithin(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        // 0) Try canonical directly as a Structure key
        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, canonicalKey, cx, cz)) return true;
                }
            }
        }

        // 1) Try structure-based lookup via variants
        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, key, cx, cz)) return true;
                }
            }
        }

        return false;
    }

    private boolean tryStructureSet(WorldContext ctx, String setName,
                                     int minX, int maxX, int minZ, int maxZ) {
        ResourceKey<StructureSet> setKey = resolveSetKey(setName);
        if (setKey == null) return false;
        var holder = ctx.structureSets().get(setKey);
        if (holder.isEmpty()) return false;
        StructureSet s = holder.get().value();
        StructurePlacement placement = s.placement();
        long seed = ctx.seed;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!isStructureChunk(ctx, placement, seed, cx, cz)) continue;
                if (!structureSetBiomeOk(ctx, s, cx, cz)) continue;
                return true;
            }
        }
        return false;
    }
}
