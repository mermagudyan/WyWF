package com.wywf.client;

import com.wywf.core.MenuColors;
import dev.diylib.client.ui.DiyAccent;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

final class MenuAccent {
    private MenuAccent() {}

    static int[] colors(int first, int last) { return MenuColors.palette(version(), first, last); }

    static MenuColors.ReadyFramePalette readyFramePalette() { return MenuColors.readyFramePalette(version()); }

    private static String version() { return SharedConstants.getCurrentVersion().id(); }

    static int mix(int a, int b, float progress) { return DiyAccent.mix(a, b, progress); }

    static Component text(String text, int first, int last) { return DiyAccent.text(text, first, last); }

    static Component versionText(String text, int first, int last) {
        var palette = colors(first, last);
        return DiyAccent.text(text, palette[0], palette[1]);
    }

    static Component heading(String text) { return DiyAccent.monochrome(text, MenuColors.GOLD); }

    static int[] selectionColors() {
        return colors(0xFFB7E8BF, 0xFFD8BE7F);
    }

    static int[] modalColors(BlockMenuSkin skin) {
        return skin == BlockMenuSkin.PRISMARINE ? new int[]{0xFF579C9D, 0xFFB5D7CD} : selectionColors();
    }

    static Component conflictText(String text) { return DiyAccent.conflictText(text); }

    static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int thickness,
                      int first, int second, long now, long seed, float strength) {
        DiyAccent.frame(graphics, x, y, width, height, thickness, first, second, now, seed, strength);
    }
}
