package com.wywf.search;

public interface BiomeChecker {

    boolean matchesAt(WorldContext ctx, int blockX, int blockZ, String biomeId);

    int nearestDistanceBlocks(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId);

    boolean exists(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String biomeId);

    /**
     * Samples the biome grid once (all points within {@code radiusChunks} of the
     * center, at {@code quartY}, stepping {@code stepChunks}) and returns it as a
     * reusable {@link BiomeField}. Reusing one field across several biome terms
     * avoids re-sampling the same coordinates.
     */
    BiomeField sampleField(WorldContext ctx, int centerX, int centerZ, int quartY, int radiusChunks, int stepChunks);
}
