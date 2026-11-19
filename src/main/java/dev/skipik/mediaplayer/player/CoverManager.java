package dev.skipik.mediaplayer.player;

import com.mojang.blaze3d.platform.NativeImage;
import dev.skipik.mediaplayer.MediaPlayerClient;
import dev.skipik.mediaplayer.api.Track;
import dev.skipik.mediaplayer.util.Http;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Cover art: downloaded and downscaled to <=128 px on IO threads, uploaded to the GPU on the
 * client thread (max 2 per tick) and kept in a small LRU texture cache.
 */
public final class CoverManager {
    public record Cover(Identifier id, int size) {}

    private record Decoded(String key, byte[] png, int size) {}

    private static final int MAX_SIZE = 128;
    private static final int MAX_ENTRIES = 24;
    private static final int UPLOADS_PER_TICK = 2;

    private static final LinkedHashMap<String, Cover> CACHE = new LinkedHashMap<>(32, 0.75f, true);
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Decoded> READY = new ConcurrentLinkedQueue<>();
    private static int counter;

    private CoverManager() {}

    /** Render-thread lookup. Returns null until the texture is ready (and starts loading it). */
    public static Cover get(Track t) {
        String url = t.artworkUrl();
        if (url == null) return null;
        Cover c = CACHE.get(url);
        if (c != null) return c;
        if (REQUESTED.add(url)) {
            Http.getBytes(url)
                .thenApplyAsync(bytes -> decode(url, bytes), Http.io())
                .thenAccept(READY::add)
                .exceptionally(err -> {
                    MediaPlayerClient.LOG.debug("Cover load failed for {}: {}", url, Http.describe(err));
                    return null;
                });
        }
        return null;
    }

    public static void tick(Minecraft mc) {
        for (int n = 0; n < UPLOADS_PER_TICK; n++) {
            Decoded d = READY.poll();
            if (d == null) return;
            try {
                NativeImage image = NativeImage.read(d.png());
                Identifier id = MediaPlayerClient.id("cover/" + (counter++));
                mc.getTextureManager().register(id, new DynamicTexture(() -> "mediaplayer cover", image));
                CACHE.put(d.key(), new Cover(id, d.size()));
                evict(mc);
            } catch (IOException | RuntimeException e) {
                MediaPlayerClient.LOG.debug("Cover upload failed", e);
            }
        }
    }

    private static void evict(Minecraft mc) {
        Iterator<Map.Entry<String, Cover>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > MAX_ENTRIES && it.hasNext()) {
            Map.Entry<String, Cover> e = it.next();
            mc.getTextureManager().release(e.getValue().id());
            REQUESTED.remove(e.getKey());
            it.remove();
        }
    }

    private static Decoded decode(String key, byte[] bytes) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) throw new IOException("Unsupported image format");
            int side = Math.min(src.getWidth(), src.getHeight());
            int size = Math.max(1, Math.min(MAX_SIZE, side));
            int sx = (src.getWidth() - side) / 2;
            int sy = (src.getHeight() - side) / 2;
            BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream(size * size * 2);
            ImageIO.write(dst, "png", out);
            return new Decoded(key, out.toByteArray(), size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
