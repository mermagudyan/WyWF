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
     *  much finer grid than the surface, so they don't fall between nodes. */
    public int effectiveStep(int quartY) {
        return isUndergroundY(quartY) ? UNDERGROUND_STEP_CHUNKS : Math.max(1, stepChunks);
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
        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return false;
        Holder<Biome> holder = biomeSource.getNoiseBiome(blockX >> 2, quartYForSurfaceMatches(), blockZ >> 2, sampler);
        if (holder == null) return false;
        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        return key != null && allowedBiomes.contains(key);
    }

    public ResourceKey<Biome> keyOf(String biomeId) {
        return keyCache.computeIfAbsent(biomeId, id -> {
            Identifier ident = Identifier.tryParse(id);
            return ident == null ? null : ResourceKey.create(Registries.BIOME, ident);
        });
    }

    @Override
    public int nearestDistanceBlocks(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId) {
        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return -1;

        ResourceKey<Biome> target = keyOf(biomeId);
        if (target == null) return -1;

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        final int quartY = quartYFor(biomeId);
        final int step = effectiveStep(quartY);
        long best = Long.MAX_VALUE;

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx += step) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz += step) {
                int quartX = (cx << 4) >> 2;
                int quartZ = (cz << 4) >> 2;

                Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
                if (holder == null) continue;

                ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
                if (key != null && target.equals(key)) {
                    long bx = (long) (cx - centerChunkX) << 4;
                    long bz = (long) (cz - centerChunkZ) << 4;
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
        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return false;

        ResourceKey<Biome> target = keyOf(biomeId);
        if (target == null) return false;

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        final int quartY = quartYFor(biomeId);
        final int step = effectiveStep(quartY);

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx += step) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz += step) {
                int quartX = (cx << 4) >> 2;
                int quartZ = (cz << 4) >> 2;

                Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
                if (holder == null) continue;

                ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
                if (target.equals(key)) return true;
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
        if (mod == Modifier.NEAR) {
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
        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return false;

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int surfaceQuartY = SURFACE_Y >> 2;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int quartX = ((centerChunkX + dx) << 4) >> 2;
                int quartZ = ((centerChunkZ + dz) << 4) >> 2;
                Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, surfaceQuartY, quartZ, sampler);
                if (holder == null) continue;
                ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
                if (undergroundKey.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public BiomeField sampleField(WorldContext ctx, int centerX, int centerZ,
                                   int quartY, int radiusChunks, int stepChunks) {
        BiomeSource biomeSource = ctx.biomeSource;
        Climate.Sampler sampler = ctx.sampler();
        if (biomeSource == null || sampler == null) return EMPTY;

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;
        int step = Math.max(1, stepChunks);

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
                int quartX = (cx << 4) >> 2;
                int quartZ = (cz << 4) >> 2;
                Holder<Biome> holder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, sampler);
                dx[i] = (cx - centerChunkX) << 4;
                dz[i] = (cz - centerChunkZ) << 4;
                keys[i] = holder == null ? null : holder.unwrapKey().orElse(null);
                i++;
            }
        }
        return new BiomeField(quartY, dx, dz, keys);
    }
}
