package com.wywf.client;

import net.minecraft.network.chat.Component;
import java.util.Locale;

final class MapTexts {
    static String coordinates(int x, int z) {
        return "X " + x + " Z " + z;
    }

    static final int EMPHASIS = 0xFFDEDEDE;
    static String text(String key, Object... values) {
        String template = ConstructorTexts.tr("wywf.map." + key);
        return values.length == 0 ? template : String.format(Locale.ROOT, template, values);
    }
    static Component label(String key, Object... values) { return Component.literal(text(key, values)); }
    private MapTexts() {}
}
