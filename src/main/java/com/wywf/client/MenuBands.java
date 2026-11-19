package com.wywf.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;

final class MenuBands {
    static void draw(GuiGraphicsExtractor g, int width, int height, int header, int footer) {
        g.fill(0, header, width, footer, 0x33000000);
        g.blit(RenderPipelines.GUI_TEXTURED, Screen.HEADER_SEPARATOR, 0, header - 2,
                0, 0, width, 2, 32, 2);
        g.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, footer,
                0, 0, width, 2, 32, 2);
    }
    private MenuBands() {}
}