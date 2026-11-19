package dev.skipik.mediaplayer.api;

/** Immutable track metadata from Audius. */
public record Track(String id, String title, String artist, int durationSec, String artworkUrl) {
    public long durationMs() {
        return durationSec * 1000L;
    }
}
