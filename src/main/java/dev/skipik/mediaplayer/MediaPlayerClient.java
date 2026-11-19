package dev.skipik.mediaplayer;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skipik.mediaplayer.config.ModConfig;
import dev.skipik.mediaplayer.hud.MediaHud;
import dev.skipik.mediaplayer.player.CoverManager;
import dev.skipik.mediaplayer.player.PlayerController;
import dev.skipik.mediaplayer.screen.PlayerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MediaPlayerClient implements ClientModInitializer {
    public static final String MOD_ID = "mediaplayer";
    public static final Logger LOG = LoggerFactory.getLogger("MediaPlayer");

    private static KeyMapping openKey;
    private static KeyMapping lyricsKey;
    private static KeyMapping playPauseKey;
    private static KeyMapping nextKey;
    private static KeyMapping prevKey;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(id("main"));
        openKey = register("open", GLFW.GLFW_KEY_M, category);
        lyricsKey = register("lyrics", GLFW.GLFW_KEY_J, category);
        playPauseKey = register("playpause", GLFW.GLFW_KEY_K, category);
        nextKey = register("next", GLFW.GLFW_KEY_RIGHT_BRACKET, category);
        prevKey = register("prev", GLFW.GLFW_KEY_LEFT_BRACKET, category);

        HudElementRegistry.addLast(id("overlay"), MediaHud.INSTANCE);
        ClientTickEvents.END_CLIENT_TICK.register(MediaPlayerClient::onEndTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PlayerController.get().shutdown();
            ModConfig.saveNow();
        });
        LOG.info("Media Player initialized");
    }

    private static KeyMapping register(String name, int key, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.mediaplayer." + name, InputConstants.Type.KEYSYM, key, category));
    }

    private static void onEndTick(Minecraft mc) {
        PlayerController controller = PlayerController.get();
        controller.tick();
        CoverManager.tick(mc);

        while (openKey.consumeClick()) {
            if (mc.gui.screen() == null) {
                mc.gui.setScreen(new PlayerScreen());
            }
        }
        while (lyricsKey.consumeClick()) {
            ModConfig cfg = ModConfig.get();
            cfg.lyricsEnabled = !cfg.lyricsEnabled;
            ModConfig.saveAsync();
            MediaHud.INSTANCE.flash(Component.translatable(
                cfg.lyricsEnabled ? "mediaplayer.msg.lyrics_on" : "mediaplayer.msg.lyrics_off").getString());
        }
        while (playPauseKey.consumeClick()) {
            controller.togglePause();
            if (controller.current() != null) {
                MediaHud.INSTANCE.flash(Component.translatable(
                    controller.isPaused() ? "mediaplayer.msg.paused" : "mediaplayer.msg.playing").getString());
            }
        }
        while (nextKey.consumeClick()) {
            controller.next();
        }
        while (prevKey.consumeClick()) {
            controller.previous();
        }
    }
}
