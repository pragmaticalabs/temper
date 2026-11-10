package org.pragmatica.temper.protocol.exec;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The drain grace of [JdkProcessRunner#complete], against a process whose output stays open after exit. A real
/// background child holding the pipe produces that only when a drain is already reading at exit, which no test can
/// arrange (see JdkProcessRunner#collected), so ProcessRunnerTest checks only the bound for it.
class JdkProcessRunnerTest {
    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    private static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected failure, got: " + value));
    }

    @Test
    void complete_failsOutputNotCollected_andClosesOutput_whenOutputStaysOpenAfterExit() {
        var process = new ExitedHoldingStdout();
        var command = ok(Command.command(List.of("/bin/sh"), Map.of(), timeSpan(300).millis()));
        var result = JdkProcessRunner.complete(command, process)
                                     .await();

        assertThat(failure(result)).isInstanceOfSatisfying(ExecError.OutputNotCollected.class,
                                                           notCollected -> assertThat(notCollected.source()
                                                                                                  .isPresent()).isTrue());
        assertThat(process.stdout.closed).succeedsWithin(Duration.ofSeconds(5));
    }

    /// Exited with rc 0, its stdout open until closed: what a background child holding the pipe looks like when the
    /// JDK has left the pipe in place.
    private static final class ExitedHoldingStdout extends Process {
        private final HeldOpen stdout = new HeldOpen();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Contract
        @Override
        public void destroy() {}
    }

    /// A stream whose read blocks until it is closed, then reports end of stream.
    private static final class HeldOpen extends InputStream {
        private final CompletableFuture<Unit> closed = new CompletableFuture<>();

        @Override
        public int read() {
            closed.join();

            return -1;
        }

        @Contract
        @Override
        public void close() {
            closed.complete(Unit.unit());
        }
    }
}
