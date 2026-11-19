package com.wywf.verify;

public final class BlockMapColors {
    public static final int LAVA = 0xFFFF851F;

    private BlockMapColors() {}

    public static int material(String id, int fallback) {
        if (!id.startsWith("minecraft:")) return fallback;
        return switch (id) {
            case "minecraft:grass_block", "minecraft:short_grass" -> 0xFF759448;
            case "minecraft:oak_leaves", "minecraft:oak_leaf_litter" -> 0xFF507435;
            case "minecraft:birch_leaves" -> 0xFF789144;
            case "minecraft:spruce_leaves" -> 0xFF3B664D;
            case "minecraft:jungle_leaves" -> 0xFF467D32;
            case "minecraft:dark_oak_leaves" -> 0xFF3D582B;
            case "minecraft:acacia_leaves" -> 0xFF839648;
            case "minecraft:mangrove_leaves" -> 0xFF497453;
            case "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves" -> 0xFF658448;
            case "minecraft:cherry_leaves" -> 0xFFD496AB;
            case "minecraft:pale_oak_leaves" -> 0xFF77857E;
            case "minecraft:glass", "minecraft:glass_pane" -> 0xFFA5B8BE;
            case "minecraft:iron_chain", "minecraft:chain" -> 0xFF737D8A;
            case "minecraft:iron_bars" -> 0xFF8C939B;
            case "minecraft:copper_chain", "minecraft:waxed_copper_chain" -> 0xFFB56D4F;
            case "minecraft:exposed_copper_chain", "minecraft:waxed_exposed_copper_chain" -> 0xFFA77C66;
            case "minecraft:weathered_copper_chain", "minecraft:waxed_weathered_copper_chain" -> 0xFF6E9279;
            case "minecraft:oxidized_copper_chain", "minecraft:waxed_oxidized_copper_chain" -> 0xFF519889;
            case "minecraft:stone" -> 0xFF797D80;
            case "minecraft:cobblestone" -> 0xFF666D71;
            case "minecraft:andesite" -> 0xFF82888B;
            case "minecraft:deepslate" -> 0xFF494C56;
            case "minecraft:gravel" -> 0xFF858078;
            case "minecraft:clay" -> 0xFF8A919D;
            case "minecraft:sand" -> 0xFFD6C18B;
            case "minecraft:sandstone" -> 0xFFBEA978;
            case "minecraft:dirt" -> 0xFF805D47;
            case "minecraft:coarse_dirt" -> 0xFF71523F;
            case "minecraft:netherrack" -> 0xFF763B41;
            case "minecraft:nether_bricks" -> 0xFF4A2C38;
            case "minecraft:basalt" -> 0xFF4B4B52;
            case "minecraft:blackstone" -> 0xFF382F38;
            case "minecraft:end_stone" -> 0xFFC4BF8C;
            case "minecraft:end_stone_bricks" -> 0xFFD6CDA4;
            default -> fallback;
        };
    }

    public static int light(int color, float level) {
        if (color == LAVA) return 0xFFFF981F;
        int r = color >>> 16 & 255, g = color >>> 8 & 255, b = color & 255;
        float gray = (r * 54 + g * 183 + b * 19) / 256f;
        float cool = Math.max(0, 1 - level) * 18;
        float sun = Math.max(0, level - .85f) * 22;
        int red = Math.clamp(Math.round((r + .04f * (r - gray)) * level + sun), 0, 255);
        int green = Math.clamp(Math.round((g + .04f * (g - gray)) * level + cool * .4f + sun * .6f), 0, 255);
        int blue = Math.clamp(Math.round((b + .04f * (b - gray)) * level + cool), 0, 255);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
