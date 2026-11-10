package org.pragmatica.temper.worker.ops;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/worker-launch` (m0-design §6.4, Q-13 conditions): only `run <ver>` and `install <ver> <sha256>` are accepted;
/// install verifies the hash before anything is renamed into place and writes only under ~/.temper; run executes
/// only a jar whose hash matches the recorded one.
class WorkerLaunchTest {
    private static final Path SCRIPT = Path.of("..", "ops", "worker-launch")
                                           .toAbsolutePath()
                                           .normalize();

    @TempDir
    Path home;

    private byte[] jar;
    private String sha;

    record Outcome(int rc, String stdout, String stderr) {}

    @BeforeEach
    void setUp() throws Exception {
        jar = helloJar();
        sha = HexFormat.of()
                       .formatHex(MessageDigest.getInstance("SHA-256")
                                               .digest(jar));
    }

    private Outcome launch(String command, byte[] stdin) throws IOException, InterruptedException {
        return launch(command, stdin, Map.of());
    }

    private Outcome launch(String command, byte[] stdin, Map<String, String> extra) throws IOException, InterruptedException {
        var builder = HermeticProcess.builder("bash", SCRIPT.toString());

        builder.environment()
               .clear();
        builder.environment()
               .putAll(extra);
        builder.environment()
               .put("HOME", home.toString());
        builder.environment()
               .put("PATH", Path.of(System.getProperty("java.home"), "bin") + ":/usr/bin:/bin:/usr/sbin:/sbin");
        if (command != null) {
            builder.environment()
                   .put("SSH_ORIGINAL_COMMAND", command);
        }

        var process = builder.start();

        try (var in = process.getOutputStream()) {
            in.write(stdin);
        } catch (IOException ignored) {
            // the script may refuse before reading stdin
        }

        var stdout = new String(process.getInputStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);
        var stderr = new String(process.getErrorStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), stdout, stderr);
    }

    private Path temper() {
        return home.resolve(".temper");
    }

    @Test
    void install_verifiesThenRenamesIntoPlace_writingOnlyUnderTemperHome() throws Exception {
        var outcome = launch("install 0.1.0-SNAPSHOT " + sha, jar);

        assertThat(outcome.rc()).as(outcome.stderr())
                                .isZero();
        assertThat(temper().resolve("worker-0.1.0-SNAPSHOT.jar")).hasBinaryContent(jar);
        assertThat(temper().resolve("worker-0.1.0-SNAPSHOT.jar.sha256")).hasContent(sha);
        try (var entries = Files.list(home)) {
            assertThat(entries.map(path -> path.getFileName()
                                               .toString())
                              .toList()).containsExactly(".temper");
        }
        try (var entries = Files.list(temper())) {
            assertThat(entries.map(path -> path.getFileName()
                                               .toString())
                              .toList()).as("the pair, and run/ for the launch lock (A2)")
                                        .containsExactlyInAnyOrder("worker-0.1.0-SNAPSHOT.jar", "worker-0.1.0-SNAPSHOT.jar.sha256", "run");
        }
        try (var entries = Files.list(temper())) {
            assertThat(entries.filter(Files::isRegularFile)
                              .allMatch(path -> ok600(path))).isTrue();
        }
    }

    /// F1: the owner's `TEMPER_WORKER_HOME` (the acceptance key's forced command) replaces `~/.temper`; nothing is
    /// written under `~/.temper`. A relative one is refused.
    @Test
    void install_writesUnderTemperWorkerHome_whenSet() throws Exception {
        var acceptance = home.resolve("temper-acceptance/.temper");
        var outcome = launch("install 0.1.0-SNAPSHOT " + sha, jar, Map.of("TEMPER_WORKER_HOME", acceptance.toString()));

        assertThat(outcome.rc()).as(outcome.stderr())
                                .isZero();
        assertThat(acceptance.resolve("worker-0.1.0-SNAPSHOT.jar")).hasBinaryContent(jar);
        assertThat(acceptance.resolve("worker-0.1.0-SNAPSHOT.jar.sha256")).hasContent(sha);
        var run = launch("run 0.1.0-SNAPSHOT", new byte[0], Map.of("TEMPER_WORKER_HOME", acceptance.toString()));

        assertThat(run.rc()).as(run.stderr())
                            .isZero();
        assertThat(run.stdout()).contains(acceptance.resolve("run")
                                                    .toRealPath()
                                                    .resolve("worker-0.1.0-SNAPSHOT.")
                                                    .toString());
        assertThat(temper()).doesNotExist();
        assertThat(launch("install 0.1.0-SNAPSHOT " + sha, jar, Map.of("TEMPER_WORKER_HOME", "rel/.temper")).rc()).isEqualTo(64);
    }

    @Test
    void install_refusesAHashMismatch_andInstallsNothing() throws Exception {
        var outcome = launch("install 1.0 " + "0".repeat(64), jar);

        assertThat(outcome.rc()).isEqualTo(65);
        assertThat(temper().resolve("worker-1.0.jar")).doesNotExist();
        try (var entries = Files.list(temper())) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void run_executesAVerifiedJar() throws Exception {
        assertThat(launch("install 1.0 " + sha, jar).rc()).isZero();

        var outcome = launch("run 1.0", new byte[0]);

        assertThat(outcome.rc()).as(outcome.stderr())
                                .isZero();
        assertThat(outcome.stdout()).contains("hello-from-worker")
                                    .contains(temper().resolve("run")
                                                      .toRealPath()
                                                      .resolve("worker-1.0.")
                                                      .toString());
    }

    @Test
    void run_removesCopiesOfWorkersThatAreGone() throws Exception {
        assertThat(launch("install 1.0 " + sha, jar).rc()).isZero();
        Files.createDirectories(temper().resolve("run"));
        Files.write(temper().resolve("run/worker-1.0.999999.jar"), jar);

        assertThat(launch("run 1.0", new byte[0]).rc()).isZero();
        assertThat(temper().resolve("run/worker-1.0.999999.jar")).doesNotExist();
    }

    @Test
    void run_refusesAJarThatNoLongerMatchesItsRecordedHash() throws Exception {
        assertThat(launch("install 1.0 " + sha, jar).rc()).isZero();
        Files.write(temper().resolve("worker-1.0.jar"), new byte[]{1}, StandardOpenOption.APPEND);

        var outcome = launch("run 1.0", new byte[0]);

        assertThat(outcome.rc()).isEqualTo(65);
        assertThat(outcome.stdout()).doesNotContain("hello-from-worker");
    }

    @Test
    void run_refusesAJarWithoutARecord_orNotInstalled() throws Exception {
        assertThat(launch("run 1.0", new byte[0]).rc()).isEqualTo(66);

        Files.createDirectories(temper());
        Files.write(temper().resolve("worker-1.0.jar"), jar);

        assertThat(launch("run 1.0", new byte[0]).rc()).isEqualTo(65);
    }

    @Test
    void anythingElse_isRefused() throws Exception {
        for (var command : List.of("bash",
                                   "run",
                                   "run ../../x",
                                   "run 1.0 extra",
                                   "run  1.0",
                                   " run 1.0",
                                   "run 1.0;id",
                                   "run $(id)",
                                   "install 1.0",
                                   "install 1.0 " + sha.toUpperCase(),
                                   "install 1.0 " + sha + " x",
                                   "scp -t .temper",
                                   "run\t1.0",
                                   "tickets x",
                                   "tickets  ",
                                   " tickets",
                                   "TICKETS",
                                   "tickets;id",
                                   "tickets\t",
                                   "ticket")) {
            assertThat(launch(command, jar).rc()).as(command)
                                                 .isEqualTo(64);
        }
        assertThat(launch(null, jar).rc()).isEqualTo(64);
        try (var entries = Files.list(home)) {
            assertThat(entries.filter(path -> Files.isDirectory(path.resolve("worker-1.0.jar")))
                              .toList()).isEmpty();
        }
    }

    /// `tickets` is READ-ONLY and execs `~/tools/bb-tickets` of the key's user, whatever `TEMPER_WORKER_HOME`
    /// says: bb tooling is per user. Its stdout and exit code are the script's own.
    @Test
    void tickets_execsTheUsersBbTickets_whateverTheWorkerHome() throws Exception {
        var tools = Files.createDirectories(home.resolve("tools"));
        var script = tools.resolve("bb-tickets");

        Files.writeString(script, "#!/bin/sh\necho \"args=$# home=$HOME\"\nexit 7\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        var elsewhere = Files.createDirectories(home.resolve("elsewhere/.temper"));

        var plain = launch("tickets", new byte[0]);
        var moved = launch("tickets", new byte[0], Map.of("TEMPER_WORKER_HOME", elsewhere.toString()));

        assertThat(plain.rc()).as(plain.stderr()).isEqualTo(7);
        assertThat(plain.stdout()).isEqualTo("args=0 home=" + home + "\n");
        assertThat(moved.rc()).isEqualTo(7);
        assertThat(moved.stdout()).isEqualTo(plain.stdout());
        assertThat(temper()).doesNotExist();
    }

    @Test
    void tickets_withoutTheScript_isExit69() throws Exception {
        var outcome = launch("tickets", new byte[0]);

        assertThat(outcome.rc()).isEqualTo(69);
        assertThat(outcome.stdout()).isEmpty();
        assertThat(outcome.stderr()).contains("worker-launch: ~/tools/bb-tickets not installed");
    }

    private static boolean ok600(Path path) {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
                                       .equals("rw-------");
        } catch (IOException e) {
            return false;
        }
    }

    /// A one-class jar printing a marker line and where it was loaded from.
    static byte[] helloJar() throws IOException {
        var source = "public class Hello { public static void main(String[] a) { System.out.println(\"hello-from-worker \""
                     + " + Hello.class.getProtectionDomain().getCodeSource().getLocation()); } }";
        var classes = new ByteArrayOutputStream();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        var output = Files.createTempDirectory("hello");

        files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
        var unit = new SimpleJavaFileObject(URI.create("string:///Hello.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        assertThat(compiler.getTask(null, files, null, null, null, List.of(unit))
                           .call()).isTrue();

        var manifest = new Manifest();

        manifest.getMainAttributes()
                .put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes()
                .put(Attributes.Name.MAIN_CLASS, "Hello");
        try (var out = new JarOutputStream(classes, manifest)) {
            out.putNextEntry(new JarEntry("Hello.class"));
            out.write(Files.readAllBytes(output.resolve("Hello.class")));
            out.closeEntry();
        }
        return classes.toByteArray();
    }
}
