package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.verify.ReliefShapes;
import com.wywf.verify.ReliefTile;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class ReliefGpuGeometry {
    static final int WORDS = 6;
    static final int QUAD = 0, CRYSTAL = 1, PLANT = 2, PLANT_CROSS = 3, RIM = 4, SLICE_RIM = 5;
    static final int ANY_DIRECTION = 6, DIRECTIONS = 7;
    private static final int FULL_BOX = 128 | 128 << 8 | 128 << 16;

    record Part(int[] data, int count, int[] offsets) {}

    record Plan(Part[] terrain, Part overlays, Part rim, boolean[] eyeChunks) {}

    static Plan prepare(ReliefMesh mesh, DimensionSpace dimension, boolean slice, BooleanSupplier cancelled) {
        return prepare(mesh, dimension, slice, null, 0, 0, cancelled);
    }

    static Plan prepare(ReliefMesh mesh, DimensionSpace dimension, boolean slice, ReliefRaster.Outline outline,
                        int originX, int originZ, BooleanSupplier cancelled) {
        int[] faces = mesh.faces();
        int[] chunks = mesh.chunkOffsets();
        int chunkCount = chunks == null ? 1 : chunks.length - 1;
        int chunkSide = Math.ceilDiv(mesh.side(), 16);
        int[][] terrainOffsets = new int[DIRECTIONS][chunkCount + 1];
        int[] overlayOffsets = new int[chunkCount + 1];
        var terrain = new IntArrayList[DIRECTIONS];
        for (int direction = 0; direction < DIRECTIONS; direction++)
            terrain[direction] = new IntArrayList(outline != null || direction == ANY_DIRECTION ? 0 : faces.length / 5);
        var overlays = new IntArrayList();
        boolean[] eyeChunks = null;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            for (int direction = 0; direction < DIRECTIONS; direction++)
                terrainOffsets[direction][chunk] = terrain[direction].size() / WORDS;
            overlayOffsets[chunk] = overlays.size() / WORDS;
            if (outline != null && chunks != null && !outline.intersectsChunk(originX + (chunk % chunkSide) * 16,
                    originZ + (chunk / chunkSide) * 16)) continue;
            int start = chunks == null ? 0 : chunks[chunk], end = chunks == null ? faces.length : chunks[chunk + 1];
            for (int i = start; i < end; i += 3) {
                if (i % 3072 == 0 && cancelled.getAsBoolean()) throw new CancellationException();
                int encoded = faces[i], cell = encoded / 6, dir = encoded % 6, y = faces[i + 1], color = faces[i + 2];
                int x = cell % mesh.side(), z = cell / mesh.side(), material = color & ReliefTile.FACE_TYPE;
                boolean fogEdge = dimension != DimensionSpace.END && (x == 0 || z == 0
                        || x == mesh.side() - 1 || z == mesh.side() - 1);
                if (outline != null && (fogEdge || !outline.matchesFace(originX + x, y, originZ + z, color))) continue;
                if (fogEdge && material != ReliefTile.END_CRYSTAL) {
                    instance(terrain[dir], x, y, z, 0xFF000000, QUAD, 0, FULL_BOX, dir, 0);
                    continue;
                }
                float shadow = ReliefRaster.shadow(mesh, cell, y);
                if (ReliefTile.plant(material)) {
                    int tint = material | (ReliefRaster.directionalColor(color, ReliefMesh.TOP, shadow) & 0xFFFFFF);
                    instance(overlays, x, y, z, tint, PLANT, 0, FULL_BOX, ReliefMesh.TOP, 0);
                    instance(overlays, x, y, z, tint, PLANT_CROSS, 0, FULL_BOX, ReliefMesh.TOP, 0);
                } else if (material == ReliefTile.WATER_FACE) {
                    instance(overlays, x, y, z, color, QUAD, 0, FULL_BOX, dir, 0);
                } else if (ReliefTile.shaped(material)) {
                    var shape = ReliefShapes.get(color);
                    boolean eye = material == ReliefTile.END_PORTAL_EYE;
                    if (eye) {
                        if (eyeChunks == null) eyeChunks = new boolean[chunkCount];
                        eyeChunks[chunk] = true;
                    }
                    for (AABB box : shape.boxes()) {
                        int low = pack(box.minX, box.minY, box.minZ), high = pack(box.maxX, box.maxY, box.maxZ);
                        for (int d = 0; d < 6; d++) {
                            int tint = eye && d == ReliefMesh.TOP && box.maxY > 13d / 16
                                    ? ReliefTile.END_PORTAL_EYE | 0x111D29
                                    : ReliefRaster.directionalColor(shape.color(), d, shadow);
                            instance(terrain[d], x, y, z, tint, QUAD, low, high, d, 0);
                        }
                    }
                } else {
                    int kind = material == ReliefTile.END_CRYSTAL ? CRYSTAL : QUAD;
                    int tint = color;
                    if (kind != CRYSTAL && material != ReliefTile.ENTRANCE_FACE
                            && color != ReliefTile.CAVE_SHADOW && material != ReliefTile.ICE_WATER_SHADOW) {
                        if ((material & 0xF0000000) == 0xE0000000) {
                            int recess = material >>> 24 & 15;
                            tint = MenuAccent.mix(0xFF000000 | color, 0xFF181821, Math.min(.92f, .35f + recess * .075f));
                        }
                        tint = ReliefRaster.directionalColor(tint, dir, shadow);
                    }
                    instance(terrain[kind == CRYSTAL ? ANY_DIRECTION : dir], x, y, z, tint, kind, 0, FULL_BOX, dir, Math.round(31 * shadow));
                }
            }
        }
        var solid = new Part[DIRECTIONS];
        for (int direction = 0; direction < DIRECTIONS; direction++) {
            var list = terrain[direction];
            int count = list.size() / WORDS;
            terrainOffsets[direction][chunkCount] = count;
            solid[direction] = new Part(list.elements(), count, terrainOffsets[direction]);
        }
        overlayOffsets[chunkCount] = overlays.size() / WORDS;
        var rim = new IntArrayList();
        if (outline == null && mesh.fogRim() != null) {
            int side = mesh.side(), bottom = mesh.minimum();
            for (int edge = 0; edge < 4; edge++) for (int i = 0; i < side; i++) {
                int top = mesh.fogRim()[edge * side + i];
                if (slice) top = Math.min(top, bottom + 8);
                if (top <= bottom) continue;
                int x = edge < 2 ? edge * side : i;
                int z = edge < 2 ? i : (edge - 2) * side;
                instance(rim, x, bottom, z, 0, slice ? SLICE_RIM : RIM, 0, top - bottom,
                        ReliefMesh.WEST + edge, 0);
            }
        }
        return new Plan(solid,
                new Part(overlays.elements(), overlays.size() / WORDS, overlayOffsets),
                new Part(rim.elements(), rim.size() / WORDS, null), eyeChunks);
    }

    static int facing(float sinYaw, float cosYaw, float cosPitch) {
        int top = 1 << ReliefMesh.TOP | 1 << ANY_DIRECTION;
        if (Math.abs(cosPitch) < 1e-6f) return top;
        if (sinYaw != 0) top |= 1 << (sinYaw < 0 ? ReliefMesh.WEST : ReliefMesh.EAST);
        if (cosYaw != 0) top |= 1 << (cosYaw < 0 ? ReliefMesh.NORTH : ReliefMesh.SOUTH);
        return top;
    }

    private static int pack(double x, double y, double z) {
        return (int)Math.round(x * 128) | (int)Math.round(y * 128) << 8 | (int)Math.round(z * 128) << 16;
    }

    private static void instance(IntArrayList out, int x, int y, int z, int tint, int kind, int low, int high,
                                 int direction, int shadow) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(tint);
        out.add(kind << 24 | low);
        out.add((shadow << 3 | direction) << 24 | high);
    }
}
