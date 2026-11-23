package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.map.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.*;

final class MapTiles implements AutoCloseable {
    private static final int SIZE = 64;
    private record Key(int step, int y, int x, int z) {}
    private static final class Tile {
        final BiomeMapSnapshot.Cell[] cells = new BiomeMapSnapshot.Cell[SIZE * SIZE];
        final MapImage biomes = new MapImage(), heat = new MapImage(), water = new MapImage(), diagnostic = new MapImage();
        boolean dirty = true;
        void close() { biomes.close(); heat.close(); water.close(); diagnostic.close(); }
    }
    private final Map<Key, Tile> tiles = new LinkedHashMap<>(64, 0.75f, true);
    private final long seed;
    private int step, y;

    MapTiles(long seed) {
        this.seed = seed;
    }
    private boolean borders, water, temperature;
    private DimensionSpace dimension = DimensionSpace.OVERWORLD;
    void dimension(DimensionSpace value) {
        close();
        dimension = value;
    }

    void temperature(boolean value) {
        if (temperature == value) return;
        temperature = value;
        tiles.values().forEach(tile -> tile.dirty = true);
    }
    private com.wywf.verify.SurfaceColors.Layer layer = com.wywf.verify.SurfaceColors.Layer.BIOMES;
    void layer(com.wywf.verify.SurfaceColors.Layer value) {
        if (layer == value) return;
        boolean diagnostic = value == com.wywf.verify.SurfaceColors.Layer.CLIMATE
                || value == com.wywf.verify.SurfaceColors.Layer.SLOPE;
        boolean wasDiagnostic = layer == com.wywf.verify.SurfaceColors.Layer.CLIMATE
                || layer == com.wywf.verify.SurfaceColors.Layer.SLOPE;
        boolean endSurfaceChanged = dimension == DimensionSpace.END
                && (value == com.wywf.verify.SurfaceColors.Layer.SURFACE || layer == com.wywf.verify.SurfaceColors.Layer.SURFACE);
        layer = value;
        if (diagnostic || wasDiagnostic || endSurfaceChanged) tiles.values().forEach(t -> t.dirty = true);
    }
    void water(boolean value) {
        if (water == value) return;
        water = value;
        if (water) tiles.values().forEach(t -> t.dirty = true);
        else tiles.values().forEach(t -> t.water.close());
    }

