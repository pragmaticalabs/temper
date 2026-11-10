package org.pragmatica.temper.worker;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.frame.Envelope;
import org.pragmatica.temper.protocol.frame.FrameCodec;
import org.pragmatica.temper.protocol.frame.FrameReader;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.temper.worker.Fixtures.ok;
import static org.assertj.core.api.Assertions.assertThat;


/// stdout carries protocol frames only (m0-design §4.1, §7.2): every byte the worker process writes to stdout must
/// parse as frames. Two independent layers keep it so, and each is pinned on its own, because either one alone
/// masks the other's loss:
///
/// - `Main.main` rebinds `System.out` to stderr before anything runs: pinned with the real `Main` and logback's own
///   status output (`-Dlogback.debug=true`, printed to `System.out`), which no `logback.xml` controls;
/// - the shipped `logback.xml` appends to `System.err`: pinned through [StdoutHarness], which runs [Main#run]
///   without the rebinding and logs at every level.
@Timeout(90)
class MainStdoutTest {
    record Exited(int rc, byte[] stdout, String stderr) {}

    @TempDir
    Path scratch;

    @Test
    void main_writesNothingButFrames_onAStartupFailure_evenWhenLogbackPrintsItsStatus() throws Exception {
        var exited = java(List.of("-Dlogback.debug=true", Main.class.getName()));

        assertThat(exited.rc()).as(exited.stderr()).isEqualTo(Main.EXIT_START_FAILED);
        // The instrument: logback did print its status and the failure was logged, both onto stderr.
        assertThat(exited.stderr()).contains("worker not started: NO_HOME").contains("|-INFO in ch.qos.logback");
        assertThat(frames(exited.stdout())).isEmpty();
    }

    @Test
    void logging_atEveryLevel_neverReachesStdout_withoutTheRebinding() throws Exception {
        var exited = java(List.of(StdoutHarness.class.getName()));

        assertThat(exited.rc()).as(exited.stderr()).isEqualTo(Main.EXIT_START_FAILED);
        assertThat(exited.stderr()).contains("harness-trace",
                                             "harness-debug",
                                             "harness-info",
                                             "harness-warn",
                                             "harness-error",
                                             "worker not started: NO_HOME");
        assertThat(frames(exited.stdout())).extracting(Envelope::msg)
                  .containsExactly(Message.Failure.failure("first", "before logging"),
                                   Message.Failure.failure("second", "after the worker"));
    }

    /// Every byte of `stdout` as frames up to a clean EOF; a stray byte fails the length or the JSON decode.
    private static List<Envelope> frames(byte[] stdout) {
        var reader = FrameReader.frameReader(new ByteArrayInputStream(stdout));
        var envelopes = new ArrayList<Envelope>();

        for (Option<byte[]> frame = ok(reader.next()); frame.isPresent(); frame = ok(reader.next())) {
            frame.onPresent(payload -> envelopes.add(ok(FrameCodec.decode(payload, TemperJson.mapper()))));
        }

        return envelopes;
    }

    /// A java subprocess on this test's classpath with `HOME` removed, so the worker fails to start; `user.home` points
    /// at the scratch directory, so not even a fallback could reach the developer's own `~/.temper`.
    private Exited java(List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                              "-Duser.home=" + scratch,
                                              "-cp",
                                              System.getProperty("java.class.path")));

        command.addAll(arguments);
        var builder = HermeticProcess.builder(command);

        builder.environment().remove("HOME");
        var stderr = scratch.resolve("stderr-" + System.nanoTime());
        var process = builder.redirectError(stderr.toFile()).start();

        process.getOutputStream().close();
        var stdout = process.getInputStream().readAllBytes();

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        return new Exited(process.exitValue(), stdout, Files.readString(stderr));
    }
}
