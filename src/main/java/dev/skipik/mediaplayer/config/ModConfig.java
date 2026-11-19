package dev.skipik.mediaplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.skipik.mediaplayer.MediaPlayerClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;

public final class ModConfig {
    public enum HudPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

        public HudPosition next() {
            HudPosition[] v = values();
            return v[(ordinal() + 1) % v.length];
        }

        public String translationKey() {
            return "mediaplayer.position." + name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Repeat {
        OFF, ALL, ONE;

        public Repeat next() {
            Repeat[] v = values();
            return v[(ordinal() + 1) % v.length];
        }

        public String translationKey() {
            return "mediaplayer.repeat." + name().toLowerCase(Locale.ROOT);
        }
    }

    // Lyrics overlay
    public boolean lyricsEnabled = true;
    public HudPosition position = HudPosition.BOTTOM_CENTER;
    public float scale = 1.0f;
    public float backgroundOpacity = 0.45f;
    public int lineCount = 3;
    public int textColor = 0xFFFFFF;
    public int highlightColor = 0xFFD54F;
    public int lyricsOffsetMs = 0;
    public boolean karaoke = true;
    public boolean hideWhenPaused = true;
    public boolean nowPlayingToast = true;

    // Player
    public float volume = 0.7f;
    public boolean shuffle = false;
    public Repeat repeat = Repeat.OFF;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MediaPlayer-Config");
        t.setDaemon(true);
        return t;
    });
    private static ModConfig instance = new ModConfig();

    public static ModConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mediaplayer.json");
    }

    public static void load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                ModConfig loaded = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), ModConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (Exception e) {
                MediaPlayerClient.LOG.warn("Failed to read config, using defaults", e);
            }
        }
        instance.sanitize();
        saveAsync();
    }

    private void sanitize() {
        if (position == null) position = HudPosition.BOTTOM_CENTER;
        if (repeat == null) repeat = Repeat.OFF;
        scale = clamp(scale, 0.5f, 2.5f);
        backgroundOpacity = clamp(backgroundOpacity, 0f, 1f);
        volume = clamp(volume, 0f, 1f);
        lineCount = Math.max(1, Math.min(7, lineCount));
        lyricsOffsetMs = Math.max(-5000, Math.min(5000, lyricsOffsetMs));
        textColor &= 0xFFFFFF;
        highlightColor &= 0xFFFFFF;
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    /** Serialises on the caller thread (cheap), writes on a background thread. */
    public static void saveAsync() {
        String json = GSON.toJson(instance);
        WRITER.execute(() -> write(json));
    }

    public static void saveNow() {
        write(GSON.toJson(instance));
    }

    private static synchronized void write(String json) {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("mediaplayer.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MediaPlayerClient.LOG.warn("Failed to save config", e);
        }
    }
}
