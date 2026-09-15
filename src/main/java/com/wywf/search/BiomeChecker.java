package com.wywf.search;

public interface BiomeChecker {

    boolean matchesAt(WorldContext ctx, int blockX, int blockZ, String biomeId);

    int nearestDistanceBlocks(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId);

    boolean exists(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId);

    // Sample the grid once per Y-level; sharing one field across terms avoids re-sampling
    BiomeField sampleField(WorldContext ctx, int centerX, int centerZ, int quartY, int radiusChunks, int stepChunks);
}
