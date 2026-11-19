package com.wywf.map;

public final class WaterMap {
    private static boolean wet(int c) {
        return c == 0xFF244DA0 || c == 0xFF183372 || c == 0xFF35B6BB || c == 0xFF307FA8
                || c == 0xFF354B91 || c == 0xFF8ABCD2 || c == 0xFF397CC0;
    }
    public static int[] raster(BiomeMapSnapshot map, int scale) {
        int w = map.width() * scale, h = map.height() * scale;
        int[] raw = new int[w * h], heights = new int[raw.length], out = new int[raw.length];
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            var cell = map.cells()[(z / scale) * map.width() + x / scale];
            int i = z * w + x;
            if (cell == null) continue;
            int c = BiomePalette.color(cell.id());
            if (cell.overview() != null && !cell.overview().detail().isEmpty()) {
                int probes = cell.overview().detail().size() == 16 ? 4 : 2;
                c = cell.overview().detail().get((z % scale * probes / scale) * probes + x % scale * probes / scale);
            }
            raw[i] = c; heights[i] = cell.height();
        }
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            int i = z * w + x, c = raw[i];
            if (c == 0) continue;
            boolean edge = false;
            if (x > 0 && raw[i - 1] != 0 && wet(c) != wet(raw[i - 1])) edge = true;
            if (z > 0 && raw[i - w] != 0 && wet(c) != wet(raw[i - w])) edge = true;
            if (x + 1 < w && raw[i + 1] != 0 && wet(c) != wet(raw[i + 1])) edge = true;
            if (z + 1 < h && raw[i + w] != 0 && wet(c) != wet(raw[i + w])) edge = true;
            out[i] = color(c, heights[i], edge);
        }
        return out;
    }
    public static int color(int biomeColor, int floor, boolean shore) {
        if (wet(biomeColor)) {
            if (biomeColor == 0xFF8ABCD2) return shore ? 0xFFE4F4F5 : 0xFF9DCBDB;
            if (biomeColor == 0xFF397CC0) return shore ? 0xFF76CAE7 : 0xFF338AC4;
            int base = biomeColor == 0xFF35B6BB ? 0xFF34BDB6 : biomeColor == 0xFF307FA8 ? 0xFF278DAA : 0xFF2878B9;
            if (shore) return 0xFF80D4D7;
            return BiomeMapColors.oceanDepth(base, floor == Integer.MIN_VALUE ? biomeColor == 0xFF183372 ? 12 : 42 : floor);
        }
        if (shore) return 0xFFE3CE93;
        int r = biomeColor >> 16 & 255, g = biomeColor >> 8 & 255, b = biomeColor & 255;
        int gray = (r * 3 + g * 6 + b) / 10;
        return 0xFF000000 | (gray * 40 / 100) << 16 | (gray * 48 / 100) << 8 | gray * 43 / 100;
    }
    private WaterMap() {}
}