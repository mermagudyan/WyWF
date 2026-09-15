package com.wywf.search;

import java.util.List;

public interface StructureChecker {

    List<int[]> positions(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    List<int[]> positionsPlacementOnly(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    int[] firstPosition(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    int[] firstPositionPlacementOnly(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    boolean hasAnyPlacementWithin(WorldContext ctx, int centerX, int centerZ, int radiusChunks, String canonical);

    // True with any concentric-rings placement. Rings are full-seed, so block prefilters must skip them
    default boolean hasConcentricRings(WorldContext ctx, String canonical) { return false; }

    final class Result {
        public final boolean found;
        public final int     structureX;
        public final int     structureZ;
        public final String  structureId;
        public final List<int[]> candidates;

        public Result(boolean found, int x, int z, String id) {
            this(found, x, z, id, null);
        }

        public Result(boolean found, int x, int z, String id, List<int[]> candidates) {
            this.found = found;
            this.structureX = x;
            this.structureZ = z;
            this.structureId = id;
            this.candidates = candidates;
        }

        public static Result notFound() { return new Result(false, 0, 0, null); }

        public static Result found(int x, int z, String id) { return new Result(true, x, z, id); }

        public static Result foundWith(int x, int z, String id, List<int[]> candidates) {
            return new Result(true, x, z, id, candidates);
        }
    }
}
