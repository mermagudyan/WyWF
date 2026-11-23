package com.wywf.client;

import dev.diylib.Diy;
import dev.diylib.client.ui.DiyModal;
import dev.diylib.theme.ThemeTokens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

final class MenuModal extends DiyModal {
    MenuModal(Font font, Component title, Component body, BooleanSupplier firstEnabled, Runnable dismiss, Button... buttons) {
        this(MenuBackgrounds.nextDialog(), font, title, body, firstEnabled, dismiss, buttons);
    }

    MenuModal(BlockMenuSkin skin, Font font, Component title, Component body, BooleanSupplier firstEnabled,
              Runnable dismiss, Button... buttons) {
        super(panel(skin), font, MenuAccent.heading(title.getString()), body, firstEnabled, dismiss, buttons);
    }

    private static DiyModal.Panel panel(BlockMenuSkin skin) {
        var frame = MenuAccent.modalColors(skin);
        return skin.modal(frame[0], frame[1],
                () -> Diy.themes().active().color(ThemeTokens.SURFACE).withAlpha(.18).argb());
    }
}
