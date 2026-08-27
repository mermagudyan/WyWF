package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import com.wywf.core.Modifier;

import java.util.*;

public final class VanillaBiomeChecker implements BiomeChecker {

    private volatile int stepChunks = 4;

    /** Underground (cave) biomes are sampled on a much finer grid than the
     *  surface, because on the coarse stepChunks=4 (64-block) grid a cave
     *  biome at Y=-50 can fall between sample nodes and never match. */
    private static final int UNDERGROUND_STEP_CHUNKS = 1;

    public static final int SURFACE_Y = 64;

    private static final BiomeField EMPTY = new BiomeField(SURFACE_Y >> 2, new int[0], new int[0], new ResourceKey[0]);

    /** Underground (cave) biomes only exist below the surface, so they must be sampled at depth. */
    private static final Map<String, Integer> UNDERGROUND_BIOME_Y = Map.of(
            "minecraft:deep_dark", -50,
            "minecraft:lush_caves", -50,
            "minecraft:dripstone_caves", -50,
            "minecraft:sulfur_caves", -50);

    /**
     * Underground biomes are only searchable by proximity (`near`/`far`) — they
     * are never "right here" at the surface. `sulfur_caves` is restricted to
     * `near` only, the others also allow `far`.
     */
    private static boolean undergroundNearOnly(String biomeId) {
        return "minecraft:sulfur_caves".equals(biomeId);
    }

    public boolean isUnderground(String biomeId) {
        return UNDERGROUND_BIOME_Y.containsKey(biomeId);
    }

    private final Map<String, ResourceKey<Biome>> keyCache = new java.util.concurrent.ConcurrentHashMap<>();

    public VanillaBiomeChecker stepChunks(int v) {
        this.stepChunks = Math.max(1, v);
        return this;
    }

    public int stepChunks() {
        return stepChunks;
    }

    /** Step used when sampling a given quart-Y. Underground (cave) biomes use a
     *  much finer grid than the surface, so they don't fall between nodes.
     *  For surface biomes, the step is capped so that the grid has at least
     *  3 sample points per axis within the search radius. */
    public int effectiveStep(int quartY) {
        return effectiveStep(quartY, Integer.MAX_VALUE);
    }

    public int effectiveStep(int quartY, int radiusChunks) {
        if (isUndergroundY(quartY)) return UNDERGROUND_STEP_CHUNKS;
        int base = Math.max(1, stepChunks);
        if (radiusChunks <= 0) return base;
        int maxStep = Math.max(1, radiusChunks / 2);
        return Math.min(base, maxStep);
    }

    private static boolean isUndergroundY(int quartY) {
        for (int y : UNDERGROUND_BIOME_Y.values()) {
            if ((y >> 2) == quartY) return true;
        }
        return false;
    }

    public int quartYFor(String biomeId) {
        return UNDERGROUND_BIOME_Y.getOrDefault(biomeId, SURFACE_Y) >> 2;
    }

    /** Unified surface quart-Y for structure-biome checks (block Y = {@code SURFACE_Y}). */
    public static int quartYForSurfaceMatches() {
        return SURFACE_Y >> 2;
    }

    /** Surface-biome predicate used by structure checks; identical Y to surface sampling. */
    public static boolean quartYForSurfaceMatches(WorldContext ctx, int blockX, int blockZ,
                                                   Set<ResourceKey<Biome>> allowedBiomes) {
        if (CubiomesBridge.isActive()) {
            // Only trust the native answer when at least one allowed biome is
            // representable in cubiomes IDs; otherwise fall back to the Java
            // sampler (a missing ID would otherwise always-fail silently).
            boolean anyKnown = false;
            int biomeId = CubiomesBridge.getBiomeAt(blockX, quartYForSurfaceMatches() * 4, blockZ);
            for (ResourceKey<Biome> key : allowedBiomes) {
                int id = cubiomesBiomeId(key);
                if (id == -1) continue;
                anyKnown = true;
                if (id == biomeId) return true;
            }
            if (anyKnown) return false;
        }
        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return false;
        Holder<Biome> holder = biomeSource.getNoiseBiome(blockX >> 2, quartYForSurfaceMatches(), blockZ >> 2, sampler);
        if (holder == null) return false;
        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        return key != null && allowedBiomes.contains(key);
    }

