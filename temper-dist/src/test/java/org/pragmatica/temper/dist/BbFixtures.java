package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.pragmatica.temper.testsupport.HermeticProcess;

import static org.assertj.core.api.Assertions.assertThat;


/// Shared plumbing of the bb script ITs (T3 WP-B): the real scripts under `ops/bb`, a process runner, and the bytes of
/// the bigboy `bb-build` the first commit of `ops/bb/bb-build` imported, vendored verbatim.
final class BbFixtures {
    static final Path OPS_BB = Path.of("..", "ops", "bb").toAbsolutePath().normalize();
    static final String BB_BUILD_OLD = "999aa32577930aaf715ed4ce7c31fe02";
    static final String BB_LIB_OLD = "814400ff1d980be4b2da3e8e0ec62644";
    static final String BB_QUEUE_OLD = "7efbfbecb9739fed35223cdc97eb185d";

    record Outcome(int rc, String stdout, String stderr) {}

    private BbFixtures() {}

    static boolean linux() {
        return System.getProperty("os.name").startsWith("Linux");
    }

    static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String md5(Path file) throws IOException {
        return md5(Files.readAllBytes(file));
    }

    /// The bigboy bb-build as the first commit of `ops/bb/bb-build` imported it, vendored verbatim as the test resource
    /// `bb/bb-build.original` (no git history, no host: a shallow CI checkout has neither). Its md5 is checked, so a
    /// corrupted fixture fails here instead of passing against the wrong file.
    static byte[] originalBbBuild() throws IOException {
        try (var in = BbFixtures.class.getResourceAsStream("/bb/bb-build.original")) {
            assertThat(in).as("test resource bb/bb-build.original").isNotNull();
            var bytes = in.readAllBytes();

            assertThat(md5(bytes)).as("the vendored fixture holds the bigboy file verbatim").isEqualTo(BB_BUILD_OLD);

            return bytes;
        }
    }

    /// The bigboy bb-queue the listing tests compare against, vendored verbatim as `bb/bb-queue.original` (md5 checked).
    static byte[] originalBbQueue() throws IOException {
        try (var in = BbFixtures.class.getResourceAsStream("/bb/bb-queue.original")) {
            assertThat(in).as("test resource bb/bb-queue.original").isNotNull();
            var bytes = in.readAllBytes();

            assertThat(md5(bytes)).as("the vendored fixture holds the bigboy file verbatim").isEqualTo(BB_QUEUE_OLD);

            return bytes;
        }
    }

    static Path executable(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));

        return file;
    }

    static Outcome run(List<String> command, Map<String, String> env, Path cwd, String stdin) throws IOException, InterruptedException {
        // hermetic: a BB_* variable of the build host (bb-submit exports BB_SESSION_*, BB_AGENT, BB_LANE, BB_PR into the maven
        // run that hosts this test) must not reach the script under test; only what the test passes does
        var builder = HermeticProcess.builder(command, env);

        if (cwd != null) {
            builder.directory(cwd.toFile());
        }

        var process = builder.start();

        try (var in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        // the script may refuse before reading stdin
        }

        var stderrBytes = new byte[][]{new byte[0]};
        var reader = Thread.ofVirtual().start(() -> {
            try {
                stderrBytes[0] = process.getErrorStream()
                                        .readAllBytes();
            } catch (IOException ignored) {
            // process gone
            }
        });
        var stdout = new String(process.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8);

        reader.join();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();

        return new Outcome(process.exitValue(), stdout, new String(stderrBytes[0], StandardCharsets.UTF_8));
    }

    /// This JVM's pid. Tests that start real or stand-in waiters suffix their ticket ids with it: the queue finds waiters with
    /// `pgrep -u $USER -f "bb-build __run <id>"`, which sees every process of the uid on the host, so concurrent IT JVMs with fixed
    /// ids killed, reaped or counted each other's waiters (fl43, #43). Production ids (timestamp-pid) are unique already.
    static final String RUN = Long.toString(ProcessHandle.current().pid());

    /// pids of this user's processes whose argument text is `bb-build __run <id>` (the waiter's own test), recorded so
    /// the caller kills exactly them.
    static List<Long> waiters(String id) throws IOException, InterruptedException {
        var found = run(List.of("pgrep",
                                "-u",
                                System.getProperty("user.name"),
                                "-f",
                                "--",
                                "bb-build __run " + id + "( |$)"),
                        Map.of(),
                        null,
                        null);
        var pids = new ArrayList<Long>();

        found.stdout().lines().filter(line -> !line.isBlank()).forEach(line -> pids.add(Long.parseLong(line.strip())));

        return pids;
    }

    static void kill(List<Long> pids) {
        pids.forEach(pid -> ProcessHandle.of(pid).ifPresent(handle -> {
            handle.destroyForcibly();
            try {
                handle.onExit()
                      .get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            // gone or unkillable: the temp directory removal will say
            }
        }));
    }
}
