package com.wywf.core;

public final class MenuColors {
    public static int[] palette(String version,int first,int last){
        if(version.startsWith("26.1"))return new int[]{0xFFB4DDA4,0xFFF0B7D0};
        if(version.startsWith("26.2"))return new int[]{0xFFC78F82,0xFFE4D99B};
        return new int[]{first,last};
    }
    public static int tone(int color, float lightness) {
        float t = Math.clamp(lightness, -1f, 1f);
        int toned = color & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int channel = color >>> shift & 255;
            toned |= Math.round(t >= 0 ? channel + (255 - channel) * t : channel * (1 + t)) << shift;
        }
        return toned;
    }
    public static float borderBlend(long millis, double position) {
        double sway = Math.sin(Math.floorMod(millis, 6400) * Math.TAU / 6400) * .18;
        double blend = .5 + .5 * Math.cos((position - sway) * Math.TAU);
        return (float) (blend * blend * (3 - 2 * blend));
    }

    public static float wave(long millis,float position){return (float)(0.5+0.5*Math.sin(position*Math.PI*2-(millis%5120)/5120d*Math.PI*2));}
    private MenuColors(){}
}
