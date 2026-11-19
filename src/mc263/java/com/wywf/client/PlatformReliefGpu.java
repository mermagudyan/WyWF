package com.wywf.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.wywf.verify.ReliefTile;
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
import java.util.concurrent.atomic.AtomicLong;

final class PlatformReliefGpu implements ReliefGpu {
    private static final String SCENE = "ReliefScene";
    private static final int INSTANCE_BYTES = 4 * Integer.BYTES;
    private static final int FACES_PER_UPLOAD = 16_384;
    private static final int UNIFORM_BYTES = 3 * 4 * Float.BYTES;
    private static final AtomicLong IDS = new AtomicLong();
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("wywf", "core/relief_opaque");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("wywf", "pipeline/relief_opaque"))
            .withVertexShader(SHADER).withFragmentShader(SHADER)
            .withVertexBinding(0, VertexFormat.builder(0).addAttribute("Corner", GpuFormat.RG32_FLOAT).build())
            .withVertexBinding(1, VertexFormat.builder(1).addAttribute("Face", GpuFormat.RGBA32_SINT).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform(SCENE, UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, true)).withCull(false).build();

    private final Identifier textureId = Identifier.fromNamespaceAndPath("wywf", "relief/gpu/" + IDS.incrementAndGet());
    private GpuBuffer instances, corners, uniform;
    private TextureTarget target;
    private ByteBuffer staging;
    private ReliefMesh uploadedMesh;
    private int uploaded;
    private boolean unsupported;

    static void register() {
        RenderPipelines.registerOptional(PIPELINE);
    }
    static boolean available() {
        return true;
    }
    static ReliefGpu create() {
        return new PlatformReliefGpu();
    }

    @Override
    public boolean render(ReliefMesh mesh, int width, int height, int fog, float centerY,
                          double yaw, double pitch, double zoom, double panX, double panY) {
        var compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
        if (compiled == null || mesh.faces().length == 0 || mesh.sunlight() != null || mesh.fogRim() != null) return false;
        if (uploadedMesh != mesh) begin(mesh);
        if (unsupported) return false;
        if (uploading()) {
            upload(mesh);
            if (uploading() || unsupported) return false;
        }
        if (target == null || target.width != width || target.height != height) resize(width, height);
        double scale = Math.min(width, height) * .82 * zoom / mesh.side();
        float depth = 2f * (mesh.side() + mesh.maximum() - mesh.minimum() + 2);
        staging.clear();
        staging.putFloat((float) Math.sin(yaw)).putFloat((float) Math.cos(yaw));
        staging.putFloat((float) Math.sin(pitch)).putFloat((float) Math.cos(pitch));
        staging.putFloat((float) (2 * scale / width)).putFloat((float) (2 * scale / height));
        staging.putFloat((float) (2 * panX)).putFloat((float) (2 * panY));
        staging.putFloat(mesh.side() * .5f).putFloat(centerY).putFloat(1 / depth).putFloat(0);
        staging.flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(uniform.slice(), staging);
        var clear = new Vector4f((fog >>> 16 & 255) / 255f, (fog >>> 8 & 255) / 255f, (fog & 255) / 255f, 1);
        try (var pass = encoder.createRenderPass(() -> "WyWF opaque relief", target.getColorTextureView(),
                Optional.of(clear), target.getDepthTextureView(), OptionalDouble.of(1))) {
            pass.setPipeline(compiled);
            pass.setUniform(SCENE, uniform);
            pass.setVertexBuffer(0, corners.slice());
            pass.setVertexBuffer(1, instances.slice());
            pass.draw(4, uploaded, 0, 0);
        }
        encoder.submit();
        return true;
    }

    private void begin(ReliefMesh mesh) {
        discardMesh();
        var device = RenderSystem.getDevice();
        uploadedMesh = mesh;
        instances = device.createBuffer(() -> "WyWF relief instances", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                (long) mesh.faces().length / 3 * INSTANCE_BYTES);
        if (staging == null) {
            staging = ByteBuffer.allocateDirect(FACES_PER_UPLOAD * INSTANCE_BYTES).order(ByteOrder.nativeOrder());
            staging.putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(1).putFloat(1).putFloat(1);
            staging.flip();
            corners = device.createBuffer(() -> "WyWF relief corners", GpuBuffer.USAGE_VERTEX, staging);
            uniform = device.createBuffer(() -> "WyWF relief scene", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
        }
    }

    private void upload(ReliefMesh mesh) {
        int[] faces = mesh.faces();
        int end = Math.min(uploaded + FACES_PER_UPLOAD, faces.length / 3);
        staging.clear();
        for (int i = uploaded; i < end; i++) {
            int encoded = faces[i * 3], color = faces[i * 3 + 2];
            if ((color & ReliefTile.FACE_TYPE) != 0xFF000000) {
                unsupported = true;
                instances.close();
                instances = null;
                return;
            }
            int direction = encoded % 6, cell = encoded / 6;
            staging.putInt(cell % mesh.side()).putInt(faces[i * 3 + 1]).putInt(cell / mesh.side());
            staging.putInt(ReliefRaster.directionalColor(color, direction, 0) & 0xFFFFFF | direction << 24);
        }
        staging.flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(instances.slice((long) uploaded * INSTANCE_BYTES, staging.remaining()), staging);
        encoder.submit();
        uploaded = end;
    }

    @Override
    public boolean uploading() {
        return uploadedMesh != null && !unsupported && uploaded < uploadedMesh.faces().length / 3;
    }

    @Override
    public void discardMesh() {
        if (instances != null) instances.close();
        instances = null;
        uploadedMesh = null;
        uploaded = 0;
        unsupported = false;
    }

    private void resize(int width, int height) {
        closeTarget();
        target = new TextureTarget("WyWF relief", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        Minecraft.getInstance().getTextureManager().register(textureId, new TargetTexture(target));
    }

    @Override
    public void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.blit(textureId, x, y, x + width, y + height, 0, 1, 0, 1);
    }

    private void closeTarget() {
        if (target == null) return;
        Minecraft.getInstance().getTextureManager().release(textureId);
        target.destroyBuffers();
        target = null;
    }

    @Override
    public void close() {
        closeTarget();
        discardMesh();
        if (corners != null) corners.close();
        if (uniform != null) uniform.close();
        corners = uniform = null;
        staging = null;
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
