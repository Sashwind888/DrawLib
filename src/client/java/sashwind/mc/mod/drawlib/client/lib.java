package sashwind.mc.mod.drawlib.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.logging.Logger;

public class lib {
    private static final Logger LOGGER = Logger.getLogger("FFCraft-lib");

    boolean closed = false;
    int POSx, POSy, POSz;
    TopologyCompat VFM;

    private static VertexFormat.Mode toMode(TopologyCompat t) {
        return switch (t) {
            case TRIANGLES -> VertexFormat.Mode.TRIANGLES;
            case LINES -> VertexFormat.Mode.LINES;
            case DEBUG_LINE_STRIP -> VertexFormat.Mode.DEBUG_LINE_STRIP;
            case QUADS -> VertexFormat.Mode.QUADS;
        };
    }
    public ArrayList<Vertex> vertices = new ArrayList<>();
    public AbstractTexture WAYPOINT_TEXTURE;
    private Identifier waypointTextureId;
    public RenderPipeline FILLED_THROUGH_WALLS;
    public WaypointRenderState waypointState;

    private static final java.util.concurrent.atomic.AtomicInteger PLACEHOLDER_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final String MOD_ID = "ffcraft";

    // Overlay 专用 1x1 白色纹理（避免绑定视频纹理导致首像素颜色污染画面）
    private static AbstractTexture OVERLAY_WHITE;

