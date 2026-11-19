package dev.skipik.mediaplayer.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skipik.mediaplayer.util.Http;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Audius public API (free, no key): search, trending and full MP3 streams.
 * Uses the api.audius.co gateway and falls back to a random discovery node if it fails.
 */
public final class AudiusApi {
    private static final String APP_NAME = "mc-media-player";
    private static final String GATEWAY = "https://api.audius.co";
    private static volatile String host = GATEWAY;

    private AudiusApi() {}

    public static CompletableFuture<List<Track>> search(String query) {
        return tracks("/v1/tracks/search?query=" + Http.enc(query) + "&limit=50&app_name=" + APP_NAME);
    }

    public static CompletableFuture<List<Track>> trending() {
        return tracks("/v1/tracks/trending?time=week&limit=50&app_name=" + APP_NAME);
    }

    public static String streamUrl(String trackId) {
        return host + "/v1/tracks/" + Http.enc(trackId) + "/stream?app_name=" + APP_NAME;
    }

    private static CompletableFuture<List<Track>> tracks(String path) {
        final String first = host;
        return Http.getJson(first + path)
            .thenApply(AudiusApi::parse)
            .exceptionallyCompose(err -> resolveHost(first)
                .thenCompose(h -> Http.getJson(h + path))
                .thenApply(AudiusApi::parse));
    }

    private static CompletableFuture<String> resolveHost(String failed) {
        return Http.getJson(GATEWAY).thenApply(json -> {
            JsonArray arr = json.getAsJsonObject().getAsJsonArray("data");
            List<String> hosts = new ArrayList<>();
            if (arr != null) {
                for (JsonElement e : arr) {
                    String h = e.getAsString();
                    if (!h.equals(failed)) hosts.add(h);
                }
            }
            if (hosts.isEmpty()) throw new IllegalStateException("No Audius hosts available");
            String h = hosts.get(ThreadLocalRandom.current().nextInt(hosts.size()));
            host = h;
            return h;
        });
    }

    private static List<Track> parse(JsonElement root) {
        JsonArray data = root.getAsJsonObject().getAsJsonArray("data");
        if (data == null) return List.of();
        List<Track> out = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            JsonElement streamable = o.get("is_streamable");
            if (streamable != null && streamable.isJsonPrimitive() && !streamable.getAsBoolean()) continue;
            String id = str(o, "id");
            if (id == null) continue;
            String title = str(o, "title");
            String artist = null;
            JsonElement user = o.get("user");
            if (user != null && user.isJsonObject()) artist = str(user.getAsJsonObject(), "name");
            JsonElement dur = o.get("duration");
            int duration = dur != null && dur.isJsonPrimitive() ? dur.getAsInt() : 0;
            String art = null;
            JsonElement artwork = o.get("artwork");
            if (artwork != null && artwork.isJsonObject()) {
                art = str(artwork.getAsJsonObject(), "150x150");
                if (art == null) art = str(artwork.getAsJsonObject(), "480x480");
            }
            out.add(new Track(id, title == null ? "Unknown" : title, artist == null ? "Unknown" : artist, duration, art));
        }
        return out;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
