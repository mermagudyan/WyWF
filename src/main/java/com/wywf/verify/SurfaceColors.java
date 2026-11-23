package com.wywf.verify;

import com.wywf.core.DimensionSpace;
import com.wywf.map.BiomePalette;

public final class SurfaceColors {
    public enum Layer { SURFACE, BIOMES, HEIGHT, SLOPE, WATER, TEMPERATURE, CLIMATE, MATCH }
    public static int[] raster(SurfaceSnapshot s, Layer layer, boolean borders) {
        int[] pixels = new int[s.colors().length];
        int legacyWater = 0xFF000000 | net.minecraft.world.level.material.MapColor.WATER.col;
        for (int z = 0; z < s.size(); z++) for (int x = 0; x < s.size(); x++) {
            int i = z * s.size() + x;
            if (s.blocks()[i] == null) { pixels[i] = 0xFF26343D; continue; }
            if (s.heights()[i] == Integer.MIN_VALUE) { pixels[i] = DimensionSpace.END_VOID_COLOR; continue; }
            int left = x > 0 && s.blocks()[i - 1] != null && s.heights()[i - 1] != Integer.MIN_VALUE ? i - 1 : i;
            int up = z > 0 && s.blocks()[i - s.size()] != null && s.heights()[i - s.size()] != Integer.MIN_VALUE ? i - s.size() : i;
            int dx = s.heights()[i] - s.heights()[left], dz = s.heights()[i] - s.heights()[up];
            int color = switch (layer) {
                case SURFACE -> BlockMapColors.light(BlockMapColors.surfaceMaterial(s.blocks()[i],
                                s.colors()[i] == legacyWater && liquidWater(s.blocks()[i]) ? 0xFF23434A : s.colors()[i]),
                        water(s.blocks()[i]) ? 1 : 1 - Math.clamp((float)((dx + dz) * .022), -.14f, .25f));
                case BIOMES -> biomeColor(s, s.biomes()[i], s.x() + x + .5, s.z() + z + .5);
                case HEIGHT -> BiomePalette.temperature((s.heights()[i] + 64) / 384f * 2.5f - 0.5f);
                case SLOPE -> BiomePalette.temperature((float) (Math.atan(Math.hypot(dx, dz)) / (Math.PI / 2) * 2.5 - 0.5));
                case WATER -> water(s.blocks()[i]) ? 0xFF397AC6 : shade(s.colors()[i], 0.45);
                case TEMPERATURE -> BiomePalette.temperature(s.temperatures()[i]);
                case CLIMATE -> BiomePalette.temperature((s.climate()[i] + 1) * 1.25f - 0.5f);
                case MATCH -> 0xFF50616B;
            };
            if (borders && layer == Layer.BIOMES && (!s.biomes()[i].equals(s.biomes()[left]) || !s.biomes()[i].equals(s.biomes()[up])))
                color = shade(color, 0.65);
            if (borders && layer == Layer.HEIGHT && (s.heights()[i] / 16 != s.heights()[left] / 16 || s.heights()[i] / 16 != s.heights()[up] / 16))
                color = shade(color, 0.7);
            if (layer == Layer.WATER && (water(s.blocks()[i]) != water(s.blocks()[left]) || water(s.blocks()[i]) != water(s.blocks()[up])))
                color = 0xFFE4D69D;
            pixels[i] = color;
        }
        return pixels;
    }
    private static int biomeColor(SurfaceSnapshot snapshot, String biome, double x, double z) {
        Integer custom = snapshot.biomeColors().get(biome);
        return custom == null ? BiomePalette.mapColor(biome, snapshot.seed(), x, z) : custom;
    }

    public static float[] climateRange(SurfaceSnapshot s) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < s.climate().length; i++) if (s.blocks()[i] != null) {
            min = Math.min(min, s.climate()[i]);
            max = Math.max(max, s.climate()[i]);
        }
        return new float[]{min, max};
    }
    public static int[] climateRaster(SurfaceSnapshot s, boolean localContrast) {
        if (!localContrast) return raster(s, Layer.CLIMATE, false);
        float[] range = climateRange(s);
        float span = range[1] - range[0];
        int[] pixels = new int[s.colors().length];
        for (int i = 0; i < pixels.length; i++) {
            float scaled = span > 0.000001f ? (s.climate()[i] - range[0]) / span * 2.5f - 0.5f : 0.75f;
            pixels[i] = s.blocks()[i] == null ? 0xFF26343D : BiomePalette.temperature(scaled);
        }
        return pixels;
    }

    public static int[] reduceBiomes(SurfaceSnapshot s, int step) {
        int size = s.size() / step;
        int[] result = new int[size * size];
        var counts = new java.util.HashMap<String, Integer>();
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            counts.clear();
            boolean missing = false;
            for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
                int i = (z * step + dz) * s.size() + x * step + dx;
                if (s.blocks()[i] == null) missing = true;
                else counts.merge(s.biomes()[i], 1, Integer::sum);
            }
            if (missing) {
                result[z * size + x] = 0xFF26343D;
                continue;
            }
            String dominant = null;
            int most = 0;
            for (var entry : counts.entrySet()) {
                int count = entry.getValue();
                if (count > most || count == most && entry.getKey().compareTo(dominant) < 0) {
                    dominant = entry.getKey();
                    most = count;
                }
            }
            result[z * size + x] = biomeColor(s, dominant,
                    s.x() + (x + .5) * step, s.z() + (z + .5) * step);
        }
        return result;
    }

    public static int[] reduce(int[] pixels, SurfaceSnapshot s, int step) {
        int size = s.size() / step;
        int[] result = new int[size * size];
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            long r = 0, g = 0, b = 0;
            boolean missing = false;
            for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
                int i = (z * step + dz) * s.size() + x * step + dx;
                missing |= s.blocks()[i] == null;
                r += pixels[i] >> 16 & 255;
                g += pixels[i] >> 8 & 255;
                b += pixels[i] & 255;
            }
            int n = step * step;
            result[z * size + x] = missing ? 0xFF26343D : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }
        return result;
    }

    private static boolean liquidWater(String block) {
        return "minecraft:water".equals(block) || "minecraft:bubble_column".equals(block)
                || "minecraft:kelp".equals(block) || "minecraft:kelp_plant".equals(block)
                || "minecraft:seagrass".equals(block) || "minecraft:tall_seagrass".equals(block);
    }

    public static boolean water(String block) { return block != null && (block.equals("minecraft:water") || block.contains("ice")); }
    private static int shade(int color, double factor) {
        int r = Math.min(255, (int) ((color >> 16 & 255) * factor));
        int g = Math.min(255, (int) ((color >> 8 & 255) * factor));
        int b = Math.min(255, (int) ((color & 255) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
    private SurfaceColors() {}
}
