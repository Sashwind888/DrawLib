package sashwind.mc.mod.drawlib.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.logging.Logger;

public class lib {
    private static final Logger LOGGER = Logger.getLogger("FFCraft-lib");

    boolean closed = false;
    int POSx, POSy, POSz;
    TopologyCompat VFM;

    public ArrayList<Vertex> vertices = new ArrayList<>();
    public AbstractTexture WAYPOINT_TEXTURE;
    private ResourceLocation waypointTextureId;
    public RenderType renderType;
    public WaypointRenderState waypointState;

    private static final java.util.concurrent.atomic.AtomicInteger PLACEHOLDER_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final String MOD_ID = "ffcraft";

    public lib(int x, int y, int z, TopologyCompat topo) {
        POSx = x; POSy = y; POSz = z;
        VFM = topo;

        // 使用原版内置 RenderType，确保光影（Iris/Oculus）完全兼容，禁止构造自定义 RenderPipeline
        if (VFM == TopologyCompat.LINES) {
            renderType = RenderType.lines();
        } else if (VFM == TopologyCompat.DEBUG_LINE_STRIP) {
            renderType = RenderType.lineStrip();
        } else {
            renderType = RenderType.debugQuads();
        }
    }

    public void extractWaypoint() {
        if (closed) return;
        waypointState = new WaypointRenderState(POSx, POSy, POSz, 1f, 1f, 1f, 1f);
    }

    public final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    public BufferBuilder buffer;
    // 保持字段兼容，避免旧引用报 NoSuchFieldError
    public Object vertexBuffer = null;

    public void renderAndDrawWaypoint(WorldRenderContext context) {
        if (closed) return;
        extractWaypoint();
        if (WAYPOINT_TEXTURE == null) {
            waypointTextureId = ResourceLocation.fromNamespaceAndPath(MOD_ID, "_placeholder" + PLACEHOLDER_COUNTER.getAndIncrement());
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            DynamicTexture dt = new DynamicTexture(1, 1, true);
            textureManager.register(waypointTextureId, dt);
            NativeImage img = createPlaceholderImage(1, 1);
            dt.setPixels(img);
            dt.upload();
            img.close();
            WAYPOINT_TEXTURE = dt;
        }

        // 如果包含视频纹理且为 QUADS，使用基于 CoreShaders 的带深度测试的纹理 RenderType
        if (!VFM.isLine() && waypointTextureId != null) {
            renderType = RenderType.guiTextured(waypointTextureId);
        }

        renderWaypoint(context);
        drawWaypoint(Minecraft.getInstance(), renderType);
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
        PoseStack matrices = context.matrixStack();
        Vec3 camera = context.camera().getPosition();
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        VertexFormat.Mode mode = renderType.mode();
        VertexFormat format = renderType.format();
        buffer = new BufferBuilder(ALLOCATOR, mode, format);

        renderFilledBox(matrices.last().pose(), buffer, waypointState.r(), waypointState.g(), waypointState.b(), waypointState.a());
        matrices.popPose();
    }

    public void renderFilledBox(Matrix4f positionMatrix, BufferBuilder buffer, float red, float green, float blue, float alpha) {
        if (closed) return;
        VertexFormat format = renderType.format();
        boolean hasUv = format == DefaultVertexFormat.POSITION_TEX_COLOR || format == DefaultVertexFormat.POSITION_TEX;
        boolean hasNormal = format == DefaultVertexFormat.POSITION_COLOR_NORMAL;

        for (Vertex v : vertices) {
            VertexConsumer consumer = buffer.addVertex(positionMatrix, v.x, v.y, v.z)
                    .setColor(v.r, v.g, v.b, v.a);
            if (hasUv) {
                consumer.setUv(v.u, v.v);
            }
            if (hasNormal) {
                consumer.setNormal(v.nx, v.ny, v.nz);
            }
        }
    }

    public void drawWaypoint(Minecraft client, RenderType type) {
        if (closed || vertices.isEmpty() || WAYPOINT_TEXTURE == null || buffer == null) return;
        MeshData builtBuffer = buffer.build();
        if (builtBuffer == null) return;

        if (type.sortOnUpload()) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
        }

        type.draw(builtBuffer);
        builtBuffer.close();
    }

    public static void setScreenCompat(Minecraft client, net.minecraft.client.gui.screens.Screen screen) {
        client.setScreen(screen);
    }

    public record WaypointRenderState(int x, int y, int z, float r, float b, float g, float a) {}

    private NativeImage createPlaceholderImage(int width, int height) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setPixel(x, y, 0xFFFFFFFF);
            }
        }
        return img;
    }

    public void close() {
        if (closed) return;
        closed = true;
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
    }
}
