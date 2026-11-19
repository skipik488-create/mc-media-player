package dev.skipik.mediaplayer.hud;

import dev.skipik.mediaplayer.api.Track;
import dev.skipik.mediaplayer.config.ModConfig;
import dev.skipik.mediaplayer.lyrics.Lyrics;
import dev.skipik.mediaplayer.player.CoverManager;
import dev.skipik.mediaplayer.player.PlayerController;
import dev.skipik.mediaplayer.util.TextUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2fStack;

/**
 * Lyrics overlay + "now playing" toast. Hot path is allocation-free: current line via binary
 * search, cached widths, karaoke prefix only rebuilt when its length changes, toast strings
 * truncated once per track. Returns immediately when there is nothing to draw.
 */
public final class MediaHud implements HudElement {
    public static final MediaHud INSTANCE = new MediaHud();

    private static final long TOAST_MS = 4500L;
    private static final long FLASH_MS = 1500L;
    private static final int TOAST_W = 170;
    private static final int TOAST_H = 36;

    private Lyrics karaokeLyrics;
    private int karaokeLine = -1;
    private int karaokeChars = -1;
    private String karaokePrefix = "";

    private String toastFor;
    private String toastTitle = "";
    private String toastArtist = "";

    private volatile String flashText;
    private volatile long flashAt;

    private MediaHud() {}

    public void flash(String text) {
        flashText = text;
        flashAt = System.currentTimeMillis();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        long now = System.currentTimeMillis();
        String flash = flashText;
        if (flash != null) renderFlash(g, flash, now);

        PlayerController pc = PlayerController.get();
        Track track = pc.current();
        if (track == null) return;

        ModConfig cfg = ModConfig.get();
        if (cfg.nowPlayingToast) renderToast(g, pc, track, now);
        if (!cfg.lyricsEnabled || (cfg.hideWhenPaused && pc.isPaused())) return;

        Lyrics lyrics = pc.lyrics();
        if (lyrics.isEmpty()) return;
        renderLyrics(g, Minecraft.getInstance().font, cfg, lyrics, pc.positionMs() + cfg.lyricsOffsetMs, pc.durationMs());
    }

    private void renderLyrics(GuiGraphicsExtractor g, Font font, ModConfig cfg, Lyrics lyrics, long pos, long durationMs) {
        int current = lyrics.indexAt(pos);
        int count = cfg.lineCount;
        int first = current - count / 2;
        int lineH = font.lineHeight + 3;

        int maxW = 0;
        for (int k = 0; k < count; k++) {
            int i = first + k;
            if (i >= 0 && i < lyrics.size()) maxW = Math.max(maxW, lyrics.width(font, i));
        }
        if (maxW == 0) return;

        int boxW = maxW + 16;
        int boxH = count * lineH + 5;
        float scale = cfg.scale;
        float w = boxW * scale;
        float h = boxH * scale;
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        float x;
        float y;
        switch (cfg.position) {
            case TOP_LEFT -> {
                x = 6;
                y = 6;
            }
            case TOP_CENTER -> {
                x = (sw - w) / 2f;
                y = 6;
            }
            case TOP_RIGHT -> {
                x = sw - w - 6;
                y = 6;
            }
            case CENTER -> {
                x = (sw - w) / 2f;
                y = sh * 0.58f;
            }
            case BOTTOM_LEFT -> {
                x = 6;
                y = sh - h - 6;
            }
            case BOTTOM_RIGHT -> {
                x = sw - w - 6;
                y = sh - h - 6;
            }
            default -> {
                x = (sw - w) / 2f;
                y = sh - h - 64;
            }
        }

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale);

        int alpha = Math.round(cfg.backgroundOpacity * 255f) & 0xFF;
        if (alpha > 0) g.fill(0, 0, boxW, boxH, alpha << 24);

        int base = 0xFF000000 | cfg.textColor;
        int dim = 0x99000000 | cfg.textColor;
        int hi = 0xFF000000 | cfg.highlightColor;
        for (int k = 0; k < count; k++) {
            int i = first + k;
            if (i < 0 || i >= lyrics.size()) continue;
            String line = lyrics.line(i);
            int lx = (boxW - lyrics.width(font, i)) / 2;
            int ly = 4 + k * lineH;
            if (i == current) {
                if (cfg.karaoke && lyrics.synced()) {
                    g.text(font, line, lx, ly, base, true);
                    String prefix = karaokePrefix(lyrics, i, line, pos, durationMs);
                    if (!prefix.isEmpty()) g.text(font, prefix, lx, ly, hi, true);
                } else {
                    g.text(font, line, lx, ly, hi, true);
                }
            } else {
                g.text(font, line, lx, ly, dim, true);
            }
        }
        pose.popMatrix();
    }

    private String karaokePrefix(Lyrics lyrics, int i, String line, long pos, long durationMs) {
        long start = lyrics.time(i);
        long end = lyrics.lineEnd(i, durationMs > 0 ? durationMs : start + 5000L);
        long span = Math.max(1L, Math.min(end - start, 8000L));
        double f = Math.max(0.0, Math.min(1.0, (pos - start) / (double) span));
        int chars = (int) Math.round(f * line.length());
        if (chars > 0 && chars < line.length() && Character.isHighSurrogate(line.charAt(chars - 1))) chars--;
        if (lyrics != karaokeLyrics || i != karaokeLine || chars != karaokeChars) {
            karaokeLyrics = lyrics;
            karaokeLine = i;
            karaokeChars = chars;
            karaokePrefix = line.substring(0, chars);
        }
        return karaokePrefix;
    }

    private void renderToast(GuiGraphicsExtractor g, PlayerController pc, Track t, long now) {
        long age = now - pc.trackStartedAt();
        if (age < 0 || age > TOAST_MS) return;
        Font font = Minecraft.getInstance().font;
        if (!t.id().equals(toastFor)) {
            toastFor = t.id();
            toastTitle = TextUtil.fit(font, t.title(), TOAST_W - 46);
            toastArtist = TextUtil.fit(font, t.artist(), TOAST_W - 46);
        }
        float slide = age < 250L ? 1f - age / 250f : age > TOAST_MS - 250L ? (age - (TOAST_MS - 250L)) / 250f : 0f;
        int x = g.guiWidth() - TOAST_W - 6 + (int) (slide * (TOAST_W + 8));
        int y = 6;
        g.fill(x, y, x + TOAST_W, y + TOAST_H, 0xD0101014);
        g.fill(x, y, x + 2, y + TOAST_H, 0xFF000000 | ModConfig.get().highlightColor);
        CoverManager.Cover cover = CoverManager.get(t);
        if (cover != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, cover.id(), x + 6, y + 4, 0f, 0f, 28, 28,
                cover.size(), cover.size(), cover.size(), cover.size());
        } else {
            g.fill(x + 6, y + 4, x + 34, y + 32, 0x40FFFFFF);
        }
        g.text(font, toastTitle, x + 40, y + 8, 0xFFFFFFFF, true);
        g.text(font, toastArtist, x + 40, y + 20, 0xFFAAAAAA, true);
    }

    private void renderFlash(GuiGraphicsExtractor g, String text, long now) {
        if (now - flashAt > FLASH_MS) {
            flashText = null;
            return;
        }
        Font font = Minecraft.getInstance().font;
        int w = font.width(text) + 12;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - 92;
        g.fill(x, y, x + w, y + 14, 0xB0000000);
        g.centeredText(font, text, g.guiWidth() / 2, y + 3, 0xFFFFFFFF);
    }
}
