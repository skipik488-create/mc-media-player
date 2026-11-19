package dev.skipik.mediaplayer.audio;

import dev.skipik.mediaplayer.util.Http;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Streams and decodes MP3 on a dedicated thread and writes PCM straight to a SourceDataLine.
 * The line buffer is small (250 ms) so pause/volume react instantly; network jitter is absorbed
 * by the prefetch buffer instead. Nothing here runs on the render or client tick thread.
 */
public final class AudioEngine {
    public interface Listener {
        void onFinished(long session);

        void onError(long session, Throwable error);
    }

    private static final int LINE_BUFFER_MS = 250;
    private static final int PREFETCH_BYTES = 768 * 1024;

    private final Listener listener;
    private final AtomicLong sessions = new AtomicLong();
    private volatile Worker worker;
    private volatile float volume = 0.7f;

    public AudioEngine(Listener listener) {
        this.listener = listener;
    }

    public synchronized long play(String url, long startMs, boolean startPaused) {
        stop();
        long id = sessions.incrementAndGet();
        Worker w = new Worker(id, url, Math.max(0L, startMs), startPaused);
        worker = w;
        Thread t = new Thread(w, "MediaPlayer-Audio-" + id);
        t.setDaemon(true);
        t.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
        t.start();
        return id;
    }

    public synchronized void stop() {
        Worker w = worker;
        worker = null;
        if (w != null) w.cancel();
    }

    public void setPaused(boolean paused) {
        Worker w = worker;
        if (w != null) w.setPaused(paused);
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
    }

    public long positionMs() {
        Worker w = worker;
        return w == null ? 0L : w.positionMs();
    }

    public boolean isBuffering() {
        Worker w = worker;
        return w != null && w.buffering;
    }

    private final class Worker implements Runnable {
        private final long session;
        private final String url;
        private final long startMs;
        private final Object lock = new Object();
        private volatile boolean cancelled;
        private volatile boolean paused;
        private volatile boolean buffering = true;
        private volatile SourceDataLine line;
        private volatile InputStream input;
        private volatile long baseMs;
        private volatile long finalPositionMs = -1;

        Worker(long session, String url, long startMs, boolean startPaused) {
            this.session = session;
            this.url = url;
            this.startMs = startMs;
            this.paused = startPaused;
            this.baseMs = startMs;
        }

        long positionMs() {
            long fin = finalPositionMs;
            if (fin >= 0) return fin;
            SourceDataLine l = line;
            if (l == null) return startMs;
            return baseMs + l.getMicrosecondPosition() / 1000L;
        }

        void setPaused(boolean p) {
            synchronized (lock) {
                paused = p;
                SourceDataLine l = line;
                if (l != null) {
                    if (p) l.stop();
                    else l.start();
                }
                lock.notifyAll();
            }
        }

        void cancel() {
            cancelled = true;
            synchronized (lock) {
                lock.notifyAll();
            }
            SourceDataLine l = line;
            if (l != null) {
                try {
                    l.stop();
                    l.flush();
                } catch (RuntimeException ignored) {
                }
            }
            closeQuietly(input);
        }

        @Override
        public void run() {
            Bitstream bitstream = null;
            try {
                HttpResponse<InputStream> resp = Http.CLIENT.send(
                    Http.request(url).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    closeQuietly(resp.body());
                    throw new IOException("HTTP " + resp.statusCode());
                }
                input = new PrefetchInputStream(resp.body(), PREFETCH_BYTES, "MediaPlayer-Net-" + session);
                if (cancelled) return;

                bitstream = new Bitstream(input);
                Decoder decoder = new Decoder();
                byte[] pcm = new byte[4608 * 2];
                double skippedMs = 0;

                while (!cancelled) {
                    Header header = bitstream.readFrame();
                    if (header == null) break;
                    double frameMs = header.ms_per_frame();
                    if (skippedMs + frameMs <= startMs) {
                        // Seeking: skip frames without decoding them.
                        skippedMs += frameMs;
                        bitstream.closeFrame();
                        continue;
                    }
                    SampleBuffer out = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    bitstream.closeFrame();
                    if (line == null) {
                        openLine(decoder.getOutputFrequency(), decoder.getOutputChannels(), (long) skippedMs);
                    }
                    int n = out.getBufferLength();
                    short[] samples = out.getBuffer();
                    if (pcm.length < n * 2) pcm = new byte[n * 2];
                    float v = volume;
                    float gain = v * v; // perceptual curve
                    for (int i = 0, j = 0; i < n; i++, j += 2) {
                        int s = (int) (samples[i] * gain);
                        pcm[j] = (byte) s;
                        pcm[j + 1] = (byte) (s >> 8);
                    }
                    awaitResume();
                    if (cancelled) break;
                    writeFully(pcm, n * 2);
                }
                SourceDataLine l = line;
                if (!cancelled && l != null) l.drain();
                if (!cancelled) listener.onFinished(session);
            } catch (Throwable t) {
                if (!cancelled) listener.onError(session, t);
            } finally {
                finalPositionMs = Math.max(0L, positionMsUnsafe());
                if (bitstream != null) {
                    try {
                        bitstream.close();
                    } catch (BitstreamException ignored) {
                    }
                }
                closeQuietly(input);
                SourceDataLine l = line;
                if (l != null) {
                    try {
                        l.close();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }

        private long positionMsUnsafe() {
            SourceDataLine l = line;
            try {
                return l == null ? startMs : baseMs + l.getMicrosecondPosition() / 1000L;
            } catch (RuntimeException e) {
                return startMs;
            }
        }

        private void openLine(int rate, int channels, long base) throws LineUnavailableException {
            AudioFormat fmt = new AudioFormat(rate, 16, channels, true, false);
            SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
            int frameBytes = channels * 2;
            int bytes = (int) ((long) rate * frameBytes * LINE_BUFFER_MS / 1000L);
            bytes -= bytes % frameBytes;
            l.open(fmt, Math.max(bytes, frameBytes * 1024));
            baseMs = base;
            synchronized (lock) {
                line = l;
                if (!paused && !cancelled) l.start();
            }
            buffering = false;
        }

        private void awaitResume() throws InterruptedException {
            synchronized (lock) {
                while (paused && !cancelled) lock.wait(250L);
            }
        }

        private void writeFully(byte[] buf, int len) throws InterruptedException {
            SourceDataLine l = line;
            int off = 0;
            while (off < len && !cancelled) {
                int written = l.write(buf, off, len - off);
                if (written <= 0) {
                    if (!l.isOpen()) return;
                    if (paused) awaitResume();
                    else Thread.sleep(2L);
                    continue;
                }
                off += written;
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
