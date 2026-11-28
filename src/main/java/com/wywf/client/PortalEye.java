package com.wywf.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

final class PortalEye implements AutoCloseable {
    record Pixels(int x, int y, int width, int height, int[] first, int[] second) {}

    private static final long PERIOD = 4_200_000_000L;
    private static volatile Textures textures;

    private record Texture(int width, int height, int[] pixels) {
        int sample(double u, double v) {
            int x = (int) ((u - Math.floor(u)) * width);
            int y = (int) ((v - Math.floor(v)) * height);
            return pixels[y * width + x];
        }
    }

    private record Textures(ResourceManager resources, Texture stars, Texture sky) {
        static Textures current() {
            var client = Minecraft.getInstance();
            var resources = client == null ? null : client.getResourceManager();
            var loaded = textures;
            if (loaded != null && loaded.resources == resources) return loaded;
            loaded = new Textures(resources, load(resources, "textures/entity/end_portal/end_portal.png"),
                    load(resources, "textures/environment/end_sky.png"));
            textures = loaded;
            return loaded;
        }

        private static Texture load(ResourceManager resources, String path) {
            try (var stream = resources == null ? PortalEye.class.getResourceAsStream("/assets/minecraft/" + path)
                    : resources.open(Identifier.withDefaultNamespace(path))) {
                if (stream == null) return null;
                try (var image = NativeImage.read(stream)) {
                    return new Texture(image.getWidth(), image.getHeight(), image.getPixels());
                }
            } catch (IOException error) {
                com.wywf.WYWFClient.LOGGER.debug("Could not load portal texture {}", path, error);
                return null;
            }
        }
    }

    private final MapImage first = new MapImage();
    private final MapImage second = new MapImage();
    private int x, y, width, height, sceneWidth, sceneHeight;

    static Pixels capture(byte[] mask, int width, int height) {
        if (mask == null) return null;
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (mask[y * width + x] == 0) continue;
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        if (right < left) return null;
        var texture = Textures.current();
        if (texture.stars == null) return null;
        int w = right - left + 1, h = bottom - top + 1;
        int[] first = new int[w * h], second = new int[first.length];
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            int alpha = Byte.toUnsignedInt(mask[y * width + x]);
            if (alpha == 0) continue;
            int index = (y - top) * w + x - left;
            first[index] = glint(texture, alpha, x, y, 0);
            second[index] = glint(texture, alpha, x, y, 1);
        }
        return new Pixels(left, top, w, h, first, second);
    }

    private static int glint(Textures texture, int alpha, int x, int y, int frame) {
        // use scene coordinates so the eye reveals a stationary pattern as the camera moves
        double u = x / 112.0, v = y / 112.0;
        int distant = texture.stars.sample(u * .75, v * .75 + frame * .045) & 255;
        int near = texture.stars.sample(v * 1.5 - frame * .05, -u * 1.5 + frame * .06) & 255;
        int sky = texture.sky == null ? 0 : texture.sky.sample(u * .5, v * .5) & 255;
        int red = 17 + distant / 14 + near / 11 + sky / 20;
        int green = 31 + distant / 6 + near / 4 + sky / 18;
        int blue = 45 + distant / 4 + near / 5 + sky / 16;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    static NativeImage gpuAtlas() {
        // the texture scales repeat together every 448 screen pixels
        final int side = 448;
        var texture = Textures.current();
        var image = new NativeImage(side, side * 2, true);
        if (texture.stars != null)
            for (int frame = 0; frame < 2; frame++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++)
                image.setPixel(x, y + frame * side, glint(texture, 255, x, y, frame));
        return image;
    }

    static float phase(long now) {
        return (float)(.5 * (1 - Math.cos(Math.floorMod(now, PERIOD) * (2 * Math.PI / PERIOD))));
    }

    void upload(Pixels pixels, int sceneWidth, int sceneHeight) {
        if (pixels == null) {
            close();
            return;
        }
        x = pixels.x();
        y = pixels.y();
        width = pixels.width();
        height = pixels.height();
        this.sceneWidth = sceneWidth;
        this.sceneHeight = sceneHeight;
        first.upload(width, height, pixels.first());
        second.upload(width, height, pixels.second());
    }

    void draw(GuiGraphicsExtractor graphics, int left, int top, int mapWidth, int mapHeight, long now) {
        if (width == 0) return;
        int dx = x * mapWidth / sceneWidth, dy = y * mapHeight / sceneHeight;
        int w = (x + width) * mapWidth / sceneWidth - dx, h = (y + height) * mapHeight / sceneHeight - dy;
        int alpha = Math.round(255 * phase(now));
        first.drawTinted(graphics, left + dx, top + dy, w, h, ((255 - alpha) << 24) | 0xFFFFFF);
        second.drawTinted(graphics, left + dx, top + dy, w, h, (alpha << 24) | 0xFFFFFF);
    }

    @Override public void close() {
        width = 0;
        first.close();
        second.close();
    }
}