    void borders(boolean value) {
        if (borders == value) return;
        borders = value;
        tiles.values().forEach(t -> t.dirty = true);
    }
    void step(int value) { step = value; }
    void y(int value) { y = value; }
    long uploads;
    void update(BiomeMapSnapshot map) {
        int step = map.step();
        y = map.y();
        Set<Key> changed = new HashSet<>();
        for (int z = 0; z < map.height(); z++) for (int x = 0; x < map.width(); x++) {
            var cell = map.cells()[z * map.width() + x];
            if (cell == null) continue;
            int wx = Math.floorDiv(map.x(), step) + x, wz = Math.floorDiv(map.z(), step) + z;
            Key key = new Key(step, y, Math.floorDiv(wx, SIZE), Math.floorDiv(wz, SIZE));
            Tile tile = tiles.computeIfAbsent(key, k -> new Tile());
            int index = Math.floorMod(wz, SIZE) * SIZE + Math.floorMod(wx, SIZE);
            if (tile.cells[index] != cell && !cell.equals(tile.cells[index])) {
                tile.cells[index] = cell;
                tile.dirty = true;
                changed.add(key);
            }
        }
        for (Key key : changed) {
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                Tile neighbor = tiles.get(new Key(step, y, key.x + dx, key.z + dz));
                if (neighbor != null) neighbor.dirty = true;
            }
        }
        while (tiles.size() > 160) {
            var iterator = tiles.entrySet().iterator();
            var old = iterator.next();
            old.getValue().close();
            iterator.remove();
        }
    }
    private BiomeMapSnapshot.Cell cell(Key key, int x, int z) {
        Tile t = tiles.get(new Key(key.step, key.y, key.x + Math.floorDiv(x, SIZE), key.z + Math.floorDiv(z, SIZE)));
        return t == null ? null : t.cells[Math.floorMod(z, SIZE) * SIZE + Math.floorMod(x, SIZE)];
    }
    private void upload(Key key, Tile tile) {
        boolean diagnosticLayer = layer == com.wywf.verify.SurfaceColors.Layer.CLIMATE
                || layer == com.wywf.verify.SurfaceColors.Layer.SLOPE;
        int padding = water && !diagnosticLayer ? WaterMap.BLEND_PADDING_CELLS + 1 : 1;
        int size = SIZE + 2 * padding;
        var cells = new BiomeMapSnapshot.Cell[size * size];
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++)
            cells[z * size + x] = cell(key, x - padding, z - padding);
        var map = new BiomeMapSnapshot((key.x * SIZE - padding) * key.step, (key.z * SIZE - padding) * key.step,
                key.step, size, size, cells, true, key.y);
        int scale = 4;
        if (diagnosticLayer) {
            int[] diagnostic = new int[cells.length];
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                int i = z * size + x;
                var c = cells[i];
                if (c == null) continue;
                if (layer == com.wywf.verify.SurfaceColors.Layer.CLIMATE) {
                    if (Float.isFinite(c.climate()))
                        diagnostic[i] = BiomePalette.temperature((c.climate() + 1) * 1.25f - 0.5f);
                } else if (c.height() != Integer.MIN_VALUE) {
                    int l = x > 0 && cells[i - 1] != null && cells[i - 1].height() != Integer.MIN_VALUE ? cells[i - 1].height() : c.height();
                    int u = z > 0 && cells[i - size] != null && cells[i - size].height() != Integer.MIN_VALUE ? cells[i - size].height() : c.height();
                    diagnostic[i] = BiomePalette.temperature((float) (Math.atan(Math.hypot(c.height() - l, c.height() - u) / key.step)
                            / (Math.PI / 2) * 2.5 - 0.5));
                }
            }
            if (dimension == DimensionSpace.END) BiomeMapColors.paintEndVoid(map, diagnostic, 1);
            tile.diagnostic.upload(size, size, diagnostic);
        } else if (water) {
            var pixels = WaterMap.raster(map, scale);
            if (dimension == DimensionSpace.END) BiomeMapColors.paintEndVoid(map, pixels, scale);
            int outputSize = (SIZE + 2) * scale, margin = (padding - 1) * scale;
            int[] cropped = new int[outputSize * outputSize];
            for (int z = 0; z < outputSize; z++)
                System.arraycopy(pixels, (z + margin) * size * scale + margin, cropped, z * outputSize, outputSize);
            tile.water.upload(outputSize, outputSize, cropped);
        } else if (temperature) {
            int[] pixels = BiomeMapColors.raster(map, true, false);
            for (int i = 0; i < cells.length; i++) if (cells[i] == null) pixels[i] = 0;
            if (dimension == DimensionSpace.END) BiomeMapColors.paintEndVoid(map, pixels, 1);
            tile.heat.upload(size, size, pixels);
        } else if (dimension == DimensionSpace.END && layer == com.wywf.verify.SurfaceColors.Layer.SURFACE) {
            tile.biomes.upload(size * scale, size * scale, BiomeMapColors.endSurface(map, scale));
        } else {
            int[] pixels = BiomeMapColors.detailed(map, scale, seed);
            if (borders) {
                if (key.step <= 8 && key.y >= 64) {
                    int[] heights = new int[cells.length];
                    for (int i = 0; i < cells.length; i++) heights[i] = cells[i] == null ? Integer.MIN_VALUE : cells[i].height();
                    TerrainRaster.contours(pixels, heights, size, size, scale, 8);
                }
                TerrainRaster.upperBoundaries(pixels, map, scale);
            }
            if (dimension == DimensionSpace.END) BiomeMapColors.paintEndVoid(map, pixels, scale);
            tile.biomes.upload(size * scale, size * scale, pixels);
        }
        tile.dirty = false;
        uploads++;
    }
    BiomeMapSnapshot.Cell at(int x, int z) {
        BiomeMapSnapshot.Cell found = null;
        int finest = Integer.MAX_VALUE;
        for (var entry : tiles.entrySet()) {
            var key = entry.getKey();
            if (key.y != y || key.step >= finest || Math.floorDiv(x,key.step*SIZE) != key.x || Math.floorDiv(z,key.step*SIZE) != key.z) continue;
            var value = entry.getValue().cells[Math.floorMod(Math.floorDiv(z,key.step),SIZE)*SIZE+Math.floorMod(Math.floorDiv(x,key.step),SIZE)];
            if (value != null) {
                found = BiomeMapColors.sampleAt(value,x,z,key.step);
                finest = key.step;
            }
        }
        return found;
    }
    void prepare(MapViewport view) {
        var budget = new MapWorkBudget();
        var entries = new ArrayList<Map.Entry<Key, Tile>>();
        for (var entry : tiles.entrySet()) {
            Key key = entry.getKey();
            if (key.y != y || !entry.getValue().dirty) continue;
            if (view.pixelX((key.x+1)*SIZE*key.step) < 0 || view.pixelZ((key.z+1)*SIZE*key.step) < 0
                    || view.pixelX(key.x*SIZE*key.step) > view.width() || view.pixelZ(key.z*SIZE*key.step) > view.height()) continue;
            entries.add(entry);
        }
        entries.sort(Comparator.<Map.Entry<Key, Tile>>comparingInt(e -> Math.abs(e.getKey().step-step))
                .thenComparingDouble(e -> {
                    var key = e.getKey();
                    double cx = (key.x + .5) * SIZE * key.step - view.x();
                    double cz = (key.z + .5) * SIZE * key.step - view.z();
                    return cx * cx + cz * cz;
                }));
        for (var entry : entries) {
            if (!budget.claim()) break;
            upload(entry.getKey(), entry.getValue());
        }
    }

    void draw(GuiGraphicsExtractor g, MapViewport view, int left, int top, boolean temperature, boolean overviewOnly) {
        var visible = new ArrayList<>(tiles.entrySet());
        visible.sort(Comparator.comparingInt((Map.Entry<Key, Tile> e) -> e.getKey().step).reversed());
        for (var entry : visible) {
            Key key = entry.getKey();
            Tile tile = entry.getValue();
            if (key.y != y || overviewOnly && key.step < step) continue;
            int x = left + (int) Math.floor(view.pixelX(key.x * SIZE * key.step));
            int z = top + (int) Math.floor(view.pixelZ(key.z * SIZE * key.step));
            int right = left + (int) Math.floor(view.pixelX((key.x + 1) * SIZE * key.step));
            int bottom = top + (int) Math.floor(view.pixelZ((key.z + 1) * SIZE * key.step));
            if (right <= left || bottom <= top || x >= left + view.width() || z >= top + view.height()) continue;

            (layer == com.wywf.verify.SurfaceColors.Layer.CLIMATE || layer == com.wywf.verify.SurfaceColors.Layer.SLOPE
                    ? tile.diagnostic : water ? tile.water : temperature ? tile.heat : tile.biomes).drawRegion(g, x, z, right - x, bottom - z, 1f / (SIZE + 2), (SIZE + 1f) / (SIZE + 2));
        }
    }
    @Override public void close() { tiles.values().forEach(Tile::close); tiles.clear(); }
}
