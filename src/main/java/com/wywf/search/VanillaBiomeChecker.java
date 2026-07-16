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
        final int step = Math.max(1, stepChunks);
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
        final int step = Math.max(1, stepChunks);

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
     */
    public boolean evalUnderground(BiomeField field, String biomeId, Modifier mod,
                                    int nearRadiusBlocks, int farMinBlocks, int farMaxBlocks) {
        if (!isUnderground(biomeId)) return false;
        ResourceKey<Biome> key = keyOf(biomeId);
        if (key == null) return false;
        if (mod == Modifier.NEAR) {
            int d = field.nearestDistanceBlocks(key, 0, nearRadiusBlocks);
            return d >= 0 && d <= nearRadiusBlocks;
        }
        if (mod == Modifier.FAR && !undergroundNearOnly(biomeId)) {
            int d = field.nearestDistanceBlocks(key, farMinBlocks, farMaxBlocks);
            return d >= 0;
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