    private static final Map<String, Integer> CUBIOMES_BIOME_MAP = Map.ofEntries(
        Map.entry("minecraft:ocean", 0), Map.entry("minecraft:plains", 1),
        Map.entry("minecraft:desert", 2), Map.entry("minecraft:windswept_hills", 3),
        Map.entry("minecraft:forest", 4), Map.entry("minecraft:taiga", 5),
        Map.entry("minecraft:swamp", 6), Map.entry("minecraft:river", 7),
        Map.entry("minecraft:snowy_plains", 12), Map.entry("minecraft:mushroom_fields", 14),
        Map.entry("minecraft:beach", 16), Map.entry("minecraft:windswept_forest", 18),
        Map.entry("minecraft:savanna", 35), Map.entry("minecraft:savanna_plateau", 36),
        Map.entry("minecraft:badlands", 37), Map.entry("minecraft:wooded_badlands", 38),
        Map.entry("minecraft:deep_ocean", 24),
        Map.entry("minecraft:birch_forest", 27), Map.entry("minecraft:dark_forest", 29),
        Map.entry("minecraft:snowy_taiga", 30), Map.entry("minecraft:old_growth_pine_taiga", 32),
        Map.entry("minecraft:windswept_wooded_hills", 34), Map.entry("minecraft:jungle", 21),
        Map.entry("minecraft:bamboo_jungle", 168), Map.entry("minecraft:sunflower_plains", 129),
        Map.entry("minecraft:flower_forest", 131), Map.entry("minecraft:meadow", 177),
        Map.entry("minecraft:grove", 178), Map.entry("minecraft:snowy_slopes", 179),
        Map.entry("minecraft:jagged_peaks", 180), Map.entry("minecraft:frozen_peaks", 181),
        Map.entry("minecraft:stony_peaks", 182), Map.entry("minecraft:cherry_grove", 185),
        Map.entry("minecraft:mangrove_swamp", 184), Map.entry("minecraft:soul_sand_valley", 170),
        Map.entry("minecraft:crimson_forest", 171), Map.entry("minecraft:warped_forest", 172),
        Map.entry("minecraft:basalt_deltas", 173), Map.entry("minecraft:dripstone_caves", 174),
        Map.entry("minecraft:lush_caves", 175), Map.entry("minecraft:deep_dark", 183),
        Map.entry("minecraft:pale_garden", 186)
    );

    private static final Map<ResourceKey<Biome>, Integer> CUBIOMES_KEY_MAP = new HashMap<>();
    private static final Map<Integer, ResourceKey<Biome>> CUBIOMES_ID_MAP = new HashMap<>();
    static {
        for (Map.Entry<String, Integer> e : CUBIOMES_BIOME_MAP.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            if (id != null) {
                ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
                CUBIOMES_KEY_MAP.put(key, e.getValue());
                CUBIOMES_ID_MAP.put(e.getValue(), key);
            }
        }
    }

    private static int cubiomesBiomeId(ResourceKey<Biome> key) {
        Integer id = CUBIOMES_KEY_MAP.get(key);
        return id != null ? id : -1;
    }

    public ResourceKey<Biome> keyOf(String biomeId) {
        return keyCache.computeIfAbsent(biomeId, id -> {
            Identifier ident = Identifier.tryParse(id);
            return ident == null ? null : ResourceKey.create(Registries.BIOME, ident);
        });
    }

