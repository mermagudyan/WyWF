package com.wywf.verify;

public record VerifiedAnchor(String id, int x, int z, int minX, int minZ, int maxX, int maxZ, int pieces, String dimension) {
    public VerifiedAnchor { dimension = dimension == null ? "minecraft:overworld" : dimension; }
    public VerifiedAnchor(String id, int x, int z, int minX, int minZ, int maxX, int maxZ, int pieces) {
        this(id, x, z, minX, minZ, maxX, maxZ, pieces, "minecraft:overworld");
    }
    public double distance(int wx, int wz) {
        return Math.hypot(Math.max(0, Math.max((double) minX - wx, (double) wx - maxX)),
                Math.max(0, Math.max((double) minZ - wz, (double) wz - maxZ)));
    }
}
