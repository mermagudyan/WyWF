package com.wywf.search;

public interface BiomeChecker {

    boolean matchesAt(WorldContext ctx, int blockX, int blockZ, String biomeId);

    int nearestDistanceBlocks(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId);

    boolean exists(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId);
}
