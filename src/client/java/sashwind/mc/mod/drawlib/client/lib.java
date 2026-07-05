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
import com.mojang.datafixers.TypeRewriteRule;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import sashwind.mc.mod.drawlib.Drawlib;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.logging.Logger;


import static net.minecraft.resources.Identifier.fromNamespaceAndPath;

public class lib {
    boolean closed = false;

    int POSx,POSy,POSz;
    VertexFormat.Mode VFM;

    public ArrayList<Vertex> vertices = new ArrayList<>();
    public AbstractTexture WAYPOINT_TEXTURE;

    private static final java.util.concurrent.atomic.AtomicInteger PLACEHOLDER_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);


    public final RenderPipeline FILLED_THROUGH_WALLS;

    public WaypointRenderState waypointState;

    public lib(int x, int y, int z, VertexFormat.Mode VertexFormatMode) {
        POSx = x;
        POSy = y;
        POSz = z;
        VFM = VertexFormatMode;

        FILLED_THROUGH_WALLS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
                .withLocation(fromNamespaceAndPath(Drawlib.MOD_ID, "pipeline/debug_filled_box_through_walls"))
                //.withDepthStencilState(Optional.empty())
                .withVertexFormat(DefaultVertexFormat.ENTITY, VFM)  // ✅ 关键修改
                .withShaderDefine("NO_OVERLAY")//bdbdbd
                .withShaderDefine("NO_FOG")
                .withSampler("Sampler0")
                .withSampler("Sampler1")
                .withSampler("Sampler2")

                .build()
        );
    }

    public void extractWaypoint(LevelExtractionContext context) {
        if (closed) return;
        /*TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        Identifier textureId = Identifier.fromNamespaceAndPath(Drawlib.MOD_ID, "textures/waypoint.png");
        //Identifier textureId = Identifier.fromNamespaceAndPath("minecraft", "textures/block/stone.png");
        WAYPOINT_TEXTURE = textureManager.getTexture(textureId);*/


        // Access data from the world or anything here in the extraction phase.
        // You can only access the (immutable and thread safe) render state in the drawing phase.
        waypointState = new WaypointRenderState(POSx, POSy, POSz, 1f, 1f, 1f, 1f);//坐标

    }


    public final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    public final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    public final Vector3f MODEL_OFFSET = new Vector3f();
    public final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    public BufferBuilder buffer;
    public MappableRingBuffer vertexBuffer;

    public void renderAndDrawWaypoint(LevelRenderContext context) {
        if (closed) return;

        if (WAYPOINT_TEXTURE == null) {
            Identifier VIDEO_TEXTURE_ID = Identifier.fromNamespaceAndPath(
                    Drawlib.MOD_ID,
                    "_placeholder" + PLACEHOLDER_COUNTER.getAndIncrement()  // → drawlib:stream_0, drawlib:stream_1, ...
            );;
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            DynamicTexture dynamicTexture = new DynamicTexture(Drawlib.MOD_ID + ".placeholder", 1, 1, true);
            textureManager.register(VIDEO_TEXTURE_ID, dynamicTexture);
            NativeImage img = createPlaceholderImage(1,1);
            dynamicTexture.setPixels(img);
            dynamicTexture.upload();
            img.close();
            WAYPOINT_TEXTURE = dynamicTexture;

        }

        renderWaypoint(context);
        drawFilledThroughWalls(Minecraft.getInstance(), FILLED_THROUGH_WALLS);
    }

    public void renderWaypoint(LevelRenderContext context) {
        if (closed) return;
        // 🔧 修复闪烁：顶点列表为空或纹理未设置时跳过渲染
        try {
            ALLOCATOR.clear();
        } catch (IllegalStateException e) {
            Logger.getLogger("WorldDraw -> lib").warning("ALLOCATOR已被 clear！");
            buffer = null;  // 🔧 防止 drawFilledThroughWalls 使用已消费的旧 buffer
            return;
        }
        if (vertices.isEmpty() || WAYPOINT_TEXTURE == null) {
            buffer = null;  // 🔧 无顶点/纹理时不渲染，清空 buffer 引用
            return;
        }

        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        // 🔧 修复闪烁：始终新建 BufferBuilder，避免 ALLOCATOR 跨帧累积脏数据
        // 旧的 buffer 引用在 drawFilledThroughWalls 中已通过 buildOrThrow 消费
        buffer = new BufferBuilder(ALLOCATOR, FILLED_THROUGH_WALLS.getVertexFormatMode(), FILLED_THROUGH_WALLS.getVertexFormat());

        renderFilledBox(matrices.last().pose(), buffer, waypointState.r(), waypointState.g(), waypointState.b(), waypointState.a());

        matrices.popPose();
    }

    public void renderFilledBox(Matrix4f positionMatrix, BufferBuilder buffer, float red, float green, float blue, float alpha) {
        /*vertices.add(new Vertex(0, 100, 0, 1,1,1,1, 0,0, 0x000F000F, 0,1,0));
        vertices.add(new Vertex(0, 100, 1, 1,1,1,1, 0,1, 0x000F000F, 0,1,0));
        vertices.add(new Vertex(1, 100, 1, 1,1,1,1, 1,1, 0x000F000F, 0,1,0));
        vertices.add(new Vertex(1, 100, 0, 1,1,1,1, 1,0, 0x000F000F, 0,1,0));*/
        if (closed) return;


        for (Vertex v : vertices) {
            // 从编码光照计算 UV1 坐标（范围 0~1）
            int blockLight = (v.light & 0xFFFF);        // 低16位：方块光照
            int skyLight = (v.light >> 16) & 0xFFFF;    // 高16位：天空光照

            // 将光照值（0~15）直接传入
            buffer.addVertex(positionMatrix, v.x, v.y, v.z)
                    .setColor(v.r, v.g, v.b, v.a)
                    .setUv(v.u, v.v)
                    .setUv1(blockLight, skyLight)   // ★ 直接传入整数光照值
                    .setUv2(0,0)
                    .setNormal(v.nx, v.ny, v.nz);
        }

    }

    public void drawFilledThroughWalls(Minecraft client, @SuppressWarnings("SameParameterValue") RenderPipeline pipeline) {
        if (closed) return;
        // 🔧 修复闪烁：顶点列表为空或纹理未设置时跳过渲染
        if (vertices.isEmpty() || WAYPOINT_TEXTURE == null) {
            return;
        }
        // 🔧 防止 buffer 为 null（renderWaypoint 因异常提前返回时会置 null）
        if (buffer == null) {
            return;
        }

        // Build the buffer
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();

        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);

        draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

        // 🔧 修复闪烁：仅在 vertexBuffer 已存在时 rotate（新建的 ring buffer 无需 rotate）
        // MappableRingBuffer.rotate() 切换到下一个槽位，避免 GPU 仍在使用的缓冲区被覆盖
        if (vertexBuffer != null) {
            vertexBuffer.rotate();
        }
        // buffer 引用由下一帧的 renderWaypoint 重新创建，无需显式置 null
    }

    public GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
        // Calculate the size needed for the vertex buffer
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

        // 🔧 修复闪烁：添加 25% 的缓冲空间，避免顶点数微调时频繁重建 MappableRingBuffer
        // 频繁重建会导致 GPU/CPU 同步问题，表现为画面闪烁
        int bufferCapacity = vertexBufferSize + (vertexBufferSize >> 2); // +25% headroom

        // Initialize or resize the vertex buffer as needed
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }

            vertexBuffer = new MappableRingBuffer(() -> Drawlib.MOD_ID + " example render pipeline", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bufferCapacity);
        }

        // Copy vertex data into the vertex buffer
        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
        }

        return vertexBuffer.currentBuffer();
    }

    public void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer, MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
        if (closed) return;

        GpuBuffer indices;
        VertexFormat.IndexType indexType;

        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
            indexType = builtBuffer.drawState().indexType();
        } else if (pipeline.getVertexFormatMode() == VertexFormat.Mode.LINES) {
            // ✅ LINES 模式：手动生成连续索引 [0,1,2,3,4,5,...]
            int vertexCount = drawParameters.vertexCount();
            ByteBuffer idxBuffer = MemoryUtil.memAlloc(vertexCount * 2); // SHORT 索引
            for (int i = 0; i < vertexCount; i++) {
                idxBuffer.putShort((short) i);
            }
            idxBuffer.flip();
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(idxBuffer);
            indexType = VertexFormat.IndexType.SHORT;
            MemoryUtil.memFree(idxBuffer);
        } else {
            // 其他模式（TRIANGLES 等）使用默认顺序缓冲区
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
            indexType = shapeIndexBuffer.type();
        }

        // Actually execute the draw
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> Drawlib.MOD_ID + " example render pipeline rendering", client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(), client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {

            // 🔧 修复偏蓝/闪烁：管道定义了 Sampler0/Sampler1/Sampler2 三个采样器，
            // 必须全部绑定有效纹理，否则着色器读取未绑定纹理单元产生未定义行为（偏蓝/闪烁）。
            // 由于着色器已启用 NO_OVERLAY + NO_FOG，Sampler1/Sampler2 不会被实际访问，
            // 直接复用主纹理绑定即可 —— 避免在 RenderPass 内触发 TextureManager 加载（会崩溃）。
            GpuSampler pointSampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    false
            );

            // Sampler0: 主纹理（视频帧/图像）
            renderPass.bindTexture("Sampler0", WAYPOINT_TEXTURE.getTextureView(), pointSampler);
            // Sampler1: 覆盖层纹理 — NO_OVERLAY 禁用，复用主纹理占位
            renderPass.bindTexture("Sampler1", WAYPOINT_TEXTURE.getTextureView(), pointSampler);
            // Sampler2: 光照贴图 — 着色器未使用，复用主纹理占位
            renderPass.bindTexture("Sampler2", WAYPOINT_TEXTURE.getTextureView(), pointSampler);

            renderPass.setPipeline(pipeline);

            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);

            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);

            // The base vertex is the starting index when we copied the data into the vertex buffer divided by vertex size
            //noinspection ConstantValue
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);

        }

        builtBuffer.close();
    }
    // Render states should be immutable, thread safe, and fast to create.
    public record WaypointRenderState(int x, int y, int z, float r, float g, float b, float a) { }


    private NativeImage createPlaceholderImage(int width, int height) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        long ptr = img.getPointer();
        ByteBuffer buf = org.lwjgl.system.MemoryUtil.memByteBuffer(ptr, width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buf.put((byte) 0xFF); // R
                buf.put((byte) 0xFF); // G
                buf.put((byte) 0xFF); // B
                buf.put((byte) 0xFF); // A
            }
        }
        return img;
    }

    public void close() {
        if (closed) {
            return; // 已经关闭，避免重复操作
        }
        closed = true;

        // 1. 取消所有已注册的渲染事件（必须在任何线程安全，Fabric API 内部同步）
        try {

        } catch (Exception e) {
            // 取消注册可能抛出异常（例如事件未注册），但忽略，继续清理
            System.err.println("[lib] 取消事件注册时发生异常: " + e);
        }

        // 2. 释放顶点缓冲区（GPU 资源）
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }

        // 3. 释放分配器（ByteBufferBuilder 实现了 AutoCloseable）
        try {
            if (ALLOCATOR != null) {
                ALLOCATOR.close();
            }
        } catch (Exception e) {
            System.err.println("[lib] 关闭 ALLOCATOR 时发生异常: " + e);
        }

        // 4. 清理顶点列表，帮助 GC
        if (vertices != null) {
            vertices.clear();
        }

        // 5. 释放纹理引用（纹理本身由 TextureManager 管理，只需置空引用）
        WAYPOINT_TEXTURE = null;

        // 可选：将 buffer 引用置空（如果 builder 还在使用，但此时已关闭）
        buffer = null;

        System.out.println("[lib] close() 完成");
    }
}
