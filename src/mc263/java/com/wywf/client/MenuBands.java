package com.wywf.client;

import dev.diylib.client.ui.DiyMenuBands;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class MenuBands {
    private MenuBands() {}

    static void draw(GuiGraphicsExtractor graphics, int width, int height, int header, int footer) {
        DiyMenuBands.draw(graphics, width, height, header, footer);
    }
}
