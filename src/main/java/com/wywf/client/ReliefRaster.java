package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.map.BiomeMapSnapshot;
import com.wywf.map.BiomePalette;
import com.wywf.verify.ReliefShapes;
import com.wywf.verify.ReliefTile;
import com.wywf.verify.VerifiedAnchor;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;

final class ReliefRaster {
    static final int PROBE_EDGE_FOG = -2;
    private static final float EDGE_FOG_WIDTH = 12, SLICE_SIDE_FOG_WIDTH = 4;
    private static final float NETHER_SLICE_HEIGHT_SCALE = .46f;
    private static final int SLICE_FOG_HEIGHT = 8;
    static final int MAX_WORKERS = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 6);
    private static final java.util.concurrent.ForkJoinPool WORKERS = new java.util.concurrent.ForkJoinPool(MAX_WORKERS);
    private record Axis(float x, float y, float depth) {
        Axis times(float amount) {
            return new Axis(x * amount, y * amount, depth * amount);
        }

        Axis plus(Axis axis) {
            return new Axis(x + axis.x, y + axis.y, depth + axis.depth);
        }
    }

    private record PlacedQuad(float x, float y, float z, Quad shape, int direction) {}
    private record ProjectedShape(PlacedQuad[] parts, int color) {}


    private record Quad(float ux, float uy, float vx, float vy, float uDepth, float vDepth,
                        float uStep, float vStep, float uRowStep, float vRowStep, boolean visible) {
        static Quad of(Axis horizontal, Axis vertical, int size) {
            float ux = horizontal.x * size, uy = horizontal.y * size;
            float vx = vertical.x * size, vy = vertical.y * size;
            float area = ux * vy - uy * vx;
            return new Quad(ux, uy, vx, vy, horizontal.depth * size, vertical.depth * size,
                    vy / area, -uy / area, -vx / area, ux / area, Math.abs(area) >= 1e-5f);
        }
    }

    static final class Buffers {
        private int[] colors;
        private float[] terrainDepth;
        private float[] overlayDepth;
        private int[] opaque;
        private int[] probes;
        private int[] outlinePixels;

        private void prepareProbes(int size, boolean outline) {
            if (!com.wywf.core.WyWFDebug.ENABLED && !outline) {
                probes = null;
                return;
            }
            if (probes == null || probes.length != size) probes = new int[size];
            Arrays.fill(probes, -1);
        }

        int[] outlinePixels() {
            return outlinePixels;
        }

        int[] copyProbes(int[] destination) {
            if (probes == null) return null;
            if (destination == null || destination.length != probes.length) return probes.clone();
            System.arraycopy(probes, 0, destination, 0, probes.length);
            return destination;
        }


        private void resize(int size) {
            if (colors != null && colors.length == size) return;
            colors = new int[size];
            terrainDepth = new float[size];
            overlayDepth = null;
            opaque = null;
        }

        private void prepareOverlays(int first, int end) {
            synchronized (this) {
                if (overlayDepth == null) {
                    overlayDepth = new float[colors.length];
                    opaque = new int[colors.length];
                }
            }
            Arrays.fill(overlayDepth, first, end, Float.NEGATIVE_INFINITY);
            System.arraycopy(colors, first, opaque, first, end - first);
        }
    }

    private final int[] pixels;
    private final float[] depth;
    private final int[] probes;
    private int currentFace = -1;
    private float[] overlayDepth;
    private int[] opaquePixels;
    private boolean clipPlantsToTerrain;
    private final int width, firstRow, lastRow, background;
    private final float scale, halfSide, heightCenter, screenX, screenY;
    private final Axis east, up, south;
    private final Quad[] faceShapes;
    private Int2ObjectOpenHashMap<ProjectedShape> projectedShapes;
    private PlacedQuad[] crossedPlant, crystalFaces;
    private final int visibleFaces;
    private final long[] lightingKeys = new long[2048];
    private final int[] lightingColors = new int[2048];

    private ReliefRaster(int width, int height, double yaw, double pitch, double zoom,
                         int side, int span, float center, int background, float heightScale, double panX, double panY,
                         Buffers buffers, int firstRow, int lastRow) {
        this.width = width;
        this.firstRow = firstRow;
        this.lastRow = lastRow;
        this.background = background;
        heightCenter = center;
        screenX = (float)(width * (.5 + panX));
        screenY = (float)(height * (.5 + panY));
        halfSide = side * .5f;
        scale = (float)(Math.min(width, height) * .82 * zoom / span);
        float sinYaw = (float)Math.sin(yaw), cosYaw = (float)Math.cos(yaw);
        float sinPitch = (float)Math.sin(pitch), cosPitch = (float)Math.cos(pitch);
        east = new Axis(scale * cosYaw, scale * sinPitch * sinYaw, cosPitch * sinYaw);
        up = new Axis(0, -scale * cosPitch * heightScale, sinPitch * heightScale);
        south = new Axis(-scale * sinYaw, scale * sinPitch * cosYaw, cosPitch * cosYaw);
        Quad horizontal = Quad.of(east, south, 1);
        Quad xWall = Quad.of(up, south, 1), zWall = Quad.of(east, up, 1);
        faceShapes = new Quad[]{horizontal, xWall, xWall, zWall, zWall, horizontal};
        visibleFaces = (up.depth == 0 ? 0 : 1 << (up.depth > 0 ? ReliefMesh.TOP : ReliefMesh.BOTTOM))
                | (east.depth == 0 ? 0 : 1 << (east.depth < 0 ? ReliefMesh.WEST : ReliefMesh.EAST))
                | (south.depth == 0 ? 0 : 1 << (south.depth < 0 ? ReliefMesh.NORTH : ReliefMesh.SOUTH));
        pixels = buffers.colors;
        depth = buffers.terrainDepth;
        probes = buffers.probes;
    }

    static int[] draw(ReliefMesh mesh, int width, int height, double yaw, double pitch, double zoom,
                      DimensionSpace dimension, boolean underRoof, int fogColor, float centerY,
                      int span, int originX, int originZ, BiomeMapSnapshot overview,
                      java.util.function.BooleanSupplier cancelled) {
        return draw(mesh, width, height, yaw, pitch, zoom, dimension, underRoof, fogColor, centerY,
                span, originX, originZ, overview, 0, 0, cancelled);
    }

    static int[] draw(ReliefMesh mesh, int width, int height, double yaw, double pitch, double zoom,
                      DimensionSpace dimension, boolean underRoof, int fogColor, float centerY,
                      int span, int originX, int originZ, BiomeMapSnapshot overview, double panX, double panY,
                      java.util.function.BooleanSupplier cancelled) {
        return draw(mesh, width, height, yaw, pitch, zoom, dimension, underRoof, fogColor, centerY,
                span, originX, originZ, overview, panX, panY, new Buffers(), cancelled);
    }

    static int[] draw(ReliefMesh mesh, int width, int height, double yaw, double pitch, double zoom,
                      DimensionSpace dimension, boolean underRoof, int fogColor, float centerY,
                      int span, int originX, int originZ, BiomeMapSnapshot overview, double panX, double panY,
                      Buffers buffers, java.util.function.BooleanSupplier cancelled) {
        int workers = mesh.faces().length >= 300_000 && height >= 300 ? MAX_WORKERS : 1;
        return draw(mesh, width, height, yaw, pitch, zoom, dimension, underRoof, fogColor, centerY,
                span, originX, originZ, overview, panX, panY, buffers, workers, cancelled);
    }

    static int[] draw(ReliefMesh mesh, int width, int height, double yaw, double pitch, double zoom,
                      DimensionSpace dimension, boolean underRoof, int fogColor, float centerY, int span,
                      int originX, int originZ, BiomeMapSnapshot overview, double panX, double panY,
                      Buffers buffers, int workerLimit, java.util.function.BooleanSupplier cancelled) {
        return draw(mesh, width, height, yaw, pitch, zoom, dimension, underRoof, fogColor, centerY, span,
                originX, originZ, overview, panX, panY, buffers, workerLimit, null, cancelled);
    }

    static int[] draw(ReliefMesh mesh, int width, int height, double yaw, double pitch, double zoom,
                      DimensionSpace dimension, boolean underRoof, int fogColor, float centerY, int span,
                      int originX, int originZ, BiomeMapSnapshot overview, double panX, double panY,
                      Buffers buffers, int workerLimit, Outline outline, java.util.function.BooleanSupplier cancelled) {
        buffers.resize(width * height);
        buffers.prepareProbes(width * height, outline != null);
        Arrays.fill(buffers.terrainDepth, Float.NEGATIVE_INFINITY);
        Arrays.fill(buffers.colors, fogColor);
        int bands = Math.clamp(workerLimit, 1, Math.min(MAX_WORKERS, height));
        if (bands == 1) {
            new ReliefRaster(width, height, yaw, pitch, zoom, mesh.side(), span, centerY,
                    fogColor, underRoof ? NETHER_SLICE_HEIGHT_SCALE : 1f, panX, panY, buffers, 0, height)
                    .drawBand(mesh, dimension, underRoof, originX, originZ, overview, buffers, cancelled);
        } else {
            int[] rows = new int[bands + 1];
            for (int band = 1; band <= bands; band++) rows[band] = height * band / bands;
            if (zoom < 4 && mesh.directions() != null) {
                var camera = new ReliefRaster(width, height, yaw, pitch, zoom, mesh.side(), span, centerY,
                        fogColor, underRoof ? NETHER_SLICE_HEIGHT_SCALE : 1f, panX, panY, buffers, 0, height);
                camera.balanceRows(mesh, rows);
            }
            WORKERS.submit(() -> java.util.stream.IntStream.range(0, bands).parallel().forEach(band ->
                    new ReliefRaster(width, height, yaw, pitch, zoom, mesh.side(), span, centerY,
                            fogColor, underRoof ? NETHER_SLICE_HEIGHT_SCALE : 1f, panX, panY, buffers, rows[band], rows[band + 1])
                            .drawBand(mesh, dimension, underRoof, originX, originZ, overview, buffers, cancelled))).join();
        }
        if (outline == null) buffers.outlinePixels = null;
        else outline.draw(mesh, originX, originZ, width, height, buffers, cancelled);
        return buffers.colors;
    }

    static final class Outline {
        private final VerifiedAnchor anchor;
        private final int y;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.List<VerifiedAnchor.Bounds>> chunks
                = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        private final java.util.List<VerifiedAnchor.Bounds> broad = new java.util.ArrayList<>();
        private byte[] classified;
        private boolean[] selected;

        Outline(VerifiedAnchor anchor, int y) {
            this.anchor = anchor;
            this.y = y;
            for (var part : anchor.bounds()) {
                int minX = part.minX() >> 4, minZ = part.minZ() >> 4;
                int maxX = part.maxX() >> 4, maxZ = part.maxZ() >> 4;
                if ((maxX - minX + 1L) * (maxZ - minZ + 1L) > 1024) {
                    broad.add(part);
                    continue;
                }
                for (int z = minZ; z <= maxZ; z++)
                    for (int x = minX; x <= maxX; x++)
                        chunks.computeIfAbsent(chunkKey(x, z), ignored -> new java.util.ArrayList<>()).add(part);
            }
        }

        private static long chunkKey(int x, int z) {
            return (long)x << 32 | (z & 0xFFFFFFFFL);
        }

        private boolean covers(int x, int y, int z) {
            if (x < anchor.minX() || x > anchor.maxX() || z < anchor.minZ() || z > anchor.maxZ()) return false;
            if (anchor.bounds().isEmpty()) return y >= this.y - 4 && y <= this.y + 32;
            var bounds = chunks.get(chunkKey(x >> 4, z >> 4));
            if (bounds != null)
                for (var part : bounds) if (part.contains(x, y, z)) return true;
            for (var part : broad) if (part.contains(x, y, z)) return true;
            return false;
        }

        private void draw(ReliefMesh mesh, int originX, int originZ, int width, int height, Buffers frame,
                          java.util.function.BooleanSupplier cancelled) {
            int[] faces = mesh.faces();
            int faceCount = faces.length / 3, size = frame.probes.length;
            if (classified == null || classified.length != faceCount) classified = new byte[faceCount];
            else Arrays.fill(classified, (byte)0);
            if (selected == null || selected.length != size) selected = new boolean[size];
            if (frame.outlinePixels == null || frame.outlinePixels.length != size) frame.outlinePixels = new int[size];
            else Arrays.fill(frame.outlinePixels, 0);
            for (int px = 0; px < selected.length; px++) {
                if ((px & 16383) == 0 && cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                int index = frame.probes[px];
                if (index < 0) {
                    selected[px] = false;
                    continue;
                }
                int face = index / 3;
                if (classified[face] == 0) {
                    int cell = faces[index] / 6, wx = originX + cell % mesh.side(), wz = originZ + cell / mesh.side();
                    int color = faces[index + 2], material = color & ReliefTile.FACE_TYPE;
                    boolean solid = material != ReliefTile.WATER_FACE && !ReliefTile.plant(material)
                            && color != ReliefTile.CAVE_SHADOW && material != ReliefTile.ICE_WATER_SHADOW;
                    classified[face] = (byte)(solid && covers(wx, faces[index + 1], wz) ? 2 : 1);
                }
                selected[px] = classified[face] == 2;
            }

            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int i = y * width + x;
                    if (selected[i]) continue;
                    float front = frame.terrainDepth[i] - .15f;
                    if (selected[i - 1] && frame.terrainDepth[i - 1] >= front
                            || selected[i + 1] && frame.terrainDepth[i + 1] >= front
                            || selected[i - width] && frame.terrainDepth[i - width] >= front
                            || selected[i + width] && frame.terrainDepth[i + width] >= front)
                        frame.outlinePixels[i] = 0xFFFFE391;
                }
            }
        }
    }

    private void balanceRows(ReliefMesh mesh, int[] rows) {
        var offsets = mesh.stripOffsets();
        var heights = mesh.stripHeights();
        int bands = rows.length - 1;

        int chunkSide = Math.ceilDiv(mesh.side(), 16), stripRows = ReliefMesh.STRIP_ROWS;
        float[] load = new float[lastRow + 1];
        for (int i = 0; i < offsets.length - 1; i++) {
            int faces = offsets[i + 1] - offsets[i];
            if (faces == 0) continue;
            int chunk = i / ReliefMesh.STRIPS_PER_CHUNK;
            int x = chunk % chunkSide * 16;
            int z = chunk / chunkSide * 16 + i % ReliefMesh.STRIPS_PER_CHUNK * stripRows;
            if (!spanVisible(x, z, stripRows, heights[i * 2], heights[i * 2 + 1])) continue;
            float sy = screenY + east.y * (x - halfSide) + south.y * (z - halfSide);
            float a = up.y * (heights[i * 2] - heightCenter);
            float b = up.y * (heights[i * 2 + 1] + 1 - heightCenter);
            int first = Math.clamp((int)Math.floor(sy + Math.min(a, b)
                    + 16 * Math.min(0, east.y) + stripRows * Math.min(0, south.y)), 0, lastRow - 1);
            int end = Math.clamp((int)Math.ceil(sy + Math.max(a, b)
                    + 16 * Math.max(0, east.y) + stripRows * Math.max(0, south.y)), first + 1, lastRow);
            float weight = (float)faces / (end - first);
            load[first] += weight;
            load[end] -= weight;
        }
        float density = 1, total = 0;
        for (int row = 0; row < lastRow; row++) {
            density += load[row];
            total += density;
            load[row] = total;
        }
        for (int band = 1, row = 1; band < bands; band++) {
            while (row < lastRow - (bands - band) && load[row - 1] < total * band / bands) row++;
            rows[band] = row++;
        }
    }

    private void drawBand(ReliefMesh mesh, DimensionSpace dimension, boolean underRoof, int originX, int originZ, BiomeMapSnapshot overview,
                          Buffers buffers, java.util.function.BooleanSupplier cancelled) {
        int side = mesh.side(), fogColor = background;
        clipPlantsToTerrain = dimension == DimensionSpace.OVERWORLD && mesh.sunlight() != null;
        if (overview != null) drawOverview(overview, originX, originZ, side);
        boolean squareFog = dimension == DimensionSpace.NETHER
                || (dimension == DimensionSpace.OVERWORLD && overview == null);
        boolean netherSlice = dimension == DimensionSpace.NETHER && underRoof;
        float sideView = netherSlice ? Math.clamp(-up.y / (scale * NETHER_SLICE_HEIGHT_SCALE), 0, 1) : 0;
        float fogWidth = EDGE_FOG_WIDTH - (EDGE_FOG_WIDTH - SLICE_SIDE_FOG_WIDTH) * sideView * sideView;
        int[] faces = mesh.faces();
        if (faces.length == 0) return;
        int chunkSide = Math.ceilDiv(side, 16);
        boolean strips = mesh.stripOffsets() != null && mesh.directions() != null;
        int[] offsets = strips ? mesh.stripOffsets() : mesh.chunkOffsets();
        int[] heights = strips ? mesh.stripHeights() : mesh.chunkHeights();
        int spans = offsets == null ? 1 : offsets.length - 1;
        var directions = mesh.directions();
        long[] vertical = directions == null ? null : directions[up.depth >= 0 ? ReliefMesh.TOP : ReliefMesh.BOTTOM];
        long[] eastWest = directions == null || east.depth == 0 ? vertical
                : directions[east.depth < 0 ? ReliefMesh.WEST : ReliefMesh.EAST];
        long[] northSouth = directions == null || south.depth == 0 ? vertical
                : directions[south.depth < 0 ? ReliefMesh.NORTH : ReliefMesh.SOUTH];
        IntArrayList overlays = null;
        for (int span = 0; span < spans; span++) {
            if ((span & 15) == 0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int first = offsets == null ? 0 : offsets[span];
            int last = offsets == null ? faces.length : offsets[span + 1];
            int chunk = strips ? span / ReliefMesh.STRIPS_PER_CHUNK : span;
            int rows = strips ? ReliefMesh.STRIP_ROWS : 16;
            int zOffset = strips ? span % ReliefMesh.STRIPS_PER_CHUNK * rows : 0;
            if (first == last || offsets != null && !spanVisible((chunk % chunkSide) * 16,
                    (chunk / chunkSide) * 16 + zOffset, rows, heights == null ? mesh.minimum() : heights[span * 2],
                    heights == null ? mesh.maximum() : heights[span * 2 + 1])) continue;
            int lastCell = -1, x = 0, z = 0;
            float haze = 0;
            int firstFace = first / 3, word = firstFace >>> 6;
            int lastWord = ((last - 3) / 3) >>> 6;
            long eligible = vertical == null ? 0
                    : (vertical[word] | eastWest[word] | northSouth[word]) & (-1L << (firstFace & 63));
            for (int cursor = first; cursor < last;) {
                int i;
                if (vertical == null) {
                    i = cursor;
                } else {
                    while (eligible == 0 && word < lastWord) {
                        word++;
                        eligible = vertical[word] | eastWest[word] | northSouth[word];
                    }
                    if (eligible == 0) break;
                    i = (word * Long.SIZE + Long.numberOfTrailingZeros(eligible)) * 3;
                    if (i >= last) break;
                    eligible &= eligible - 1;
                }
                cursor = i + 3;
                int face = faces[i] % 6, color = faces[i + 2];
                int material = color & ReliefTile.FACE_TYPE;
                boolean crystal = material == ReliefTile.END_CRYSTAL;
                if (!crystal && !visible(face)) continue;
                int cell = faces[i] / 6;
                if (cell != lastCell) {
                    lastCell = cell;
                    x = cell % side;
                    z = cell / side;
                    haze = 0;
                    if (squareFog) {
                        int margin = Math.min(Math.min(x, side - x - 1), Math.min(z, side - z - 1));
                        float edge = Math.max(0, 1 - margin / fogWidth);
                        haze = edge * edge;
                    }
                }
                int y = faces[i + 1];
                if (crystal) {
                    currentFace = i;
                    drawCrystal(x, y, z, face, color);
                    continue;
                }
                if (haze >= 1) {
                    currentFace = PROBE_EDGE_FOG;
                    face(x, y, z, face, fogColor, 1, 0);
                    continue;
                }
                if (material != ReliefTile.FACE_TYPE) {
                    if (material == ReliefTile.WATER_FACE || ReliefTile.plant(material)) {
                        if (overlays == null) overlays = new IntArrayList();
                        overlays.add(i);
                        overlays.add(Float.floatToRawIntBits(haze));
                        continue;
                    }
                    if (color == ReliefTile.CAVE_SHADOW && up.depth <= .38f) continue;
                    if (material == ReliefTile.ENTRANCE_FACE)
                        color = MenuAccent.mix(0xFF000000 | color, 0xFF181821,
                                .83f * Math.clamp((up.depth - .38f) / .35f, 0, 1));
                    else if ((material & 0xF0000000) == 0xE0000000) {
                        int recess = (material >>> 24) & 15;
                        color = MenuAccent.mix(0xFF000000 | color, 0xFF181821, Math.min(.92f, .35f + recess * .075f));
                    } else if (ReliefTile.shaped(material)) {
                        currentFace = i;
                        drawShape(x, y, z, color, haze, shadow(mesh, cell, y));
                        continue;
                    }
                }
                currentFace = i;
                face(x, y, z, face, color, haze, shadow(mesh, cell, y));
            }
        }
        if (!netherSlice && mesh.fogRim() != null) drawFogRim(mesh.fogRim(), side, mesh.minimum(), false);
        if (overlays != null) {
            buffers.prepareOverlays(firstRow * width, lastRow * width);
            overlayDepth = buffers.overlayDepth;
            opaquePixels = buffers.opaque;
            for (int index = 0; index < overlays.size(); index += 2) {
                if ((index & 4095) == 0 && cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                int i = overlays.getInt(index), cell = faces[i] / 6;
                currentFace = i;
                float haze = Float.intBitsToFloat(overlays.getInt(index + 1));
                int color = faces[i + 2], x = cell % side, y = faces[i + 1], z = cell / side;
                if (ReliefTile.plant(color & ReliefTile.FACE_TYPE))
                    drawPlant(x, y, z, color, haze, shadow(mesh, cell, y));
                else {
                    face(x, y, z, faces[i] % 6, color, haze, 0, mesh.iceWaterFog());
                }
            }
        }
        if (netherSlice && mesh.fogRim() != null) drawFogRim(mesh.fogRim(), side, mesh.minimum(), true);
    }

    private static float shadow(ReliefMesh mesh, int cell, int y) {
        return mesh.sunlight() == null ? 0 : Math.clamp((mesh.sunlight()[cell] - y - .35f) / 2, 0, 1);
    }

    private void drawFogRim(int[] rim, int side, int bottom, boolean fadeUp) {
        currentFace = PROBE_EDGE_FOG;
        for (int edge = 0; edge < 4; edge++) {
            if (!visible(ReliefMesh.WEST + edge)) continue;
            boolean alongZ = edge < 2;
            Axis along = alongZ ? south : east;
            int limit = fadeUp ? bottom + SLICE_FOG_HEIGHT : Integer.MAX_VALUE;
            int offset = edge * side;
            for (int i = 0; i < side;) {
                int first = i++;
                int top = Math.min(rim[offset + first], limit);
                if (top <= bottom) continue;
                if (fadeUp) {
                    while (i < side && Math.min(rim[offset + i], limit) == top) i++;
                }
                int x = alongZ ? edge * side : first;
                int z = alongZ ? first : (edge - 2) * side;
                int height = top - bottom;
                Axis vertical = new Axis(0, up.y * height, up.depth * height);
                if (fadeUp) {
                    int length = i - first;
                    Axis horizontal = new Axis(along.x * length, along.y * length, along.depth * length);
                    quad(x, bottom, z, Quad.of(horizontal, vertical, 1), background, 1, -1, 0,
                            (float) height / SLICE_FOG_HEIGHT);
                } else {
                    quad(x, bottom, z, along, vertical, 1, background, 1);
                }
            }
        }
    }

    private boolean spanVisible(int x, int z, int rows, int minimum, int maximum) {
        float sx = screenX + east.x * (x - halfSide) + south.x * (z - halfSide);
        float sy = screenY + east.y * (x - halfSide) + south.y * (z - halfSide);
        return sx + 16 * Math.max(0, east.x) + rows * Math.max(0, south.x) >= 0
                && sx + 16 * Math.min(0, east.x) + rows * Math.min(0, south.x) <= width
                && sy + Math.max(up.y * (minimum - heightCenter), up.y * (maximum + 1 - heightCenter))
                    + 16 * Math.max(0, east.y) + rows * Math.max(0, south.y) >= firstRow
                && sy + Math.min(up.y * (minimum - heightCenter), up.y * (maximum + 1 - heightCenter))
                    + 16 * Math.min(0, east.y) + rows * Math.min(0, south.y) <= lastRow;
    }

    private void drawOverview(BiomeMapSnapshot map, int originX, int originZ, int side) {
        int step = map.step();
        for (int row = 0; row < map.height(); row++) for (int col = 0; col < map.width(); col++) {
            var cell = map.cells()[row * map.width() + col];
            if (cell == null || cell.height() == Integer.MIN_VALUE) continue;
            int x = map.x() - originX + col * step, z = map.z() - originZ + row * step;
            if (x < side && x + step > 0 && z < side && z + step > 0) continue;
            int y = cell.height();
            int color = BiomePalette.color(cell.surfaceId());
            quad(x, y, z, east, south, step, light(color, ReliefMesh.TOP, 0), .12f);
            if (col > 0 && visible(ReliefMesh.WEST))
                overviewWall(x, z, y, step, south, color, ReliefMesh.WEST, map, col - 1, row);
            if (col + 1 < map.width() && visible(ReliefMesh.EAST))
                overviewWall(x + step, z, y, step, south, color, ReliefMesh.EAST, map, col + 1, row);
            if (row > 0 && visible(ReliefMesh.NORTH))
                overviewWall(x, z, y, step, east, color, ReliefMesh.NORTH, map, col, row - 1);
            if (row + 1 < map.height() && visible(ReliefMesh.SOUTH))
                overviewWall(x, z + step, y, step, east, color, ReliefMesh.SOUTH, map, col, row + 1);
        }
    }

    private void overviewWall(int x, int z, int top, int step, Axis along, int color,
                              int face, BiomeMapSnapshot map, int col, int row) {
        var neighbor = map.cells()[row * map.width() + col];
        if (neighbor == null || neighbor.height() == Integer.MIN_VALUE || neighbor.height() >= top) return;
        int side = top - neighbor.height();
        quad(x, neighbor.height(), z, new Axis(along.x * step, along.y * step, along.depth * step),
                new Axis(0, up.y * side, up.depth * side), 1, light(color, face, 0), .12f);
    }

    private boolean visible(int direction) {
        return (visibleFaces & 1 << direction) != 0;
    }

    private void face(int x, int y, int z, int direction, int color, float haze, float shadow) {
        face(x, y, z, direction, color, haze, shadow, null);
    }

    private void face(int x, int y, int z, int direction, int color, float haze, float shadow, IceWaterFog iceFog) {
        int lighting = (color & ReliefTile.FACE_TYPE) == ReliefTile.ICE_WATER_SHADOW ? -1 : direction;
        quad(x + (direction == ReliefMesh.EAST ? 1 : 0), y + (direction == ReliefMesh.TOP ? 1 : 0),
                z + (direction == ReliefMesh.SOUTH ? 1 : 0), faceShapes[direction], color, haze, lighting, shadow, 0, iceFog);
    }

    private void drawCrystal(int x, int y, int z, int direction, int color) {
        if (crystalFaces == null) {
            // the body diagonal spans one block, matching the GPU vertex transform
            float across = .4082483f, rise = 1f / 3, along = .23570226f;
            Axis a = east.times(across).plus(up.times(rise)).plus(south.times(-along));
            Axis b = east.times(-across).plus(up.times(rise)).plus(south.times(-along));
            Axis c = up.times(rise).plus(south.times(2 * along));
            crystalFaces = new PlacedQuad[]{
                    new PlacedQuad(.5f - across, rise, .5f - along, Quad.of(a, c, 1), ReliefMesh.TOP),
                    new PlacedQuad(.5f, 0, .5f, Quad.of(b, c, 1), ReliefMesh.WEST),
                    new PlacedQuad(.5f + across, rise, .5f - along, Quad.of(b, c, 1), ReliefMesh.EAST),
                    new PlacedQuad(.5f, 0, .5f, Quad.of(a, b, 1), ReliefMesh.NORTH),
                    new PlacedQuad(.5f, rise, .5f + 2 * along, Quad.of(a, b, 1), ReliefMesh.SOUTH),
                    new PlacedQuad(.5f, 0, .5f, Quad.of(a, c, 1), ReliefMesh.BOTTOM)
            };
        }
        var part = crystalFaces[direction];
        quad(x + part.x, y + part.y, z + part.z, part.shape, color, 0, -1, 0, 0);
    }

    private void drawShape(int x, int y, int z, int id, float haze, float shadow) {
        if (projectedShapes == null) projectedShapes = new Int2ObjectOpenHashMap<>();
        var projected = projectedShapes.get(id);
        if (projected == null) {
            var geometry = ReliefShapes.get(id);
            var boxes = geometry.boxes();
            var parts = new java.util.ArrayList<PlacedQuad>(boxes.size() * 3);
            for (var bounds : boxes)
                box(parts, (float)bounds.minX, (float)bounds.minY, (float)bounds.minZ,
                        (float)bounds.maxX, (float)bounds.maxY, (float)bounds.maxZ);
            projected = new ProjectedShape(parts.toArray(PlacedQuad[]::new), geometry.color());
            projectedShapes.put(id, projected);
        }
        drawParts(x, y, z, projected.parts, projected.color, haze, shadow);
    }

    private void drawPlant(int x, int y, int z, int color, float haze, float shadow) {
        if (crossedPlant == null) {
            if (scale < 2.5f || Math.abs(up.y) < .02f * scale) {
                float size = scale < 2.5f ? .75f : .25f;
                float inset = (1 - size) / 2;
                crossedPlant = new PlacedQuad[]{new PlacedQuad(inset, .625f, inset,
                        Quad.of(east.times(size), south.times(size), 1), ReliefMesh.TOP)};
            } else {
                float inset = 3f / 16, span = 1 - 2 * inset;
                crossedPlant = new PlacedQuad[]{
                        new PlacedQuad(inset, 0, inset, Quad.of(east.plus(south).times(span), up, 1), ReliefMesh.TOP),
                        new PlacedQuad(inset, 0, 1 - inset,
                                Quad.of(east.plus(south.times(-1)).times(span), up, 1), ReliefMesh.TOP)
                };
            }
        }
        drawParts(x, y, z, crossedPlant, color, haze, shadow);
    }

    private void drawParts(int x, int y, int z, PlacedQuad[] parts, int color, float haze, float shadow) {
        for (var part : parts)
            quad(x + part.x, y + part.y, z + part.z, part.shape, color, haze, part.direction, shadow, 0);
    }

    private void box(java.util.ArrayList<PlacedQuad> parts, float x0, float y0, float z0, float x1, float y1, float z1) {
        Axis dx = east.times(x1 - x0), dy = up.times(y1 - y0), dz = south.times(z1 - z0);
        if (visible(ReliefMesh.TOP)) parts.add(new PlacedQuad(x0, y1, z0, Quad.of(dx, dz, 1), ReliefMesh.TOP));
        if (visible(ReliefMesh.BOTTOM)) parts.add(new PlacedQuad(x0, y0, z0, Quad.of(dx, dz, 1), ReliefMesh.BOTTOM));
        if (visible(ReliefMesh.EAST)) parts.add(new PlacedQuad(x1, y0, z0, Quad.of(dy, dz, 1), ReliefMesh.EAST));
        if (visible(ReliefMesh.WEST)) parts.add(new PlacedQuad(x0, y0, z0, Quad.of(dy, dz, 1), ReliefMesh.WEST));
        if (visible(ReliefMesh.SOUTH)) parts.add(new PlacedQuad(x0, y0, z1, Quad.of(dx, dy, 1), ReliefMesh.SOUTH));
        if (visible(ReliefMesh.NORTH)) parts.add(new PlacedQuad(x0, y0, z0, Quad.of(dx, dy, 1), ReliefMesh.NORTH));
    }

    private static final float[] LIGHT = {1.00f, .99f, .73f, .97f, .76f, .66f};

    static int directionalColor(int color, int direction, float shadow) {
        return com.wywf.verify.BlockMapColors.light(color, LIGHT[direction] * (1 - .25f * shadow));
    }

    private int light(int color, int direction, float shadow) {
        long key = ((long)color << 35) | ((long)Float.floatToRawIntBits(shadow) << 3) | direction;
        int slot = (int)((key * 0x9E3779B97F4A7C15L) >>> 53);
        if (lightingKeys[slot] != key || lightingColors[slot] == 0) {
            lightingKeys[slot] = key;
            lightingColors[slot] = directionalColor(color, direction, shadow);
        }
        return lightingColors[slot];
    }

    private void quad(int x, int y, int z, Axis horizontal, Axis vertical, int size, int color, float haze) {
        quad(x, y, z, Quad.of(horizontal, vertical, size), color, haze, -1, 0);
    }

    private void drawSliceFog(Quad shape, int left, int right, int top, int bottom, float sy, float startX,
                              float baseDepth, float fogHeight) {
        for (int row = top; row <= bottom; row++) {
            float rowY = row + .5f - sy;
            float u = startX * shape.uStep + rowY * shape.uRowStep;
            float v = startX * shape.vStep + rowY * shape.vRowStep;
            int end = row * width + right;
            for (int i = row * width + left; i <= end; i++, u += shape.uStep, v += shape.vStep) {
                // Half-open strips keep neighboring faces from blending the same pixel twice
                if (u < 0 || u >= 1 || v < 0 || v > 1 || pixels[i] == background) continue;
                float d = baseDepth + u * shape.uDepth + v * shape.vDepth;
                if (d < depth[i] || overlayDepth != null && d < overlayDepth[i]) continue;
                float t = 1 - v * fogHeight;
                pixels[i] = MenuAccent.mix(pixels[i], background, t * t * (3 - 2 * t));
                if (probes != null && pixels[i] == background) probes[i] = PROBE_EDGE_FOG;
            }
        }
    }

    private void quad(int x, int y, int z, Quad shape, int color, float haze, int direction, float shadow) {
        quad(x, y, z, shape, color, haze, direction, shadow, 0);
    }

    private void quad(float x, float y, float z, Quad shape, int color, float haze, int direction, float shadow, float fogHeight) {
        quad(x, y, z, shape, color, haze, direction, shadow, fogHeight, null);
    }

    private void quad(float x, float y, float z, Quad shape, int color, float haze, int direction, float shadow,
                      float fogHeight, IceWaterFog iceFog) {
        if (!shape.visible) return;
        float dx = x - halfSide, dz = z - halfSide, dy = y - heightCenter;
        float sx = screenX + east.x * dx + south.x * dz;
        float sy = screenY + east.y * dx + south.y * dz + up.y * dy;
        int left = Math.max(0, (int)Math.ceil(sx + Math.min(0, shape.ux) + Math.min(0, shape.vx) - .5f));
        int right = Math.min(width - 1, (int)Math.floor(sx + Math.max(0, shape.ux) + Math.max(0, shape.vx) - .5f));
        int top = Math.max(firstRow, (int)Math.ceil(sy + Math.min(0, shape.uy) + Math.min(0, shape.vy) - .5f));
        int bottom = Math.min(lastRow - 1, (int)Math.floor(sy + Math.max(0, shape.uy) + Math.max(0, shape.vy) - .5f));
        if (left > right || top > bottom) return;

        float baseDepth = east.depth * dx + south.depth * dz + up.depth * dy;
        float uDepth = shape.uDepth, vDepth = shape.vDepth;
        float uStep = shape.uStep, vStep = shape.vStep;
        float uRowStep = shape.uRowStep, vRowStep = shape.vRowStep;
        float startX = left + .5f - sx;
        if (fogHeight > 0) {
            drawSliceFog(shape, left, right, top, bottom, sy, startX, baseDepth, fogHeight);
            return;
        }
        int material = color & ReliefTile.FACE_TYPE;
        boolean water = material == ReliefTile.WATER_FACE;
        boolean plant = ReliefTile.plant(material);
        if (direction >= 0 && !water) color = light(color, direction, shadow);
        int fill = haze == 0 ? 0xFF000000 | color : MenuAccent.mix(0xFF000000 | color, background, haze);
        int waterTint = 0;
        if (water) {
            int tint = (color & 0xFFFFFF) == 0 ? 0xFF1B464E : 0xFF000000 | color;
            waterTint = haze == 0 ? tint : MenuAccent.mix(tint, background, haze);
        }
        boolean underIce = iceFog != null && iceFog.maySee(x, z, east.depth, south.depth);
        int iceTint = underIce ? MenuAccent.mix(IceWaterFog.shadow(color), background, haze) : 0;
        boolean xWall = direction == ReliefMesh.WEST || direction == ReliefMesh.EAST;
        boolean zWall = direction == ReliefMesh.NORTH || direction == ReliefMesh.SOUTH;
        boolean plantDetail = plant && scale >= 8 && shape.vDepth == up.depth;
        int berry = plantDetail && material == ReliefTile.BERRY_FACE ? MenuAccent.mix(0xFFBF4940, background, haze) : 0;
        for (int row = top; row <= bottom; row++) {
            float rowY = row + .5f - sy;
            float u = startX * uStep + rowY * uRowStep, v = startX * vStep + rowY * vRowStep;
            int end = row * width + right;
            for (int i = row * width + left; i <= end; i++, u += uStep, v += vStep) {
                if (u < -1e-5f || u > 1.00001f || v < -1e-5f || v > 1.00001f) continue;
                float d = baseDepth + u * uDepth + v * vDepth;
                if (d <= depth[i]) continue;
                if (water || plant) {
                    if (d <= overlayDepth[i]
                            || plant && clipPlantsToTerrain && depth[i] == Float.NEGATIVE_INFINITY) continue;
                    if (plantDetail && (v > .80f && (u < .16f || u > .87f) || v > .94f && (u < .35f || u > .64f)))
                        continue;
                    overlayDepth[i] = d;
                    if (probes != null) probes[i] = currentFace;
                    if (plant) {
                        boolean berryPixel = berry != 0 && (v > .54f && v < .70f && u > .19f && u < .33f
                                || v > .32f && v < .48f && u > .66f && u < .80f);
                        pixels[i] = MenuAccent.mix(opaquePixels[i], berryPixel ? berry : fill, .8f);
                    } else if (depth[i] == Float.NEGATIVE_INFINITY) {
                        pixels[i] = waterTint;
                    } else {
                        float distance = Math.clamp((d - depth[i]) * up.depth, 0, 36);
                        float opacity = 1 - .71f / (1 + distance * .42f);
                        pixels[i] = MenuAccent.mix(opaquePixels[i], waterTint, opacity);
                        if (underIce && distance > 0) {
                            // sample the submerged surface along the ray, below the water face
                            float ray = d - depth[i];
                            float worldX = x + (xWall ? 0 : u) - ray * east.depth;
                            float worldY = y + (xWall ? u : zWall ? v : 0) - ray * up.depth;
                            float worldZ = z + (zWall ? 0 : v) - ray * south.depth;
                            float fade = iceFog.strength(worldX, worldY, worldZ) * Math.min(1, distance);
                            if (fade > 0) pixels[i] = MenuAccent.mix(pixels[i], iceTint, fade * fade * (3 - 2 * fade));
                        }
                    }
                } else {
                    depth[i] = d;
                    if (probes != null) probes[i] = currentFace;
                    pixels[i] = fill;
                }
            }
        }
    }
}
