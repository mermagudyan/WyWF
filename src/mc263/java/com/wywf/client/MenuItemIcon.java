package com.wywf.client;

import dev.diylib.client.ui.DiyItemIcon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;

final class MenuItemIcon {
    private MenuItemIcon() {}

    static void draw(GuiGraphicsExtractor graphics, Item item, int x, int y) {
        DiyItemIcon.draw(graphics, item, x, y);
    }
}
