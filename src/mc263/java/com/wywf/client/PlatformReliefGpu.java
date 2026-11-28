package com.wywf.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.wywf.core.DimensionSpace;
import com.wywf.core.MapBackground;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

final class PlatformReliefGpu implements ReliefGpu {
    private static final String SCENE = "ReliefScene", COLOR = "OpaqueColor", DEPTH = "OpaqueDepth";
    private static final String ICE = "IceField", EYE = "EyePattern";
    private static final int INSTANCE_BYTES = ReliefGpuGeometry.WORDS * Integer.BYTES;
    private static final int FACES_PER_UPLOAD = 98_304;
    private static final int UNIFORM_BYTES = 6 * 4 * Float.BYTES;
    private static final AtomicLong IDS = new AtomicLong();
    private static final RenderPipeline TERRAIN = pipeline("relief_opaque", false, false);
    private static final RenderPipeline OVERLAY = pipeline("relief_overlay", true, false);
    private static final RenderPipeline RIM = pipeline("relief_rim", false, true);
    private static final RenderPipeline OUTLINE_MASK = pipeline("relief_outline_mask", false, false);
    private static final RenderPipeline OUTLINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("wywf", "pipeline/relief_outline"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("wywf", "core/relief_outline"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform(SCENE, UniformType.UNIFORM_BUFFER)
                    .withUniform("SelectedMask", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(ColorTargetState.DEFAULT).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false).build();

    private final Identifier textureId = Identifier.fromNamespaceAndPath("wywf", "relief/gpu/" + IDS.incrementAndGet());
    private final Identifier outlineId = textureId.withSuffix("_outline");
    private final DimensionSpace dimension;
    private final Batch[] selected = new Batch[ReliefGpuGeometry.DIRECTIONS];
    private volatile ReliefRaster.Outline requestedOutline;
    private ReliefRaster.Outline renderedOutline;
    private CompletableFuture<ReliefGpuGeometry.Plan> outlinePreparation;
    private TextureTarget outlineMask, outlineImage;
    private GpuBuffer corners, uniform;
    private TextureTarget target, opaque;
    private boolean registered;
    private ByteBuffer staging;
    private volatile ReliefMesh requested;
    private boolean slice;
    private CompletableFuture<ReliefGpuGeometry.Plan> preparation;
    private final Batch[] terrain = new Batch[ReliefGpuGeometry.DIRECTIONS];
    private final Batch overlays = new Batch(), rim = new Batch();
    private boolean[] visible, eyeChunks;
    private boolean eyeVisible;
    private long eyeDrawAt;
    private SampledTexture ice, emptyIce, eye, emptyEye;

    private PlatformReliefGpu(DimensionSpace dimension) {
        this.dimension = dimension;
        for (int direction = 0; direction < terrain.length; direction++) {
            terrain[direction] = new Batch();
            selected[direction] = new Batch();
        }
    }

    private static RenderPipeline pipeline(String name, boolean overlays, boolean blend) {
        var bindings = BindGroupLayout.builder().withUniform(SCENE, UniformType.UNIFORM_BUFFER);
        if (overlays) bindings.withUniform(COLOR, UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform(DEPTH, UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform(ICE, UniformType.COMBINED_IMAGE_SAMPLER);
        else if (!blend && !name.equals("relief_outline_mask")) bindings.withUniform(EYE, UniformType.COMBINED_IMAGE_SAMPLER);
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("wywf", "pipeline/" + name))
                .withVertexShader(Identifier.fromNamespaceAndPath("wywf", "core/relief_opaque"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("wywf", "core/" + name))
                .withVertexBinding(0, VertexFormat.builder(0).addAttribute("Corner", GpuFormat.RG32_FLOAT).build())
                .withVertexBinding(1, VertexFormat.builder(1).addAttribute("Face", GpuFormat.RGBA32_SINT)
                        .addAttribute("Box", GpuFormat.RG32_SINT).build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(blend ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT)
                .withDepthStencilState(name.equals("relief_outline_mask")
                        ? new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)
                        : new DepthStencilState(CompareOp.LESS_THAN, !blend)).withCull(false).build();
    }

    static void register() {
        RenderPipelines.registerOptional(TERRAIN);
        RenderPipelines.registerOptional(OVERLAY);
        RenderPipelines.registerOptional(RIM);
        RenderPipelines.registerOptional(OUTLINE_MASK);
        RenderPipelines.registerOptional(OUTLINE);
    }

    static boolean available() { return true; }

    static ReliefGpu create(DimensionSpace dimension) { return new PlatformReliefGpu(dimension); }

    @Override
    public boolean render(ReliefMesh mesh, int width, int height, int fog, float centerY,
                          double yaw, double pitch, double zoom, double panX, double panY, boolean slice,
                          ReliefRaster.Outline outline, int originX, int originZ) {
        if (mesh.faces().length == 0) return false;
        var solidPipeline = RenderSystem.getCompiledPipelineNullable(TERRAIN);
        var overlayPipeline = RenderSystem.getCompiledPipelineNullable(OVERLAY);
        var rimPipeline = RenderSystem.getCompiledPipelineNullable(RIM);
        if (solidPipeline == null || overlayPipeline == null || rimPipeline == null) return false;
        if (requested != mesh || this.slice != slice) {
            discardMesh();
            this.slice = slice;
            requested = mesh;
            preparation = MapBackground.cpuResult(() -> ReliefGpuGeometry.prepare(mesh, dimension, slice,
                    () -> requested != mesh || Thread.currentThread().isInterrupted()));
        }
        if (preparation != null) {
            if (!preparation.isDone()) return false;
            var plan = preparation.join();
            preparation = null;
            initialize(plan);
        }
        if (outline != null && (RenderSystem.getCompiledPipelineNullable(OUTLINE_MASK) == null
                || RenderSystem.getCompiledPipelineNullable(OUTLINE) == null)) return false;
        if (outline != requestedOutline) {
            clearSelection();
            requestedOutline = outline;
            if (outline != null) outlinePreparation = MapBackground.cpuResult(() ->
                    ReliefGpuGeometry.prepare(mesh, dimension, slice, outline, originX, originZ, () ->
                            requested != mesh || requestedOutline != outline || Thread.currentThread().isInterrupted()));
        }
        if (outlinePreparation != null && outlinePreparation.isDone()) {
            var parts = outlinePreparation.join().terrain();
            outlinePreparation = null;
            for (int i = 0; i < parts.length; i++) selected[i].initialize(parts[i]);
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (uploading()) {
            int budget = FACES_PER_UPLOAD;
            for (var batch : terrain) budget -= batch.upload(encoder, staging, budget);
            budget -= overlays.upload(encoder, staging, budget);
            rim.upload(encoder, staging, budget);
            if (uploading()) {
                encoder.submit();
                return false;
            }
        }
        boolean translucent = overlays.count != 0;
        if (target == null || target.width != width || target.height != height || translucent != (opaque != null))
            resize(width, height, translucent);

        int selectedBudget = FACES_PER_UPLOAD;
        for (var batch : selected) selectedBudget -= batch.upload(encoder, staging, selectedBudget);

        double scale = Math.min(width, height) * .82 * zoom / mesh.side();
        float heightScale = slice ? .46f : 1;
        float depthRange = 2f * (mesh.side() + Math.max(Math.abs(centerY - mesh.minimum()),
                Math.abs(mesh.maximum() + 1 - centerY)) + 2);
        float sinYaw = (float)Math.sin(yaw), cosYaw = (float)Math.cos(yaw);
        float sinPitch = (float)Math.sin(pitch), cosPitch = (float)Math.cos(pitch);
        float fogWidth = dimension == DimensionSpace.END ? 0 : slice ? 12 - 8 * cosPitch * cosPitch : 12;
        var clear = new Vector4f((fog >>> 16 & 255) / 255f, (fog >>> 8 & 255) / 255f, (fog & 255) / 255f, 1);
        long now = System.nanoTime();
        staging.clear();
        staging.putFloat(sinYaw).putFloat(cosYaw).putFloat(sinPitch).putFloat(cosPitch);
        staging.putFloat((float)(2 * scale / width)).putFloat((float)(2 * scale / height));
        staging.putFloat((float)(2 * panX)).putFloat((float)(2 * panY));
        staging.putFloat(mesh.side() * .5f).putFloat(centerY).putFloat(1 / depthRange).putFloat((float)scale);
        staging.putFloat(clear.x).putFloat(clear.y).putFloat(clear.z).putFloat(ice == null ? 1 : requested.iceWaterFog().stride());
        staging.putFloat(heightScale).putFloat(fogWidth).putFloat(dimension == DimensionSpace.OVERWORLD && mesh.sunlight() != null ? 1 : 0)
                .putFloat(depthRange);
        staging.putFloat(height).putFloat(PortalEye.phase(now)).putFloat(eyeChunks == null ? 0 : 1).putFloat(0);
        staging.flip();
        encoder.writeToBuffer(uniform.slice(), staging);
        selectChunks(mesh, width, height, scale, centerY, sinYaw, cosYaw, sinPitch, cosPitch * heightScale, panX, panY);

        eyeVisible = false;
        if (eyeChunks != null) for (int i = 0; i < eyeChunks.length; i++)
            if (eyeChunks[i] && (visible == null || visible[i])) { eyeVisible = true; break; }
        eyeDrawAt = now;
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int directions = ReliefGpuGeometry.facing(sinYaw, cosYaw, cosPitch);
        var base = translucent ? opaque : target;
        try (var pass = encoder.createRenderPass(() -> "WyWF relief terrain", base.getColorTextureView(),
                Optional.of(clear), base.getDepthTextureView(), OptionalDouble.of(1))) {
            setup(pass, solidPipeline);
            pass.setUniform(EYE, (eyeChunks == null ? emptyEye : eye).view, sampler);
            drawFacing(pass, terrain, directions);
            if (!slice) rim.draw(pass, null);
        }
        if (translucent) {
            encoder.copyTextureToTexture(opaque.getColorTexture(), target.getColorTexture(), 0, 0, 0, 0, 0, width, height);
            encoder.copyTextureToTexture(opaque.getDepthTexture(), target.getDepthTexture(), 0, 0, 0, 0, 0, width, height);
            try (var pass = encoder.createRenderPass(() -> "WyWF relief water and plants", target.getColorTextureView(),
                    Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                setup(pass, overlayPipeline);
                pass.setUniform(ICE, (ice == null ? emptyIce : ice).view, sampler);
                pass.setUniform(COLOR, opaque.getColorTextureView(), sampler);
                pass.setUniform(DEPTH, opaque.getDepthTextureView(), sampler);
                overlays.draw(pass, visible);
            }
        }
        if (slice && rim.count != 0) {
            try (var pass = encoder.createRenderPass(() -> "WyWF Nether slice fog", target.getColorTextureView(),
                    Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                setup(pass, rimPipeline);
                rim.draw(pass, null);
            }
        }
        if (outline != null && outlinePreparation == null && !uploadingSelection())
            renderOutline(encoder, directions, outline);
        encoder.submit();
        if (!registered) {
            Minecraft.getInstance().getTextureManager().register(textureId, new TargetTexture(target));
            registered = true;
        }
        return true;
    }

    private void drawFacing(RenderPass pass, Batch[] batches, int facing) {
        while (facing != 0) {
            int direction = Integer.numberOfTrailingZeros(facing);
            batches[direction].draw(pass, visible);
            facing &= facing - 1;
        }
    }

    private void renderOutline(CommandEncoder encoder, int directions, ReliefRaster.Outline outline) {
        if (outlineMask == null) {
            outlineMask = new TextureTarget("WyWF selected faces", target.width, target.height, GpuFormat.RGBA8_UNORM, null);
            outlineImage = new TextureTarget("WyWF structure outline", target.width, target.height, GpuFormat.RGBA8_UNORM, null);
            Minecraft.getInstance().getTextureManager().register(outlineId, new TargetTexture(outlineImage));
        }
        Optional<org.joml.Vector4fc> transparent = Optional.of(new Vector4f());
        try (var pass = encoder.createRenderPass(() -> "WyWF selected faces", outlineMask.getColorTextureView(),
                transparent, target.getDepthTextureView(), OptionalDouble.empty())) {
            setup(pass, RenderSystem.getCompiledPipelineNullable(OUTLINE_MASK));
            drawFacing(pass, selected, directions);
        }
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = encoder.createRenderPass(() -> "WyWF structure outline", outlineImage.getColorTextureView(), transparent)) {
            pass.setPipeline(RenderSystem.getCompiledPipelineNullable(OUTLINE));
            pass.setUniform(SCENE, uniform);
            pass.setUniform("SelectedMask", outlineMask.getColorTextureView(), sampler);
            pass.setUniform("SceneDepth", target.getDepthTextureView(), sampler);
            pass.draw(3, 1, 0, 0);
        }
        renderedOutline = outline;
    }

    private void setup(RenderPass pass, com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline pipeline) {
        pass.setPipeline(pipeline);
        pass.setUniform(SCENE, uniform);
        pass.setVertexBuffer(0, corners.slice());
    }

    private void selectChunks(ReliefMesh mesh, int width, int height, double scale, float centerY,
                              float sinYaw, float cosYaw, float sinPitch, float vertical, double panX, double panY) {
        int[] heights = mesh.chunkHeights();
        if (heights == null) {
            visible = null;
            return;
        }
        int count = heights.length / 2, chunkSide = Math.ceilDiv(mesh.side(), 16);
        if (visible == null || visible.length != count) visible = new boolean[count];
        double left = -width * (.5 + panX) / scale, right = left + width / scale;
        double top = -height * (.5 + panY) / scale, bottom = top + height / scale;
        double xHalf = 9 * (Math.abs(cosYaw) + Math.abs(sinYaw));
        double yHalf = xHalf * sinPitch;
        for (int chunk = 0; chunk < count; chunk++) {
            int min = heights[chunk * 2], max = heights[chunk * 2 + 1];
            if (min > max) {
                visible[chunk] = false;
                continue;
            }
            double x = chunk % chunkSide * 16 + 8 - mesh.side() * .5;
            double z = chunk / chunkSide * 16 + 8 - mesh.side() * .5;
            double u = cosYaw * x - sinYaw * z;
            double v = (sinYaw * x + cosYaw * z) * sinPitch;
            visible[chunk] = u + xHalf >= left && u - xHalf <= right
                    && v + yHalf - (min - centerY) * vertical >= top
                    && v - yHalf - (max + 1 - centerY) * vertical <= bottom;
        }
    }

    private void initialize(ReliefGpuGeometry.Plan plan) {
        var device = RenderSystem.getDevice();
        if (staging == null) {
            staging = ByteBuffer.allocateDirect(FACES_PER_UPLOAD * INSTANCE_BYTES).order(ByteOrder.nativeOrder());
            staging.putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(1).putFloat(1).putFloat(1);
            staging.flip();
            corners = device.createBuffer(() -> "WyWF relief corners", GpuBuffer.USAGE_VERTEX, staging);
            uniform = device.createBuffer(() -> "WyWF relief scene", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
        }
        eyeChunks = plan.eyeChunks();
        var encoder = device.createCommandEncoder();
        if (emptyEye == null) emptyEye = uploadInts(encoder, "WyWF empty portal texture", GpuFormat.RGBA8_UNORM, 1, new int[]{0});
        if (eyeChunks != null && eye == null) {
            try (var image = PortalEye.gpuAtlas()) {
                eye = new SampledTexture("WyWF portal texture", GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight());
                encoder.writeToTexture(eye.texture, image);
            }
        }
        if (plan.overlays().count() > 0) {
            var field = requested.iceWaterFog();
            if (field != null) ice = uploadInts(encoder, "WyWF under ice", GpuFormat.R32_SINT, field.stride(), field.corners());
            else if (emptyIce == null) emptyIce = uploadInts(encoder, "WyWF empty ice map", GpuFormat.R32_SINT, 1, new int[]{0});
        }
        encoder.submit();
        for (int direction = 0; direction < terrain.length; direction++) terrain[direction].initialize(plan.terrain()[direction]);
        overlays.initialize(plan.overlays());
        rim.initialize(plan.rim());
    }

    private static SampledTexture uploadInts(CommandEncoder encoder, String name, GpuFormat format, int size, int[] values) {
        var result = new SampledTexture(name, format, size, size);
        var data = org.lwjgl.system.MemoryUtil.memAlloc(values.length * Integer.BYTES);
        try {
            data.asIntBuffer().put(values);
            encoder.writeToTexture(result.texture, data, 0, 0, 0, 0, size, size);
            return result;
        } catch (RuntimeException error) {
            result.close();
            throw error;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(data);
        }
    }

    @Override public boolean hasOutline(ReliefRaster.Outline outline) {
        return outline != null && outline == renderedOutline;
    }

    @Override public boolean animating() {
        return (outlinePreparation != null && outlinePreparation.isDone()) || uploadingSelection()
                || eyeVisible && System.nanoTime() - eyeDrawAt > 66_000_000;
    }

    @Override public boolean uploading() {
        if (preparation != null || overlays.data != null || rim.data != null) return true;
        for (var batch : terrain) if (batch.data != null) return true;
        return false;
    }

    private boolean uploadingSelection() {
        for (var batch : selected) if (batch.data != null) return true;
        return false;
    }

    private void clearSelection() {
        requestedOutline = renderedOutline = null;
        if (outlinePreparation != null) outlinePreparation.cancel(false);
        outlinePreparation = null;
        for (var batch : selected) batch.close();
    }

    @Override public void discardMesh() {
        requested = null;
        clearSelection();
        if (preparation != null) preparation.cancel(false);
        preparation = null;
        for (var batch : terrain) batch.close();
        overlays.close();
        rim.close();
        if (ice != null) ice.close();
        ice = null;
        eyeChunks = null;
        eyeVisible = false;
    }

    private void resize(int width, int height, boolean translucent) {
        closeTarget();
        target = new TextureTarget("WyWF relief", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        if (translucent)
            opaque = new TextureTarget("WyWF relief opaque layer", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
    }

    @Override public void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                               ReliefRaster.Outline outline, int outlineAlpha) {
        if (registered) graphics.blit(textureId, x, y, x + width, y + height, 0, 1, 0, 1);
        if (outlineAlpha > 0 && outline != null && outline == renderedOutline && outlineImage != null)
            graphics.blit(RenderPipelines.GUI_TEXTURED, outlineId, x, y, 0, 0, width, height,
                    outlineImage.width, outlineImage.height, outlineImage.width, outlineImage.height, outlineAlpha << 24 | 0xFFFFFF);
    }

    private void closeTarget() {
        renderedOutline = null;
        if (outlineImage != null) {
            Minecraft.getInstance().getTextureManager().release(outlineId);
            outlineImage.destroyBuffers();
            outlineImage = null;
        }
        if (outlineMask != null) {
            outlineMask.destroyBuffers();
            outlineMask = null;
        }
        if (registered) Minecraft.getInstance().getTextureManager().release(textureId);
        registered = false;
        if (target != null) target.destroyBuffers();
        if (opaque != null) opaque.destroyBuffers();
        target = opaque = null;
    }

    @Override public void close() {
        closeTarget();
        discardMesh();
        if (corners != null) corners.close();
        if (uniform != null) uniform.close();
        corners = uniform = null;
        if (emptyIce != null) emptyIce.close();
        if (emptyEye != null) emptyEye.close();
        if (eye != null) eye.close();
        emptyIce = emptyEye = eye = null;
        staging = null;
    }

    private static final class Batch {
        private int[] data, offsets;
        private int count, uploaded;
        private GpuBuffer instances;

        void initialize(ReliefGpuGeometry.Part part) {
            count = part.count();
            if (count == 0) return;
            data = part.data();
            offsets = part.offsets();
            instances = RenderSystem.getDevice().createBuffer(() -> "WyWF relief instances",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, (long)count * INSTANCE_BYTES);
        }

        int upload(CommandEncoder encoder, ByteBuffer staging, int budget) {
            if (data == null || budget <= 0) return 0;
            int written = Math.min(budget, count - uploaded);
            staging.clear();
            staging.asIntBuffer().put(data, uploaded * ReliefGpuGeometry.WORDS, written * ReliefGpuGeometry.WORDS);
            staging.limit(written * INSTANCE_BYTES);
            encoder.writeToBuffer(instances.slice((long)uploaded * INSTANCE_BYTES, staging.remaining()), staging);
            uploaded += written;
            if (uploaded == count) data = null;
            return written;
        }

        void draw(RenderPass pass, boolean[] visible) {
            if (count == 0) return;
            pass.setVertexBuffer(1, instances.slice());
            if (offsets == null || visible == null) {
                pass.draw(4, count, 0, 0);
                return;
            }
            int from = 0;
            for (int chunk = 0; chunk < visible.length; chunk++) {
                if (visible[chunk] || offsets[chunk] == offsets[chunk + 1]) continue;
                if (offsets[chunk] > from) pass.draw(4, offsets[chunk] - from, 0, from);
                from = offsets[chunk + 1];
            }
            if (count > from) pass.draw(4, count - from, 0, from);
        }

        void close() {
            if (instances != null) instances.close();
            instances = null;
            data = offsets = null;
            count = uploaded = 0;
        }
    }

    private static final class SampledTexture implements AutoCloseable {
        final GpuTexture texture;
        final GpuTextureView view;

        SampledTexture(String name, GpuFormat format, int width, int height) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture(name, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    format, width, height, 1, 1);
            view = device.createTextureView(texture);
        }

        @Override public void close() {
            view.close();
            texture.close();
        }
    }

    private static final class TargetTexture extends AbstractTexture {
        TargetTexture(TextureTarget target) {
            texture = target.getColorTexture();
            textureView = target.getColorTextureView();
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }
        @Override public void close() {}
    }
}
