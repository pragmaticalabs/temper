package org.pragmatica.temper.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// m0-design §2.6 adapter, against real POSIX processes (absolute paths: the isolated env has no PATH).
class ProcessRunnerTest {
    private static final ProcessRunner RUNNER = ProcessRunner.processRunner();
    private static final TimeSpan TEN_SECONDS = timeSpan(10).seconds();

    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    private static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected failure, got: " + value));
    }

    private static Command command(TimeSpan timeout, String... argv) {
        return ok(Command.command(List.of(argv), Map.of(), timeout));
    }

    @Test
    void run_capturesStdoutAndRc() {
        var completed = ok(RUNNER.run(command(TEN_SECONDS, "/bin/echo", "hello", "world"))
                                 .await());

        assertThat(completed.rc()).isZero();
        assertThat(completed.stdoutText()).isEqualTo("hello world\n");
    }

    @Test
    void run_reportsNonZeroAsCompleted_andRequireZeroFails() {
        var completed = ok(RUNNER.run(command(TEN_SECONDS, "/usr/bin/false"))
                                 .await());

        assertThat(completed.rc()).isNotZero();
        assertThat(failure(completed.requireZero())).isInstanceOfSatisfying(ExecError.NonZero.class,
                                                                            nonZero -> assertThat(nonZero.rc()).isEqualTo(completed.rc()));
    }

    @Test
    void run_destroysAndFailsTimedOut_whenTimeoutPasses() {
        var started = System.nanoTime();
        var result = RUNNER.run(command(timeSpan(300).millis(), "/bin/sleep", "30"))
                           .await();

        assertThat(failure(result)).isInstanceOf(ExecError.TimedOut.class);
        assertThat(System.nanoTime() - started).isLessThan(timeSpan(10).seconds()
                                                                       .nanos());
    }

    @Test
    void run_failsStartFailed_forMissingExecutable() {
        assertThat(failure(RUNNER.run(command(TEN_SECONDS, "/nonexistent/temper-binary"))
                                 .await())).isInstanceOfSatisfying(ExecError.StartFailed.class,
                                                                   startFailed -> assertThat(startFailed.source()
                                                                                                        .isPresent()).isTrue());
    }

    @Test
    void run_isolatesEnvironment_whenNotInheriting() {
        var completed = ok(RUNNER.run(ok(Command.command(List.of("/usr/bin/env"), Map.of("ONLY", "this"), TEN_SECONDS)))
                                 .await());

        assertThat(completed.stdoutText()
                            .strip()).isEqualTo("ONLY=this");
    }

    @Test
    void run_drainsLargeOutputWithoutBlocking() {
        var completed = ok(RUNNER.run(command(TEN_SECONDS, "/usr/bin/head", "-c", "3000000", "/dev/zero"))
                                 .await());

        assertThat(completed.stdout()).hasSize(3_000_000);
    }

    @Test
    void run_usesWorkingDirectory() {
        var completed = ok(RUNNER.run(command(TEN_SECONDS, "/bin/pwd").inDirectory(Path.of("/")))
                                 .await());

        assertThat(completed.stdoutText()
                            .strip()).isEqualTo("/");
    }

    /// Which outcome a held pipe gets is decided by the JDK at exit, by whether a drain is already reading (see
    /// JdkProcessRunner#collected): sh exits within a millisecond, so on Linux 5-15% of runs completed here while this
    /// test expected OutputNotCollected. The bound holds either way; JdkProcessRunnerTest pins the
    /// OutputNotCollected branch with a process whose output stays open after exit.
    @Test
    void run_returnsWithinGrace_whenBackgroundChildHoldsThePipe() {
        var started = System.nanoTime();
        var result = RUNNER.run(command(timeSpan(2).seconds(), "/bin/sh", "-c", "/bin/sleep 30 & exit 0"))
                           .await();

        result.onFailure(cause -> assertThat(cause).isInstanceOf(ExecError.OutputNotCollected.class));
        assertThat(System.nanoTime() - started).isLessThan(timeSpan(10).seconds()
                                                                       .nanos());
    }

    @Test
    void run_killsDescendants_onTimeout() throws Exception {
        var pidFile = Files.createTempFile("temper-grandchild", ".pid");
        var result = RUNNER.run(command(timeSpan(1).seconds(), "/bin/sh", "-c", "/bin/sleep 30 & echo $! > " + pidFile + "; wait"))
                           .await();
        var grandchild = Long.parseLong(Files.readString(pidFile)
                                             .strip());

        assertThat(failure(result)).isInstanceOf(ExecError.TimedOut.class);
        Thread.sleep(500);
        assertThat(ProcessHandle.of(grandchild)
                                .map(ProcessHandle::isAlive)
                                .orElse(false)).isFalse();
        Files.deleteIfExists(pidFile);
    }

    @Test
    void run_capsCapturedOutput_andMarksTruncated() {
        var completed = ok(RUNNER.run(command(TEN_SECONDS, "/usr/bin/head", "-c", "100000", "/dev/zero").withOutputLimit(1000))
                                 .await());

        assertThat(completed.stdout()).hasSize(1000);
        assertThat(completed.truncated()).isTrue();
        assertThat(completed.rc()).isZero();
    }

    @Test
    void requireZero_keepsStderrOutOfTheMessage() {
        var completed = Completed.completed("git", 128, new byte[0], "fatal: https://user:secret@host".getBytes(StandardCharsets.UTF_8), false);

        assertThat(failure(completed.requireZero())).isInstanceOfSatisfying(ExecError.NonZero.class, nonZero -> {
            assertThat(nonZero.message()).doesNotContain("secret");
            assertThat(nonZero.stderrTail()).contains("secret");
        });
    }

    @Test
    void command_refusesEmptyArgv() {
        assertThat(failure(Command.command(List.of(), Map.of(), TEN_SECONDS))).isEqualTo(ExecError.General.EMPTY_ARGV);
    }

    @Test
    void spawn_givesStreamsAndExitCode() throws IOException {
        var spawned = ok(RUNNER.spawn(command(TEN_SECONDS, "/bin/cat")));

        spawned.stdin()
               .write("ping".getBytes(StandardCharsets.UTF_8));
        spawned.stdin()
               .close();
        assertThat(new String(spawned.stdout()
                                     .readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("ping");
        assertThat(ok(spawned.exit()
                             .await())).isZero();
        assertThat(spawned.pid()).isPositive();
    }

    @Test
    void spawn_killEndsTheProcess() {
        var spawned = ok(RUNNER.spawn(command(TEN_SECONDS, "/bin/sleep", "30")));

        spawned.kill();
        assertThat(ok(spawned.exit()
                             .await(timeSpan(10).seconds()))).isNotZero();
    }

    /// m1-design §1.2 / §3.2: a redirected stream lands in its file, truncated first, and is not captured.
    @Test
    void run_redirectsStdoutAndStderrToFiles(@TempDir Path dir) throws IOException {
        var out = dir.resolve("m1wp1-out.bin");
        var err = dir.resolve("m1wp1-err.txt");

        Files.writeString(out, "stale content that must be truncated away");
        var completed = ok(RUNNER.run(command(TEN_SECONDS, "/bin/sh", "-c", "printf payload; printf oops >&2")
                                          .stdoutTo(out)
                                          .stderrTo(err))
                                 .await());

        assertThat(completed.rc()).isZero();
        assertThat(completed.stdout()).isEmpty();
        assertThat(completed.stderr()).isEmpty();
        assertThat(Files.readString(out)).isEqualTo("payload");
        assertThat(Files.readString(err)).isEqualTo("oops");
    }

    @Test
    void spawn_redirectsStderrToAFile_andKeepsStdoutCaptured(@TempDir Path dir) throws IOException {
        var err = dir.resolve("m1wp1-spawn-err.txt");
        var spawned = ok(RUNNER.spawn(command(TEN_SECONDS, "/bin/sh", "-c", "printf id; printf 'granted T1' >&2").stderrTo(err)));

        assertThat(new String(spawned.stdout()
                                     .readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("id");
        assertThat(ok(spawned.exit()
                             .await())).isZero();
        assertThat(Files.readString(err)).isEqualTo("granted T1");
    }

    @Test
    void completed_requireZero_passesRcZero() {
        assertThat(ok(Completed.completed("x", 0, new byte[0], new byte[0], false).requireZero()).rc()).isZero();
    }
}
