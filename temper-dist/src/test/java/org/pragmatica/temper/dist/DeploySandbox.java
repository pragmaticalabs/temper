package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A hermetic setting for `ops/deploy/lib.sh` through the toy unit (`src/test/resources/deploy/deploy-toy.sh`): a
/// HOME of its own, shims first on PATH (`systemctl`, `docker`, `curl`, `bb-temper`, ... each logging its argv to
/// `$HOME/shim.log`), a JDK HttpServer that decides the toy's health by the sha256 of the file live in HOME, a
/// fixture git repository with a bare origin for `stage`, and the shrunken health timers of the deploy-script tests. The toy has one live file, `$HOME/.toy/dist/toy.bin`, so its set id is that file's sha256.
final class DeploySandbox implements AutoCloseable {
    static final Path OPS_DEPLOY = Path.of("..", "ops", "deploy").toAbsolutePath().normalize();
    static final Path RESOURCES = Path.of("src", "test", "resources", "deploy").toAbsolutePath();
    static final String UNIT = "toy";
    static final String TOKEN = "toy-token-9f3a7c51d2";
    private static final List<String> SHIMS = List.of("systemctl", "docker", "java", "launchctl", "curl", "bb-temper");

    record Outcome(int rc, String out, String err) {}

    final Path root;
    final Path home;
    final Path bin;
    final Map<String, String> env = new HashMap<>();
    private final Map<String, String> behaviours = new ConcurrentHashMap<>();
    private final HttpServer server;

