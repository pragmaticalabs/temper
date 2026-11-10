package org.pragmatica.temper.worker.host;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.worker.WorkerError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// One worker per host (m0-design §7.2): a lock held by another process makes the next worker wait, then fail
/// with `LockTimeout` (exit 75); a released lock is taken.
class HostLockTest {
    private static final String HOLDER = """
        import java.nio.channels.FileChannel;
        import java.nio.file.*;
        public class Hold {
            public static void main(String[] args) throws Exception {
                var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.lock();
                System.out.println("locked");
                System.out.flush();
                Thread.sleep(30_000);
                lock.release();
            }
        }
        """;

    @Test
    void hostLock_takesAFreeLock_andReleasesIt(@TempDir Path dir) {
        var lock = ok(HostLock.hostLock(dir.resolve("sub/worker.lock"), TimeSpan.timeSpan(1).seconds()));

        ok(lock.release());
        ok(ok(HostLock.hostLock(dir.resolve("sub/worker.lock"), TimeSpan.timeSpan(1).seconds())).release());
    }

    @Test
    void hostLock_timesOut_whileAnotherProcessHoldsIt(@TempDir Path dir) throws Exception {
        var source = Files.writeString(dir.resolve("Hold.java"), HOLDER);
        var lockFile = dir.resolve("worker.lock");
        var holder = HermeticProcess.builder(Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                        source.toString(),
                                        lockFile.toString()).start();

        try {
            var line = new String(holder.getInputStream()
                                        .readNBytes(6));

            assertThat(line).isEqualTo("locked");

            var started = System.nanoTime();
            var cause = failure(HostLock.hostLock(lockFile, TimeSpan.timeSpan(600).millis()));

            assertThat(cause).isInstanceOf(WorkerError.LockTimeout.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(500);
        } finally {
            holder.destroyForcibly()
                  .waitFor();
        }

        ok(ok(HostLock.hostLock(lockFile, TimeSpan.timeSpan(5).seconds())).release());
    }

    @Test
    void release_closesTheChannel(@TempDir Path dir) {
        var lock = ok(HostLock.hostLock(dir.resolve("worker.lock"), TimeSpan.timeSpan(1).seconds()));

        ok(lock.release());

        assertThat(lock.channel()
                       .isOpen()).isFalse();
    }
}
