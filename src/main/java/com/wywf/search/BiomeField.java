package com.wywf.search;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

// Grid of biome keys around a center at fixed Y. Built once per seed, shared by terms on that Y
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

    // True when the biome sits within radiusBlocks of the center
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

    // Nearest distance inside [minBlocks, maxBlocks], else -1. For far/near bands
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

    public int count(ResourceKey<Biome> biomeKey, int radiusBlocks) {
        long r2 = (long) radiusBlocks * radiusBlocks;
        int count = 0;
        for (int i = 0; i < dx.length; i++) {
            if (biomeKey.equals(keys[i])) {
                long d2 = (long) dx[i] * dx[i] + (long) dz[i] * dz[i];
                if (d2 <= r2) count++;
            }
        }
        return count;
    }
}