    @Override
    public int nearestDistanceBlocks(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId) {
        ResourceKey<Biome> target = keyOf(biomeId);
        if (target == null) return -1;

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        final int quartY = quartYFor(biomeId);
        final int step = effectiveStep(quartY, radiusChunks);
        final int blockY = quartY << 2;
        // Native path only when the target biome is representable in cubiomes;
        // otherwise the Java sampler below is the source of truth.
        final boolean useNative = CubiomesBridge.isActive() && CUBIOMES_KEY_MAP.containsKey(target);
        long best = Long.MAX_VALUE;

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx += step) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz += step) {
                boolean match = false;

                if (useNative) {
                    int biomeId2 = CubiomesBridge.getBiomeAt(cx << 4, blockY, cz << 4);
                    if (biomeId2 >= 0) {
                        Integer expected = CUBIOMES_KEY_MAP.get(target);
                        match = expected != null && expected == biomeId2;
                    }
                } else {
                    BiomeSource biomeSource = ctx.biomeSource;
                    Climate.Sampler sampler = ctx.sampler();
                    if (biomeSource == null || sampler == null) return -1;
                    int quartX = (cx << 4) >> 2;
                    int quartZ = (cz << 4) >> 2;
                    Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
                    if (holder != null) {
                        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
                        match = target.equals(key);
                    }
                }

                if (match) {
                    long bx = ((long) cx << 4) - centerX;
                    long bz = ((long) cz << 4) - centerZ;
                    long d2 = bx * bx + bz * bz;
                    if (d2 < best) best = d2;
                }
            }
        }

        if (best == Long.MAX_VALUE) return -1;
        return (int) Math.round(Math.sqrt((double) best));
    }

    @Override
    public boolean exists(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId) {
        ResourceKey<Biome> target = keyOf(biomeId);
        if (target == null) return false;

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        final int quartY = quartYFor(biomeId);
        final int step = effectiveStep(quartY, radiusChunks);
        final long radiusBlocks = (long) radiusChunks << 4;
        final long r2 = radiusBlocks * radiusBlocks;
        final int blockY = quartY << 2;
        // Native path only when the target biome is representable in cubiomes.
        final boolean useNative = CubiomesBridge.isActive() && CUBIOMES_KEY_MAP.containsKey(target);

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx += step) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz += step) {
                long bx = ((long) cx << 4) - centerX;
                long bz = ((long) cz << 4) - centerZ;
                if (bx * bx + bz * bz > r2) continue;

                int blockX = cx << 4;

                if (useNative) {
                    int biomeId2 = CubiomesBridge.getBiomeAt(blockX, blockY, cz << 4);
                    if (biomeId2 >= 0) {
                        Integer expected = CUBIOMES_KEY_MAP.get(target);
                        if (expected != null && expected == biomeId2) return true;
                    }
                } else {
                    BiomeSource biomeSource = ctx.biomeSource;
                    Climate.Sampler sampler = ctx.sampler();
                    if (biomeSource == null || sampler == null) return false;
                    int quartX = (cx << 4) >> 2;
                    int quartZ = (cz << 4) >> 2;
                    Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
                    if (holder == null) continue;
                    ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
                    if (target.equals(key)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean matchesAt(WorldContext ctx, int blockX, int blockZ, String biomeId) {
        // UNDER is a surface-relative check and does not apply to cave biomes.
        if (isUnderground(biomeId)) return false;

        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return false;

        ResourceKey<Biome> target = keyOf(biomeId);
        if (target == null) return false;

        Holder<Biome> holder = biomeSource.getNoiseBiome(blockX >> 2, SURFACE_Y >> 2, blockZ >> 2, sampler);
        if (holder == null) return false;

        return target.equals(holder.unwrapKey().orElse(null));
    }

    /**
     * Evaluates an underground (cave) biome against an already-sampled {@link BiomeField}.
     * Cave biomes only exist below the surface, so they are searchable by proximity
     * only: {@code near} always, and {@code far} for all except {@code sulfur_caves}
     * (near-only). All other modifiers return false.
     *
     * <p>Cross-check: if the same biome also appears at the surface Y level,
     * the climate sampler is producing a false positive (underground biomes
     * should never match at surface depth). This filters out inaccuracies in
     * {@link ReusableClimateSampler} at underground Y levels.</p>
     */
    public boolean evalUnderground(BiomeField field, String biomeId, Modifier mod,
                                    int nearRadiusBlocks, int farMinBlocks, int farMaxBlocks) {
        return evalUnderground(field, biomeId, mod, nearRadiusBlocks, farMinBlocks, farMaxBlocks, null, 0, 0);
    }

    public boolean evalUnderground(BiomeField field, String biomeId, Modifier mod,
                                    int nearRadiusBlocks, int farMinBlocks, int farMaxBlocks,
                                    WorldContext ctx, int centerX, int centerZ) {
        if (!isUnderground(biomeId)) return false;
        ResourceKey<Biome> key = keyOf(biomeId);
        if (key == null) return false;

        boolean found = false;
        if (mod == Modifier.NEAR || mod == Modifier.UNDER) {
            int d = field.nearestDistanceBlocks(key, 0, nearRadiusBlocks);
            found = d >= 0 && d <= nearRadiusBlocks;
        } else if (mod == Modifier.FAR && !undergroundNearOnly(biomeId)) {
            int d = field.nearestDistanceBlocks(key, farMinBlocks, farMaxBlocks);
            found = d >= 0;
        } else if (mod == Modifier.NEVER) {
            int d = field.nearestDistanceBlocks(key, 0, Integer.MAX_VALUE);
            if (d < 0) return true;
            if (ctx != null && isSamplerFalsePositive(ctx, key, centerX, centerZ)) {
                return true;
            }
            return false;
        }

        if (!found) return false;

        if (ctx != null && isSamplerFalsePositive(ctx, key, centerX, centerZ)) {
            return false;
        }
        return true;
    }

    /**
     * Checks if an underground biome also appears at surface Y — a clear sign
     * the climate sampler produced a false positive. Underground biomes like
     * {@code deep_dark} should never match at the surface depth parameter.
     */
    private boolean isSamplerFalsePositive(WorldContext ctx, ResourceKey<Biome> undergroundKey,
                                            int centerX, int centerZ) {
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int surfaceBlockY = SURFACE_Y;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean match = false;
                if (CubiomesBridge.isActive()) {
                    int biomeId = CubiomesBridge.getBiomeAt((centerChunkX + dx) << 4, surfaceBlockY, (centerChunkZ + dz) << 4);
                    if (biomeId >= 0) {
                        Integer expected = CUBIOMES_KEY_MAP.get(undergroundKey);
                        match = expected != null && expected == biomeId;
                    }
                } else {
                    BiomeSource biomeSource = ctx.biomeSource;
                    Climate.Sampler sampler = ctx.sampler();
                    if (biomeSource == null || sampler == null) return false;
                    int quartX = ((centerChunkX + dx) << 4) >> 2;
                    int quartZ = ((centerChunkZ + dz) << 4) >> 2;
                    int surfaceQuartY = SURFACE_Y >> 2;
                    Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, surfaceQuartY, quartZ, sampler);
                    if (holder != null) {
                        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
                        match = undergroundKey.equals(key);
                    }
                }
                if (match) return true;
            }
        }
        return false;
    }

    @Override
    public BiomeField sampleField(WorldContext ctx, int centerX, int centerZ,
                                   int quartY, int radiusChunks, int stepChunks) {
        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int step = Math.max(1, stepChunks);
        final int blockY = quartY << 2;

        int count = 0;
        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx += step) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz += step) {
                count++;
            }
        }

        int[] dx = new int[count];
        int[] dz = new int[count];
        ResourceKey<Biome>[] keys = new ResourceKey[count];
        int i = 0;
        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx += step) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz += step) {
                dx[i] = (cx << 4) - centerX;
                dz[i] = (cz << 4) - centerZ;

                if (CubiomesBridge.isActive()) {
                    int biomeId = CubiomesBridge.getBiomeAt(cx << 4, blockY, cz << 4);
                    keys[i] = CUBIOMES_ID_MAP.get(biomeId);
                } else {
                    BiomeSource biomeSource = ctx.biomeSource;
                    Climate.Sampler sampler = ctx.sampler();
                    if (biomeSource == null || sampler == null) return EMPTY;
                    int quartX = (cx << 4) >> 2;
                    int quartZ = (cz << 4) >> 2;
                    Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
                    keys[i] = holder == null ? null : holder.unwrapKey().orElse(null);
                }
                i++;
            }
        }
        return new BiomeField(quartY, dx, dz, keys);
    }
}
