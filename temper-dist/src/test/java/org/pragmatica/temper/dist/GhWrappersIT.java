package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// `ops/gh/` (§8 A.7-A.9) against a stub GitHub on loopback, in a fresh `HOME` into which
/// `ops/gh/install.sh` installs the wrappers, as it would on bigboy. Keys are generated per test and exist only below the
/// test's temp directory.
///
/// Probes (each a single mutation of the script): drop the `TEMPER_GH_WRAPPER` check in `gh-app-token` →
/// [#a7_gh_app_token_refusesWithoutTheMarker] red; carry the token in an argument of the wrapper's process →
/// [#a8_token_isInTheCommandsEnvironmentOnly] red; write it to `$TMPDIR` → the same test red; ship a `with-reviewer`
/// that reads the reviewer PAT → [#a9_noReviewerWrapper] red.
class GhWrappersIT {
    private static final Path OPS_GH = Path.of("..", "ops", "gh").toAbsolutePath().normalize();

    private static final String TOKEN = "ghs_" + HexFormat.of().formatHex(UUID.randomUUID()
                                                                              .toString()
                                                                              .getBytes(StandardCharsets.UTF_8));

    /// Tools the wrappers call and through which a secret could pass on an argv, even for a millisecond.
    private static final List<String> SHIMMED = List.of("curl", "env", "openssl", "sed", "tr", "cat");

    record Outcome(int rc, String out, String err) {}

    record Seen(String path, String authorization) {
        String jwt() {
            return authorization.substring("Bearer ".length());
        }
    }

    @TempDir
    Path dir;

    private Path home;
    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private KeyPair kilnKey;

    @BeforeEach
    void install() throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(home.resolve("tmp"));
        shims();
        kilnKey = account("kiln", "app", "app-key.pem", "Iv23kiln", 111);
        account("temper", "gh-app", "gh-app-key.pem", "Iv23temper", 222);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        var installed = run(List.of(OPS_GH.resolve("install.sh").toString()),
                            false);

