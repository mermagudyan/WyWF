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
            return pos.x() == x && pos.z() == z;
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
        long k = ((long) System.identityHashCode(placement) << 32) ^ (seed & 0xFFFFFFFFFFFFL);
        List<ChunkPos> cached = ringCache.get(k);
        if (cached != null) return cached;
        if (ringCache.size() >= MAX_RING_CACHE) ringCache.clear();
        return ringCache.computeIfAbsent(k, key -> generateRingPositions(ctx, placement, seed));
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
        for (StructurePlacement placement : ctx.placementsFor(key)) {
            if (isStructureChunk(ctx, placement, seed, cx, cz)) {
                if (!checkBiome) return true;
                Set<ResourceKey<Biome>> allowed = STRUCTURE_BIOME_KEYS.get(key.identifier().toString());
                if (allowed == null) return true;
                int bx = cx * 16 + 8, bz = cz * 16 + 8;
                return VanillaBiomeChecker.quartYForSurfaceMatches(ctx, bx, bz, allowed);
            }
        }
        return false;
    }

    @Override
    public List<int[]> positions(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        List<int[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        // 0) Try canonical directly as Structure key
        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (!structureChunk(ctx, canonicalKey, cx, cz)) continue;
                    long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(pk)) continue;
                    out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
                }
            }
        }

        // 1) Try variant Structure keys
        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (!structureChunk(ctx, key, cx, cz)) continue;
                    long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(pk)) continue;
                    out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
                }
            }
        }

        // 2) Try canonical as StructureSet
        if (out.isEmpty()) {
            collectFromStructureSet(ctx, canonical, minX, maxX, minZ, maxZ, seen, out);
        }
        // 3) Try each variant as StructureSet
        if (out.isEmpty()) {
            for (String variant : dict.getVariants(canonical)) {
                if (variant.equals(canonical)) continue;
                collectFromStructureSet(ctx, variant, minX, maxX, minZ, maxZ, seen, out);
                if (!out.isEmpty()) break;
            }
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
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (!structureChunk(ctx, canonicalKey, cx, cz, false)) continue;
                    long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(pk)) continue;
                    out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
                }
            }
        }

        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (!structureChunk(ctx, key, cx, cz, false)) continue;
                    long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(pk)) continue;
                    out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
                }
            }
        }

        if (out.isEmpty()) {
            collectFromStructureSet(ctx, canonical, minX, maxX, minZ, maxZ, seen, out);
        }
        if (out.isEmpty()) {
            for (String variant : dict.getVariants(canonical)) {
                if (variant.equals(canonical)) continue;
                collectFromStructureSet(ctx, variant, minX, maxX, minZ, maxZ, seen, out);
                if (!out.isEmpty()) break;
            }
        }
        return out;
    }

    @Override
    public int[] firstPosition(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        // 0) Try canonical directly as a Structure key
        ResourceKey<Structure> canonicalKey = resolveKey(canonical);
        if (canonicalKey != null) {
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, canonicalKey, cx, cz)) {
                        return new int[]{cx * 16 + 8, cz * 16 + 8};
                    }
                }
            }
        }

        // 1) Try variant Structure keys
        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, key, cx, cz)) {
                        return new int[]{cx * 16 + 8, cz * 16 + 8};
                    }
                }
            }
        }

        // 2) Try canonical as StructureSet
        int[] r = firstFromStructureSet(ctx, canonical, minX, maxX, minZ, maxZ);
        if (r != null) return r;

        // 3) Try each variant as a StructureSet
        for (String variant : dict.getVariants(canonical)) {
            if (variant.equals(canonical)) continue;
            r = firstFromStructureSet(ctx, variant, minX, maxX, minZ, maxZ);
            if (r != null) return r;
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
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, canonicalKey, cx, cz, false)) {
                        return new int[]{cx * 16 + 8, cz * 16 + 8};
                    }
                }
            }
        }

        for (String realId : dict.getVariants(canonical)) {
            if (realId.equals(canonical)) continue;
            ResourceKey<Structure> key = resolveKey(realId);
            if (key == null) continue;
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    if (structureChunk(ctx, key, cx, cz, false)) {
                        return new int[]{cx * 16 + 8, cz * 16 + 8};
                    }
                }
            }
        }

        int[] r = firstFromStructureSet(ctx, canonical, minX, maxX, minZ, maxZ);
        if (r != null) return r;

        for (String variant : dict.getVariants(canonical)) {
            if (variant.equals(canonical)) continue;
            r = firstFromStructureSet(ctx, variant, minX, maxX, minZ, maxZ);
            if (r != null) return r;
        }

        return null;
    }

    private int[] firstFromStructureSet(WorldContext ctx, String canonical,
                                         int minX, int maxX, int minZ, int maxZ) {
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
                    return new int[]{cx * 16 + 8, cz * 16 + 8};
                }
            }
        }
        return null;
    }

    private void collectFromStructureSet(WorldContext ctx, String canonical,
                                          int minX, int maxX, int minZ, int maxZ,
                                          Set<Long> seen, List<int[]> out) {
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
                long pk = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                if (!seen.add(pk)) continue;
                out.add(new int[]{cx * 16 + 8, cz * 16 + 8});
            }
        }
    }

    @Override
    public boolean hasAnyPlacementWithin(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical) {
        int cX = centerX >> 4, cZ = centerZ >> 4;
        int minX = cX - radiusChunks, maxX = cX + radiusChunks;
        int minZ = cZ - radiusChunks, maxZ = cZ + radiusChunks;

        // 0) Try canonical directly as a Structure key
        //    (e.g. "minecraft:village_desert" IS a valid Structure key in the compile-time registry)
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

        // 2) Try canonical as StructureSet key
        if (tryStructureSet(ctx, canonical, minX, maxX, minZ, maxZ)) return true;

        // 3) Try each variant as a StructureSet key
        for (String variant : dict.getVariants(canonical)) {
            if (variant.equals(canonical)) continue;
            if (tryStructureSet(ctx, variant, minX, maxX, minZ, maxZ)) return true;
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
                if (isStructureChunk(ctx, placement, seed, cx, cz)) return true;
            }
        }
        return false;
    }
}
