package org.pragmatica.kiln.service.scan;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.scan.ScanLine;
import org.pragmatica.kiln.core.scan.ScanLine.CheckoutLine;
import org.pragmatica.kiln.core.scan.ScanLine.NonGitLine;
import org.pragmatica.kiln.core.scan.ScanLine.StubLine;
import org.pragmatica.kiln.core.scan.ScanLine.Trailer;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.scan.ScanOutcome.Complete;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// Fixtures for the `kiln-scan` ITs: real git repositories in a `@TempDir` home, and the real script
/// (`ops/kiln/kiln-scan`, as `DeployJarsIT` runs `ops/deploy-jars.sh`) run as the forced command runs it, through
/// [LocalScanTransport]. Fixture git runs with no system or global config and a fixed identity.
final class ScanFixture {
    static final Path SCRIPT = Path.of("..", "ops", "kiln", "kiln-scan").toAbsolutePath().normalize();
    static final TimeSpan TIMEOUT = TimeSpan.timeSpan(120).seconds();
    static final String MAVEN_MAIN = "org.codehaus.plexus.classworlds.launcher.Launcher";
    static final Map<String, String> GIT_ENV = Map.of("GIT_CONFIG_NOSYSTEM", "1",
                                                              "GIT_CONFIG_GLOBAL", "/dev/null",
                                                              "GIT_AUTHOR_NAME", "kiln",
                                                              "GIT_AUTHOR_EMAIL", "kiln@example.org",
                                                              "GIT_COMMITTER_NAME", "kiln",
                                                              "GIT_COMMITTER_EMAIL", "kiln@example.org");

    private ScanFixture() {}

    record Ran(int rc, String stdout, String stderr) {}

    /// Runs `argv` in `dir` with this JVM's environment plus `env`, feeding `stdin`.
    static Ran run(Path dir, Map<String, String> env, String stdin, List<String> argv) {
        var builder = HermeticProcess.builder(argv).directory(dir.toFile());
        builder.environment().putAll(env);
        return ran(builder, stdin);
    }

    /// Runs `builder` as configured, feeding `stdin` while both streams drain. The drains run on their own virtual
    /// threads, never on the common pool: on a 2-CPU host that pool has one worker, and a test holding it (as the commit
    /// loop of `KilnScanReadOnlyIT` did) would leave the drains, and this call, waiting forever (PR #59 CI). Every wait
    /// is bounded, and a late one names the command.
    static Ran ran(ProcessBuilder builder, String stdin) {
        var drains = Executors.newVirtualThreadPerTaskExecutor();

        try {
            var process = builder.start();
            var stdout = drains.submit(() -> readAll(process.getInputStream()));
            var stderr = drains.submit(() -> readAll(process.getErrorStream()));
            fed(process, stdin);
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("%s finished", builder.command()).isTrue();
            return new Ran(process.exitValue(), stdout.get(30, TimeUnit.SECONDS), stderr.get(30, TimeUnit.SECONDS));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(builder.command() + ": output not drained", e);
        } finally {
            // never waits: a drain held by a background child's pipe is left behind, not waited for
            drains.shutdownNow();
        }
    }

    /// Feeds `stdin` and closes it. A process may exit before it reads stdin (a script refusing its request at once), so a
    /// broken pipe here is expected and ignored; the caller judges the exit code and the output.
    private static void fed(Process process, String stdin) {
        try (var in = process.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException brokenPipe) {
            // The process stopped reading; its exit code says why.
        }
    }

    private static String readAll(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// `git <args>` in `dir`, which must succeed; its stdout without the trailing newline.
    static String git(Path dir, String... args) {
        var ran = gitRan(dir, args);
        assertThat(ran.rc()).as("git %s: %s", String.join(" ", args), ran.stderr()).isZero();
        return ran.stdout().strip();
    }

    /// `git <args>` in `dir`, whatever its outcome.
    static Ran gitRan(Path dir, String... args) {
        return run(dir, GIT_ENV, "", Stream.concat(Stream.of("git"), Arrays.stream(args)).toList());
    }

    /// A repository on `main` with one commit of `f` and a `.gitignore` ignoring `target/`.
    static Path repo(Path dir) throws IOException {
        Files.createDirectories(dir);
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("f"), "one\n");
        Files.writeString(dir.resolve(".gitignore"), "target/\n");
        git(dir, "add", "f", ".gitignore");
        git(dir, "commit", "-q", "-m", "one");
        return dir;
    }

    static String commit(Path repo, String file, String content) throws IOException {
        Files.writeString(repo.resolve(file), content);
        git(repo, "add", file);
        git(repo, "commit", "-q", "-m", file + " " + content.strip());
        return git(repo, "rev-parse", "HEAD");
    }

