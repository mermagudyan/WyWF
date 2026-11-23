package com.wywf.client;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class PauseMapButton extends ConstructorCompassButton {
    private PauseMapButton(Screen parent) {
        super(MapTexts.label("live_map"), button -> LiveMapScreen.open(parent));
        setTooltip(Tooltip.create(MapTexts.label("live_map_help")));
    }

    public static void openMap(Screen parent) {
        LiveMapScreen.open(parent);
    }

    public static void add(Minecraft client, Screen screen) {
        if (!(screen instanceof PauseScreen) || client.level == null || client.player == null)
            return;
        var buttons = Screens.getWidgets(screen);
        if (buttons.stream().anyMatch(PauseMapButton.class::isInstance))
            return;
        Button worldOptions = null, options = null;
        for (var widget : buttons) {
            if (!(widget instanceof Button button)
                    || !(button.getMessage().getContents() instanceof TranslatableContents text))
                continue;
            if (text.getKey().equals("options.worldOptions.button")
                    || text.getKey().equals("menu.shareToLan")
                    || text.getKey().equals("menu.multiplayerOptions.button"))
                worldOptions = button;
            if (text.getKey().equals("menu.options")) options = button;
        }
        if (options == null)
            return;
        var compass = new PauseMapButton(screen);
        Button neighbor = worldOptions == null
                ? options : worldOptions;
        int comfortableWidth = client.font.width(neighbor.getMessage()) + 10;
        int shift = worldOptions == null
                ? 0 : Math.clamp(neighbor.getWidth() - comfortableWidth, 0, compass.getWidth() + 4);
        neighbor.setWidth(neighbor.getWidth() - shift);
        compass.setX(neighbor.getX() + neighbor.getWidth() + 4);
        compass.setY(neighbor.getY());
        buttons.add(compass);
    }
}
