package com.wywf.verify;

import com.wywf.core.DimensionSpace;
import java.nio.file.Path;
import java.util.List;

public final class SavedSurfaceReader {
    private SavedSurfaceReader() {}

    public static List<MapFeature> endReturnGateways(Path world, int x, int z) {
        return List.of();
    }

    public static List<ReliefTile> reliefPage(Path world, long seed, ReliefRequest request) {
        return List.of();
    }

    public static java.util.Map<Long, SurfaceSnapshot> readVisited(Path world, long seed,
            com.wywf.core.DimensionSpace dimension, List<Long> keys) { return java.util.Map.of(); }

    public static List<SurfaceSnapshot> read(Path world, long seed, DimensionSpace dimension, int x, int z) {
        return List.of();
    }
}
