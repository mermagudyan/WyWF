package com.wywf.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class GuiGraphicsHelper {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("wywf");
    private static Method drawString6;
    private static Method drawString5;
    private static boolean initialized = false;

    private GuiGraphicsHelper() {}

    private static void init() {
        if (initialized) return;
        initialized = true;

        for (Method m : GuiGraphics.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 6 && p[0] == Font.class && p[1] == Component.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class && p[5] == boolean.class) {
                m.setAccessible(true);
                drawString6 = m;
                LOGGER.info("GuiGraphicsHelper: 6-param: {} ret={}", m.getName(), m.getReturnType().getSimpleName());
            }
            if (p.length == 5 && p[0] == Font.class && p[1] == Component.class
                    && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                m.setAccessible(true);
                if (drawString5 == null) {
                    drawString5 = m;
                    LOGGER.info("GuiGraphicsHelper: 5-param: {} ret={}", m.getName(), m.getReturnType().getSimpleName());
                }
            }
        }
    }

    public static void drawString(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        init();
        if (drawString6 != null) {
            try {
                drawString6.invoke(g, font, text, x, y, color, true);
                return;
            } catch (Exception e) {
                LOGGER.error("GuiGraphicsHelper 6-param failed: {}", e.getMessage());
            }
        }
        if (drawString5 != null) {
            try {
                drawString5.invoke(g, font, text, x, y, color);
            } catch (Exception e) {
                LOGGER.error("GuiGraphicsHelper 5-param failed: {}", e.getMessage());
            }
        }
    }

    public static void drawCenteredString(GuiGraphics g, Font font, Component text, int centerX, int y, int color) {
        int width = font.width(text);
        drawString(g, font, text, centerX - width / 2, y, color);
    }
}
