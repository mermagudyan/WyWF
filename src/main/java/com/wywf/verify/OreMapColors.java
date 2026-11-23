package com.wywf.verify;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class OreMapColors {
    private record Drop(String item, int fallback) {}
    private static final Map<String, Drop> DROPS = Map.ofEntries(
            Map.entry("coal", new Drop("coal", 0xFF43434A)),
            Map.entry("copper", new Drop("raw_copper", 0xFFCC8356)),
            Map.entry("iron", new Drop("raw_iron", 0xFFC6A18B)),
            Map.entry("gold", new Drop("raw_gold", 0xFFF0BF2E)),
            Map.entry("nether_gold", new Drop("gold_nugget", 0xFFF6BC2B)),
            Map.entry("nether_quartz", new Drop("quartz", 0xFFEEE0D8)),
            Map.entry("redstone", new Drop("redstone", 0xFFC32216)),
            Map.entry("lapis", new Drop("lapis_lazuli", 0xFF3159C6)),
            Map.entry("diamond", new Drop("diamond", 0xFF3AD6D0)),
            Map.entry("emerald", new Drop("emerald", 0xFF26C659)));
    private static final ConcurrentHashMap<Drop, Integer> COLORS = new ConcurrentHashMap<>();
    private static volatile ResourceManager resources;

    private OreMapColors() {}

    static int choose(String block, int color) {
        if (!block.startsWith("minecraft:") || !block.endsWith("_ore")) return color;
        String key = block.substring(block.startsWith("minecraft:deepslate_") ? 20 : 10, block.length() - 4);
        var drop = DROPS.get(key);
        if (drop == null) return color;
        var client = Minecraft.getInstance();
        var manager = client == null ? null : client.getResourceManager();
        if (resources != manager) {
            synchronized (COLORS) {
                if (resources != manager) {
                    COLORS.clear();
                    resources = manager;
                }
            }
        }
        return COLORS.computeIfAbsent(drop, item -> sample(manager, item));
    }

    private static int sample(ResourceManager manager, Drop drop) {
        String asset = "textures/item/" + drop.item + ".png";
        try (InputStream stream = manager == null
                ? OreMapColors.class.getResourceAsStream("/assets/minecraft/" + asset)
                : manager.getResource(Identifier.withDefaultNamespace(asset)).orElseThrow(IOException::new).open()) {
            if (stream != null) {
                try (var image = NativeImage.read(stream)) {
                    return representativePixel(image, drop.fallback);
                }
            }
        } catch (IOException ignored) {}
        return drop.fallback;
    }

    static int representativePixel(NativeImage image, int fallback) {
        double total = 0, red = 0, green = 0, blue = 0;
        int[] pixels = image.getPixels();
        for (int pixel : pixels) {
            if ((pixel >>> 24) < 160) continue;
            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
            double weight = 30 + Math.max(r, Math.max(g, b));
            total += weight;
            red += r * weight;
            green += g * weight;
            blue += b * weight;
        }
        if (total == 0) return fallback;
        double targetR = red / total, targetG = green / total, targetB = blue / total;
        double best = Double.POSITIVE_INFINITY;
        int result = fallback;
        for (int pixel : pixels) {
            if ((pixel >>> 24) < 160) continue;
            double dr = (pixel >> 16 & 255) - targetR, dg = (pixel >> 8 & 255) - targetG, db = (pixel & 255) - targetB;
            double distance = dr * dr + dg * dg + db * db;
            if (distance >= best) continue;
            best = distance;
            result = 0xFF000000 | (pixel & 0xFFFFFF);
        }
        return result;
    }
}
