package com.wywf.map;

public final class TerrainRaster {
    public static int[] draw(int[] colors, int[] heights, int width, int height, int scale, boolean contours) {
        return draw(colors, heights, width, height, scale, contours, 8);
    }
    public static int[] draw(int[] colors, int[] heights, int width, int height, int scale, boolean contours, int interval) {
        if (scale < 1 || interval < 1) throw new IllegalArgumentException("Invalid raster scale");
        int[] pixels = new int[width * height * scale * scale];
        for (int z = 0; z < height * scale; z++) for (int x = 0; x < width * scale; x++)
            pixels[z * width * scale + x] = colors[(z / scale) * width + x / scale];
        if (scale > 1) for (int z = 1; z < height; z++) for (int x = 1; x < width; x++) {
            int i = z * width + x;
            if (colors[i - 1] == colors[i - width] && colors[i] != colors[i - 1])
                pixels[z * scale * width * scale + x * scale] = colors[i - 1];
        }
        if (contours) contours(pixels, heights, width, height, scale, interval);
        return pixels;
    }

    public static void contours(int[] pixels, int[] heights, int width, int height, int scale, int interval) {
        int outWidth = width * scale, outHeight = height * scale;
        int[] previous = new int[outWidth];
        java.util.Arrays.fill(previous, Integer.MIN_VALUE);
        for (int z = 0; z < outHeight; z++) {
            int left = Integer.MIN_VALUE;
            for (int x = 0; x < outWidth; x++) {
                double value = interpolated(heights, width, height, (x + 0.5) / scale - 0.5, (z + 0.5) / scale - 0.5);
                int band = Double.isNaN(value) ? Integer.MIN_VALUE : (int) Math.floor(value / interval);
                if (band != Integer.MIN_VALUE && (left != Integer.MIN_VALUE && band != left
                        || previous[x] != Integer.MIN_VALUE && band != previous[x]) && pixels[z * outWidth + x] != 0)
                    pixels[z * outWidth + x] = shade(pixels[z * outWidth + x], 0.78);
                previous[x] = band;
                left = band;
            }
        }
    }

    private static double interpolated(int[] h, int width, int height, double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z), x1 = x0 + 1, z1 = z0 + 1;
        double fx = x - x0, fz = z - z0;
        x0 = Math.max(0, Math.min(width - 1, x0));
        x1 = Math.max(0, Math.min(width - 1, x1));
        z0 = Math.max(0, Math.min(height - 1, z0));
        z1 = Math.max(0, Math.min(height - 1, z1));
        int a = h[z0 * width + x0], b = h[z0 * width + x1], c = h[z1 * width + x0], d = h[z1 * width + x1];
        if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE || c == Integer.MIN_VALUE || d == Integer.MIN_VALUE) return Double.NaN;
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    public static void upperBoundaries(int[] pixels, BiomeMapSnapshot map, int scale) {
        if (map.y() >= 64) return;
        int width = map.width(), outWidth = width * scale;
        for (int z = 0; z < map.height(); z++) for (int x = 0; x < width; x++) {
            int i = z * width + x;
            var cell = map.cells()[i];
            if (cell == null) continue;
            if (x > 0 && map.cells()[i - 1] != null && !cell.surfaceId().equals(map.cells()[i - 1].surfaceId()))
                for (int dz = 0; dz < scale; dz++) {
                    int p = (z * scale + dz) * outWidth + x * scale;
                    pixels[p] = shade(pixels[p], 1.12);
                }
            if (z > 0 && map.cells()[i - width] != null && !cell.surfaceId().equals(map.cells()[i - width].surfaceId()))
                for (int dx = 0; dx < scale; dx++) {
                    int p = z * scale * outWidth + x * scale + dx;
                    pixels[p] = shade(pixels[p], 1.12);
                }
        }
    }
    private static int shade(int color, double factor) {
        return color & 0xFF000000 | Math.min(255, (int) ((color >> 16 & 255) * factor)) << 16
                | Math.min(255, (int) ((color >> 8 & 255) * factor)) << 8 | Math.min(255, (int) ((color & 255) * factor));
    }
    private TerrainRaster() {}
}
