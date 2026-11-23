package com.wywf.core;

import dev.diylib.theme.DiyColorMath;

public final class MenuColors {
    public static final int GOLD = 0xFFE8BE68;

    private MenuColors() {}

    public static int[] palette(String version, int first, int last) {
        if (release(version, "26.1")) return new int[]{0xFFB4DDA4, 0xFFF0B7D0};
        if (release(version, "26.2")) return new int[]{0xFFC78F82, 0xFFE4D99B};
        return new int[]{first, last};
    }

    public static ReadyFramePalette readyFramePalette(String version) {
        if (release(version, "26.1")) return new ReadyFramePalette(0xFFF0B7D0, 0xFF91C784);
        if (release(version, "26.2")) return new ReadyFramePalette(0xFFAC4559, 0xFFE7D671);
        if (release(version, "26.3")) return new ReadyFramePalette(0xFFEA9652, 0xFF81B96A);
        return new ReadyFramePalette(0xFFD8BE7F, 0xFFB7E8BF);
    }

    public static float readyFrameCenter(long millis) {
        return .5f + (wave(millis, 0) - .5f) * .32f;
    }

    public record ReadyFramePalette(int first, int last) {
        public int shade(boolean lastColor, long millis, float position) {
            float lightness = (wave(millis, position) - .5f) * .46f;
            return tone(lastColor ? last : first, lightness);
        }
    }

    private static boolean release(String version, String line) {
        return version.equals(line) || version.startsWith(line + ".") || version.startsWith(line + "-");
    }

    public static int tone(int color, float lightness) { return DiyColorMath.tone(color, lightness); }

    public static float borderBlend(long millis, double position) { return DiyColorMath.borderBlend(millis, position); }

    public static float wave(long millis, float position) { return DiyColorMath.wave(millis, position); }
}
