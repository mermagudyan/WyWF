package com.wywf.client;

import dev.diylib.client.ui.DiyBlockPanel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

enum BlockMenuSkin {
    STONE, DEEPSLATE, NETHERRACK, DIRT, GRAVEL, SANDSTONE, TUFF, QUARTZ, DIAMOND, PRISMARINE;

    private final DiyBlockPanel style = DiyBlockPanel.valueOf(name());

    private static final Identifier DIRT_TEXTURE = Identifier.withDefaultNamespace("textures/block/dirt.png");
    private static final Identifier GRASS_SIDE = Identifier.withDefaultNamespace("textures/block/grass_block_side.png");
    private static final Identifier GRASS_OVERLAY = Identifier.withDefaultNamespace("textures/block/grass_block_side_overlay.png");

    static void drawEarth(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean grass) {
        if (width <= 0 || height <= 0) return;
        repeat(graphics, DIRT_TEXTURE, x, y, width, height);
        if (!grass) return;
        repeat(graphics, GRASS_SIDE, x, y, width, Math.min(16, height));
        graphics.enableScissor(x, y, x + width, y + height);
        for (int dx = 0; dx < width; dx += 16)
            graphics.blit(RenderPipelines.GUI_TEXTURED, GRASS_OVERLAY,
                    x + dx, y, 0, 0, 16, 16, 16, 16, 0xFF83B95E);
        graphics.disableScissor();
    }

    private static void repeat(GuiGraphicsExtractor graphics, Identifier id, int x, int y, int width, int height) {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        var sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        graphics.blit(texture.getTextureView(), sampler, x, y, x + width, y + height,
                0, width / 16f, 0, height / 16f);
    }

    static BlockMenuSkin random() { return valueOf(DiyBlockPanel.random().name()); }

    void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, long seed) {
        style.draw(graphics, x, y, width, height, seed);
    }

    dev.diylib.client.ui.DiyModal.Panel modal(int first, int last, java.util.function.IntSupplier overlay) {
        return style.modal(first, last, overlay);
    }

    static void panel(GuiGraphicsExtractor graphics, BlockMenuSkin skin, int x, int y, int width, int height,
                      int first, int last) {
        DiyBlockPanel.panel(graphics, skin.style, x, y, width, height, first, last);
    }
}
