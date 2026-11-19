package com.wywf.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

final class SettingSlider extends AbstractSliderButton {
    private final int min, max;
    private final IntConsumer setter;
    private final IntFunction<String> format;
    private final String label;
    SettingSlider(int width, String label, int current, int min, int max, IntConsumer setter, IntFunction<String> format) {
        super(0,0,width,18,Component.literal(label),(Math.max(min,Math.min(max,current))-min)/(double)(max-min));
        this.label = label; this.min = min; this.max = max; this.setter = setter; this.format = format; updateMessage();
    }
    int number() {
        return min+(int)Math.round(value*(max-min));
    }
    @Override protected void updateMessage() {
        if (format != null)
            setMessage(Component.literal(label+": "+format.apply(number())));
    }
    @Override protected void applyValue() {
        setter.accept(number());
    }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if(active&&(event.isLeft()||event.isRight())){
            setValue((Math.max(min,Math.min(max,number()+(event.isRight()?1:-1)))-min)/(double)(max-min));return true;
        }
        return super.keyPressed(event);
    }
    @Override public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int knob = getX()+(int)Math.round(value*(getWidth()-8)), mid = getY()+getHeight()/2;
        g.fill(getX()+4,mid-2,getX()+getWidth()-4,mid+2,active
                ? 0xFF414141 : 0xFF222222);
        g.fill(getX()+4,mid-2,knob+4,mid+2,active
                ? 0xFFB5B5B5 : 0xFF555555);
        var sprite = Identifier.withDefaultNamespace(isHoveredOrFocused()
                ? "widget/slider_handle_highlighted" : "widget/slider_handle");
        g.blitSprite(RenderPipelines.GUI_TEXTURED,sprite,knob,getY(),8,getHeight(),active
                ? 0xFFFFFFFF : 0xFF777777);
        String text = format.apply(number()); var font = Minecraft.getInstance().font;
        int center = Math.max(getX()+font.width(text)/2,Math.min(getX()+getWidth()-font.width(text)/2,knob+4));
        g.centeredText(font,text,center,getY()-11,active
                ? 0xFFFFFFFF : 0xFF777777);
    }
}
