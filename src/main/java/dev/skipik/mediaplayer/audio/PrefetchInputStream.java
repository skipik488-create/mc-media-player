package dev.skipik.mediaplayer.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/**
 * Reads the network stream on its own thread into a ring buffer so short network stalls never
 * starve the decoder (and never touch the game threads).
 */
final class PrefetchInputStream extends InputStream {
    private final InputStream source;
    private final byte[] ring;
    private final byte[] single = new byte[1];
    private int readPos;
    private int writePos;
    private int count;
    private boolean eof;
    private IOException error;
    private volatile boolean closed;

    PrefetchInputStream(InputStream source, int capacity, String threadName) {
        this.source = source;
        this.ring = new byte[capacity];
        Thread t = new Thread(this::pump, threadName);
        t.setDaemon(true);
        t.start();
    }

    private void pump() {
        byte[] chunk = new byte[16 * 1024];
        try {
            while (!closed) {
                int n = source.read(chunk);
                if (n < 0) break;
                int off = 0;
                while (off < n) {
                    synchronized (this) {
                        while (count == ring.length && !closed) wait();
                        if (closed) return;
                        int space = Math.min(ring.length - count, n - off);
                        int first = Math.min(space, ring.length - writePos);
                        System.arraycopy(chunk, off, ring, writePos, first);
                        System.arraycopy(chunk, off + first, ring, 0, space - first);
                        writePos = (writePos + space) % ring.length;
                        count += space;
                        off += space;
                        notifyAll();
                    }
                }
            }
        } catch (IOException e) {
            synchronized (this) {
                error = e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                eof = true;
                notifyAll();
            }
        }
    }

    @Override
    public synchronized int read() throws IOException {
        int n = read(single, 0, 1);
        return n <= 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        try {
            while (count == 0 && !eof && !closed) wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
        if (closed) throw new IOException("Stream closed");
        if (count == 0) {
            if (error != null) throw error;
            return -1;
        }
        int n = Math.min(len, count);
        int first = Math.min(n, ring.length - readPos);
        System.arraycopy(ring, readPos, b, off, first);
        System.arraycopy(ring, 0, b, off + first, n - first);
        readPos = (readPos + n) % ring.length;
        count -= n;
        notifyAll();
        return n;
    }

    @Override
    public synchronized int available() {
        return count;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (this) {
            notifyAll();
        }
        try {
            source.close();
        } catch (IOException ignored) {
        }
    }
}
