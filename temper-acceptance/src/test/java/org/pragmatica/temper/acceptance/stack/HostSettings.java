package org.pragmatica.temper.acceptance.stack;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.BuildVersion;

/// How the host tier reaches the host it runs on (README.md "Host tier"), from system properties:
///
/// | Property | Default |
/// |---|---|
/// | `temper.host` | required: the explicit host flag; without it every host IT skips |
/// | `temper.host.address` | `localhost` (the key's `from=` allows only localhost) |
/// | `temper.host.user` | `user.name` |
/// | `temper.host.workerHome` | `~/temper-acceptance`: the worker's temper home is its `.temper`; refused if that is, or lies in, the live `~/.temper` |
/// | `temper.host.key` | `<workerHome>/controller_ed25519`: the acceptance key; refused if it is under `~/.temper` (the live key) |
/// | `temper.host.knownHosts` | `~/.temper/known_hosts` (read only) |
/// | `temper.version` | the build's version (the worker jar's own, D-6): `BuildVersion` of `project.version` and `temper.judge` |
///
/// The run never uses the host's live `~/.temper` (M1 WP7 verification, F1): the acceptance key's forced command sets
/// `TEMPER_WORKER_HOME=<workerHome>/.temper` (README.md "Host tier"), so the worker's mirror, jobs, seed and installed
/// jar are the acceptance home's. `HOME` stays the user's, so `~/tools` (bb-hold, bb-queue) is the host's own.
///
/// The tests run ON the host as the worker's user: they read `<workerHome>/.temper/jobs/...`, `~/tools/bb-queue` and
/// docker directly.
public record HostSettings(String name, String address, String user, Path key, Path knownHosts, String version, Path home, Path workerHome) {
    /// The job id of the routing probe (A2): 19 digits, the largest shape `worker-launch` accepts, far above the live ids
    /// and the acceptance offset ([AcceptanceStack#ACCEPTANCE_JOB_ID_OFFSET]), and created only in the acceptance home.
    static final String ROUTING_JOB = "9000000000000000000";
    static final String ROUTING_UNIT = "routing-probe";

    /// One ssh call through the key's forced command: the remote words, the file fed to stdin (if any), the stderr
    /// file; returns the exit code. A seam so the order of the setup calls is testable without a host.
    @FunctionalInterface
    interface Ssh {
        int call(List<String> remote, Option<Path> stdin, Path stderr) throws Exception;
    }

    public static HostSettings fromProperties() {
        var home = Path.of(System.getProperty("user.home"));
        var workerHome = Path.of(System.getProperty("temper.host.workerHome", home.resolve("temper-acceptance").toString())).toAbsolutePath().normalize();
        var key = Path.of(System.getProperty("temper.host.key", workerHome.resolve("controller_ed25519").toString()));
        refuseLive(home, workerHome, key);
        return new HostSettings(System.getProperty("temper.host"),
                                System.getProperty("temper.host.address", "localhost"),
                                System.getProperty("temper.host.user", System.getProperty("user.name")),
                                key,
                                Path.of(System.getProperty("temper.host.knownHosts", home.resolve(".temper/known_hosts").toString())),
                                System.getProperty("temper.version", buildVersion()),
                                home,
                                workerHome);
    }

    /// This build's version, as the worker jar derives its own (D-6): failsafe passes `project.version` and
    /// `temper.judge`, so the controller expects exactly the jar [#installWorker] installs.
    static String buildVersion() {
        return BuildVersion.buildVersion(System.getProperty("project.version"), System.getProperty("temper.judge"))
                           .unwrap();
    }

    /// A2, before anything reaches the host: the acceptance worker's `.temper` must not be, or lie inside, the live
    /// `~/.temper`, and the key must not be the live controller key or anything else under `~/.temper`. Both compare
    /// real paths, so a symbolic link to `~` or into `~/.temper` is refused as well. A key given as a literal `~` or
    /// `~/…` (the shell passes `-Dtemper.host.key=~/…` through unexpanded, and `ssh -i` expands it) is compared as the
    /// home path it names (F5). Any other leading `~` (`~user/…`, which `ssh -i` expands to that user's home) is
    /// refused outright (N5): give the key's absolute path.
    static void refuseLive(Path home, Path workerHome, Path key) {
        var text = key.toString();
        if (text.startsWith("~") && !text.equals("~") && !text.startsWith("~/")) {
            throw new AssertionError("temper.host.key " + key + ": only ~ and ~/ are read as the home; ssh -i expands ~user/ to that user's home,"
                                     + " which this check does not resolve. Give the key's absolute path");
        }
        var live = real(home.resolve(".temper"));
        if (real(workerHome.resolve(".temper")).startsWith(live)) {
            throw new AssertionError("temper.host.workerHome " + workerHome + " must not be the live home " + home + ": the run would use " + live);
        }
        if (real(expanded(home, key)).startsWith(live)) {
            throw new AssertionError("temper.host.key " + key + " is under the live " + live + ": the live controller key routes to the live home,"
                                     + " and its install would replace the live worker jar");
        }
    }

