package com.wywf.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

final class SettingToggle extends AbstractWidget {
    private boolean value;
    private final Consumer<Boolean> setter;
    SettingToggle(String label, boolean value, Consumer<Boolean> setter) {
        super(0,0,18,18,Component.literal(label)); this.value=value; this.setter=setter;
    }
    private void toggle() { value=!value; setter.accept(value); }
    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { toggle(); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!active || !event.isSelection()) return false;
        toggle(); playButtonClickSound(Minecraft.getInstance().getSoundManager()); return true;
    }
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        int x=getX(),y=getY(),color=!active ? 0xFF555555 : isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF9A9A9A;
        g.fill(x,y,x+18,y+1,color);g.fill(x,y+17,x+18,y+18,color);
        g.fill(x,y,x+1,y+18,color);g.fill(x+17,y,x+18,y+18,color);
        if(value)g.fill(x+4,y+4,x+14,y+14,active ? 0xFFE0E0E0 : 0xFF777777);
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,getMessage());
        output.add(NarratedElementType.USAGE,Component.translatable(value ? "options.on" : "options.off"));
    }
}
