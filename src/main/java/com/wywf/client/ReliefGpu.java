package com.wywf.client;

import com.wywf.core.ConfigStore;
import com.wywf.core.DimensionSpace;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface ReliefGpu extends AutoCloseable {
    static void register() {
        PlatformReliefGpu.register();
    }

    static boolean available() {
        return PlatformReliefGpu.available();
    }

    static ReliefGpu create(DimensionSpace dimension) {
        if (Boolean.getBoolean("wywf.relief.cpu") || !available()) return null;
        return Boolean.getBoolean("wywf.relief.gpu") || ConfigStore.load().gpuRelief() ? PlatformReliefGpu.create(dimension) : null;
    }

    boolean render(ReliefMesh mesh, int width, int height, int fog, float centerY,
                   double yaw, double pitch, double zoom, double panX, double panY, boolean slice,
                   ReliefRaster.Outline outline, int originX, int originZ);

    boolean uploading();
    boolean animating();
    boolean hasOutline(ReliefRaster.Outline outline);
    void discardMesh();
    void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
              ReliefRaster.Outline outline, int outlineAlpha);
    @Override void close();
}
