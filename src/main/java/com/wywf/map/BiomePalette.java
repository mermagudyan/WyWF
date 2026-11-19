package com.wywf.map;

public final class BiomePalette {
    public static int color(String id) {
        String name = id.substring(id.indexOf(':') + 1);
        return 0xFF000000 | switch (name) {
            case "ocean" -> 0x244DA0;
            case "deep_ocean" -> 0x183372;
            case "warm_ocean", "deep_warm_ocean" -> 0x35B6BB;
            case "lukewarm_ocean", "deep_lukewarm_ocean" -> 0x307FA8;
            case "cold_ocean", "deep_cold_ocean" -> 0x354B91;
            case "frozen_ocean", "deep_frozen_ocean", "frozen_river" -> 0x8ABCD2;
            case "river" -> 0x397CC0;
            case "plains" -> 0x8DB85A;
            case "sunflower_plains" -> 0xB2C85A;
            case "forest" -> 0x43894A;
            case "flower_forest" -> 0x69A764;
            case "birch_forest", "old_growth_birch_forest" -> 0x85A76C;
            case "dark_forest" -> 0x345B3B;
            case "pale_garden" -> 0xA4A99D;
            case "dappled_forest" -> 0x879D50;
            case "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga" -> 0x587F71;
            case "snowy_taiga" -> 0xA3BFB0;
            case "snowy_plains", "ice_spikes" -> 0xDCEBEA;
            case "grove", "snowy_slopes" -> 0xB9CFD2;
            case "frozen_peaks", "jagged_peaks" -> 0xD1DBDE;
            case "stony_peaks", "stony_shore" -> 0x939692;
            case "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_wooded_hills" -> 0x789478;
            case "meadow" -> 0x8EC183;
            case "cherry_grove" -> 0xEBA7C2;
            case "jungle", "bamboo_jungle" -> 0x3B9346;
            case "sparse_jungle" -> 0x72A855;
            case "swamp" -> 0x6E8051;
            case "mangrove_swamp" -> 0x486B42;
            case "desert", "beach" -> 0xE6D499;
            case "snowy_beach" -> 0xCCDCCB;
            case "savanna", "savanna_plateau", "windswept_savanna" -> 0xB4AE5B;
            case "badlands", "eroded_badlands", "wooded_badlands" -> 0xBD7853;
            case "mushroom_fields" -> 0xA677A7;
            case "nether_wastes" -> 0x874539;
            case "soul_sand_valley" -> 0x615047;
            case "crimson_forest" -> 0xA33549;
            case "warped_forest" -> 0x368F82;
            case "basalt_deltas" -> 0x545358;
            case "the_end", "small_end_islands" -> 0xDDD5A1;
            case "end_highlands" -> 0xB5AD7D;
            case "end_midlands" -> 0xCBC38E;
            case "end_barrens" -> 0x928C68;
            case "lush_caves" -> 0x70934E;
            case "dripstone_caves" -> 0x8E7863;
            case "deep_dark" -> 0x24505A;
            case "sulfur_caves" -> 0xB7AE58;
            default -> 0x505050 | (id.hashCode() & 0x7F7F7F);
        };
    }

    public static int temperature(float value) {
        double t = Math.max(0, Math.min(1, (value + 0.5) / 2.5));
        int[] stops = {0x577BCC, 0x7CC4D5, 0xAFCB73, 0xE8B65C, 0xCC604D};
        double pos = t * (stops.length - 1);
        int a = stops[Math.min(stops.length - 1, (int) pos)];
        int b = stops[Math.min(stops.length - 1, (int) pos + 1)];
        double f = pos - (int) pos;
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int blue = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }

    private BiomePalette() {}
}
