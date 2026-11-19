package com.wywf.client;

import com.wywf.core.MenuColors;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import java.util.*;

final class MenuAccent {
    private static final Map<String,Component> LABELS=new LinkedHashMap<>(32,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Component> entry){return size()>128;}
    };
    static int mix(int a,int b,float progress){
        float t=Math.max(0,Math.min(1,progress));int color=0;
        for(int shift=0;shift<=24;shift+=8)color|=Math.round(((a>>>shift)&255)*(1-t)+((b>>>shift)&255)*t)<<shift;
        return color;
    }
    static int[] colors(int first,int last){return com.wywf.core.MenuColors.palette(net.minecraft.SharedConstants.getCurrentVersion().id(),first,last);}
    static float wave(float position){return com.wywf.core.MenuColors.wave(System.currentTimeMillis(),position);}
    static Component text(String value,int first,int last){
        var palette=colors(first,last);return animated(value,palette[0],palette[1]);
    }
    static Component conflictText(String value){return animated(value,0xFFF39A95,0xFFDA757D);}
    private static Component animated(String value,int a,int b){
        long phase=(System.currentTimeMillis()/80)%64;
        String key=value+":"+a+":"+b+":"+phase;
        return LABELS.computeIfAbsent(key,k->{
            var result=Component.empty();int[] points=value.codePoints().toArray();
            for(int i=0;i<points.length;i++){
                float position = points.length <= 1 ? 0 : i / (float)(points.length - 1);
                float wave = com.wywf.core.MenuColors.wave(phase * 80, position);
                float blend = MenuColors.borderBlend(phase * 80, position / 2.0);
                int color = mix(MenuColors.tone(a, (wave - .5f) * .48f),
                        MenuColors.tone(b, (wave - .5f) * .48f), blend) & 0xFFFFFF;
                result.append(Component.literal(new String(Character.toChars(points[i]))).withStyle(style->style.withColor(color)));
            }
            return result;
        });
    }
    static float sheen(long now, int x, int y, long seed) {
        double t = (now / 1_000_000L) / 1000d;
        double offset = (seed ^ seed >>> 32) % 4096 / 360d;
        double a = .5 + .5 * Math.cos(x * .114 + y * .168 - t * 1.65 + offset);
        double b = .5 + .5 * Math.cos(x * -.075 + y * .091 - t * 1.07 + offset * .83);
        return (float)(.78 * a * a * a * a + .22 * b * b * a);
    }

    static void frame(GuiGraphicsExtractor g, int x, int y, int width, int height, int thickness,
                      int first, int second, long now, long seed, float strength) {
        if (width < 2 * thickness || height < 2 * thickness) return;
        int right = x + width, bottom = y + height;
        double perimeter = 2.0 * (width + height - 2);
        for (int dx = 0; dx < width; dx++) {
            g.fill(x + dx, y, x + dx + 1, y + thickness,
                    frameColor(first, second, dx / perimeter, dx, 0, now, seed, strength));
            g.fill(x + dx, bottom - thickness, x + dx + 1, bottom,
                    frameColor(first, second, (2.0 * (width - 1) + height - 1 - dx) / perimeter,
                            dx, height - 1, now, seed, strength));
        }
        for (int dy = thickness; dy < height - thickness; dy++) {
            g.fill(x, y + dy, x + thickness, y + dy + 1,
                    frameColor(first, second, (perimeter - dy) / perimeter, 0, dy, now, seed, strength));
            g.fill(right - thickness, y + dy, right, y + dy + 1,
                    frameColor(first, second, (width - 1.0 + dy) / perimeter, width - 1, dy, now, seed, strength));
        }
    }

    private static int frameColor(int first, int second, double position, int x, int y,
                                  long now, long seed, float strength) {
        long millis = now / 1_000_000L;
        float blend = MenuColors.borderBlend(millis, position);
        float light = (sheen(now, x, y, seed) * .42f - .11f) * strength;
        return mix(MenuColors.tone(first, light), MenuColors.tone(second, light), blend);
    }
    private MenuAccent(){}
}
