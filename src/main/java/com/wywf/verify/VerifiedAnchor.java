package com.wywf.verify;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;

public record VerifiedAnchor(String id, int x, int z, int minX, int minZ, int maxX, int maxZ,
                             int pieces, String dimension, List<Bounds> bounds, String structureId) {
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds of(BoundingBox box) {
            return new Bounds(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        }

        public boolean contains(int x, int y, int z) {
            return minX <= x && x <= maxX && minY <= y && y <= maxY && minZ <= z && z <= maxZ;
        }
    }

    public VerifiedAnchor {
        dimension = dimension == null ? "minecraft:overworld" : dimension;
        bounds = bounds == null ? List.of() : List.copyOf(bounds);
        structureId = structureId == null ? id : structureId;
    }

    public VerifiedAnchor(String id, int x, int z, int minX, int minZ, int maxX, int maxZ,
                          int pieces, String dimension, List<Bounds> bounds) {
        this(id, x, z, minX, minZ, maxX, maxZ, pieces, dimension, bounds, id);
    }

    public VerifiedAnchor(String id, int x, int z, int minX, int minZ, int maxX, int maxZ, int pieces, String dimension) {
        this(id, x, z, minX, minZ, maxX, maxZ, pieces, dimension, List.of());
    }

    public VerifiedAnchor(String id, int x, int z, int minX, int minZ, int maxX, int maxZ, int pieces) {
        this(id, x, z, minX, minZ, maxX, maxZ, pieces, "minecraft:overworld");
    }

    static VerifiedAnchor of(String id, int x, int z, String dimension, StructureStart start) {
        return of(id, id, x, z, dimension, start);
    }

    static VerifiedAnchor of(String id, String actual, int x, int z, String dimension, StructureStart start) {
        var box = start.getBoundingBox();
        return new VerifiedAnchor(id, x, z, box.minX(), box.minZ(), box.maxX(), box.maxZ(), start.getPieces().size(),
                dimension, start.getPieces().stream().map(piece -> Bounds.of(piece.getBoundingBox())).toList(), actual);
    }

    public double distance(int wx, int wz) {
        return Math.hypot(Math.max(0, Math.max((double) minX - wx, (double) wx - maxX)),
                Math.max(0, Math.max((double) minZ - wz, (double) wz - maxZ)));
    }
}
