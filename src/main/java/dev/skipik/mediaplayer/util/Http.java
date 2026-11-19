package dev.skipik.mediaplayer.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared async HTTP client. All callbacks run on low-priority daemon IO threads, never the render thread. */
public final class Http {
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService IO = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "MediaPlayer-IO-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public static final String USER_AGENT =
        "MinecraftMediaPlayer/1.0 (+https://github.com/skipik488-create/mc-media-player)";

    public static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .executor(IO)
        .build();

    private Http() {}

    public static Executor io() {
        return IO;
    }

    public static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", USER_AGENT);
    }

    public static CompletableFuture<String> getString(String url) {
        return CLIENT.sendAsync(request(url).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(r -> {
                if (r.statusCode() / 100 != 2) throw new StatusException(r.statusCode(), url);
                return r.body();
            });
    }

    public static CompletableFuture<byte[]> getBytes(String url) {
        return CLIENT.sendAsync(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(r -> {
                if (r.statusCode() / 100 != 2) throw new StatusException(r.statusCode(), url);
                return r.body();
            });
    }

    public static CompletableFuture<JsonElement> getJson(String url) {
        return getString(url).thenApply(JsonParser::parseString);
    }

    public static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Unwraps CompletionException chains into a short user-facing message. */
    public static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return msg == null || msg.isBlank() ? c.getClass().getSimpleName() : msg;
    }

    public static final class StatusException extends RuntimeException {
        public final int status;

        public StatusException(int status, String url) {
            super("HTTP " + status);
            this.status = status;
        }
    }
}
