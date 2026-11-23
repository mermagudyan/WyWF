package com.wywf.client;

import dev.diylib.client.ui.DiyContextMenu;
import net.minecraft.client.gui.Font;

import java.util.List;

final class MapContextMenu extends DiyContextMenu {
    MapContextMenu(Font font, int width, int height, double mouseX, double mouseY, List<Entry> entries) {
        super(font, width, height, mouseX, mouseY, entries);
    }
}
