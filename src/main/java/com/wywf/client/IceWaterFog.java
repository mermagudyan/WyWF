package com.wywf.client;

import com.wywf.map.WaterMap;
import com.wywf.verify.ReliefTile;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class IceWaterFog {
    private static final int[] WEIGHT = {255, 128, 75};
    private final int stride;
    private final int[] corners;
    private int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX, maxZ;

    private IceWaterFog(int side) {
        stride = side + 1;
        corners = new int[stride * stride];
    }

    static IceWaterFog build(ReliefTile[] tiles, int tileSide, BooleanSupplier cancelled) {
        IceWaterFog fog = null;
        for (int chunk = 0; chunk < tiles.length; chunk++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var tile = tiles[chunk];
            if (tile == null || !tile.hasCoveredWater()) continue;
            if (fog == null) fog = new IceWaterFog(tileSide * 16);
            int x = (chunk % tileSide) * 16, z = (chunk / tileSide) * 16;
            for (int column = 0; column < 256; column++) {
                int top = tile.coveredWaterTop(column);
                if (top != Integer.MIN_VALUE) fog.mark(x + (column & 15), z + (column >> 4), top);
            }
        }
        return fog;
    }

    private void mark(int x, int z, int top) {
        minX = Math.min(minX, Math.max(0, x - 2));
        minZ = Math.min(minZ, Math.max(0, z - 2));
        maxX = Math.max(maxX, Math.min(stride - 1, x + 3));
        maxZ = Math.max(maxZ, Math.min(stride - 1, z + 3));
        // one world block on each side of the cell corners, then zero at two blocks
        for (int dz = -1; dz <= 2; dz++) for (int dx = -1; dx <= 2; dx++) {
            int cx = x + dx, cz = z + dz;
            if (cx < 0 || cz < 0 || cx >= stride || cz >= stride) continue;
            int distanceSquared = (dx == -1 || dx == 2 ? 1 : 0) + (dz == -1 || dz == 2 ? 1 : 0);
            int weight = WEIGHT[distanceSquared];
            int at = cz * stride + cx, existing = corners[at];
            if (weight > (existing & 255) || weight == (existing & 255) && top > existing >> 8)
                corners[at] = top << 8 | weight;
        }
    }

    int stride() { return stride; }
    int[] corners() { return corners; }

    boolean maySee(float x, float z, float cameraX, float cameraZ) {
        return (x + 1 >= minX || cameraX < 0) && (x - 1 <= maxX || cameraX > 0)
                && (z + 1 >= minZ || cameraZ < 0) && (z - 1 <= maxZ || cameraZ > 0);
    }

    float strength(float x, float y, float z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return 0;
        int left = Math.min((int) x, stride - 2), near = Math.min((int) z, stride - 2);
        int at = near * stride + left;
        float u = x - left, v = z - near;
        float first = lerp(weight(at, y), weight(at + 1, y), u);
        float last = lerp(weight(at + stride, y), weight(at + stride + 1, y), u);
        return lerp(first, last, v) / 255f;
    }

    private int weight(int at, float y) {
        int entry = corners[at];
        return y <= (entry >> 8) + 1.0001f ? entry & 255 : 0;
    }

    private static float lerp(float a, float b, float progress) {
        return a + (b - a) * progress;
    }

    static int shadow(int waterTint) {
        int ocean = (waterTint & 0xFFFFFF) == 0 ? 0xFF244668 : waterTint;
        return WaterMap.blend(ocean, 0xFF0A182D, .72f);
    }
}
