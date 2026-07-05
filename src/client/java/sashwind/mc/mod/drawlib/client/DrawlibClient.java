package sashwind.mc.mod.drawlib.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.core.ClientAsset;

import java.io.IOException;


public class DrawlibClient implements ClientModInitializer {

    private static DrawlibClient instance;//单例
    public static DrawlibClient getInstance() {
        return instance;
    }

    /*WorldDraw wd = new WorldDraw(0, 100, 0);*/

    @Override
    public void onInitializeClient() {
        instance = this;


        /*ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            ResourceManager manager = Minecraft.getInstance().getResourceManager();
            Identifier textureId = Identifier.fromNamespaceAndPath("minecraft", "textures/block/stone.png");

            wd.init();
            try {
                TextureContents contents = TextureContents.load(manager,textureId);

                wd.addVertices(0, 100, 0, 0,0,0,0, 1,0);
                wd.addVertices(0, 100, 1, 0,0,1,0, 1,0);
                wd.addVertices(1, 100, 1, 0,1,1,0, 1,0);
                wd.addVertices(1, 100, 0, 0,1,0,0, 1,0);
                wd.setTexture(16, 16, contents.image());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        });*/


    }
}
