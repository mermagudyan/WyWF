package com.wywf.verify;

import java.util.Map;

public record SurfaceSnapshot(long seed, int x, int z, int size, int[] colors, int[] heights,
                              String[] blocks, String[] biomes, float[] temperatures, float[] climate,
                              boolean complete, Map<String, Integer> biomeColors) {
    public SurfaceSnapshot {
        biomeColors = biomeColors == null ? Map.of() : Map.copyOf(biomeColors);
    }

    public SurfaceSnapshot(long seed, int x, int z, int size, int[] colors, int[] heights,
                           String[] blocks, String[] biomes, float[] temperatures, float[] climate, boolean complete) {
        this(seed, x, z, size, colors, heights, blocks, biomes, temperatures, climate, complete, Map.of());
    }

    public boolean contains(int wx, int wz) { return wx >= x && wz >= z && wx < x + size && wz < z + size; }
    public int index(int wx, int wz) { return (wz - z) * size + wx - x; }
}
