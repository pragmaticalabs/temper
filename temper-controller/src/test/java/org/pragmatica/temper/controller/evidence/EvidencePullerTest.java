package org.pragmatica.temper.controller.evidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoredEvidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// `EvidencePullerTest` (m1-design §3.2, §9.5) over a scripted ssh process: the byte cap, the gzip magic, the exit
/// code and the atomic rename. A pull either publishes a verified tarball or leaves nothing behind.
class EvidencePullerTest {
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(7).unwrap(), UnitName.MAIN);
    private static final ControllerConfig.Host HOST = ControllerConfig.Host.host(HostName.hostName("bb").unwrap(),
                                                                                 "localhost",
                                                                                 "builder",
                                                                                 Path.of("/k"),
                                                                                 Path.of("/kh"),
                                                                                 5,
                                                                                 20480,
                                                                                 false);
    @TempDir
    Path root;
    private Scripted runner;

    @BeforeEach
    void setUp() {
        runner = new Scripted();
    }

    private SshEvidence puller(long smallCap, TimeSpan timeout) {
        return SshEvidence.sshEvidence(runner,
                                       ControllerConfig.Evidence.evidence(root, smallCap, 1L << 20, 0),
                                       timeout,
                                       timeSpan(10).millis());
    }

    private SshEvidence puller() {
        return puller(1L << 20, timeSpan(10).seconds());
    }

    private Result<StoredEvidence> pull(SshEvidence puller, EvidenceKind kind) {
        return puller.pull(HOST, REF, 2, kind)
                     .await(timeSpan(20).seconds());
    }

    private Path target(String file) {
        return root.resolve("jobs/7/units/main/a2/" + file);
    }

    @Test
    void pull_publishesTheVerifiedTarball_withSizeAndSha256() throws Exception {
        var tarball = gzip("evidence");
        runner.script = command -> runner.exits(command, tarball, 0);

        var stored = pull(puller(), EvidenceKind.SMALL).unwrap();

        assertThat(stored.path()).isEqualTo(target("evidence.tar.gz"));
        assertThat(stored.bytes()).isEqualTo(tarball.length);
        assertThat(stored.sha256()).isEqualTo(sha256(tarball));
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(tarball);
        assertThat(Files.list(stored.path().getParent())).containsExactly(stored.path());
        assertThat(runner.argv.getFirst()).startsWith("ssh", "-T")
                                          .endsWith("builder@localhost", "evidence", "7", "main", "small")
                                          .doesNotContain("sh", "-c");
    }

    @Test
    void pull_full_usesItsOwnFile() throws Exception {
        var tarball = gzip("everything");
        runner.script = command -> runner.exits(command, tarball, 0);

        assertThat(pull(puller(), EvidenceKind.FULL).unwrap().path()).isEqualTo(target("full.tar.gz"));
        assertThat(runner.argv.getFirst()).endsWith("full");
    }

    /// The exit code alone decides here: the bytes are a valid gzip stream, so only the rc check can refuse them.
    @Test
    void pull_fails_nonZeroExit_andLeavesNothing() throws Exception {
        var tarball = gzip("half");
        runner.script = command -> runner.exits(command, tarball, 1);

        var failed = pull(puller(), EvidenceKind.SMALL);

        assertThat(failed.isFailure()).isTrue();
        assertThat(cause(failed)).isInstanceOf(EvidenceError.Refused.class);
        assertThat(runner.argv).as("one retry").hasSize(2);
        assertThat(target("evidence.tar.gz")).doesNotExist();
        assertThat(Files.list(target("x").getParent())).isEmpty();
    }

    @Test
    void pull_isNotRetried_whenWorkerLaunchRefusesOrFindsNoDirectory() throws Exception {
        for (var rc : List.of(64, 66)) {
            runner = new Scripted();
            runner.script = command -> runner.exits(command, new byte[0], rc);

            var failed = pull(puller(), EvidenceKind.SMALL);

            assertThat(cause(failed)).isInstanceOf(EvidenceError.Refused.class);
            assertThat(runner.argv).as("rc " + rc).hasSize(1);
        }
    }

    @Test
    void pull_killsTheTransfer_pastTheCap() throws Exception {
        runner.script = command -> runner.streams(command, new byte[4096]);

        var failed = pull(puller(1024, timeSpan(10).seconds()), EvidenceKind.SMALL);

        assertThat(cause(failed)).isInstanceOf(EvidenceError.CapExceeded.class);
        assertThat(runner.killed.get()).isTrue();
        assertThat(runner.argv).as("a cap is terminal").hasSize(1);
        assertThat(Files.list(target("x").getParent())).isEmpty();
    }

    @Test
    void pull_refuses_contentWithoutTheGzipMagic() throws Exception {
        runner.script = command -> runner.exits(command, "plain text".getBytes(StandardCharsets.UTF_8), 0);

        var failed = pull(puller(), EvidenceKind.SMALL);

        assertThat(cause(failed)).isInstanceOf(EvidenceError.NotGzip.class);
        assertThat(target("evidence.tar.gz")).doesNotExist();
    }

    @Test
    void pull_killsAndFails_onTimeout() throws Exception {
        runner.script = command -> runner.streams(command, new byte[10]);

        var failed = pull(puller(1L << 20, timeSpan(300).millis()), EvidenceKind.SMALL);

        assertThat(cause(failed)).isInstanceOf(EvidenceError.TransferFailed.class);
        assertThat(runner.killed.get()).isTrue();
        assertThat(target("evidence.tar.gz")).doesNotExist();
    }

    @Test
    void pull_retriesOnce_afterATransientFailure() throws Exception {
        var tarball = gzip("second time");
        runner.script = command -> runner.argv.size() == 1
                                   ? runner.exits(command, new byte[0], 255)
                                   : runner.exits(command, tarball, 0);

        var stored = pull(puller(), EvidenceKind.SMALL).unwrap();

        assertThat(runner.argv).hasSize(2);
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(tarball);
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------
    private static Cause cause(Result<?> result) {
        return result.fold(cause -> cause, _ -> Causes.cause("expected a failure"));
    }

    static byte[] gzip(String text) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// A process runner whose `spawn` writes the scripted bytes to the command's stdout file.
    static final class Scripted implements ProcessRunner {
        final List<List<String>> argv = new CopyOnWriteArrayList<>();
        final AtomicBoolean killed = new AtomicBoolean();
        volatile Function<Command, Spawned> script;

        @Override
        public Promise<Completed> run(Command command) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            argv.add(command.argv());
            return Result.success(script.apply(command));
        }

        /// Writes `bytes` and exits `rc` at once.
        Spawned exits(Command command, byte[] bytes, int rc) {
            write(command, bytes);
            var process = new Process(killed);
            process.exit.succeed(rc);
            return process;
        }

        /// Writes `bytes` and keeps running until killed.
        Spawned streams(Command command, byte[] bytes) {
            write(command, bytes);
            return new Process(killed);
        }

        private static void write(Command command, byte[] bytes) {
            try {
                Files.write(command.stdoutFile().unwrap(), bytes);
                Files.writeString(command.stderrFile().unwrap(), "ssh: scripted\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static final class Process implements Spawned {
        final Promise<Integer> exit = Promise.promise();
        private final AtomicBoolean killed;

        Process(AtomicBoolean killed) {
            this.killed = killed;
        }

        @Override
        public InputStream stdout() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream stdin() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream stderr() {
            return InputStream.nullInputStream();
        }

        @Override
        public Promise<Integer> exit() {
            return exit;
        }

        @Override
        public Unit kill() {
            killed.set(true);
            exit.succeed(137);
            return unit();
        }

        @Override
        public long pid() {
            return 1;
        }
    }
}
