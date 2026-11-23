package com.wywf.map;

public final class BiomePalette {
    private static final int MANGROVE_COLOR = 0x742B3D;
    private static final int GRAVELLY_HILLS_COLOR = 0x97938C;

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
            case "windswept_hills", "windswept_forest", "windswept_wooded_hills" -> 0x789478;
            case "windswept_gravelly_hills" -> GRAVELLY_HILLS_COLOR;
            case "meadow" -> 0x8EC183;
            case "cherry_grove" -> 0xEBA7C2;
            case "jungle", "bamboo_jungle" -> 0x3B9346;
            case "sparse_jungle" -> 0x72A855;
            case "swamp" -> 0x6E8051;
            case "mangrove_swamp" -> MANGROVE_COLOR;
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

    public static int mapColor(String id) {
        Tint tint = tint(id);
        return tint == null ? color(id) : tint.base;
    }

    public static int mapColor(String id, long seed, double x, double z) {
        Tint tint = tint(id);
        return tint == null ? color(id) : tint.color(seed, x, z);
    }

    public static Tint tint(String id) {
        return switch (id) {
            case "minecraft:dappled_forest" -> Tint.DAPPLED_FOREST;
            case "minecraft:windswept_hills" -> Tint.WINDSWEPT_HILLS;
            case "minecraft:windswept_gravelly_hills" -> Tint.GRAVELLY_HILLS;
            case "minecraft:windswept_forest", "minecraft:windswept_wooded_hills" -> Tint.WINDSWEPT_FOREST;
            case "minecraft:taiga" -> Tint.TAIGA;
            case "minecraft:old_growth_pine_taiga" -> Tint.OLD_GROWTH_PINE;
            case "minecraft:old_growth_spruce_taiga" -> Tint.OLD_GROWTH_SPRUCE;
            case "minecraft:snowy_taiga" -> Tint.SNOWY_TAIGA;
            case "minecraft:pale_garden" -> Tint.PALE_GARDEN;
            case "minecraft:mangrove_swamp" -> Tint.MANGROVE;
            case "minecraft:windswept_savanna" -> Tint.WINDSWEPT_SAVANNA;
            case "minecraft:jungle" -> Tint.JUNGLE;
            case "minecraft:sparse_jungle" -> Tint.SPARSE_JUNGLE;
            case "minecraft:bamboo_jungle" -> Tint.BAMBOO_JUNGLE;
            default -> null;
        };
    }

    public enum Tint {
        DAPPLED_FOREST(0xD48A39, 0xB96B31, 0xF0CA60, 0xAA422B, 96),
        WINDSWEPT_HILLS(0x8E908E, 0x7F8181, 0xA2A3A0, 78),
        GRAVELLY_HILLS(GRAVELLY_HILLS_COLOR, 0x918D86, 0xA39F97, 0x88847D, 66),
        WINDSWEPT_FOREST(0x678862, 0x567554, 0x779568, 88),
        TAIGA(0x376B4D, 0x315F45, 0x457751, 96),
        OLD_GROWTH_PINE(0x4C704A, 0x406642, 0x607B4E, 0x203F2B, 86),
        OLD_GROWTH_SPRUCE(0x3E6758, 0x345A4D, 0x4B735F, 0x1C3A30, 90),
        SNOWY_TAIGA(0xB4C8B0, 0xA4BCA5, 0xC6D3BF, 100),
        PALE_GARDEN(0xCDD1C6, 0xBBC3B7, 0xDBDED6, 92),
        MANGROVE(MANGROVE_COLOR),
        WINDSWEPT_SAVANNA(0xBBA766, 0xA39458, 0xC6B575, 0x847365, 84),
        JUNGLE(0x2F8F4B, 0x277D42, 0x429953, 100),
        SPARSE_JUNGLE(0x559A43, 0x4D8E3F, 0x65A34B, 102),
        BAMBOO_JUNGLE(0x498D4B, 0x3F8343, 0x599C55, 0x294F39, 76);

        private static final double ACCENT_START = .39;
        private static final double ACCENT_FULL = .93;

        private final int base;
        private final int low;
        private final int high;
        private final int accent;
        private final int span;

        Tint(int color) {
            this(color, color, color, 0);
        }

        Tint(int base, int low, int high, int span) {
            this(base, low, high, 0, span);
        }

        Tint(int base, int low, int high, int accent, int span) {
            this.base = 0xFF000000 | base;
            this.low = low;
            this.high = high;
            this.accent = accent;
            this.span = span;
        }

        int color(long seed, double x, double z) {
            if (span == 0) return base;
            long salt = seed ^ (0x9E3779B97F4A7C15L * (ordinal() + 1));
            double u = (x + z * .23) / span;
            double v = (z - x * .17) / span;
            double mottling = noise(salt ^ 0xA0761D6478BD642FL, u * 2.8, v * 2.8);
            int ground = blend(low, high, .77 * noise(salt, u, v) + .23 * mottling);
            if (accent == 0) return ground;
            double blendWeight = Math.clamp((mottling - ACCENT_START) / (ACCENT_FULL - ACCENT_START), 0, 1);
            return blend(ground, accent, smooth(blendWeight));
        }
    }

    private static double noise(long seed, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fz = smooth(z - iz);
        double north = lerp(fx, corner(seed, ix, iz), corner(seed, ix + 1, iz));
        double south = lerp(fx, corner(seed, ix, iz + 1), corner(seed, ix + 1, iz + 1));
        return lerp(fz, north, south);
    }

    private static double corner(long seed, int x, int z) {
        long bits = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xD1B54A32D192ED03L);
        bits = (bits ^ (bits >>> 30)) * 0xBF58476D1CE4E5B9L;
        bits = (bits ^ (bits >>> 27)) * 0x94D049BB133111EBL;
        return ((bits ^ (bits >>> 31)) >>> 40) * 0x1.0p-24;
    }

    private static double lerp(double t, double first, double last) {
        return first + (last - first) * t;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static int blend(int first, int second, double t) {
        int r = (int) Math.round(lerp(t, first >>> 16 & 255, second >>> 16 & 255));
        int g = (int) Math.round(lerp(t, first >>> 8 & 255, second >>> 8 & 255));
        int b = (int) Math.round(lerp(t, first & 255, second & 255));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private BiomePalette() {}
}
