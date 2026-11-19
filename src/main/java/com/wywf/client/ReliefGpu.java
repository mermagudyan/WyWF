package com.wywf.client;

import com.wywf.core.ConfigStore;
import com.wywf.core.DimensionSpace;
import com.wywf.core.WyWFDebug;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface ReliefGpu extends AutoCloseable {
    static void register() {
        PlatformReliefGpu.register();
    }

    static boolean available() {
        return PlatformReliefGpu.available();
    }

    static ReliefGpu create(DimensionSpace dimension) {
        if (dimension != DimensionSpace.END || WyWFDebug.ENABLED || !available()) return null;
        return Boolean.getBoolean("wywf.relief.gpu") || ConfigStore.load().gpuRelief() ? PlatformReliefGpu.create() : null;
    }

    boolean render(ReliefMesh mesh, int width, int height, int fog, float centerY,
                   double yaw, double pitch, double zoom, double panX, double panY);

    boolean uploading();
    void discardMesh();
    void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height);
    @Override void close();
}
