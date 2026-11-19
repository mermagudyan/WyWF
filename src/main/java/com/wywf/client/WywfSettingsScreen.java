package com.wywf.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WywfSettingsScreen extends Screen {
    private final Screen parent;
    public WywfSettingsScreen(Screen parent) { super(Component.literal("WyWF Settings"));this.parent=parent; }
    @Override protected void init() { SearchConstructorScreen.openSettings(parent); }
}
