package org.pragmatica.temper.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/// stdout for tests: records every byte written, and can play "killed after the N-th line": the N-th line's
/// bytes are recorded (the reader saw it) but its flush fails, exactly like a process that died between
/// printing and acknowledging. `onFlush` lets a test journal the moment a line was flushed.
///
/// Completed lines are counted as they are written, so a flush costs the same after 30 000 lines as after one
/// (re-splitting the whole buffer per flush made long-run tests quadratic).
final class CapturedOutput extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream current = new ByteArrayOutputStream();
    private int completed;
    private String lastCompleted = "";
    private final int killAfterLines;
    private final Consumer<String> onFlush;
    private volatile boolean dead;
    private int journaled;

    private CapturedOutput(int killAfterLines, Consumer<String> onFlush) {
        this.killAfterLines = killAfterLines;
        this.onFlush = onFlush;
    }

    static CapturedOutput capturing() {
        return new CapturedOutput(Integer.MAX_VALUE, _ -> {});
    }

    static CapturedOutput killedAfter(int lines) {
        return new CapturedOutput(lines, _ -> {});
    }

    static CapturedOutput journaled(int killAfterLines, Consumer<String> onFlush) {
        return new CapturedOutput(killAfterLines, onFlush);
    }

    PrintStream printStream() {
        return new PrintStream(this, false, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void write(int value) throws IOException {
        if (dead) {
            throw new IOException("stdout closed");
        }
        bytes.write(value);
        track(value);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
        if (dead) {
            throw new IOException("stdout closed");
        }
        bytes.write(buffer, offset, length);
        for (var index = offset; index < offset + length; index++) {
            track(buffer[index]);
        }
    }

    private void track(int value) {
        if (value == '\n') {
            completed++;
            lastCompleted = current.toString(StandardCharsets.UTF_8);
            current.reset();
        } else {
            current.write(value);
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        if (completed > journaled) {
            journaled = completed;
            onFlush.accept(lastCompleted);
        }
        if (dead || completed >= killAfterLines) {
            dead = true;
            throw new IOException("stdout closed");
        }
    }

    synchronized String text() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    synchronized List<String> lines() {
        return FakeController.lines(text());
    }
}