    /// `key` with a leading literal `~` (alone, or followed by `/`) replaced by `home`, as `ssh -i` reads it.
    static Path expanded(Path home, Path key) {
        var text = key.toString();
        if (text.equals("~")) {
            return home;
        }
        return text.startsWith("~/")
               ? home.resolve(text.substring(2))
               : key;
    }

    /// The real path of `path`: its longest existing ancestor resolved through every link, plus the rest.
    static Path real(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        var existing = absolute;
        while (existing.getParent() != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Installs THIS build's worker jar through the forced command's `install <ver> <sha256>` verb, so the run never
    /// meets an older jar of the same version (the controller deploys only when a session ends before `Hello`).
    ///
    /// BEFORE the install (A2), a read-only `evidence` call proves the key routes to the acceptance home: it asks for a
    /// sentinel unit directory created only under `<workerHome>/.temper/jobs`, which a key routing to the live home
    /// answers with 66. No path from `<workerHome>/.temper` down to the sentinel's `log.tail` may be a symbolic link,
    /// and the sentinel's real path must lie under the acceptance `.temper`, checked before anything is created: a `jobs` (F4), `jobs/<id>`, `units` (N4) or unit link into the live `~/.temper/jobs` would
    /// land the sentinel in the live home, where the live key would find it. After the install, it checks that the jar
    /// landed in the acceptance home.
    public void installWorker(Path jar) throws Exception {
        installWorker(jar, this::ssh);
    }

    void installWorker(Path jar, Ssh ssh) throws Exception {
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        provenRouting(jar, ssh);
        var err = Path.of(jar + ".install.err");
        if (ssh.call(List.of("install", version, sha), Option.some(jar), err) != 0) {
            throw new AssertionError("worker install failed: " + stderrOf(err));
        }
        var record = workerHome.resolve(".temper/worker-" + version + ".jar.sha256");
        if (!Files.isRegularFile(record) || !Files.readString(record).strip().equals(sha)) {
            throw new AssertionError("the jar was not installed in " + workerHome.resolve(".temper") + ": the key " + key
                                     + " is not the acceptance key whose forced command sets TEMPER_WORKER_HOME (README.md \"Host tier\")");
        }
    }

    private void provenRouting(Path jar, Ssh ssh) throws Exception {
        var job = workerHome.resolve(".temper/jobs").resolve(ROUTING_JOB);
        var unit = job.resolve("units").resolve(ROUTING_UNIT);
        var sentinel = unit.resolve("log.tail");
        refuseLinks(sentinel);
        Files.createDirectories(unit);
        try {
            Files.writeString(sentinel, "acceptance routing probe\n");
            var err = Path.of(jar + ".routing.err");
            var rc = ssh.call(List.of("evidence", ROUTING_JOB, ROUTING_UNIT, "small"), Option.none(), err);
            if (rc != 0) {
                throw new AssertionError("the key " + key + " does not route to " + workerHome.resolve(".temper") + " (evidence of the sentinel unit: rc "
                                         + rc + ", " + stderrOf(err) + "); nothing was installed (README.md \"Host tier\")");
            }
        } finally {
            try (var paths = Files.walk(job)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /// Refuses `sentinel` when it, or any directory above it up to and including `<workerHome>/.temper`, is a symbolic
    /// link, or when its real path is outside the acceptance `.temper` (N4).
    private void refuseLinks(Path sentinel) {
        var temper = workerHome.resolve(".temper");
        for (var path = sentinel; path.startsWith(temper); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) {
                throw new AssertionError("the routing sentinel's path " + path + " is a symbolic link: the sentinel could land outside "
                                         + temper + "; nothing was installed");
            }
        }
        if (!real(sentinel).startsWith(real(temper))) {
            throw new AssertionError("the routing sentinel " + sentinel + " resolves to " + real(sentinel) + ", outside " + real(temper)
                                     + "; nothing was installed");
        }
    }

    private static String stderrOf(Path err) throws IOException {
        return Files.exists(err)
               ? Files.readString(err).strip()
               : "no stderr";
    }

    private int ssh(List<String> remote, Option<Path> stdin, Path stderr) throws Exception {
        var argv = new ArrayList<>(List.of("ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                                           "-o", "UserKnownHostsFile=" + knownHosts, "-o", "IdentitiesOnly=yes",
                                           "-i", key.toString(), user + "@" + address));
        argv.addAll(remote);
        var builder = HermeticProcess.builder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                              .redirectError(stderr.toFile());
        stdin.onPresent(file -> builder.redirectInput(file.toFile()));
        var process = builder.start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }
}
