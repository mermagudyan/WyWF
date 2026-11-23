package com.wywf.client;

import dev.diylib.client.ui.DiyUiLayers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;

final class TargetTileLayer {
    private TargetTileLayer() {}

    static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, Runnable tile) {
        DiyUiLayers.drawTile(graphics, new DiyUiLayers.Area(x, y, width, height), tile);
    }
    static ScreenRectangle reveal(ScreenRectangle bounds, ScreenRectangle origin, double progress) {
        var mask = DiyUiLayers.revealFrom(area(bounds), area(origin), progress);
        return new ScreenRectangle(mask.x(), mask.y(), mask.width(), mask.height());
    }

    static void clip(GuiGraphicsExtractor graphics, ScreenRectangle bounds, Runnable content) {
        DiyUiLayers.clip(graphics, area(bounds), content);
    }

    private static DiyUiLayers.Area area(ScreenRectangle bounds) {
        return new DiyUiLayers.Area(bounds.left(), bounds.top(), bounds.width(), bounds.height());
    }

    static void dim(GuiGraphicsExtractor g, int x, int y, int width, int height, double opacity) {
        DiyUiLayers.dim(g, new DiyUiLayers.Area(x, y, width, height), opacity);
    }

    static void toggle(GuiGraphicsExtractor g, int x, int y, int width, int height,
                       boolean enabled, boolean hover, boolean active) {
        dev.diylib.client.ui.DiyToggle.drawBox(g, x, y, width, height, enabled, hover, active);
    }

    static void button(GuiGraphicsExtractor g, int x, int y, int width, int height,
                       boolean active, boolean hover, int border) {
        var normal = dev.diylib.client.ui.DiyOutline.solid(border);
        var focus = dev.diylib.client.ui.DiyOutline.solid(dev.diylib.client.ui.DiyAccent.tone(border, .22f));
        dev.diylib.client.ui.DiyControlSkin.button(g, x, y, width, height, active, hover, normal, focus);
    }

    static void chevron(GuiGraphicsExtractor graphics, int x, int y, double direction, double opening, int color) {
        dev.diylib.client.ui.DiyChevron.horizontal(graphics, x, y, direction, opening, color);
    }

    static void locked(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        dev.diylib.client.ui.DiyControlSkin.enchanted(g, x, y, width, height);
    }
}
