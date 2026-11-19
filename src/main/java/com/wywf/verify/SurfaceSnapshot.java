package com.wywf.verify;

public record SurfaceSnapshot(long seed, int x, int z, int size, int[] colors, int[] heights,
                              String[] blocks, String[] biomes, float[] temperatures, float[] climate,
                              boolean complete) {
    public boolean contains(int wx, int wz) { return wx >= x && wz >= z && wx < x + size && wz < z + size; }
    public int index(int wx, int wz) { return (wz - z) * size + wx - x; }
}