    /// An executable shell script.
    static Path script(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /// The script run as the forced command runs it (`request` in `SSH_ORIGINAL_COMMAND`, an empty stdin, an environment
    /// of `PATH`, `USER`, `HOME` and then `env`) from `cwd`, whatever its outcome: for the exit codes and the lines a
    /// failing call still writes, which [LocalScanTransport] turns into a failure.
    static Ran forced(Path home, Path cwd, Map<String, String> env, String request) {
        var builder = HermeticProcess.builder(List.of("bash", SCRIPT.toString())).directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", System.getenv("PATH"), "USER", System.getProperty("user.name")));
        builder.environment().putAll(env);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("SSH_ORIGINAL_COMMAND", request);
        return ran(builder, "");
    }

    static LocalScanTransport transport(Path home) {
        return transport(home, Map.of());
    }

    static LocalScanTransport transport(Path home, Map<String, String> environment) {
        return LocalScanTransport.localScanTransport(SCRIPT, home, environment, TIMEOUT);
    }

    static List<String> scanned(LocalScanTransport transport, List<String> roots, List<CheckoutPath> full) {
        return transport.scan(roots, full)
                        .await()
                        .unwrap();
    }

    static Complete complete(List<String> lines) {
        var outcome = ScanOutcome.of(lines);
        assertThat(outcome).as(String.join("\n", lines)).isInstanceOf(Complete.class);
        return (Complete) outcome;
    }

    /// The rows of a complete scan by path.
    static Map<String, ScanLine> rows(Complete complete) {
        var rows = new LinkedHashMap<String, ScanLine>();
        complete.rows().forEach(row -> rows.put(pathOf(row), row));
        assertThat(rows).as("one row per path").hasSize(complete.rows().size());
        return rows;
    }

    static String pathOf(ScanLine row) {
        return switch (row) {
            case CheckoutLine checkout -> checkout.facts().path().value();
            case StubLine stub -> stub.path().value();
            case NonGitLine nonGit -> nonGit.path().value();
            case Trailer _ -> throw new IllegalArgumentException("a trailer is no row");
        };
    }

    static CheckoutFacts facts(Map<String, ScanLine> rows, Path checkout) throws IOException {
        assertThat(rows).containsKey(real(checkout));
        assertThat(rows.get(real(checkout))).isInstanceOf(CheckoutLine.class);
        return ((CheckoutLine) rows.get(real(checkout))).facts();
    }

    static String real(Path path) throws IOException {
        return path.toRealPath().toString();
    }

    static CheckoutPath checkoutPath(Path path) throws IOException {
        return CheckoutPath.checkoutPath(real(path)).unwrap();
    }

    static Sha sha(String value) {
        return Sha.sha(value).unwrap();
    }

    static BranchTip tip(String name, String sha) {
        return new BranchTip(BranchName.branchName(name).unwrap(), sha(sha));
    }

    /// The facts the scan must report for `checkout`: the git-derived values read here with git itself, the rest given.
    static CheckoutFacts expected(Path checkout,
                                  Option<String> branch,
                                  Option<String> remote,
                                  List<BranchTip> branches,
                                  int modified,
                                  int untracked,
                                  Option<String> op,
                                  int stash,
                                  int salvageRefs) throws IOException {
        var head = gitRan(checkout, "rev-parse", "-q", "--verify", "HEAD^{commit}").stdout().strip();
        return new CheckoutFacts(checkoutPath(checkout),
                                 head.isEmpty() ? Option.none() : Option.some(sha(head)),
                                 branch.map(name -> BranchName.branchName(name).unwrap()),
                                 remote,
                                 branches,
                                 modified,
                                 untracked,
                                 op,
                                 Instant.ofEpochSecond(activity(checkout)),
                                 List.of(),
                                 false,
                                 real(Path.of(git(checkout, "rev-parse", "--path-format=absolute", "--git-common-dir"))),
                                 stash,
                                 Option.some(salvageRefs));
    }

    /// The newest mtime, in whole epoch seconds, of the checkout's git dir `HEAD`, `index` and `logs/HEAD`.
    static long activity(Path checkout) throws IOException {
        var gitDir = Path.of(git(checkout, "rev-parse", "--absolute-git-dir"));
        long newest = 0;
        for (var name : List.of("HEAD", "index", "logs/HEAD")) {
            var file = gitDir.resolve(name);
            if (Files.exists(file)) {
                newest = Math.max(newest, Files.getLastModifiedTime(file).to(TimeUnit.SECONDS));
            }
        }
        return newest;
    }

    /// Every file under `dir` (relative path → sha256 and mtime in nanoseconds), and every directory.
    static Map<String, String> snapshot(Path dir) throws IOException {
        var snapshot = new TreeMap<String, String>();
        try (var walk = Files.walk(dir)) {
            for (var path : walk.toList()) {
                var key = dir.relativize(path).toString();
                snapshot.put(key,
                             Files.isRegularFile(path)
                             ? sha256(Files.readAllBytes(path)) + "@" + Files.getLastModifiedTime(path).toInstant()
                             : "dir");
            }
        }
        return snapshot;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// This JVM's environment with `PATH` led by `bin`.
    static Map<String, String> pathFirst(Path bin) {
        var env = new HashMap<String, String>();
        env.put("PATH", bin + ":" + System.getenv("PATH"));
        return env;
    }

    /// The first `name` on this JVM's `PATH`.
    static Path onPath(String name) {
        return Arrays.stream(System.getenv("PATH").split(":"))
                     .map(dir -> Path.of(dir, name))
                     .filter(Files::isExecutable)
                     .findFirst()
                     .orElseThrow();
    }

    static boolean linux() {
        return System.getProperty("os.name").equals("Linux");
    }
}
