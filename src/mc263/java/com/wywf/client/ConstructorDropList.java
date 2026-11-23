package com.wywf.client;

import dev.diylib.client.ui.DiyDropdown;
import net.minecraft.client.gui.Font;

import java.util.List;
import java.util.function.Consumer;

final class ConstructorDropList extends DiyDropdown {
    ConstructorDropList(int x, int y, int width, String title, List<String> options, int selected,
                        Font font, Consumer<Integer> onPick, Consumer<ConstructorDropList> onOpen) {
        super(x, y, width, title, options, selected, font, onPick,
                dropdown -> onOpen.accept((ConstructorDropList) dropdown));
        workshopPalette();
    }

    void biomeIcons(java.util.function.IntFunction<String> idAt) {
        optionIcons((graphics, index, x, y) -> BiomeIcons.draw(graphics, idAt.apply(index), x, y));
    }

    ConstructorDropList popupLimits(int top, int bottom) {
        popupViewport(() -> new dev.diylib.client.ui.DiyUiLayers.Area(getX(), top, Math.max(0, getWidth()),
                Math.max(0, bottom - top)));
        return this;
    }
}