    DeploySandbox(Path dir) throws IOException {
        root = dir.toRealPath();
        home = Files.createDirectories(root.resolve("home"));
        bin = Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(home.resolve("shim"));
        for (var shim : SHIMS) {
            var copy = Files.copy(RESOURCES.resolve("shims").resolve(shim), bin.resolve(shim));
            Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.writeString(Files.createDirectories(home.resolve(".toy")).resolve("token"), TOKEN + "\n");
        Files.writeString(home.resolve("shim/schema"), "4\n");
        TestHosts.write(home);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        env.put("HOME", home.toString());
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("TMPDIR", root.resolve("tmp").toString());
        env.put("DL_HEALTH_TIMEOUT", "6");
        env.put("DL_HEALTH_INTERVAL", "1");
        env.put("DL_SETTLE", "1");
        env.put("DL_TOY_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        env.put("DL_REMOTE", "bash -c");
        env.put("DL_BUILD_REMOTE", "bash -c");
        env.put("DL_HOSTNAME", "bigboy");
        env.put("DL_FETCH_SLEEP", "0");
        env.put("DL_NOW", "2026-11-05T09:12:44Z");
        env.put("BB_OWNER", "t");
        env.put("DL_BB_TEMPER", bin.resolve("bb-temper").toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "t");
        env.put("GIT_AUTHOR_EMAIL", "t@t");
        env.put("GIT_COMMITTER_NAME", "t");
        env.put("GIT_COMMITTER_EMAIL", "t@t");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------------------------------------- the stub

    /// How the toy's health answers while the file with this sha256 (or `absent`) is live: `ok` (the default),
    /// `500`, `wrong-version`, `fixed:<version>`, `500+rmbak` (answers 500 and deletes the backups, as a disk that
    /// loses them between the swap and the rollback).
    void behave(String sha, String behaviour) {
        behaviours.put(sha, behaviour);
    }

    int get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(env.get("DL_TOY_URL") + path))
                                 .header("Authorization", "Bearer " + TOKEN).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void answer(HttpExchange exchange) throws IOException {
        var live = liveFile();
        var sha = Files.isRegularFile(live) ? sha256(Files.readAllBytes(live)) : "absent";
        var behaviour = behaviours.getOrDefault(sha, "ok");
        var path = exchange.getRequestURI().getPath();
        var status = 200;
        var version = sha.substring(0, Math.min(12, sha.length()));
        if (behaviour.equals("500") || behaviour.equals("500+rmbak")) {
            status = 500;
            if (behaviour.endsWith("rmbak")) {
                try (Stream<Path> files = Files.list(live.getParent())) {
                    for (var file : files.filter(f -> f.getFileName().toString().contains(".bak-")).toList()) {
                        Files.delete(file);
                    }
                }
            }
        } else if (behaviour.equals("wrong-version")) {
            version = "deadbeef0000";
        } else if (behaviour.startsWith("fixed:")) {
            version = behaviour.substring("fixed:".length());
        }
        if (path.equals("/hosts") && !("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            status = 401;
        }
        exchange.getResponseHeaders().add("Toy-Version", version);
        var body = "ok\n".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------------------------------- fixtures

    Path liveFile() {
        return home.resolve(".toy/dist/toy.bin");
    }

    /// The toy's live file with these bytes; returns its sha256.
    String live(String content) throws IOException {
        Files.createDirectories(liveFile().getParent());
        Files.writeString(liveFile(), content);
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    Path stageDir(String commit) {
        return home.resolve(".deploy/stage").resolve(UNIT).resolve(commit);
    }

    /// A stage dir as `stage` leaves it: the scripts of `ops/deploy`/the toy copied in, then `stage-local` run on a
    /// fixture source holding `toy.bin`. Returns its set id (the file's sha256).
    String stage(String commit, String content) throws Exception {
        var into = Files.createDirectories(stageDir(commit));
        for (var script : List.of(OPS_DEPLOY.resolve("lib.sh"), RESOURCES.resolve("deploy-toy.sh"))) {
            var copy = Files.copy(script, into.resolve(script.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        var src = Files.createDirectories(root.resolve("src-" + commit));
        Files.writeString(src.resolve("toy.bin"), content);
        var outcome = run(into.resolve("deploy-toy.sh"), root, "stage-local", "--commit", commit, "--src", src.toString(), "--into", into.toString());
        assertThat(outcome.rc()).as(outcome.out() + outcome.err()).isZero();
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    /// A git repository with a bare origin: `main` holds `ops/deploy/{lib.sh,deploy-toy.sh}` and `toy.bin`; the
    /// returned commits are main's head and (pushed to `side` only) one that never landed.
    record Repo(Path work, String main, String side) {}

    Repo repo() throws Exception {
        var origin = root.resolve("origin.git");
        var work = root.resolve("work-tree");
        git(root, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(root, "clone", "-q", origin.toString(), work.toString());
        var ops = Files.createDirectories(work.resolve("ops/deploy"));
        Files.copy(OPS_DEPLOY.resolve("lib.sh"), ops.resolve("lib.sh"));
        Files.copy(RESOURCES.resolve("deploy-toy.sh"), ops.resolve("deploy-toy.sh"));
        Files.writeString(work.resolve("toy.bin"), "toy build one\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "chore: main");
        git(work, "push", "-q", "origin", "HEAD:main");
        var main = git(work, "rev-parse", "HEAD");
        git(work, "checkout", "-q", "-b", "side");
        Files.writeString(work.resolve("toy.bin"), "toy build two\n");
        git(work, "commit", "-q", "-am", "chore: side");
        git(work, "push", "-q", "origin", "side");
        var side = git(work, "rev-parse", "HEAD");
        git(work, "checkout", "-q", "main");
        return new Repo(work, main, side);
    }

    String git(Path in, String... argv) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(argv));
        var outcome = ran(command, in);
        assertThat(outcome.rc()).as(String.join(" ", command) + ": " + outcome.err()).isZero();
        return outcome.out().strip();
    }

    // ------------------------------------------------------------------------------------------------- runs

    Outcome run(Path script, Path in, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(List.of(args));
        return ran(command, in);
    }

    /// Hermetic child environment: the host's families are stripped (HermeticProcess.prepared), then only the sandbox's `env` is added.
    ProcessBuilder prepared(ProcessBuilder builder) {
        return HermeticProcess.prepared(builder, env);
    }

    Outcome ran(List<String> command, Path in) throws Exception {
        var out = Files.createTempFile(root, "out", ".txt");
        var err = Files.createTempFile(root, "err", ".txt");
        var builder = prepared(HermeticProcess.builder(command).directory(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()));
        var process = builder.start();
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("finished: " + command).isTrue();
        return new Outcome(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    /// `install --dry-run` of the staged copy of `commit`.
    Outcome dryRun(String commit, String expectCurrent, String expectNew, String... flags) throws Exception {
        var args = new ArrayList<>(List.of("install", "--commit", commit, "--expect-current", expectCurrent, "--expect-new", expectNew, "--dry-run"));
        args.addAll(List.of(flags));
        return run(stageDir(commit).resolve("deploy-toy.sh"), root, args.toArray(String[]::new));
    }

    static String planSha(Outcome dry) {
        return dry.out().lines().filter(l -> l.startsWith("plan-sha256 ")).findFirst().orElseThrow().substring("plan-sha256 ".length());
    }

    /// The dry-run's plan digest, then the install that passes it.
    Outcome install(String commit, String expectCurrent, String expectNew) throws Exception {
        var dry = dryRun(commit, expectCurrent, expectNew);
        assertThat(dry.rc()).as(dry.err()).isZero();
        return installWithPlan(commit, expectCurrent, expectNew, planSha(dry));
    }

    Outcome installWithPlan(String commit, String expectCurrent, String expectNew, String plan) throws Exception {
        return run(stageDir(commit).resolve("deploy-toy.sh"), root, "install", "--commit", commit, "--expect-current", expectCurrent,
                   "--expect-new", expectNew, "--expect-plan", plan);
    }

    String shimLog() throws IOException {
        var log = home.resolve("shim.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    List<String> deploysLog() throws IOException {
        var log = home.resolve(".deploy/deploys.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    /// Every path under HOME (not the shim state and log) mapped to a sha256 and a mode: directories included.
    Map<String, String> snapshot() throws IOException {
        var map = new TreeMap<String, String>();
        try (Stream<Path> walk = Files.walk(home)) {
            for (var path : walk.toList()) {
                var rel = home.relativize(path).toString();
                if (rel.isEmpty() || rel.equals("shim.log") || rel.equals("shim") || rel.startsWith("shim/")) {
                    continue;
                }
                var mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
                map.put(rel, Files.isDirectory(path) ? "dir " + mode : sha256(Files.readAllBytes(path)) + " " + mode);
            }
        }
        return map;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }
}
