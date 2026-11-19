package com.wywf.map;

import com.wywf.core.DimensionSpace;
import com.wywf.verify.BlockMapColors;

public final class BiomeMapColors {
    public static boolean cave(String id) {
        return id.equals("minecraft:lush_caves") || id.equals("minecraft:dripstone_caves")
                || id.equals("minecraft:deep_dark") || id.equals("minecraft:sulfur_caves");
    }

    public static int[] raster(BiomeMapSnapshot map, boolean temperature, boolean borders) {
        int[] result = new int[map.cells().length];
        boolean underground = map.y() < 64;
        for (int i = 0; i < result.length; i++) {
            var cell = map.cells()[i];
            if (cell == null) { result[i] = 0xFF29343D; continue; }
            int color = temperature ? BiomePalette.temperature(cell.temperature()) : BiomePalette.color(cell.id());
            if (underground && !cave(cell.id())) color = mix(0xFF172126,
                    temperature ? BiomePalette.temperature(cell.surfaceTemperature()) : BiomePalette.color(cell.surfaceId()), 0.24);
            if (cell.overview() != null) {
                var overview = cell.overview();
                color = temperature ? BiomePalette.temperature(overview.temperature()) : overview.color();
                if (underground && !cave(cell.id())) color = mix(0xFF172126,
                        temperature ? BiomePalette.temperature(overview.upperTemperature()) : overview.upperColor(), 0.24);
            }
            if (!temperature && underground && !cave(cell.id()) && cell.surfaceId().contains("ocean")) color = oceanDepth(color, map.y());
            if (!temperature && !underground && cell.id().contains("ocean")) color = oceanDepth(color, cell.height());
            if (underground && cell.overview() != null && !cell.overview().samples().isEmpty()) {
                var samples = cell.overview().samples(); int best = 0, frequency = -1;
                for (var sample : samples) {
                    int candidate = sampleColor(sample,map.y(),temperature), count = 0;
                    for (var other : samples) if (candidate == sampleColor(other,map.y(),temperature)) count++;
                    if (count > frequency || count == frequency && candidate < best) { best = candidate; frequency = count; }
                }
                color = best;
            }
            result[i] = color;
        }
        return result;
    }

    static int sampleColor(BiomeMapSnapshot.Cell cell, int y, boolean temperature) {
        if (y < 64 && !cave(cell.id())) {
            int color = mix(0xFF172126,temperature ? BiomePalette.temperature(cell.surfaceTemperature()) : BiomePalette.color(cell.surfaceId()),0.24);
            return !temperature && cell.surfaceId().contains("ocean") ? oceanDepth(color,y) : color;
        }
        return temperature ? BiomePalette.temperature(cell.temperature()) : BiomePalette.color(cell.id());
    }

    public static BiomeMapSnapshot.Cell sampleAt(BiomeMapSnapshot.Cell cell, int worldX, int worldZ, int step) {
        if (cell == null || cell.overview() == null || cell.overview().samples().isEmpty()) return cell;
        var samples = cell.overview().samples(); int probes = samples.size() == 16 ? 4 : 2;
        int x = Math.min(probes-1,Math.floorMod(worldX,step)*probes/step);
        int z = Math.min(probes-1,Math.floorMod(worldZ,step)*probes/step);
        return samples.get(z*probes+x);
    }

    public static int oceanDepth(int color, int height) {
        if (height == Integer.MIN_VALUE) return color;
        double depth = Math.max(0, 62 - height);
        return mix(color, 0xFF07152F, Math.min(0.78, 1 - Math.exp(-depth / 36)));
    }

    public static int[] detailed(BiomeMapSnapshot map, int scale) {
        int[] base = raster(map, false, false), pixels = new int[map.width() * map.height() * scale * scale];
        int width = map.width() * scale;
        for (int z = 0; z < map.height(); z++) for (int x = 0; x < map.width(); x++) {
            int i = z * map.width() + x; var cell = map.cells()[i];
            for (int dz = 0; dz < scale; dz++) for (int dx = 0; dx < scale; dx++) {
                int color = cell == null ? 0 : base[i];
                if (map.y() >= 64 && cell != null && cell.overview() != null && !cell.overview().detail().isEmpty()) {
                    int probes = cell.overview().detail().size() == 16 ? 4 : 2;
                    color = cell.overview().detail().get((dz * probes / scale) * probes + dx * probes / scale);
                    if (color == BiomePalette.color(cell.id()) && cell.id().contains("ocean")) color = oceanDepth(color, cell.height());
                }
                if (map.y() < 64 && cell != null && cell.overview() != null && !cell.overview().samples().isEmpty()) {
                    var samples = cell.overview().samples(); int probes = samples.size() == 16 ? 4 : 2;
                    color = sampleColor(samples.get((dz*probes/scale)*probes+dx*probes/scale),map.y(),false);
                }
                pixels[(z * scale + dz) * width + x * scale + dx] = color;
            }
        }
        return pixels;
    }

