package com.wywf.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ConstructorCompassButton extends Button {
    private static final Identifier[] FRAMES = new Identifier[32];
    static {
        for (int i = 0; i < FRAMES.length; i++) FRAMES[i] = Identifier.withDefaultNamespace(
                String.format(java.util.Locale.ROOT, "textures/item/recovery_compass_%02d.png", i));
    }
    private final boolean labeled;
    public ConstructorCompassButton(OnPress action) {
        this(SearchConstructorScreen.label("open"), action, true);
    }
    protected ConstructorCompassButton(Component label, OnPress action) {
        this(label, action, false);
    }

    private ConstructorCompassButton(Component label, OnPress action, boolean labeled) {
        super(0, 0, labeled ? 55 : 24, 20, label, action, DEFAULT_NARRATION);
        this.labeled = labeled;
    }
    @Override protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float tick) {
        if (active) extractDefaultSprite(g);
        else TargetTileLayer.button(g, getX(), getY(), getWidth(), getHeight(), false, false, 0xFFB385E5);
        float bearing = active ? ClientPlatform.panoramaBearing(Minecraft.getInstance()) : 0;
        int frame = Math.floorMod(Math.round(bearing / 360f * 32), 32);
        g.blit(RenderPipelines.GUI_TEXTURED, FRAMES[frame], getX() + 4, getY() + 2, 0, 0, 16, 16, 16, 16);
        if (labeled) g.text(Minecraft.getInstance().font, "WyWF", getX() + 23, getY() + 6, 0xFFFFFFFF);
    }
}