package com.wywf.map;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchDraft;
import java.util.List;
import java.util.Random;

public final class AdaptiveBiomeSketch {
    public record Patch(SearchDraft.Placement placement, double width, double height, double phase) {}

    public static List<Patch> patches(List<SearchDraft.Placement> placements, long variation) {
        return placements.stream().filter(p -> p.target().category() == KeywordDictionary.Category.BIOME).map(p -> {
            Random random = new Random(variation * 0x9E3779B97F4A7C15L ^ p.target().canonical().hashCode());
            double band = (p.radius() - p.minRadius()) / (double) p.radius();
            double size = 0.12 + 0.06 * band;
            return new Patch(p, size * (0.7 + random.nextDouble() * 0.9),
                    size * (0.7 + random.nextDouble() * 0.9), random.nextDouble() * Math.PI * 2);
        }).toList();
    }

    public static int[] raster(List<Patch> patches, int width, int height, double aspect) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double px = (x + 0.5 - width / 2.0) / (width / 2.0) * aspect;
            double py = (y + 0.5 - height / 2.0) / (height / 2.0);
            double best = 1;
            int color = 0xFF26343A;
            for (Patch patch : patches) {
                double dx = px - patch.placement().x(), dy = py - patch.placement().y();
                if (Math.abs(dx) > patch.width() * 1.3 || Math.abs(dy) > patch.height() * 1.3) continue;
                double angle = Math.atan2(dy, dx);
                double edge = 1 + 0.16 * Math.sin(angle * 3 + patch.phase())
                        + 0.10 * Math.sin(angle * 7 - patch.phase());
                double score = (dx * dx / (patch.width() * patch.width())
                        + dy * dy / (patch.height() * patch.height())) / (edge * edge);
                if (score < best) { best = score; color = BiomePalette.color(patch.placement().target().canonical()); }
            }
            pixels[y * width + x] = color;
        }
        return pixels;
    }

    public static int[] natural(List<Patch> patches, BiomeMapSnapshot map, int size) {
        int[] pixels = new int[size * size];
        for (var patch : patches) {
            String target = patch.placement().target().canonical();
            int source = -1;
            for (int i = 0; i < map.cells().length; i++)
                if (map.cells()[i] != null && map.cells()[i].id().equals(target)) { source = i; break; }
            if (source < 0) {
                for (int i = 0; i < map.cells().length; i++) {
                    var cell = map.cells()[i];
                    if (cell != null && !cell.id().contains("ocean") && !cell.id().contains("river")) { source = i; break; }
                }
            }
            if (source < 0) continue;
            String shape = map.cells()[source].id();
            boolean[] selected = new boolean[map.cells().length];
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            selected[source] = true; queue.add(source);
            int minX = source % map.width(), maxX = minX, minZ = source / map.width(), maxZ = minZ;
            int count = 0;
            while (!queue.isEmpty() && count++ < 2048) {
                int i = queue.removeFirst(), x = i % map.width(), z = i / map.width();
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                for (int next : new int[]{x > 0 ? i - 1 : -1, x + 1 < map.width() ? i + 1 : -1,
                        z > 0 ? i - map.width() : -1, z + 1 < map.height() ? i + map.width() : -1}) {
                    if (next >= 0 && !selected[next] && map.cells()[next] != null && shape.equals(map.cells()[next].id())) {
                        selected[next] = true; queue.add(next);
                    }
                }
            }
            double aspect = Math.max(0.5, Math.min(2, (maxX - minX + 1.0) / (maxZ - minZ + 1)));
            double radiusX = patch.width() * Math.sqrt(aspect), radiusZ = patch.height() / Math.sqrt(aspect);
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                double dx = ((x + 0.5) * 2 / size - 1 - patch.placement().x()) / radiusX;
                double dz = ((z + 0.5) * 2 / size - 1 - patch.placement().y()) / radiusZ;
                if (Math.abs(dx) >= 1 || Math.abs(dz) >= 1) continue;
                int sx = minX + Math.min(maxX - minX, (int) ((dx + 1) * 0.5 * (maxX - minX + 1)));
                int sz = minZ + Math.min(maxZ - minZ, (int) ((dz + 1) * 0.5 * (maxZ - minZ + 1)));
                if (selected[sz * map.width() + sx]) pixels[z * size + x] = BiomePalette.color(target);
            }
        }
        return pixels;
    }

    private AdaptiveBiomeSketch() {}
}
