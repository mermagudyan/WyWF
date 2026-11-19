package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.verify.ReliefTile;
import com.wywf.verify.ReliefDiagnostics;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

record ReliefMesh(int side, int[] faces, int minimum, int maximum, float[] sunlight, int[] chunkOffsets, int[] fogRim, int[] chunkHeights,
                  int[] stripOffsets, int[] stripHeights, long[][] directions) {
    static final int STRIP_ROWS = 4, STRIPS_PER_CHUNK = 16 / STRIP_ROWS;
    ReliefMesh(int side, int[] faces, int minimum, int maximum) {
        this(side, faces, minimum, maximum, null, null, null, null, null, null, null);
    }

    ReliefMesh(int side, int[] faces, int minimum, int maximum, float[] sunlight, int[] chunkOffsets, int[] fogRim) {
        this(side, faces, minimum, maximum, sunlight, chunkOffsets, fogRim, null, null, null, null);
    }
    static final int TOP = 0, WEST = 1, EAST = 2, NORTH = 3, SOUTH = 4, BOTTOM = 5;
    private static final String[] SIDE_NAMES = {"west", "east", "north", "south"};
    private static final int[] SIDE_FACES = {WEST, EAST, NORTH, SOUTH};
    private static final int[] SIDE_X = {-1, 1, 0, 0}, SIDE_Z = {0, 0, -1, 1};

    static ReliefMesh build(ReliefTile[] tiles, int tileSide, java.util.function.BooleanSupplier cancelled) {
        return build(tiles, tileSide, DimensionSpace.OVERWORLD, cancelled);
    }

    static ReliefMesh build(ReliefTile[] tiles, int tileSide, DimensionSpace dimension, BooleanSupplier cancelled) {
        return new Builder().build(tiles, tileSide, dimension, cancelled);
    }

    static final class Builder {
        private ReliefMesh prior;
        private ReliefTile[] priorTiles;
        private ReliefOpenAir.Exposure priorExposure;
        private DimensionSpace priorDimension;
        private int reusedChunks;

        ReliefMesh build(ReliefTile[] tiles, int tileSide, DimensionSpace dimension, BooleanSupplier cancelled) {
            boolean surface = Arrays.stream(tiles).anyMatch(tile -> tile != null && tile.ground() != null);
            var openAir = surface ? new ReliefOpenAir(tiles, tileSide, cancelled) : null;
            var exposure = openAir == null ? null : openAir.exposure();
            boolean compatible = prior != null && prior.side == tileSide * 16 && priorDimension == dimension
                    && (priorExposure == null) == (exposure == null);
            boolean[] dirty = new boolean[tiles.length];
            if (compatible) {
                for (int chunk = 0; chunk < tiles.length; chunk++) {
                    if (tiles[chunk] == priorTiles[chunk]) continue;
                    dirty[chunk] = true;
                    int x = chunk % tileSide, z = chunk / tileSide;
                    if (x > 0) dirty[chunk - 1] = true;
                    if (x + 1 < tileSide) dirty[chunk + 1] = true;
                    if (z > 0) dirty[chunk - tileSide] = true;
                    if (z + 1 < tileSide) dirty[chunk + tileSide] = true;
                }
                if (exposure != null) exposure.markChanges(priorExposure, dirty, tileSide * 16);
            } else {
                Arrays.fill(dirty, true);
            }
            var mesh = buildMesh(tiles, tileSide, dimension, openAir, compatible ? prior : null, dirty, cancelled);
            reusedChunks = 0;
            for (int chunk = 0; chunk < tiles.length; chunk++) {
                if (!dirty[chunk] && tiles[chunk] != null) reusedChunks++;
            }
            prior = mesh;
            priorTiles = tiles.clone();
            priorExposure = exposure;
            priorDimension = dimension;
            return mesh;
        }

        int reusedChunks() {
            return reusedChunks;
        }
    }

    private static ReliefMesh buildMesh(ReliefTile[] tiles, int tileSide, DimensionSpace dimension,
                                        ReliefOpenAir openAir, ReliefMesh prior, boolean[] dirty, BooleanSupplier cancelled) {
        int side = tileSide * 16;
        var faces = prior == null ? new IntArrayList() : new IntArrayList(prior.faces.length);
        var neighbors = new ReliefTile[4];
        var columns = new int[4];
        boolean surface = openAir != null;
        float[] sunlight = surface ? new float[side * side] : null;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int[] chunkOffsets = new int[tiles.length + 1], chunkHeights = new int[tiles.length * 2];
        int[] stripOffsets = new int[tiles.length * STRIPS_PER_CHUNK + 1];
        int[] stripHeights = new int[tiles.length * STRIPS_PER_CHUNK * 2];
        int[] fogRim = surface || dimension == DimensionSpace.NETHER ? new int[side * 4] : null;
        if (fogRim != null) java.util.Arrays.fill(fogRim, Integer.MIN_VALUE);
        for (int chunk = 0; chunk < tiles.length; chunk++) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            chunkOffsets[chunk] = faces.size();
            int chunkMin = Integer.MAX_VALUE, chunkMax = Integer.MIN_VALUE;
            int startX = (chunk % tileSide) * 16, startZ = (chunk / tileSide) * 16;
            var tile = tiles[chunk];
            int stripStart = chunk * STRIPS_PER_CHUNK;
            boolean reuse = !dirty[chunk] && tile != null;
            if (tile == null && sunlight == null) {
                java.util.Arrays.fill(stripOffsets, stripStart, stripStart + STRIPS_PER_CHUNK, faces.size());
                continue;
            }
            for (int z = startZ; z < startZ + 16; z++) for (int x = startX; x < startX + 16; x++) {
                int strip = stripStart + (z - startZ) / STRIP_ROWS;
                if (x == startX && (z - startZ) % STRIP_ROWS == 0) {
                    stripOffsets[strip] = faces.size();
                    stripHeights[strip * 2] = Integer.MAX_VALUE;
                    stripHeights[strip * 2 + 1] = Integer.MIN_VALUE;
                }
                int col = index(x, z), cell = z * side + x;
                boolean solid = tile != null && tile.colors()[col] != 0;
                int top = solid ? tile.heights()[col] : Integer.MIN_VALUE;
                if (sunlight != null) {
                    float incoming = x == 0 || z == 0 ? Float.NEGATIVE_INFINITY : sunlight[cell - side - 1] - .85f;
                    sunlight[cell] = Math.max(top, incoming);
                }
                if (!solid) continue;
                if (fogRim != null) {
                    if (x == 0) fogRim[z] = top + 1;
                    if (x == side - 1) fogRim[side + z] = top + 1;
                    if (z == 0) fogRim[side * 2 + x] = top + 1;
                    if (z == side - 1) fogRim[side * 3 + x] = top + 1;
                }

                if (reuse) continue;

                neighbors[0] = tile(tiles, tileSide, x - 1, z);
                neighbors[1] = tile(tiles, tileSide, x + 1, z);
                neighbors[2] = tile(tiles, tileSide, x, z - 1);
                neighbors[3] = tile(tiles, tileSide, x, z + 1);
                columns[0] = index(x - 1, z);
                columns[1] = index(x + 1, z);
                columns[2] = index(x, z - 1);
                columns[3] = index(x, z + 1);
                int depth = tile.depth(col);
                int visibleDepth = depth;
                if (tile.solidUpTo() != null) {
                    int enclosed = tile.solidUpTo()[col] - 1;
                    for (int direction = 0; direction < neighbors.length; direction++) {
                        var neighbor = neighbors[direction];
                        if (neighbor == null) continue;
                        int other = columns[direction];
                        if (neighbor.solidUpTo() == null || neighbor.colors()[other] == 0) {
                            enclosed = Integer.MIN_VALUE;
                            break;
                        }
                        enclosed = Math.min(enclosed, neighbor.solidUpTo()[other]);
                    }
                    if (enclosed >= top - depth) visibleDepth = top - enclosed;
                }
                int firstFace = faces.size();
                for (int offset = 0; offset < visibleDepth; offset++) {
                    int y = top - offset;
                    int color = tile.voxel(col, y);
                    if (color == 0) {
                        if (openAir != null && offset == depth - 1 && openAir.contains(x, y, z)
                                && y < tile.ground()[col] - 1)
                            face(faces, cell, y, TOP, ReliefTile.CAVE_SHADOW);
                        continue;
                    }
                    if (color == ReliefTile.COVERED_WATER) continue;
                    int type = color & ReliefTile.FACE_TYPE;
                    if (type == ReliefTile.WATER_FACE) {
                        if ((tile.voxel(col, y + 1) & ReliefTile.FACE_TYPE) != ReliefTile.WATER_FACE)
                            face(faces, cell, y, TOP, color);
                        for (int d = 0; d < 4; d++) {
                            var neighbor = neighbors[d];
                            if (neighbor == null) continue;
                            int c = columns[d];
                            if (neighbor.ground() != null && y < neighbor.ground()[c] && neighbor.colors()[c] != 0) continue;
                            if (neighbor.voxel(c, y) == 0 && !neighbor.belowSample(c, y))
                                face(faces, cell, y, SIDE_FACES[d], color);
                        }
                        continue;
                    }
                    boolean aboveGround = tile.ground() == null || y >= tile.ground()[col];
                    if (ReliefTile.shaped(type)) {
                        if (shapeExposed(tile, col, neighbors, columns, x, y, z, aboveGround, openAir))
                            face(faces, cell, y, TOP, color);
                        continue;
                    }
                    // Omit the artificial ceiling where the selected height cuts through solid rock
                    int above = tile.voxel(col, y + 1);
                    if (exposesFace(above)) {
                        if (aboveGround && type != ReliefTile.CLIPPED_FACE && (tile.caveDepth() == 0 || offset > 0))
                            face(faces, cell, y, TOP, color);
                        else if (type == ReliefTile.CAVE_FLOOR && above == 0)
                            face(faces, cell, y, TOP, 0xEF000000 | (color & 0xFFFFFF));
                        else if (openAir != null && ((above & ReliefTile.FACE_TYPE) == ReliefTile.WATER_FACE
                                || above == 0 && openAir.contains(x, y + 1, z)))
                            face(faces, cell, y, TOP, recessedColor(color, tile.ground()[col] - y));
                        else if (tile.trace() != null)
                            ReliefDiagnostics.face(tile, col, y, "top", aboveGround ? "slice-cap" : "below-ground", tile, col);
                    }
                    int below = tile.voxel(col, y - 1);
                    if (!tile.belowSample(col, y - 1) && exposesFace(below)
                            && (aboveGround || openAir != null && openAir.contains(x, y - 1, z)))
                        face(faces, cell, y, BOTTOM, aboveGround ? color : recessedColor(color, tile.ground()[col] - y));
                    for (int d = 0; d < 4; d++) {
                        var neighbor = neighbors[d];
                        int c = columns[d];
                        if (neighbor == null) {
                            if (tile.trace() != null) ReliefDiagnostics.face(tile, col, y, SIDE_NAMES[d],
                                    "neighbor-chunk-unavailable", null, c);
                            continue;
                        }
                        int adjacent = neighbor.voxel(c, y);
                        if (!exposesFace(adjacent)) continue;
                        String rejection = null;
                        if (neighbor.belowSample(c, y))
                            rejection = "neighbor-below-sample-depth";
                        int roof = neighbor.ground() == null ? y : neighbor.ground()[c];
                        boolean underRoof = neighbor.colors()[c] != 0 && y < roof;
                        if (rejection == null && underRoof && (adjacent & ReliefTile.FACE_TYPE) != ReliefTile.WATER_FACE
                                && (openAir == null || !openAir.contains(x + SIDE_X[d], y, z + SIDE_Z[d])))
                            rejection = "enclosed-below-ground";
                        if (rejection == null)
                            face(faces, cell, y, SIDE_FACES[d], underRoof ? recessedColor(color, roof - y) : color);
                        else if (tile.trace() != null)
                            ReliefDiagnostics.face(tile, col, y, SIDE_NAMES[d], rejection, neighbor, c);
                    }
                }
                for (int i = firstFace + 1; i < faces.size(); i += 3) {
                    int y = faces.getInt(i);
                    stripHeights[strip * 2] = Math.min(stripHeights[strip * 2], y);
                    stripHeights[strip * 2 + 1] = Math.max(stripHeights[strip * 2 + 1], y);
                }
            }
            if (reuse) {
                int src = prior.chunkOffsets[chunk];
                int count = prior.chunkOffsets[chunk + 1] - src;
                int delta = faces.size() - src;
                faces.addElements(faces.size(), prior.faces, src, count);
                for (int strip = stripStart; strip < stripStart + STRIPS_PER_CHUNK; strip++)
                    stripOffsets[strip] = prior.stripOffsets[strip] + delta;
                System.arraycopy(prior.stripHeights, 2 * stripStart, stripHeights, 2 * stripStart, 2 * STRIPS_PER_CHUNK);
            }
            for (int strip = stripStart; strip < stripStart + STRIPS_PER_CHUNK; strip++) {
                chunkMin = Math.min(chunkMin, stripHeights[strip * 2]);
                chunkMax = Math.max(chunkMax, stripHeights[strip * 2 + 1]);
            }
            chunkHeights[chunk * 2] = chunkMin;
            chunkHeights[chunk * 2 + 1] = chunkMax;
            min = Math.min(min, chunkMin);
            max = Math.max(max, chunkMax);
        }
        chunkOffsets[tiles.length] = faces.size();
        stripOffsets[stripOffsets.length - 1] = faces.size();
        int[] packedFaces = faces.toIntArray();
        long[][] directions = packedFaces.length >= 300_000 ? directionMask(packedFaces, cancelled) : null;
        return new ReliefMesh(side, packedFaces, min, max, sunlight, chunkOffsets, fogRim, chunkHeights,
                stripOffsets, stripHeights, directions);
    }

    static long[][] directionMask(int[] faces, java.util.function.BooleanSupplier cancelled) {
        int count = faces.length / 3;
        var directions = new long[6][Math.ceilDiv(count, Long.SIZE)];
        for (int i = 0; i < count; i++) {
            if ((i & 4095) == 0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            directions[faces[i * 3] % 6][i >>> 6] |= 1L << (i & 63);
        }
        return directions;
    }

    private static int recessedColor(int color, int depth) {
        if (color == com.wywf.verify.BlockMapColors.LAVA)
            return color;
        return (0xE0 + Math.clamp(depth, 1, 15)) << 24 | (color & 0xFFFFFF);
    }

    private static boolean shapeExposed(ReliefTile tile, int col, ReliefTile[] neighbors, int[] columns,
                                        int x, int y, int z, boolean aboveGround, ReliefOpenAir openAir) {
        if (aboveGround) {
            if (exposesFace(tile.voxel(col, y + 1)) || exposesFace(tile.voxel(col, y - 1))) return true;
            for (int d = 0; d < neighbors.length; d++)
                if (neighbors[d] == null || exposesFace(neighbors[d].voxel(columns[d], y))) return true;
        } else if (openAir != null) {
            if (openAir.contains(x, y + 1, z) || openAir.contains(x, y - 1, z)) return true;
            for (int d = 0; d < neighbors.length; d++)
                if (neighbors[d] != null && openAir.contains(x + SIDE_X[d], y, z + SIDE_Z[d])) return true;
        }
        return false;
    }

    private static boolean exposesFace(int neighbor) {
        int material = neighbor & ReliefTile.FACE_TYPE;
        return neighbor == 0 || material == ReliefTile.WATER_FACE || ReliefTile.shaped(material);
    }

    private static void face(IntArrayList faces, int cell, int y, int direction, int color) {
        faces.add(cell * 6 + direction);
        faces.add(y);
        faces.add(color);
    }

    private static int index(int x, int z) {
        return (z & 15) * 16 + (x & 15);
    }

    private static ReliefTile tile(ReliefTile[] tiles, int side, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        return cx < 0 || cz < 0 || cx >= side || cz >= side ? null : tiles[cz * side + cx];
    }
}
