package org.pragmatica.temper.controller.fake;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Consumer;

/// An in-memory pipe whose ends may be used from any thread (JDK `PipedInputStream` breaks when its writer thread
/// ends, and the controller writes from short-lived virtual threads). Closing either end gives EOF to the reader.
public final class BytePipe {
    private final byte[] buffer = new byte[1 << 22];
    private int head;
    private int size;
    private boolean closed;
    /// Sees each write, on the writer's thread, before it is buffered (a check that must run at send time).
    private volatile Consumer<byte[]> tap = _ -> {};

    public final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            var one = new byte[1];
            var n = read(one, 0, 1);
            return n < 0
                   ? -1
                   : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            return take(target, offset, length);
        }

        @Override
        public void close() {
            shut();
        }
    };

    public final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            tap.accept(Arrays.copyOfRange(source, offset, offset + length));
            put(source, offset, length);
        }

        @Override
        public void close() {
            shut();
        }
    };

    private synchronized int take(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        while (size == 0 && !closed) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        if (size == 0) {
            return -1;
        }
        var n = Math.min(length, size);
        for (int i = 0; i < n; i++) {
            target[offset + i] = buffer[(head + i) % buffer.length];
        }
        head = (head + n) % buffer.length;
        size -= n;
        notifyAll();
        return n;
    }

    private synchronized void put(byte[] source, int offset, int length) throws IOException {
        for (int i = 0; i < length; i++) {
            while (size == buffer.length && !closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            if (closed) {
                throw new IOException("pipe closed");
            }
            buffer[(head + size) % buffer.length] = source[offset + i];
            size++;
        }
        notifyAll();
    }

    /// Replaces the write tap.
    public void tap(Consumer<byte[]> next) {
        tap = next;
    }

    public synchronized void shut() {
        closed = true;
        notifyAll();
    }
}
