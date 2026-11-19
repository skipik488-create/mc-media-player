package dev.skipik.mediaplayer.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;

/**
 * Parsed lyrics as parallel arrays (time -> line). Lookup is a binary search and text widths are
 * cached lazily, so the HUD never measures or allocates per frame.
 */
public final class Lyrics {
    public static final Lyrics EMPTY = new Lyrics(new long[0], new String[0], false);

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");
    private static final String GAP = "\u266A";

    private final long[] times;
    private final String[] lines;
    private final boolean synced;
    private final int[] widths;

    private Lyrics(long[] times, String[] lines, boolean synced) {
        this.times = times;
        this.lines = lines;
        this.synced = synced;
        this.widths = new int[lines.length];
        Arrays.fill(this.widths, -1);
    }

    public static Lyrics instrumental() {
        return new Lyrics(new long[] {0L}, new String[] {"\u266A Instrumental \u266A"}, false);
    }

    public boolean isEmpty() {
        return lines.length == 0;
    }

    public int size() {
        return lines.length;
    }

    public boolean synced() {
        return synced;
    }

    public long time(int i) {
        return times[i];
    }

    public String line(int i) {
        return lines[i];
    }

    public int width(Font font, int i) {
        int w = widths[i];
        if (w < 0) {
            w = font.width(lines[i]);
            widths[i] = w;
        }
        return w;
    }

    /** Index of the last line whose timestamp is <= ms, or -1 before the first line. */
    public int indexAt(long ms) {
        int lo = 0;
        int hi = times.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= ms) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    public long lineEnd(int i, long fallbackEnd) {
        return i + 1 < times.length ? times[i + 1] : Math.max(times[i] + 1, fallbackEnd);
    }

    private record Entry(long time, String text) {}

    public static Lyrics parseLrc(String text) {
        List<Entry> entries = new ArrayList<>();
        long offset = 0;
        List<Long> stamps = new ArrayList<>(4);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[offset:") && line.endsWith("]")) {
                try {
                    offset = Long.parseLong(line.substring(8, line.length() - 1).trim());
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            stamps.clear();
            Matcher m = TIME.matcher(line);
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                stamps.add(min * 60_000L + sec * 1000L + ms);
                end = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = WORD_TIME.matcher(line.substring(end)).replaceAll("").trim();
            if (body.isEmpty()) body = GAP;
            for (long t : stamps) entries.add(new Entry(Math.max(0, t - offset), body));
        }
        if (entries.isEmpty()) return EMPTY;
        entries.sort(Comparator.comparingLong(Entry::time));
        long[] times = new long[entries.size()];
        String[] lines = new String[entries.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = entries.get(i).time();
            lines[i] = entries.get(i).text();
        }
        return new Lyrics(times, lines, true);
    }

    /** Plain lyrics have no timestamps: spread them evenly across the track. */
    public static Lyrics fromPlain(String text, long durationMs) {
        List<String> ls = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String s = raw.trim();
            if (!s.isEmpty()) ls.add(s);
        }
        if (ls.isEmpty()) return EMPTY;
        long total = durationMs > 0 ? durationMs : ls.size() * 4000L;
        long[] times = new long[ls.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = (long) (i * (double) total / ls.size());
        }
        return new Lyrics(times, ls.toArray(new String[0]), false);
    }
}
