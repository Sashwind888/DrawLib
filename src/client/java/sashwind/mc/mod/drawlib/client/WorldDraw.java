package sashwind.mc.mod.drawlib.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import sashwind.mc.mod.drawlib.Drawlib;


public class WorldDraw {
    lib lib = null;
    private final Identifier VIDEO_TEXTURE_ID = Identifier.fromNamespaceAndPath(Drawlib.MOD_ID, "stream");
    DynamicTexture dynamicTexture;


    public WorldDraw(int x, int y, int z) {
        lib = new lib(x, y, z);
    }
    public void init() {
        LevelRenderEvents.END_EXTRACTION.register(lib::extractWaypoint);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(lib::renderAndDrawWaypoint);
    }

    // light: 0-15
    public void addVertices(float x, float y, float z, int light, float u, float v, float nx, float ny, float nz) {
        int l = ((light & 0xFF) << 16) | (light & 0xFF);
        lib.vertices.add(new Vertex(x, y, z, 1,1,1,1, u,v, l, 0,1,0));
    }

    public void clearVertices() {
        lib.vertices.clear();
    }

    public void setTexture(int w, int h, NativeImage image) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        // 创建一个初始的空纹理，例如 640x480
        dynamicTexture = new DynamicTexture(Drawlib.MOD_ID + ".stream", w, h, false);
        // 注册到纹理管理器
        textureManager.register(VIDEO_TEXTURE_ID, dynamicTexture);

        Minecraft.getInstance().execute(() -> {
            // 直接设置新的 NativeImage
            dynamicTexture.setPixels(image);
            // 上传到 GPU
            dynamicTexture.upload();
        });

        lib.WAYPOINT_TEXTURE = dynamicTexture;
    }

    public void setTexture(Identifier textureId) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        //Identifier textureId = Identifier.fromNamespaceAndPath("minecraft", "textures/block/stone.png");
        lib.WAYPOINT_TEXTURE = textureManager.getTexture(textureId);
    }

    public void close() {
        lib.ALLOCATOR.close();

        if (lib.vertexBuffer != null) {
            lib.vertexBuffer.close();
            lib.vertexBuffer = null;
        }
    }

}
