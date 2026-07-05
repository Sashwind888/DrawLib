package sashwind.mc.mod.drawlib.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.drawlib.Drawlib;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


public class WorldDraw {
    lib lib = null;
    private final Queue<Runnable> RENDER_TASKS = new ConcurrentLinkedQueue<>();
    //private final Identifier VIDEO_TEXTURE_ID = Identifier.fromNamespaceAndPath(Drawlib.MOD_ID, "stream");
    DynamicTexture dynamicTexture;

    private static final java.util.concurrent.atomic.AtomicInteger TEXTURE_ID_COUNTER
            = new java.util.concurrent.atomic.AtomicInteger(0);

    // 把固定的 VIDEO_TEXTURE_ID 改为每个实例唯一
    private final Identifier VIDEO_TEXTURE_ID;


    public WorldDraw(int x, int y, int z, VertexFormat.Mode VertexFormatMode) {
        this.VIDEO_TEXTURE_ID = Identifier.fromNamespaceAndPath(
                Drawlib.MOD_ID,
                "stream_" + TEXTURE_ID_COUNTER.getAndIncrement()  // → drawlib:stream_0, drawlib:stream_1, ...
        );

        lib = new lib(x, y, z, VertexFormatMode);
    }
    public void init() {
        LevelRenderEvents.END_EXTRACTION.register(lib::extractWaypoint);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(lib::renderAndDrawWaypoint);
        //ClientTickEvents.END_CLIENT_TICK.register(this::RENDER_TASKS_FLUSH);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::RENDER_TASKS_FLUSH);
    }



    // light: 0-15
    public void addVertices(float x, float y, float z, int light, float u, float v, float r, float g, float b, float a) {

        int l = ((light & 0xFF) << 16) | (light & 0xFF);
        lib.vertices.add(new Vertex(x, y, z, r,g,b,a, u,v, l, 0,1,0));
    }

    public void clearVertices() {
        lib.vertices.clear();
    }

    private int currentWidth = 0;
    private int currentHeight = 0;

    // ========== setTexture(NativeImage) — 原有方法 ==========
    public void setTexture(int w, int h, NativeImage image) {
        // 🔧 修复：用 byte[] 从源 NativeImage 的原生内存拷贝数据，
        // 然后同时写入目标 NativeImage 的 int[]（getPixels）和原生内存（getPointer），
        // 确保无论 DynamicTexture.setPixels 读取哪一边，数据都是正确的。
        int size = w * h * 4;
        long srcPtr = image.getPointer();
        NativeImage.Format fmt = image.format();

        // 在调用线程（image 尚未 close）拷贝原始字节
        byte[] rawPixels = new byte[size];
        org.lwjgl.system.MemoryUtil.memByteBuffer(srcPtr, size).get(rawPixels);

        RENDER_TASKS.offer(() -> {
            NativeImage nativeImage = null;
            try {
                nativeImage = new NativeImage(fmt, w, h, false);

                // 同时写入 int[] 和原生内存，覆盖两种读取路径
                int[] pixels = nativeImage.getPixels();
                java.nio.ByteBuffer.wrap(rawPixels).asIntBuffer().get(pixels);

                long dstPtr = nativeImage.getPointer();
                org.lwjgl.system.MemoryUtil.memByteBuffer(dstPtr, size).put(rawPixels);

                ensureTexture(w, h);
                dynamicTexture.setPixels(nativeImage);
                dynamicTexture.upload();
                lib.WAYPOINT_TEXTURE = dynamicTexture;
            } finally {
                if (nativeImage != null) {
                    nativeImage.close();
                }
            }
        });
    }

    // ========== setTexture(Buffer[]) — 直接填入图像缓冲区 ==========
    /**
     * 使用 java.nio.Buffer[] 直接填入图像数据（推荐方式，与 StreamPuller 一致）。<br>
     * <br>
     * 关键原理：RGBA 字节在 little-endian 下被解释为 int 时，自动变成 ABGR int。<br>
     *   例如 RGBA 字节 [0xFF, 0x00, 0x00, 0xFF]（红色）→ int=0xFF0000FF → NativeImage 红色 ✓<br>
     * 因此 <b>无需通道交换</b>，与 StreamPuller.frameToNativeImage 的 IntBuffer.put() 路径完全一致。<br>
     * <br>
     * 支持格式：<br>
     * 1. ByteBuffer — RGBA8 字节像素 (R,G,B,A,...)，通过 asIntBuffer 自然转换<br>
     * 2. IntBuffer  — ABGR8 int 像素 (0xAABBGGRR)，直接复制<br>
     * <br>
     * 如果你的数据源是 ARGB int（如 BufferedImage.getRGB），请用 {@link #setTextureSwapped(int, int, Buffer[])}。
     *
     * @param w     图像宽度（像素）
     * @param h     图像高度（像素）
     * @param image 图像缓冲区数组，image[0] 为主数据平面
     */
    public void setTexture(int w, int h, Buffer[] image) {
        Buffer[] pixels = image.clone();
        RENDER_TASKS.offer(()-> {
            ensureTexture(w, h);

            NativeImage nativeImage = null;
            try {
                nativeImage = bufferArrayToNativeImage(w, h, pixels, false);
                dynamicTexture.setPixels(nativeImage);
                dynamicTexture.upload();
                lib.WAYPOINT_TEXTURE = dynamicTexture;
            } finally {
                if (nativeImage != null) {
                    nativeImage.close();
                }
            }
        });
    }

