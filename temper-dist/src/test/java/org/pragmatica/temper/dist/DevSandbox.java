package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A hermetic setting for the `ops/dev` tools: a bare `origin`, a `main` checkout cloned from it holding copies of
/// `ops/dev` (the scripts resolve the repository from their own location), a worktree root, a HOME of its own (so no
/// colima socket or git config of the machine is seen) and a PATH of `bin/` (fakes, and a link to the real `git`)
/// then `sys/`: a link to every program of `/usr/bin:/bin:/usr/sbin:/sbin` except [#HIDDEN]. So a real `docker`, `gh`
/// or `mvn` is never run, wherever it is installed: a Linux CI runner has /usr/bin/docker and /usr/bin/gh.
final class DevSandbox {
    static final Path OPS_DEV = Path.of("..", "ops", "dev").toAbsolutePath().normalize();
    private static final List<String> HIDDEN = List.of("docker", "gh", "mvn", "mvnw");
    private static final List<String> SYSTEM = List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin");
    private static final List<String> TOOLS = List.of("lib.sh", "preflight", "wt-new", "wt-prune", "land-pr", "known-flakes", "required-checks",
                                                      "hooks/commit-msg", "hooks/pre-commit", "hooks/pre-push");

    record Outcome(int rc, String output) {}

    final Path dir;
    final Path bin;
    final Path home;
    final Path origin;
    final Path main;
    final Path root;
    final Map<String, String> env = new HashMap<>();

    DevSandbox(Path dir) throws IOException, InterruptedException {
        this.dir = dir.toRealPath();
        bin = Files.createDirectories(this.dir.resolve("bin"));
        home = Files.createDirectories(this.dir.resolve("home"));
        origin = this.dir.resolve("origin.git");
        main = this.dir.resolve("main");
        root = this.dir.resolve("wt");
        Files.createSymbolicLink(bin.resolve("git"), onPath("git"));
        var sys = Files.createDirectories(this.dir.resolve("sys"));
        linkSystemPrograms(sys);
        env.put("PATH", bin + ":" + sys);
        env.put("HOME", home.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "t");
        env.put("GIT_AUTHOR_EMAIL", "t@t");
        env.put("GIT_COMMITTER_NAME", "t");
        env.put("GIT_COMMITTER_EMAIL", "t@t");
        env.put("TEMPER_WT_ROOT", root.toString());
        env.put("TEMPER_MIN_FREE_GIB", "0");
        git(this.dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(this.dir, "clone", "-q", origin.toString(), main.toString());
        for (var tool : TOOLS) {
            var copy = Files.createDirectories(main.resolve("ops/dev").resolve(tool).getParent()).resolve(Path.of(tool).getFileName());
            Files.copy(OPS_DEV.resolve(tool), copy);
            Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.writeString(main.resolve(".gitignore"), ".m2-local/\ntarget/\n");
        Files.writeString(main.resolve("README"), "one\n");
        git(main, "add", ".");
        git(main, "commit", "-q", "-m", "chore: one");
        git(main, "push", "-q", "origin", "HEAD:main");
        git(main, "fetch", "-q", "origin");
    }

    /// A fake on PATH: `bin/<name>` running `body` under `#!/bin/sh`.
    Path fake(String name, String body) throws IOException {
        var fake = Files.writeString(bin.resolve(name), "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        return fake;
    }

    /// `docker info` answering like a running daemon, `docker context inspect` naming `$FAKE_CONTEXT_HOST`, and
    /// DOCKER_HOST set, so the CLI and Testcontainers both use it.
    void dockerUp() throws IOException {
        fake("docker", "case \"$1\" in context) echo \"$FAKE_CONTEXT_HOST\" ;; *) echo server=29.2.1 colima ;; esac");
        env.put("DOCKER_HOST", "unix://" + dir.resolve("docker.sock"));
    }

    /// `ops/dev/<tool> args...` in the main checkout.
    Outcome run(String tool, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("bash", main.resolve("ops/dev").resolve(tool).toString()));
        command.addAll(List.of(args));
        return ran(command, main);
    }

    /// Hermetic child environment: the host's families are stripped (HermeticProcess.prepared), then only the sandbox's `env` is added.
    ProcessBuilder prepared(ProcessBuilder builder) {
        return HermeticProcess.prepared(builder, env);
    }

    Outcome ran(List<String> command, Path in) throws IOException, InterruptedException {
        var builder = prepared(HermeticProcess.builder(command).directory(in.toFile()).redirectErrorStream(true));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("finished: " + command).isTrue();
        return new Outcome(process.exitValue(), output);
    }

    String git(Path in, String... argv) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(argv));
        var outcome = ran(command, in);
        assertThat(outcome.rc()).as(String.join(" ", command) + ": " + outcome.output()).isZero();
        return outcome.output().strip();
    }

    /// A Maven repository directory holding `marker` as its one artifact.
    Path m2(Path at, String marker) throws IOException {
        Files.writeString(Files.createDirectories(at.resolve("org/example/a/1")).resolve("a-1.pom"), marker);
        return at;
    }

    static List<Path> linked(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).filter(DevSandbox::multiplyLinked).toList();
        }
    }

    private static boolean multiplyLinked(Path file) {
        try {
            return (int) Files.getAttribute(file, "unix:nlink") > 1;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `sys/<name>` → the first `<dir>/<name>` of [#SYSTEM], for every executable name but the [#HIDDEN] ones.
    private static void linkSystemPrograms(Path sys) throws IOException {
        for (var system : SYSTEM) {
            var dir = Path.of(system);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> programs = Files.list(dir)) {
                for (var program : programs.toList()) {
                    var link = sys.resolve(program.getFileName().toString());
                    if (!HIDDEN.contains(link.getFileName().toString()) && Files.notExists(link, LinkOption.NOFOLLOW_LINKS)
                        && Files.isExecutable(program) && !Files.isDirectory(program)) {
                        Files.createSymbolicLink(link, program);
                    }
                }
            }
        }
    }

    private static Path onPath(String tool) {
        return Stream.of(System.getenv("PATH").split(":"))
                     .map(entry -> Path.of(entry, tool))
                     .filter(Files::isExecutable)
                     .findFirst()
                     .orElseThrow(() -> new IllegalStateException(tool + " is not on PATH"));
    }
}
