package sashwind.mc.mod.drawlib.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import static sashwind.mc.mod.drawlib.client.lib.ALLOCATOR;

public class DrawlibClient implements ClientModInitializer {
    private static DrawlibClient instance;//单例
    public static DrawlibClient getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        LevelRenderEvents.END_EXTRACTION.register(lib::extractWaypoint);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(lib::renderAndDrawWaypoint);
    }

    public void close() {
        ALLOCATOR.close();

        if (lib.vertexBuffer != null) {
            lib.vertexBuffer.close();
            lib.vertexBuffer = null;
        }
    }
}
