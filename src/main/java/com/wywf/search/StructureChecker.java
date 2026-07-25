package com.wywf.search;

import java.util.List;

public interface StructureChecker {

    List<int[]> positions(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    List<int[]> positionsPlacementOnly(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    int[] firstPosition(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    int[] firstPositionPlacementOnly(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    boolean hasAnyPlacementWithin(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    final class Result {
        public final boolean found;
        public final int     structureX;
        public final int     structureZ;
        public final String  structureId;

        public Result(boolean found, int x, int z, String id) {
            this.found = found;
            this.structureX = x;
            this.structureZ = z;
            this.structureId = id;
        }

        public static Result notFound() { return new Result(false, 0, 0, null); }

        public static Result found(int x, int z, String id) { return new Result(true, x, z, id); }
    }
}
