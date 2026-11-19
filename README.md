# Media Player for Minecraft 26.2 (Fabric)

In-game music player with synced lyrics overlay. Built for performance.

## Download
Grab the latest `mediaplayer-*.jar` from [Releases](https://github.com/skipik488-create/mc-media-player/releases/latest).
Requires **Fabric Loader** and **Fabric API** for Minecraft **26.2** (Java 25).

## Features
- Music search and full-track streaming via the free **Audius API** (no key)
- Synced lyrics via the free **LRCLIB API** (LRC timestamps, karaoke highlight)
- Lyrics HUD you can toggle at any time, with position, scale, background opacity, line count, colors, timing offset
- Queue, shuffle, repeat (off / all / one), volume, seek +/-10s, cover art, "now playing" toast
- Settings saved to `config/mediaplayer.json`

## Keys (rebindable in Controls)
| Action | Default |
| --- | --- |
| Open player | M |
| Toggle lyrics overlay | J |
| Play / Pause | K |
| Next / Previous | ] / [ |

## Performance
- Network and MP3 decoding run on background threads; a prefetch ring buffer absorbs network jitter
- Audio goes straight to a `SourceDataLine` with a 250 ms buffer (instant pause and volume changes)
- Covers are downscaled to 128 px off-thread and uploaded at most 2 per tick, with an LRU texture cache
- Lyrics HUD: binary search for the current line, cached text widths, no per-frame allocations, skipped entirely when hidden or paused

Built automatically by GitHub Actions on every push.