        assertThat(installed.rc()).as(installed.err()).isZero();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /// `~/.config/<dir>/<ids>.ids` and the key beside it, mode 0600.
    private KeyPair account(String name, String ids, String keyName, String clientId, int installation) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");

        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var config = Files.createDirectories(home.resolve(".config").resolve(name));
        // the wrapper checks every directory up to HOME: do not leave them to the build host's umask (002 gives 775)
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(home.resolve(".config"), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));
        var pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64,
                                                                          "\n".getBytes())
                                                          .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        Files.writeString(config.resolve(ids + ".ids"),
                          "CLIENT_ID=" + clientId + "\nINSTALLATION_ID=" + installation + "\n");
        Files.writeString(config.resolve(keyName), pem);
        Files.setPosixFilePermissions(config.resolve(keyName), PosixFilePermissions.fromString("rw-------"));

        return pair;
    }

    private void answer(HttpExchange exchange) throws IOException {
        seen.add(new Seen(exchange.getRequestURI().getPath(),
                          exchange.getRequestHeaders().getFirst("Authorization")));
        var body = ("{\"token\":\"" + TOKEN + "\",\"expires_at\":\"2099-01-01T00:00:00Z\",\"permissions\":{}}").getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(201, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /// PATH shims, outside `HOME`: for each tool the wrappers call, a script that appends its own argv to `$SHIM_LOG`
    /// and execs the real binary. `/proc` sampling can miss a process that lives for milliseconds; the shim cannot.
    private void shims() throws IOException {
        var directory = Files.createDirectories(dir.resolve("shims"));

        for (var name : SHIMMED) {
            var real = realBinary(name);
            var script = "#!/bin/sh\n"
                       + "{ printf '%s' '" + name
                       + "'; for a in \"$@\"; do printf ' %s' \"$a\"; done; printf '\\n'; } >> \"$SHIM_LOG\"\n"
                       + "exec " + real
                       + " \"$@\"\n";
            var file = directory.resolve(name);

            Files.writeString(file, script);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static String realBinary(String name) {
        for (var entry : System.getenv("PATH").split(":")) {
            var candidate = Path.of(entry, name);

            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        throw new IllegalStateException("no " + name + " on PATH");
    }

    private Path shimLog() {
        return dir.resolve("argv.log");
    }

    private ProcessBuilder command(List<String> argv, boolean marker) {
        var builder = HermeticProcess.builder(argv);
        var env = builder.environment();

        env.clear();
        env.put("PATH",
                dir.resolve("shims") + ":" + System.getenv("PATH"));
        env.put("SHIM_LOG", shimLog().toString());
        env.put("HOME", home.toString());
        env.put("TMPDIR",
                home.resolve("tmp").toString());
        env.put("TEMPER_GH_API",
                "http://127.0.0.1:" + server.getAddress().getPort());
        if (marker) {
            env.put("TEMPER_GH_WRAPPER", "1");
        }

        return builder;
    }

    private Outcome run(List<String> argv, boolean marker) throws Exception {
        var process = command(argv, marker).start();
        var out = process.getInputStream().readAllBytes();
        var err = process.getErrorStream().readAllBytes();

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        return new Outcome(process.exitValue(),
                           new String(out, StandardCharsets.UTF_8),
                           new String(err, StandardCharsets.UTF_8));
    }

    private Path libexec() {
        return home.resolve(".local/libexec/temper-gh/gh-app-token");
    }

    private Path bin(String name) {
        return home.resolve(".local/bin")
                   .resolve(name);
    }

    private static boolean verifies(String jwt, KeyPair pair) throws Exception {
        var parts = jwt.split("\\.");
        var signature = Signature.getInstance("SHA256withRSA");

        signature.initVerify(pair.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

        return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    // A.7. Mutation: drop the marker check.
    @Test
    void a7_gh_app_token_refusesWithoutTheMarker() throws Exception {
        var ids = home.resolve(".config/kiln/app.ids").toString();
        var key = home.resolve(".config/kiln/app-key.pem").toString();
        var refused = run(List.of(libexec().toString(), ids, key),
                          false);

        assertThat(refused.rc()).isEqualTo(64);
        assertThat(refused.out()).isEmpty();
        assertThat(seen).isEmpty();
        // control: the same call with the marker mints
        var minted = run(List.of(libexec().toString(), ids, key),
                         true);

        assertThat(minted.rc()).as(minted.err()).isZero();
        assertThat(minted.out().strip()).isEqualTo(TOKEN);
    }

    // A.7, second half. Mutation: none needed beyond the first; this is the wrapper's positive control.
    @Test
    void a7_withKilnApp_mints_forTheKilnApp_andWithTemperApp_forTheControllerApp() throws Exception {
        var kiln = run(List.of(bin("with-kiln-app").toString(), "printenv", "GH_TOKEN"),
                       false);

        assertThat(kiln.rc()).as(kiln.err()).isZero();
        assertThat(kiln.out().strip()).isEqualTo(TOKEN);
        var temper = run(List.of(bin("with-temper-app").toString(), "printenv", "GH_TOKEN"),
                         false);

        assertThat(temper.rc()).as(temper.err()).isZero();
        assertThat(temper.out().strip()).isEqualTo(TOKEN);
        assertThat(seen).extracting(Seen::path)
                  .containsExactly("/app/installations/111/access_tokens", "/app/installations/222/access_tokens");
        assertThat(verifies(seen.get(0).jwt(),
                            kilnKey)).isTrue();
        assertThat(new String(Base64.getUrlDecoder().decode(seen.get(0).jwt().split("\\.") [1]),
                              StandardCharsets.UTF_8)).contains("\"iss\":\"Iv23kiln\"");
        assertThat(new String(Base64.getUrlDecoder().decode(seen.get(1).jwt().split("\\.") [1]),
                              StandardCharsets.UTF_8)).contains("\"iss\":\"Iv23temper\"");
        // the marker is for the minting script alone: the command does not inherit it
        var env = run(List.of(bin("with-kiln-app").toString(), "env"),
                      false);

        assertThat(env.out()).doesNotContain("TEMPER_GH_WRAPPER");
    }

    @Test
    void a7_openKeyFile_isRefused_beforeAnyRequest() throws Exception {
        Files.setPosixFilePermissions(home.resolve(".config/kiln/app-key.pem"),
                                      PosixFilePermissions.fromString("rw-r--r--"));
        var refused = run(List.of(bin("with-kiln-app").toString(), "printenv", "GH_TOKEN"),
                          false);

        assertThat(refused.rc()).isEqualTo(64);
        assertThat(refused.out()).isEmpty();
        assertThat(seen).isEmpty();
    }

    // A.8. Mutations: pass the token as an argument; write it to $TMPDIR. Needs Linux /proc: it runs on the build host.
    @Test
    void a8_token_isInTheCommandsEnvironmentOnly() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("/proc/self/environ")),
                               "needs Linux /proc");
        var sampler = new Sampler();
        var thread = new Thread(sampler, "proc-sampler");
        var process = command(List.of(bin("with-kiln-app").toString(), "sleep", "3"),
                              false).start();

        thread.start();
        var holders = new ArrayList<String>();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (holders.isEmpty() && System.nanoTime() < deadline) {
            holders.addAll(holdersOfToken());
            Thread.sleep(20);
        }

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        sampler.stop.set(true);
        thread.join();
        // every argv of the tools the wrappers call, logged by their PATH shims, however short-lived (A.8 r1 F6)
        var argv = Files.readString(shimLog());

        assertThat(argv).as("argv logged by the shims")
                  .contains("curl ", "openssl ", "sed ", "tr ")
                  .doesNotContain(TOKEN)
                  .doesNotContain(seen.getFirst().jwt());
        // the process that runs the command holds the token in its environment, and is the `sleep`
        assertThat(holders).as("a process holding the token in /proc/<pid>/environ").contains("sleep 3");
        // no command line sampled during the run (the wrapper, openssl, curl, sleep) holds the token or the JWT
        assertThat(seen).hasSize(1);
        assertThat(sampler.commandLines).as("sampled command lines")
                  .isNotEmpty()
                  .noneMatch(line -> line.contains(TOKEN))
                  .noneMatch(line -> line.contains(seen.getFirst().jwt()));
        // control, for every shim (curl and openssl carry the JWT's path, so they matter most): a secret on its argv is logged
        for (var name : SHIMMED) {
            var controlLog = dir.resolve("control-" + name + ".log");
            var shimmed = HermeticProcess.builder(dir.resolve("shims").resolve(name).toString(),
                                             "--",
                                             "GH_TOKEN=" + TOKEN);

            shimmed.environment().put("SHIM_LOG", controlLog.toString());
            shimmed.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            shimmed.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            shimmed.redirectError(ProcessBuilder.Redirect.DISCARD);
            assertThat(shimmed.start().waitFor(10, TimeUnit.SECONDS)).as(name).isTrue();
            assertThat(Files.readString(controlLog)).as("control for the " + name + " shim")
                      .contains(name + " ")
                      .contains(TOKEN);
        }
        // control: the sampler does see a command line when one carries the marker
        var control = HermeticProcess.builder("sleep", "2").start();
        var controlSampler = new Sampler();
        var controlThread = new Thread(controlSampler);

        controlThread.start();
        Thread.sleep(300);
        controlSampler.stop.set(true);
        controlThread.join();
        control.destroy();
        assertThat(controlSampler.commandLines).anyMatch(line -> line.equals("sleep 2"));
        // and no file under the fresh HOME or TMPDIR holds the token
        try (Stream<Path> files = Files.walk(home)) {
            var holdingToken = files.filter(Files::isRegularFile).filter(GhWrappersIT::holdsToken).toList();

            assertThat(holdingToken).isEmpty();
        }
    }

    // v5. Mutation: drop `exec` in the wrapper (a lingering shell would stay as the command's parent).
    @Test
    void wrappers_execTheCommand_soItHasTheWrappersPid() throws Exception {
        for (var wrapper : List.of("with-kiln-app", "with-temper-app")) {
            var process = command(List.of(bin(wrapper).toString(), "sh", "-c", "echo $$"),
                                  false).start();
            var out = new String(process.getInputStream().readAllBytes(),
                                 StandardCharsets.UTF_8).strip();

            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(out).as(wrapper).isEqualTo(String.valueOf(process.pid()));
        }
    }

    private Path kilnKeyFile() {
        return home.resolve(".config/kiln/app-key.pem");
    }

    private Outcome refusedKilnApp() throws Exception {
        var refused = run(List.of(bin("with-kiln-app").toString(), "printenv", "GH_TOKEN"),
                          false);

        assertThat(refused.rc()).isEqualTo(64);
        assertThat(refused.out()).isEmpty();
        assertThat(seen).isEmpty();

        return refused;
    }

    // LOW 4. Mutation: drop the symlink check.
    @Test
    void symlinkedKey_isRefused_withItsOwnMessage() throws Exception {
        var real = Files.move(kilnKeyFile(), home.resolve(".config/kiln/real.pem"));

        Files.createSymbolicLink(kilnKeyFile(), real);
        assertThat(refusedKilnApp().err()).contains("symbolic link");
    }

    // LOW 4. Mutation: drop the directory-mode check.
    @Test
    void keyInAGroupOrWorldWritableDirectory_isRefused() throws Exception {
        for (var mode : List.of("rwxrwx---", "rwx-w----", "rwx----w-")) {
            Files.setPosixFilePermissions(kilnKeyFile().getParent(), PosixFilePermissions.fromString(mode));
            assertThat(refusedKilnApp().err()).as(mode).contains("directory").contains("writable");
        }
        // control: not writable by others is accepted
        Files.setPosixFilePermissions(kilnKeyFile().getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        var accepted = run(List.of(bin("with-kiln-app").toString(), "printenv", "GH_TOKEN"),
                           false);

        assertThat(accepted.rc()).as(accepted.err()).isZero();
    }

    // Round 2. Mutation: check only the key's own directory (a 0777 grandparent is accepted).
    @Test
    void keyBelowAWorldWritableGrandparent_isRefused() throws Exception {
        var config = home.resolve(".config");

        for (var directory : List.of(config, home)) {
            var before = Files.getPosixFilePermissions(directory);

            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThat(refusedKilnApp().err()).as(directory.toString()).contains("directory").contains("writable");
            Files.setPosixFilePermissions(directory, before);
        }
        // control: both restored, the key is accepted
        var accepted = run(List.of(bin("with-kiln-app").toString(), "printenv", "GH_TOKEN"),
                           false);

        assertThat(accepted.rc()).as(accepted.err()).isZero();
    }

    private Path symlinkedKilnDirectory(String targetMode) throws Exception {
        var target = Files.createDirectories(dir.resolve("elsewhere"));
        var real = Files.move(kilnKeyFile().getParent(), target.resolve("kiln"));

        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString(targetMode));
        Files.createSymbolicLink(home.resolve(".config/kiln"), real);

        return real;
    }

    // Round 2. Mutation: stat without -L / no cd -P (the link is checked, not its target).
    @Test
    void symlinkedParent_whoseTargetIsWorldWritable_isRefused() throws Exception {
        symlinkedKilnDirectory("rwxrwxrwx");
        assertThat(refusedKilnApp().err()).contains("directory").contains("writable");
    }

    // Round 2. Mutation: judge the link's logical path (cd/pwd without -P): the directory above the target is never seen.
    @Test
    void symlinkedParent_whoseTargetSitsUnderAWritableDirectoryInHome_isRefused() throws Exception {
        var grand = Files.createDirectories(home.resolve("grand"));
        var real = Files.move(kilnKeyFile().getParent(), grand.resolve("kiln"));

        Files.setPosixFilePermissions(grand, PosixFilePermissions.fromString("rwxrwxrwx"));
        Files.createSymbolicLink(home.resolve(".config/kiln"), real);
        assertThat(refusedKilnApp().err()).contains(grand.toRealPath().toString()).contains("writable");
    }

    @Test
    void symlinkedParent_whoseTargetIsSafe_isAccepted() throws Exception {
        symlinkedKilnDirectory("rwx------");
        var accepted = run(List.of(bin("with-kiln-app").toString(), "printenv", "GH_TOKEN"),
                           false);

        assertThat(accepted.rc()).as(accepted.err()).isZero();
        assertThat(accepted.out().strip()).isEqualTo(TOKEN);
    }

    // Info. Mutation: drop the RSA check (an EC key would be signed as RS256).
    @Test
    void ecKey_isRefused_beforeAnyRequest() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");

        generator.initialize(256);
        var pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64,
                                                                          "\n".getBytes())
                                                          .encodeToString(generator.generateKeyPair()
                                                                                   .getPrivate()
                                                                                   .getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        Files.writeString(kilnKeyFile(), pem);
        assertThat(refusedKilnApp().err()).contains("not an RSA private key");
    }

    // A.9. Mutation: ship a with-reviewer wrapper.
    @Test
    void a9_noReviewerWrapper() throws Exception {
        for (var root : List.of(home.resolve(".local"), OPS_GH)) {
            try (Stream<Path> files = Files.walk(root)) {
                var all = files.filter(Files::isRegularFile).toList();

                assertThat(all).isNotEmpty();
                assertThat(all).noneMatch(file -> file.getFileName()
                                                      .toString()
                                                      .contains("reviewer"));
                assertThat(all.stream().filter(file -> contains(file, "reviewer-token")).toList()).isEmpty();
            }
        }

        assertThat(bin("with-reviewer")).doesNotExist();
        assertThat(bin("with-kiln-app")).exists();
    }

    private static boolean contains(Path file, String needle) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(needle);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean holdsToken(Path file) {
        return contains(file, TOKEN);
    }

    /// Command lines of processes whose environment holds the token (those we may read).
    private static List<String> holdersOfToken() throws IOException {
        var holders = new ArrayList<String>();

        try (Stream<Path> procs = Files.list(Path.of("/proc"))) {
            for (var proc : procs.filter(p -> p.getFileName()
                                               .toString()
                                               .matches("\\d+")).toList()) {
                if (contains(proc.resolve("environ"), TOKEN)) {
                    holders.add(read(proc.resolve("cmdline")));
                }
            }
        }

        return holders;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).replace('\0', ' ')
                                                                                    .strip();
        } catch (IOException e) {
            return "";
        }
    }

    /// Reads every `/proc/*/cmdline` in a loop until stopped, keeping each distinct command line seen.
    static final class Sampler implements Runnable {
        final AtomicBoolean stop = new AtomicBoolean();
        final Set<String> commandLines = ConcurrentHashMap.newKeySet();

        @Override
        public void run() {
            while (!stop.get()) {
                try (Stream<Path> procs = Files.list(Path.of("/proc"))) {
                    procs.filter(p -> p.getFileName()
                                       .toString()
                                       .matches("\\d+"))
                         .forEach(p -> {
                                      var line = read(p.resolve("cmdline"));

                                      if (!line.isEmpty()) {
                                      commandLines.add(line);
                                  }
                                  });
                } catch (IOException e) {
                    return;
                }
            }
        }
    }
}