    public static int[] endSurface(BiomeMapSnapshot map, int scale) {
        var cells = map.cells();
        int stride = map.width() * scale;
        int[] pixels = new int[cells.length * scale * scale];
        int stone = BlockMapColors.material("minecraft:end_stone", 0);
        for (int i = 0; i < cells.length; i++) {
            var cell = cells[i];
            if (cell == null) continue;
            int x = i % map.width(), z = i / map.width();
            var left = x == 0 ? null : cells[i - 1];
            var up = z == 0 ? null : cells[i - map.width()];
            int rise = endVoid(cell) ? 0
                    : (left == null || endVoid(left) ? 0 : cell.height() - left.height())
                    + (up == null || endVoid(up) ? 0 : cell.height() - up.height());
            int color = BlockMapColors.light(stone, 1 - Math.clamp(rise * .022f / map.step(), -.14f, .25f));
            for (int row = 0; row < scale; row++) {
                int start = (z * scale + row) * stride + x * scale;
                java.util.Arrays.fill(pixels, start, start + scale, color);
            }
        }
        paintEndVoid(map, pixels, scale);
        return pixels;
    }

    public static boolean endVoid(BiomeMapSnapshot.Cell cell) {
        return cell.height() == Integer.MIN_VALUE;
    }

    public static void paintEndVoid(BiomeMapSnapshot map, int[] pixels, int scale) {
        int width = map.width(), stride = width * scale, span = 2 * scale;
        for (int z = 0; z < map.height(); z++) for (int x = 0; x < width; x++) {
            var cell = map.cells()[z * width + x];
            if (cell == null) continue;
            int center = endVoid(cell) ? -1 : 1;
            int westX = Math.max(0, x - 1), eastX = Math.min(width - 1, x + 1);
            int northZ = Math.max(0, z - 1), southZ = Math.min(map.height() - 1, z + 1);
            int west = endTerrainSample(map, westX, z, center), east = endTerrainSample(map, eastX, z, center);
            int north = endTerrainSample(map, x, northZ, center), south = endTerrainSample(map, x, southZ, center);
            int northwest = endTerrainSample(map, westX, northZ, center), northeast = endTerrainSample(map, eastX, northZ, center);
            int southwest = endTerrainSample(map, westX, southZ, center), southeast = endTerrainSample(map, eastX, southZ, center);
            int start = z * scale * stride + x * scale;
            if (north + south + west + east + northwest + northeast + southwest + southeast == 8 * center) {
                if (center < 0) for (int dz = 0; dz < scale; dz++) {
                    int row = start + dz * stride;
                    java.util.Arrays.fill(pixels, row, row + scale, DimensionSpace.END_VOID_COLOR);
                }
                continue;
            }
            for (int dz = 0; dz < scale; dz++) {
                int offsetZ = 2 * dz + 1 - scale, wz = Math.abs(offsetZ);
                int vertical = offsetZ < 0 ? north : south;
                int diagonalWest = offsetZ < 0 ? northwest : southwest;
                int diagonalEast = offsetZ < 0 ? northeast : southeast;
                int row = start + dz * stride;
                for (int dx = 0; dx < scale; dx++) {
                    int offsetX = 2 * dx + 1 - scale, wx = Math.abs(offsetX);
                    int horizontal = offsetX < 0 ? west : east;
                    int diagonal = offsetX < 0 ? diagonalWest : diagonalEast;
                    int near = center * (span - wx) + horizontal * wx;
                    int far = vertical * (span - wx) + diagonal * wx;
                    if (near * (span - wz) + far * wz <= 0) pixels[row + dx] = DimensionSpace.END_VOID_COLOR;
                }
            }
        }
    }

    private static int endTerrainSample(BiomeMapSnapshot map, int x, int z, int fallback) {
        var cell = map.cells()[z * map.width() + x];
        return cell == null ? fallback : endVoid(cell) ? -1 : 1;
    }

    private static int mix(int a, int b, double f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int blue = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }

    private BiomeMapColors() {}
}
