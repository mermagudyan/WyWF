package com.wywf.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.Screen;

public final class ClientPlatform {
    private ClientPlatform() {}

    public static Screen screen(Minecraft client) { return client.gui.screen(); }

    static float panoramaBearing(Minecraft client) {
        var state = client.gameRenderer.gameRenderState().guiRenderState.panoramaRenderState;
        return state == null ? 0 : state.spin();
    }

    static TabNavigationBar navigation(TabManager manager, int width, Tab[] tabs) {
        var bar = MenuTabBar.builder(manager, width).addTabs(tabs).build();
        bar.arrangeElements(width);
        return bar;
    }
}
