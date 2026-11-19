package com.wywf.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.concurrent.atomic.AtomicLong;

final class MapImage implements AutoCloseable {
    private static final AtomicLong IDS = new AtomicLong();
    private final Identifier id = Identifier.fromNamespaceAndPath("wywf", "preview/" + IDS.incrementAndGet());
    private boolean registered;
    private NativeImage pixels;
    private DynamicTexture texture;
    private int width,height;

    void upload(int width, int height, int[] colors) {
        if(texture==null||this.width!=width||this.height!=height){
            close();
            pixels=new NativeImage(width,height,false);
            this.width=width;this.height=height;
            texture=new DynamicTexture(()->
                    "WyWF map",pixels);Minecraft.getInstance().getTextureManager().register(id,texture);
            registered=true;
        }
        for(int y=0;y<height;y++)
            for(int x=0;x<width;x++)
                pixels.setPixel(x,y,colors[y*width+x]);
        texture.upload();
    }

    void draw(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        if (registered && width > 0 && height > 0) g.blit(id, x, y, x + width, y + height, 0, 1, 0, 1);
    }

    void drawCropped(GuiGraphicsExtractor g, int x, int y, int width, int height, float uv) {
        if (registered && width > 0 && height > 0) g.blit(id, x, y, x + width, y + height, 0, uv, 0, uv);
    }

    void drawRegion(GuiGraphicsExtractor g, int x, int y, int width, int height, float start, float end) {
        if (registered && width > 0 && height > 0) g.blit(id, x, y, x + width, y + height, start, end, start, end);
    }

    @Override public void close() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(id);
            registered = false;
        }
        pixels=null;
        texture=null;
        width=height=0;
    }
}