    private static void ensureOverlayWhite() {
        if (OVERLAY_WHITE != null) return;
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "_overlay_white");
        DynamicTexture dt = new DynamicTexture(MOD_ID + ".overlay", 1, 1, true);
        tm.register(id, dt);
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        img.setPixel(0, 0, 0xFFFFFFFF); // 白色不透明
        dt.setPixels(img);
        dt.upload();
        img.close();
        OVERLAY_WHITE = dt;
    }

    public lib(int x, int y, int z, TopologyCompat topo) {
        POSx = x; POSy = y; POSz = z;
        VFM = topo;

        // 使用原版静态管线以保持光影兼容
        if (VFM.isLine()) {
            FILLED_THROUGH_WALLS = RenderPipelines.LINES;
        } else {
            FILLED_THROUGH_WALLS = RenderPipelines.ENTITY_CUTOUT;
        }
    }

    public void extractWaypoint(WorldExtractionContext context) {
        if (closed) return;
        waypointState = new WaypointRenderState(POSx, POSy, POSz, 1f, 1f, 1f, 1f);
    }

    public final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    public final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    public final Vector3f MODEL_OFFSET = new Vector3f();
    public final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    public BufferBuilder buffer;
    public MappableRingBuffer vertexBuffer;

    public void renderAndDrawWaypoint(WorldRenderContext context) {
        if (closed) return;
        // 纹理上传必须在 render pass 之外完成
        ensureOverlayWhite();
        if (WAYPOINT_TEXTURE == null) {
            waypointTextureId = Identifier.fromNamespaceAndPath(MOD_ID, "_placeholder" + PLACEHOLDER_COUNTER.getAndIncrement());
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            DynamicTexture dt = new DynamicTexture(MOD_ID + ".placeholder", 1, 1, true);
            textureManager.register(waypointTextureId, dt);
            NativeImage img = createPlaceholderImage(1,1);
            dt.setPixels(img);
            dt.upload();
            img.close();
            WAYPOINT_TEXTURE = dt;
        }
        renderWaypoint(context);
        drawFilledThroughWalls(Minecraft.getInstance(), FILLED_THROUGH_WALLS);
    }

    public void renderWaypoint(WorldRenderContext context) {
        if (closed) return;
        try {
            ALLOCATOR.clear();
        } catch (IllegalStateException e) {
            Logger.getLogger("WorldDraw -> lib").warning("ALLOCATOR已被 clear！");
            buffer = null;
            return;
        }
        if (vertices.isEmpty() || WAYPOINT_TEXTURE == null) {
            buffer = null;
            return;
        }
        PoseStack matrices = context.matrices();
        Vec3 camera = context.worldState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        buffer = new BufferBuilder(ALLOCATOR, toMode(VFM), FILLED_THROUGH_WALLS.getVertexFormat());
        renderFilledBox(matrices.last().pose(), buffer, waypointState.r(), waypointState.g(), waypointState.b(), waypointState.a());
        matrices.popPose();
    }

    public void renderFilledBox(Matrix4f positionMatrix, BufferBuilder buffer, float red, float green, float blue, float alpha) {
        if (closed) return;
        for (Vertex v : vertices) {
            buffer.addVertex(positionMatrix, v.x, v.y, v.z)
                    .setColor(1f, 1f, 1f, v.a)
                    .setUv(v.u, v.v)
                    .setUv1(0, 0)
                    .setUv2(255, 255)   // emissive：始终最大亮度
                    .setNormal(v.nx, v.ny, v.nz);
        }
    }

    public void drawFilledThroughWalls(Minecraft client, RenderPipeline pipeline) {
        if (closed || vertices.isEmpty() || WAYPOINT_TEXTURE == null || buffer == null) return;
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();
        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);
        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);
        if (vertexBuffer != null) vertexBuffer.rotate();
    }

    private GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();
        int bufferCapacity = vertexBufferSize + (vertexBufferSize >> 2);
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " example render pipeline",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bufferCapacity);
        }
        GpuBuffer vertices = vertexBuffer.currentBuffer();
        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (var mappedView = commandEncoder.mapBuffer(
                vertices.slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }
        return vertices;
    }

    private void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer, MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        if (closed) return;
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        if (drawParameters.mode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = RenderSystem.getDevice().createBuffer(
                    () -> "immediate_index", GpuBuffer.USAGE_INDEX, builtBuffer.indexBuffer());
            indexType = drawParameters.indexType();
        } else if (VFM == TopologyCompat.LINES) {
            int vc = drawParameters.vertexCount();
            ByteBuffer idxBuffer = MemoryUtil.memAlloc(vc * 2);
            for (int i = 0; i < vc; i++) idxBuffer.putShort((short) i);
            idxBuffer.flip();
            indices = RenderSystem.getDevice().createBuffer(
                    () -> "immediate_line_index", GpuBuffer.USAGE_INDEX, idxBuffer);
            indexType = VertexFormat.IndexType.SHORT;
            MemoryUtil.memFree(idxBuffer);
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                    RenderSystem.getSequentialBuffer(drawParameters.mode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> MOD_ID + " example render pipeline rendering", client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(), client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            GpuSampler pointSampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, false);
            renderPass.bindTexture("Sampler0", WAYPOINT_TEXTURE.getTextureView(), pointSampler);
            // lightmap: 用 Minecraft 的真实光照贴图
            renderPass.bindTexture("Sampler2", client.gameRenderer.lightTexture().getTextureView(), pointSampler);
            renderPass.bindTexture("Sampler1", OVERLAY_WHITE.getTextureView(), pointSampler);
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(drawParameters.indexCount(), 1, 0, 0);
        }
        builtBuffer.close();
    }

    public static void setScreenCompat(Minecraft client, net.minecraft.client.gui.screens.Screen screen) {
        client.setScreen(screen);
    }

    public record WaypointRenderState(int x, int y, int z, float r, float g, float b, float a) {}

    private NativeImage createPlaceholderImage(int width, int height) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        long ptr = img.getPointer();
        ByteBuffer buf = MemoryUtil.memByteBuffer(ptr, width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buf.put((byte) 0xFF); buf.put((byte) 0xFF);
                buf.put((byte) 0xFF); buf.put((byte) 0xFF);
            }
        }
        return img;
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (vertexBuffer != null) { vertexBuffer.close(); vertexBuffer = null; }
        try { if (ALLOCATOR != null) ALLOCATOR.close(); } catch (Exception e) {}
        if (vertices != null) vertices.clear();
        if (waypointTextureId != null && WAYPOINT_TEXTURE != null) {
            try { Minecraft.getInstance().getTextureManager().release(waypointTextureId); } catch (Exception e) {}
            WAYPOINT_TEXTURE.close();
        }
        WAYPOINT_TEXTURE = null;
        waypointTextureId = null;
        buffer = null;
    }

    public static void cleanupStatics() {
        if (OVERLAY_WHITE != null) {
            try { Minecraft.getInstance().getTextureManager().release(Identifier.fromNamespaceAndPath(MOD_ID, "_overlay_white")); } catch (Exception e) {}
            OVERLAY_WHITE.close();
            OVERLAY_WHITE = null;
        }
    }

}
