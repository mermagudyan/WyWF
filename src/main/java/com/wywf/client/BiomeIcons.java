package com.wywf.client;

import com.wywf.map.BiomePalette;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class BiomeIcons {
    static final int MAP_SIZE = 26;
    private static final Map<String, Identifier> SPRITES = Stream.of(
            "cherry_grove",
            "badlands",
            "deep_dark",
            "lush_caves",
            "dark_forest",
            "desert",
            "jungle",
            "mushroom_fields",
            "frozen_peaks",
            "basalt_deltas",
            "bamboo_jungle",
            "beach",
            "birch_forest",
            "cold_ocean",
            "crimson_forest",
            "dappled_forest",
            "deep_cold_ocean",
            "deep_frozen_ocean",
            "deep_lukewarm_ocean",
            "deep_ocean",
            "dripstone_caves",
            "end_barrens",
            "end_highlands",
            "end_midlands",
            "eroded_badlands",
            "flower_forest",
            "forest",
            "frozen_ocean",
            "frozen_river",
            "grove",
            "ice_spikes",
            "jagged_peaks",
            "lukewarm_ocean",
            "mangrove_swamp",
            "meadow",
            "nether_wastes",
            "ocean",
            "old_growth_birch_forest",
            "old_growth_pine_taiga",
            "old_growth_spruce_taiga",
            "pale_garden",
            "plains",
            "river",
            "savanna",
            "savanna_plateau",
            "small_end_islands",
            "snowy_beach",
            "snowy_plains",
            "snowy_slopes",
            "snowy_taiga",
            "soul_sand_valley",
            "sparse_jungle",
            "stony_peaks",
            "stony_shore",
            "sulfur_caves",
            "sunflower_plains",
            "swamp",
            "taiga",
            "the_end",
            "warm_ocean",
            "warped_forest",
            "windswept_forest",
            "windswept_gravelly_hills",
            "windswept_hills",
            "windswept_savanna",
            "wooded_badlands"
    ).collect(Collectors.toUnmodifiableMap(
            id -> "minecraft:" + id,
            id -> Identifier.fromNamespaceAndPath("wywf", "biome/" + id)));

    private BiomeIcons() {}

    static void draw(GuiGraphicsExtractor graphics, String biome, int x, int y) {
        draw(graphics, biome, x, y, 16);
    }

    static void draw(GuiGraphicsExtractor graphics, String biome, int x, int y, int size) {
        Identifier sprite = SPRITES.get(biome);
        if (sprite != null) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, size, size);
        } else {
            int inset = size / 8;
            graphics.fill(x + inset, y + inset, x + size - inset, y + size - inset, BiomePalette.color(biome));
        }
    }
}
