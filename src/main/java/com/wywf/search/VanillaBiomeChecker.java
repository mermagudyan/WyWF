package com.wywf.search;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.*;

public final class VanillaBiomeChecker implements BiomeChecker {

    private volatile int stepChunks = 4;

    private static final int SURFACE_Y = 64;

    /** Underground (cave) biomes only exist below the surface, so they must be sampled at depth. */
    private static final Map<String, Integer> UNDERGROUND_BIOME_Y = Map.of(
            "minecraft:deep_dark", -50,
            "minecraft:lush_caves", -50,
            "minecraft:dripstone_caves", -50);

    private static boolean isUnderground(String biomeId) {
        return UNDERGROUND_BIOME_Y.containsKey(biomeId);
    }

    private static int quartYFor(String biomeId) {
        return UNDERGROUND_BIOME_Y.getOrDefault(biomeId, SURFACE_Y) >> 2;
    }

    private final Map<String, ResourceKey<Biome>> keyCache = new java.util.concurrent.ConcurrentHashMap<>();

    public VanillaBiomeChecker stepChunks(int v) {
        this.stepChunks = Math.max(1, v);
        return this;
    }

    private ResourceKey<Biome> keyOf(String biomeId) {
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
}
