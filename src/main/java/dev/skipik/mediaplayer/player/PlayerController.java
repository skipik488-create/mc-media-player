package dev.skipik.mediaplayer.player;

import dev.skipik.mediaplayer.MediaPlayerClient;
import dev.skipik.mediaplayer.api.AudiusApi;
import dev.skipik.mediaplayer.api.LrcLibApi;
import dev.skipik.mediaplayer.api.Track;
import dev.skipik.mediaplayer.audio.AudioEngine;
import dev.skipik.mediaplayer.config.ModConfig;
import dev.skipik.mediaplayer.lyrics.Lyrics;
import dev.skipik.mediaplayer.util.Http;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Queue + playback state. Mutated on the client thread; audio callbacks only set volatile flags. */
public final class PlayerController implements AudioEngine.Listener {
    private static final PlayerController INSTANCE = new PlayerController();
    private static final int MAX_HISTORY = 64;

    public static PlayerController get() {
        return INSTANCE;
    }

    private final AudioEngine engine = new AudioEngine(this);
    private final List<Track> queue = new ArrayList<>();
    private final List<Track> queueView = Collections.unmodifiableList(queue);
    private final Deque<Track> history = new ArrayDeque<>();
    private final Random random = new Random();

    private int index = -1;
    private volatile Track current;
    private volatile Lyrics lyrics = Lyrics.EMPTY;
    private volatile boolean lyricsLoading;
    private volatile long finishedSession = -1;
    private volatile long failedSession = -1;
    private volatile String lastError;
    private long session = -1;
    private boolean paused;
    private long trackStartedAt;
    private int consecutiveErrors;

    private PlayerController() {}

    // ---- audio thread callbacks ----

    @Override
    public void onFinished(long s) {
        finishedSession = s;
    }

    @Override
    public void onError(long s, Throwable error) {
        lastError = Http.describe(error);
        MediaPlayerClient.LOG.warn("Playback failed: {}", lastError);
        failedSession = s;
    }

    // ---- client tick ----

    public void tick() {
        if (session < 0) return;
        if (finishedSession == session) {
            finishedSession = -1;
            consecutiveErrors = 0;
            advance(true);
        } else if (failedSession == session) {
            failedSession = -1;
            consecutiveErrors++;
            if (consecutiveErrors >= 3 || queue.size() <= 1) stop();
            else advance(true);
        }
    }

    // ---- queue ----

    public void playList(List<Track> tracks, int start) {
        queue.clear();
        history.clear();
        queue.addAll(tracks);
        index = -1;
        playIndex(start, false);
    }

    public void playNow(Track t) {
        int existing = indexOf(t.id());
        if (existing >= 0) {
            playIndex(existing, true);
            return;
        }
        int at = index < 0 ? queue.size() : index + 1;
        queue.add(at, t);
        playIndex(at, true);
    }

    public void enqueue(Track t) {
        if (indexOf(t.id()) >= 0) return;
        queue.add(t);
        if (current == null) playIndex(queue.size() - 1, true);
    }

    public void clearQueue() {
        Track cur = current;
        queue.clear();
        history.clear();
        if (cur != null) {
            queue.add(cur);
            index = 0;
        } else {
            index = -1;
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private void playIndex(int i, boolean recordHistory) {
        if (i < 0 || i >= queue.size()) {
            stop();
            return;
        }
        Track prev = current;
        if (recordHistory && prev != null) {
            history.push(prev);
            while (history.size() > MAX_HISTORY) history.removeLast();
        }
        index = i;
        Track t = queue.get(i);
        current = t;
        paused = false;
        lastError = null;
        lyrics = Lyrics.EMPTY;
        lyricsLoading = true;
        trackStartedAt = System.currentTimeMillis();
        engine.setVolume(ModConfig.get().volume);
        session = engine.play(AudiusApi.streamUrl(t.id()), 0L, false);

        final String id = t.id();
        LrcLibApi.fetch(t).whenComplete((l, err) -> {
            Track c = current;
            if (c != null && c.id().equals(id)) {
                lyrics = l == null ? Lyrics.EMPTY : l;
                lyricsLoading = false;
            }
        });
    }

    // ---- transport ----

    public void next() {
        advance(false);
    }

    private void advance(boolean auto) {
        if (queue.isEmpty()) {
            stop();
            return;
        }
        ModConfig cfg = ModConfig.get();
        if (auto && cfg.repeat == ModConfig.Repeat.ONE && index >= 0) {
            playIndex(index, false);
            return;
        }
        int nextIdx;
        if (cfg.shuffle && queue.size() > 1) {
            do {
                nextIdx = random.nextInt(queue.size());
            } while (nextIdx == index);
        } else {
            nextIdx = index + 1;
            if (nextIdx >= queue.size()) {
                if (auto && cfg.repeat == ModConfig.Repeat.OFF) {
                    stop();
                    return;
                }
                nextIdx = 0;
            }
        }
        playIndex(nextIdx, true);
    }

    public void previous() {
        if (current != null && positionMs() > 3000L) {
            seekTo(0L);
            return;
        }
        while (!history.isEmpty()) {
            int i = indexOf(history.pop().id());
            if (i >= 0) {
                playIndex(i, false);
                return;
            }
        }
        if (index > 0) playIndex(index - 1, false);
        else if (current != null) seekTo(0L);
    }

    public void seekTo(long ms) {
        Track t = current;
        if (t == null) return;
        long max = t.durationSec() > 0 ? t.durationMs() - 1000L : Long.MAX_VALUE;
        long target = Math.max(0L, Math.min(ms, max));
        session = engine.play(AudiusApi.streamUrl(t.id()), target, paused);
    }

    public void seekRelative(long deltaMs) {
        seekTo(positionMs() + deltaMs);
    }

    public void togglePause() {
        if (current == null) {
            if (!queue.isEmpty()) playIndex(Math.max(0, index), false);
            return;
        }
        paused = !paused;
        engine.setPaused(paused);
    }

    public void stop() {
        engine.stop();
        session = -1;
        current = null;
        paused = false;
        lyrics = Lyrics.EMPTY;
        lyricsLoading = false;
    }

    public void shutdown() {
        engine.stop();
    }

    public void setVolume(float v) {
        ModConfig.get().volume = v;
        engine.setVolume(v);
    }

    // ---- state ----

    public Track current() {
        return current;
    }

    public Lyrics lyrics() {
        return lyrics;
    }

    public boolean isLyricsLoading() {
        return lyricsLoading;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isBuffering() {
        return current != null && engine.isBuffering();
    }

    public long positionMs() {
        return current == null ? 0L : engine.positionMs();
    }

    public long durationMs() {
        Track t = current;
        return t == null ? 0L : t.durationMs();
    }

    public List<Track> queue() {
        return queueView;
    }

    public int index() {
        return index;
    }

    public String lastError() {
        return lastError;
    }

    public long trackStartedAt() {
        return trackStartedAt;
    }
}
