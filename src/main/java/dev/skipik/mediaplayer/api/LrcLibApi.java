package dev.skipik.mediaplayer.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skipik.mediaplayer.lyrics.Lyrics;
import dev.skipik.mediaplayer.util.Http;
import dev.skipik.mediaplayer.util.LruCache;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** LRCLIB (free, no key): time-synced lyrics in LRC format, cached per track. */
public final class LrcLibApi {
    private static final String BASE = "https://lrclib.net/api";
    private static final LruCache<String, Lyrics> CACHE = new LruCache<>(64);
    private static final Pattern BRACKETS = Pattern.compile("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]");
    private static final Pattern FEAT = Pattern.compile("(?i)\\s+(feat\\.?|ft\\.?|prod\\.?)\\s+.*$");
    private static final int MAX_DURATION_DIFF = 15;

    private LrcLibApi() {}

    public static CompletableFuture<Lyrics> fetch(Track t) {
        Lyrics cached = CACHE.get(t.id());
        if (cached != null) return CompletableFuture.completedFuture(cached);

        String title = clean(t.title());
        String artist = t.artist();
        int dur = t.durationSec();
        String url = BASE + "/get?track_name=" + Http.enc(title) + "&artist_name=" + Http.enc(artist)
            + (dur > 0 ? "&duration=" + dur : "");

        return Http.getJson(url)
            .thenApply(j -> j.isJsonObject() ? fromRecord(j.getAsJsonObject(), t.durationMs()) : Lyrics.EMPTY)
            .thenCompose(l -> l.isEmpty() ? search(title, artist, dur, t.durationMs()) : CompletableFuture.completedFuture(l))
            .exceptionallyCompose(err -> search(title, artist, dur, t.durationMs()))
            .handle((l, err) -> {
                if (err != null || l == null) return Lyrics.EMPTY;
                CACHE.put(t.id(), l);
                return l;
            });
    }

    private static CompletableFuture<Lyrics> search(String title, String artist, int dur, long durationMs) {
        String q = (artist + " " + title).trim();
        return Http.getJson(BASE + "/search?q=" + Http.enc(q)).thenCompose(j -> {
            Lyrics best = pick(j, dur, durationMs);
            if (!best.isEmpty()) return CompletableFuture.completedFuture(best);
            return Http.getJson(BASE + "/search?track_name=" + Http.enc(title)).thenApply(j2 -> pick(j2, dur, durationMs));
        });
    }

    private static Lyrics pick(JsonElement json, int dur, long durationMs) {
        if (!json.isJsonArray()) return Lyrics.EMPTY;
        JsonObject bestSynced = null;
        JsonObject bestPlain = null;
        double syncedDiff = Double.MAX_VALUE;
        double plainDiff = Double.MAX_VALUE;
        for (JsonElement e : json.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonElement d = o.get("duration");
            double candidate = d != null && d.isJsonPrimitive() ? d.getAsDouble() : 0;
            double diff = dur > 0 && candidate > 0 ? Math.abs(candidate - dur) : 0;
            if (dur > 0 && diff > MAX_DURATION_DIFF) continue;
            String synced = AudiusApi.str(o, "syncedLyrics");
            if (synced != null && !synced.isBlank() && diff < syncedDiff) {
                syncedDiff = diff;
                bestSynced = o;
            }
            String plain = AudiusApi.str(o, "plainLyrics");
            if (plain != null && !plain.isBlank() && diff < plainDiff) {
                plainDiff = diff;
                bestPlain = o;
            }
        }
        if (bestSynced != null) return fromRecord(bestSynced, durationMs);
        if (bestPlain != null) return fromRecord(bestPlain, durationMs);
        return Lyrics.EMPTY;
    }

    private static Lyrics fromRecord(JsonObject o, long durationMs) {
        JsonElement instrumental = o.get("instrumental");
        if (instrumental != null && instrumental.isJsonPrimitive() && instrumental.getAsBoolean()) {
            return Lyrics.instrumental();
        }
        String synced = AudiusApi.str(o, "syncedLyrics");
        if (synced != null && !synced.isBlank()) {
            Lyrics l = Lyrics.parseLrc(synced);
            if (!l.isEmpty()) return l;
        }
        String plain = AudiusApi.str(o, "plainLyrics");
        if (plain != null && !plain.isBlank()) return Lyrics.fromPlain(plain, durationMs);
        return Lyrics.EMPTY;
    }

    static String clean(String title) {
        String s = BRACKETS.matcher(title).replaceAll("");
        s = FEAT.matcher(s).replaceAll("");
        s = s.trim();
        return s.isEmpty() ? title : s;
    }
}
