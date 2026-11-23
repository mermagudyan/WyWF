package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class MenuButton {
    private MenuButton() {}

    static Button.Builder builder(Component label, Button.OnPress press) {
        return DiyVanillaButton.builder(label, press);
    }
}
