package com.wywf.map;

public final class WaterMap {
    private static final int BLEND_RADIUS_CELLS = 2;
    public static final int BLEND_PADDING_CELLS = 2 * BLEND_RADIUS_CELLS;
    static boolean wet(int c) {
        return c == 0xFF244DA0 || c == 0xFF183372 || c == 0xFF35B6BB || c == 0xFF307FA8
                || c == 0xFF354B91 || c == 0xFF8ABCD2 || c == 0xFF397CC0;
    }
    public static int[] raster(BiomeMapSnapshot map, int scale) {
        int w = map.width() * scale, h = map.height() * scale;
        int[] raw = new int[w * h], tints = new int[raw.length], out = new int[raw.length];
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            var cell = map.cells()[(z / scale) * map.width() + x / scale];
            if (cell == null) continue;
            int c = BiomePalette.color(cell.id());
            if (cell.overview() != null && !cell.overview().detail().isEmpty()) {
                int probes = cell.overview().detail().size() == 16 ? 4 : 2;
                c = cell.overview().detail().get((z % scale * probes / scale) * probes + x % scale * probes / scale);
            }
            int i = z * w + x;
            raw[i] = c;
            if (wet(c)) tints[i] = color(c, cell.height(), false);
        }
        int[] blended = smoothTints(tints, w, h, scale * BLEND_RADIUS_CELLS);
        for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) {
            int i = z * w + x, c = raw[i];
            if (c == 0) continue;
            boolean edge = false;
            if (x > 0 && raw[i - 1] != 0 && wet(c) != wet(raw[i - 1])) edge = true;
            if (z > 0 && raw[i - w] != 0 && wet(c) != wet(raw[i - w])) edge = true;
            if (x + 1 < w && raw[i + 1] != 0 && wet(c) != wet(raw[i + 1])) edge = true;
            if (z + 1 < h && raw[i + w] != 0 && wet(c) != wet(raw[i + w])) edge = true;
            if (tints[i] != 0) {
                out[i] = edge ? blend(blended[i], 0xFFC5E1DD, .41f) : blended[i];
            } else {
                out[i] = color(c, Integer.MIN_VALUE, edge);
            }
        }
        return out;
    }
    public static int color(int biomeColor, int floor, boolean shore) {
        if (wet(biomeColor)) {
            int base = tint(biomeColor);
            if (shore) return blend(base, 0xFFC5E1DD, .41f);
            if (biomeColor == 0xFF397CC0) return base;
            return BiomeMapColors.oceanDepth(base, floor == Integer.MIN_VALUE ? biomeColor == 0xFF183372 ? 12 : 42 : floor);
        }
        if (shore) return 0xFFE3CE93;
        int r = biomeColor >> 16 & 255, g = biomeColor >> 8 & 255, b = biomeColor & 255;
        int gray = (r * 3 + g * 6 + b) / 10;
        return 0xFF000000 | (gray * 40 / 100) << 16 | (gray * 48 / 100) << 8 | gray * 43 / 100;
    }
    public static int[] smoothTints(int[] colors, int width, int height, int radius) {
        int[] rows = new int[colors.length];
        int[] blended = new int[colors.length];
        blurLines(colors, rows, height, width, width, 1, radius);
        blurLines(rows, blended, width, height, 1, width, radius);
        blurLines(blended, rows, height, width, width, 1, radius);
        blurLines(rows, blended, width, height, 1, width, radius);
        return blended;
    }

    // use a rolling average and ignore missing water samples
    private static void blurLines(int[] source, int[] result, int lines, int length,
                                  int lineStride, int pixelStride, int radius) {
        int reach = Math.min(radius, (length - 1) / 2);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStride;
            int red = 0, green = 0, blue = 0, count = 0;
            for (int pos = -reach; pos < length; pos++) {
                int enter = pos + reach, leave = pos - reach - 1;
                if (enter < length) {
                    int color = source[base + enter * pixelStride];
                    if (color != 0) {
                        red += color >>> 16 & 255;
                        green += color >>> 8 & 255;
                        blue += color & 255;
                        count++;
                    }
                }
                if (leave >= 0) {
                    int color = source[base + leave * pixelStride];
                    if (color != 0) {
                        red -= color >>> 16 & 255;
                        green -= color >>> 8 & 255;
                        blue -= color & 255;
                        count--;
                    }
                }
                if (pos >= 0) {
                    result[base + pos * pixelStride] = count == 0 ? 0
                            : 0xFF000000 | ((red + count / 2) / count) << 16
                            | ((green + count / 2) / count) << 8 | (blue + count / 2) / count;
                }
            }
        }
    }

    public static int tint(String biome) {
        return tint(BiomePalette.color(biome));
    }

    private static int tint(int color) {
        return switch (color) {
            case 0xFF35B6BB -> 0xFF49BDB8;
            case 0xFF307FA8 -> 0xFF328FA9;
            case 0xFF244DA0, 0xFF183372 -> 0xFF2B6898;
            case 0xFF354B91 -> 0xFF264C78;
            case 0xFF8ABCD2 -> 0xFF213C5B;
            case 0xFF397CC0 -> 0xFF397FAC;
            default -> 0xFF1B464E;
        };
    }

    public static int blend(int first, int second, float progress) {
        int rgb = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = first >>> shift & 255, b = second >>> shift & 255;
            rgb |= Math.round(a + (b - a) * progress) << shift;
        }
        return rgb;
    }

    private WaterMap() {}
}