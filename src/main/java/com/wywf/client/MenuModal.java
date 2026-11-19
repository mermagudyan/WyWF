package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.BooleanSupplier;

final class MenuModal {
    private final List<Button> buttons;
    private final Font font;
    private final BlockMenuSkin skin;
    private final Component title, body;
    private final BooleanSupplier firstEnabled;
    private final Runnable dismiss;
    private List<FormattedCharSequence> lines = List.of();
    private int x, y, width, height, screenWidth, screenHeight, focus;

    MenuModal(Font font, Component title, Component body, BooleanSupplier firstEnabled, Runnable dismiss, Button... buttons) {
        this(BlockMenuSkin.PRISMARINE, font, title, body, firstEnabled, dismiss, buttons);
    }

    MenuModal(BlockMenuSkin skin, Font font, Component title, Component body, BooleanSupplier firstEnabled,
              Runnable dismiss, Button... buttons) {
        this.skin = skin;
        this.font = font;
        this.title = title;
        this.body = body;
        this.firstEnabled = firstEnabled;
        this.dismiss = dismiss;
        this.buttons = List.of(buttons);
        focus = firstEnabled.getAsBoolean() ? 0 : buttons.length - 1;
        this.buttons.get(focus).setFocused(true);
    }

    void bounds(int w, int h) {
        screenWidth = w;
        screenHeight = h;
        width = Math.max(32, Math.min(400, w - 24));
        lines = font.split(body, width - 24);
        height = 40 + buttons.size() * 24 + lines.size() * font.lineHeight;
        x = (w - width) / 2;
        y = (h - height) / 2;
        int top = y + 34 + lines.size() * font.lineHeight;
        for (int i = 0; i < buttons.size(); i++) {
            var button = buttons.get(i);
            button.setX(x + 12);
            button.setY(top + i * 24);
            button.setWidth(width - 24);
        }
        buttons.getFirst().active = firstEnabled.getAsBoolean();
        if (!buttons.get(focus).active) moveFocus(1);
    }

    void draw(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.nextStratum();
        g.fill(0, 0, screenWidth, screenHeight, 0xB0000000);
        BlockMenuSkin.panel(g, skin, x, y, width, height, 0xFF579C9D, 0xFFB5D7CD);
        g.centeredText(font, title, screenWidth / 2, y + 10, 0xFFFFDD86);
        int textY = y + 25;
        for (var line : lines) {
            g.centeredText(font, line, screenWidth / 2, textY, 0xFFFFFFFF);
            textY += font.lineHeight;
        }
        buttons.forEach(button -> button.extractRenderState(g, mx, my, delta));
    }

    boolean click(MouseButtonEvent event, boolean keyboard) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (var button : buttons) {
                if (button.active && button.isMouseOver(event.x(), event.y())) {
                    button.mouseClicked(event, keyboard);
                    break;
                }
            }
        }
        return true;
    }

    boolean key(KeyEvent event) {
        if (event.isEscape()) dismiss.run();
        else if (event.key() == InputConstants.KEY_TAB || event.isDown() || event.isUp()) {
            boolean backwards = event.isUp() || event.key() == InputConstants.KEY_TAB && event.hasShiftDown();
            moveFocus(backwards ? -1 : 1);
        } else if (event.isSelection()) buttons.get(focus).keyPressed(event);
        return true;
    }

    private void moveFocus(int step) {
        buttons.get(focus).setFocused(false);
        do {
            focus = Math.floorMod(focus + step, buttons.size());
        } while (!buttons.get(focus).active);
        buttons.get(focus).setFocused(true);
    }
}