    /**
     * 使用 java.nio.Buffer[] 填入图像数据（带 R↔B 通道交换）。
     * 适用于 ARGB int 源（如 BufferedImage.getRGB 的 0xAARRGGBB）→ ABGR int 转换。
     *
     * @param w     图像宽度（像素）
     * @param h     图像高度（像素）
     * @param image 图像缓冲区数组，image[0] 为主数据平面
     */
    public void setTextureSwapped(int w, int h, Buffer[] image) {
        Buffer[] pixels = image.clone();
        RENDER_TASKS.offer(()->{
            ensureTexture(w, h);

            NativeImage nativeImage = null;
            try {
                nativeImage = bufferArrayToNativeImage(w, h, pixels, true);
                dynamicTexture.setPixels(nativeImage);
                dynamicTexture.upload();
                lib.WAYPOINT_TEXTURE = dynamicTexture;
            } finally {
                if (nativeImage != null) {
                    nativeImage.close();
                }
            }
        });

    }

    public void setTexture(Identifier textureId) {
        RENDER_TASKS.offer(()->{
            System.out.println("[setTexture] [DEBUG] 纹理设置，方法4");
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            lib.WAYPOINT_TEXTURE = textureManager.getTexture(textureId);
            System.out.println("[setTexture] [DEBUG] 纹理设置，方法4 完成");
        });

    }

    // ========== 内部方法 ==========

    /** 确保 DynamicTexture 存在且尺寸匹配 */
    private void ensureTexture(int w, int h) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        if (dynamicTexture == null || currentWidth != w || currentHeight != h) {
            if (dynamicTexture != null) {
                textureManager.release(VIDEO_TEXTURE_ID);
            }
            // 🔧 useNativeImage=true：让 DynamicTexture 持有内部 NativeImage，
            // setPixels 时 copyFrom 复制数据，upload 才能正确创建 Blaze3D GpuTextureView
            dynamicTexture = new DynamicTexture(Drawlib.MOD_ID + ".stream", w, h, true);
            textureManager.register(VIDEO_TEXTURE_ID, dynamicTexture);
            currentWidth = w;
            currentHeight = h;
        }
    }

    /**
     * 将 java.nio.Buffer[] 转换为 NativeImage。
     * <br>
     * 核心原理（与 StreamPuller 一致的 IntBuffer 路径）：
     *   Minecraft NativeImage 内部是 ABGR int[]（小端字节序）。
     *   外部 RGBA 字节数组 → IntBuffer 按 little-endian 解释 → 自然得到 ABGR int。
     *   例如 RGBA 字节 [0xFF, 0x00, 0x00, 0xFF]（红色）→ int=0xFF0000FF → ABGR 的红色 ✓
     * <br>
     * 所以 swapRB=false 时直接用 IntBuffer 批量 copy（与 StreamPuller 完全一致）。
     * swapRB=true 时额外做 R↔B 交换（适用于真正的 RGBA → ABGR 转换）。
     */
    private NativeImage bufferArrayToNativeImage(int w, int h, Buffer[] image, boolean swapRB) {
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int pixelCount = w * h;

        if (image == null || image.length == 0 || image[0] == null) {
            return nativeImage; // 返回空白图像
        }

        int[] pixels = nativeImage.getPixels();
        Buffer srcBuf = image[0];

        // 统一转为 IntBuffer（与 StreamPuller.frameToNativeImage 相同路径）
        IntBuffer srcIntBuf;
        if (srcBuf instanceof IntBuffer ib) {
            srcIntBuf = ib;
        } else if (srcBuf instanceof ByteBuffer bb) {
            srcIntBuf = bb.asIntBuffer();
        } else {
            return nativeImage; // 不支持的类型
        }

        srcIntBuf.rewind();
        IntBuffer dstIntBuf = IntBuffer.wrap(pixels);

        if (swapRB) {
            // 批量读取 → 交换 R↔B → 写入
            int[] temp = new int[pixelCount];
            srcIntBuf.get(temp, 0, Math.min(pixelCount, srcIntBuf.remaining()));
            for (int i = 0; i < pixelCount && i < temp.length; i++) {
                int val = temp[i];
                int a = (val >>> 24) & 0xFF;
                int r = (val >>> 16) & 0xFF;
                int g = (val >>>  8) & 0xFF;
                int b =  val         & 0xFF;
                pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        } else {
            // 直接批量复制（与 StreamPuller 完全相同的路径）
            dstIntBuf.put(srcIntBuf);
        }

        return nativeImage;
    }

    public void close() {
        RENDER_TASKS.offer(()-> {
            System.out.println("[close] [DEBUG] 关闭");

            // 先设置 closed 标志，阻止后续帧继续渲染
            lib.closed = true;

            // 使用 try-finally 确保 lib.close() 一定执行，
            // 即使中间步骤抛出异常也不会留下半关闭状态
            try {
                if (lib.vertexBuffer != null) {
                    lib.vertexBuffer.close();
                    lib.vertexBuffer = null;
                }

                if (dynamicTexture != null) {
                    dynamicTexture.close();
                    dynamicTexture = null;
                }

                clearVertices();

                if (lib.WAYPOINT_TEXTURE != null) {
                    lib.WAYPOINT_TEXTURE.close();
                    lib.WAYPOINT_TEXTURE = null;
                }
            } finally {
                lib.close();  // 一定会执行，释放 ALLOCATOR 等资源
            }

            System.out.println("[close] [DEBUG] 关闭 完成");
        });
    }

    private void RENDER_TASKS_FLUSH(LevelRenderContext levelRenderContext) {
        Runnable task = RENDER_TASKS.poll();
        while (task != null) {
            try {
                task.run();
            } catch (Throwable e) {
                // 打印异常堆栈到日志（标准错误）
                System.err.println("[RENDER_TASKS_FLUSH] 任务执行异常: " + e);
                e.printStackTrace();
                // 如果需要，也可以将异常写入日志文件
            }
            task = RENDER_TASKS.poll();
            System.out.println("[RENDER_TASKS_FLUSH] Runed a new task.");
        }
    }


}
