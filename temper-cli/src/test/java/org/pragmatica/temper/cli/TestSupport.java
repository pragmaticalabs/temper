package org.pragmatica.temper.cli;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.temper.protocol.api.WsServerMessage;

/// Test helpers for the sub-packages (the fakes themselves stay package-private here).
public final class TestSupport {
    private TestSupport() {}

    public record Time(FakeTime fake) {
        public java.time.InstantSource clock() {
            return fake;
        }

        public Pacer pacer() {
            return fake;
        }

        public List<Duration> pauses() {
            return fake.pauses();
        }
    }

    public record Out(CapturedOutput stdout, CapturedOutput stderr) {
        public Console console() {
            return Console.console(stdout.printStream(), stderr.printStream());
        }

        public List<String> lines() {
            return stdout.lines();
        }

        public String errors() {
            return stderr.text();
        }
    }

    public static Time time() {
        return new Time(new FakeTime());
    }

    /// stdout that journals each flushed line and dies at the flush of line `killAfterLines`.
    public static Out journaled(int killAfterLines, Consumer<String> onFlush) {
        return new Out(CapturedOutput.journaled(killAfterLines, onFlush), CapturedOutput.capturing());
    }

    public static WsServerMessage.WsEvent finished(long seq) {
        return Fixtures.finished(seq, 4700 + seq, "PASSED");
    }
}
