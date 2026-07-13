package com.wywf.search;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * A sampled grid of biome keys around a center, at a fixed Y (quart height).
 * Built once per seed and reused across all biome terms that share its Y, so a
 * query with several biome conditions only pays the {@code getNoiseBiome} cost
 * once instead of once per term.
 */
public final class BiomeField {

    private final int quartY;
    private final int[] dx;
    private final int[] dz;
    private final ResourceKey<Biome>[] keys;

    @SuppressWarnings("unchecked")
    BiomeField(int quartY, int[] dx, int[] dz, ResourceKey<Biome>[] keys) {
        this.quartY = quartY;
        this.dx = dx;
        this.dz = dz;
        this.keys = keys;
    }

    public int quartY() {
        return quartY;
    }

    /** True if the given biome is present within {@code radiusBlocks} of the center. */
    public boolean exists(ResourceKey<Biome> biomeKey, int radiusBlocks) {
        long r2 = (long) radiusBlocks * radiusBlocks;
        for (int i = 0; i < dx.length; i++) {
            if (biomeKey.equals(keys[i])) {
                long d2 = (long) dx[i] * dx[i] + (long) dz[i] * dz[i];
                if (d2 <= r2) return true;
            }
        }
        return false;
    }

    /**
     * Nearest block distance to the biome that falls inside {@code [minBlocks, maxBlocks]},
     * or {@code -1} if none. Used for {@code far} / {@code near} distance bands.
     */
    public int nearestDistanceBlocks(ResourceKey<Biome> biomeKey, int minBlocks, int maxBlocks) {
        long min2 = (long) minBlocks * minBlocks;
        long max2 = (long) maxBlocks * maxBlocks;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < dx.length; i++) {
            if (biomeKey.equals(keys[i])) {
                long d2 = (long) dx[i] * dx[i] + (long) dz[i] * dz[i];
                if (d2 >= min2 && d2 <= max2 && d2 < best) best = d2;
            }
        }
        return best == Long.MAX_VALUE ? -1 : (int) Math.round(Math.sqrt((double) best));
    }
}
