package dev.skipik.mediaplayer.util;

import net.minecraft.client.gui.Font;

public final class TextUtil {
    private static final String ELLIPSIS = "...";

    private TextUtil() {}

    /** Truncates a string to the given pixel width. Call when data changes, not every frame. */
    public static String fit(Font font, String s, int maxWidth) {
        if (s == null) return "";
        if (font.width(s) <= maxWidth) return s;
        int budget = Math.max(0, maxWidth - font.width(ELLIPSIS));
        return font.plainSubstrByWidth(s, budget) + ELLIPSIS;
    }

    public static String time(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long m = s / 60L;
        long r = s % 60L;
        return m + (r < 10 ? ":0" : ":") + r;
    }

    public static String hex(int rgb) {
        String h = Integer.toHexString(rgb & 0xFFFFFF).toUpperCase(java.util.Locale.ROOT);
        return "#" + "000000".substring(h.length()) + h;
    }
}
