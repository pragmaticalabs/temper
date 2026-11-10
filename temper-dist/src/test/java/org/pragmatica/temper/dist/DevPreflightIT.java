package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/dev/preflight` (owner decision D-2): every check runs and names itself; any failure exits 1. The checks are
/// the ones whose failure looked like a code defect: a full disk, docker down (colima after a restart: 81 spurious IT
/// errors), a `.m2-local` hard-linked to its seed (cp -al), a build still running in the tree.
/// Probes: `-ge` → `-lt` in disk_ok → [#lowDisk_fails_andTheOtherChecksStillRun] red; `-links +1` → `-links +2` in
/// hardlinks_of → [#hardLinkedM2_fails_namingTheFile] red; lock_held without `ps -p` →
/// [#deadPidLock_isNotHeld] red; the young-directory clause removed →
/// [#pidlessLockDir_isHeld_whileYoung_andStale_afterTenSeconds] red; only the first listed pid read →
/// [#lockWithSeveralPids_isHeld_whileAnyLives] red; the lstart comparison dropped →
/// [#lockWithLstart_holdsOnlyForTheSameProcess] red, and so is `TZ=UTC` dropped from `started` (the test runs in
/// another zone); the `-L` check removed from m2_ok → [#symlinkedM2_fails] red (find does not descend the link);
/// the pid regex loosened to the first number → [#pidLinesThatAreNotJustAPid_areIgnored] red; the host comparison
/// made always true → [#testcontainersOnAnotherHost_fails] red; the raw strings compared again →
/// [#aSymlinkToTheSameSocket_isTheSameHost] red; the exit code of `docker info` trusted again →
/// [#dockerInfoExitingZeroWithoutAServer_fails] red.
class DevPreflightIT {
    @TempDir
    Path dir;
    private DevSandbox sandbox;
    private Path tree;

    @BeforeEach
    void sandbox() throws Exception {
        sandbox = new DevSandbox(dir);
        tree = sandbox.m2(sandbox.main.resolve(".m2-local"), "seed").getParent();
        Files.createDirectories(tree.resolve(".wp-logs"));
    }

    @Test
    void allChecksPass_withATree() throws Exception {
        sandbox.dockerUp();
        var outcome = sandbox.run("preflight", tree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: disk:")
                                    .contains("ok: docker: reachable via docker info (colima 29.2.1)")
                                    .contains("ok: docker-host: Testcontainers will use unix://" + sandbox.dir.resolve("docker.sock")
                                              + " (DOCKER_HOST), the host docker info used")
                                    .contains("ok: m2: " + tree + "/.m2-local exists, no hard links")
                                    .contains("ok: lock: no live build lock")
                                    .doesNotContain("FAILED");
    }

    @Test
    void withoutATree_checksOnlyDiskAndDocker() throws Exception {
        sandbox.dockerUp();
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: disk:").contains("ok: docker:").doesNotContain("m2:").doesNotContain("lock:");
    }

    @Test
    void lowDisk_fails_andTheOtherChecksStillRun() throws Exception {
        sandbox.dockerUp();
        sandbox.env.put("TEMPER_MIN_FREE_GIB", "100000000");
        var outcome = sandbox.run("preflight", tree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: disk:").contains("need 100000000").contains("ok: docker:").contains("ok: m2:");
    }

    @Test
    void aThresholdThatIsNotANumber_fails() throws Exception {
        sandbox.dockerUp();
        sandbox.env.put("TEMPER_MIN_FREE_GIB", "20G");

        assertThat(sandbox.run("preflight").output()).contains("FAILED: disk: TEMPER_MIN_FREE_GIB '20G' is not a whole number");
    }

    @Test
    void dockerInfoFailing_fails() throws Exception {
        sandbox.fake("docker", "echo 'Cannot connect to the Docker daemon' >&2; exit 1");
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: docker: docker info failed: Cannot connect to the Docker daemon");
    }

    /// docker 28 (the Linux CI runner's) exits 0 from `docker info --format` when it cannot reach the daemon, printing
    /// the error and an empty template: no server version means no daemon.
    @Test
    void dockerInfoExitingZeroWithoutAServer_fails() throws Exception {
        sandbox.fake("docker", "case \"$1\" in context) echo unix:///x.sock ;; *) echo 'Cannot connect to the Docker daemon at "
                               + "unix:///x.sock. Is the docker daemon running?' >&2; echo 'server= ' ;; esac");
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: docker: docker info failed:").doesNotContain("ok: docker:");
    }

    /// The sandbox hides a docker installed in a system directory (/usr/bin/docker on a Linux runner): "no CLI" means
    /// none, wherever the machine keeps it.
    @Test
    void theSandbox_hidesSystemDockerAndGh() throws Exception {
        var outcome = sandbox.ran(List.of("sh", "-c", "for t in docker gh mvn git awk ps; do command -v $t || echo none:$t; done"),
                                  dir);

        assertThat(outcome.output()).contains("none:docker", "none:gh", "none:mvn", "/git\n", "/awk\n", "/ps\n");
    }

    /// No docker CLI on PATH (an unlinked brew docker, 2026-10-24) fails even though the daemon answers: Testcontainers
    /// needs only the socket, but PgDumpIT and the worker exec `docker`. The line says what the socket does.
    @Test
    void noDockerCli_fails_sayingTheSocketAnswers() throws Exception {
        var socketDir = Files.createTempDirectory(Path.of("/tmp"), "dk");  // a unix socket path must stay short
        var socket = socketDir.resolve("d.sock");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            var daemon = Thread.ofVirtual().start(() -> answerPing(server));
            sandbox.env.put("DOCKER_HOST", "unix://" + socket);
            var outcome = sandbox.run("preflight");

            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
            assertThat(outcome.output()).contains("FAILED: docker: no docker CLI on PATH (temper's tests exec docker); "
                                                  + "the daemon answers on socket " + socket);
            daemon.interrupt();
        } finally {
            Files.deleteIfExists(socket);
            Files.delete(socketDir);
        }
    }

    @Test
    void noDockerCli_andADeadSocket_fails() throws Exception {
        var dead = Files.createDirectories(dir.resolve("s")).resolve("absent.sock");
        sandbox.env.put("DOCKER_HOST", "unix://" + dead);
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: docker: no docker CLI on PATH").contains("socket " + dead + " does not answer _ping");
    }

    /// Without DOCKER_HOST the line falls back to colima's socket (none: HOME is the sandbox's) and the machine's
    /// /var/run/docker.sock, which a Linux CI runner has: the message names it then, and the check fails either way.
    @Test
    void noDockerCli_andNoDockerHost_fails() throws Exception {
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: docker: no docker CLI on PATH");
        if (Files.exists(Path.of("/var/run/docker.sock"))) {
            assertThat(outcome.output()).contains("socket /var/run/docker.sock");
        } else {
            assertThat(outcome.output()).contains("no unix socket either");
        }
    }

    @Test
    void missingM2_fails() throws Exception {
        sandbox.dockerUp();
        var bare = Files.createDirectories(dir.resolve("bare-tree"));
        var outcome = sandbox.run("preflight", bare.toString());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: m2: " + bare + "/.m2-local does not exist");
    }

    /// The cp -al accident: a file of `.m2-local` that is a link of a file elsewhere.
    @Test
    void hardLinkedM2_fails_namingTheFile() throws Exception {
        sandbox.dockerUp();
        var pom = tree.resolve(".m2-local/org/example/a/1/a-1.pom");
        Files.createLink(dir.resolve("shared-a-1.pom"), pom);
        var outcome = sandbox.run("preflight", tree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: m2:").contains("hard-linked files").contains(pom.toString());
    }

    @Test
    void liveLockFile_fails() throws Exception {
        sandbox.dockerUp();
        var self = ProcessHandle.current().pid();
        Files.writeString(tree.resolve(".wp-logs/build.lock"), self + "\n");
        var outcome = sandbox.run("preflight", tree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: lock: " + tree + "/.wp-logs/build.lock is held by live pid " + self);
    }

    /// A pid line is a pid and nothing else: `host-1: N` names a process of another machine, `pid=N` is no pid line.
    /// Such a lock has no usable pid, so it is stale once older than 10 s.
    @Test
    void pidLinesThatAreNotJustAPid_areIgnored() throws Exception {
        sandbox.dockerUp();
        var self = ProcessHandle.current().pid();
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        for (var content : List.of("host-1: " + self + "\n", "pid=" + self + "\n", " " + self + "\n", self + "x\n")) {
            Files.writeString(lock.resolve("pid"), content);
            Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minusSeconds(60)));
            var outcome = sandbox.run("preflight", tree.toString());

            assertThat(outcome.rc()).as(content + ": " + outcome.output()).isZero();
            assertThat(outcome.output()).contains("ok: lock:");
        }
    }

    /// A symlinked .m2-local: `find` does not descend a symlinked root, so the hard-link check alone would pass it,
    /// while every build writes through the link.
    @Test
    void symlinkedM2_fails() throws Exception {
        sandbox.dockerUp();
        var linkedTree = Files.createDirectories(dir.resolve("linked-tree"));
        Files.createSymbolicLink(linkedTree.resolve(".m2-local"), tree.resolve(".m2-local"));
        var outcome = sandbox.run("preflight", linkedTree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: m2: " + linkedTree + "/.m2-local is a symlink (to " + tree.resolve(".m2-local"));
    }

    /// Testcontainers picks tc.host, then DOCKER_HOST, then docker.host of ~/.testcontainers.properties; the CLI
    /// DOCKER_HOST, then its context. Two daemons (or none for Testcontainers) is a failure, before the ITs find out.
    @Test
    void testcontainersOnAnotherHost_fails() throws Exception {
        sandbox.dockerUp();
        sandbox.env.remove("DOCKER_HOST");
        sandbox.env.put("FAKE_CONTEXT_HOST", "unix:///colima/docker.sock");
        var properties = sandbox.home.resolve(".testcontainers.properties");
        Files.writeString(properties, "# docker.host=unix:///colima/docker.sock\ndocker.host = unix:///other/docker.sock\n");
        var other = sandbox.run("preflight");

        assertThat(other.rc()).as(other.output()).isEqualTo(1);
        assertThat(other.output()).contains("FAILED: docker-host: Testcontainers will use unix:///other/docker.sock (docker.host in "
                                            + "~/.testcontainers.properties) but docker info used 'unix:///colima/docker.sock'");

        Files.writeString(properties, "docker.host=unix:///colima/docker.sock\r\n");
        var same = sandbox.run("preflight");

        assertThat(same.rc()).as(same.output()).isZero();
        assertThat(same.output()).contains("ok: docker-host: Testcontainers will use unix:///colima/docker.sock (docker.host in");

        Files.writeString(properties, "tc.host=tcp://cloud:2375\ndocker.host=unix:///colima/docker.sock\n");
        var tcHost = sandbox.run("preflight");

        assertThat(tcHost.rc()).as(tcHost.output()).isEqualTo(1);
        assertThat(tcHost.output()).contains("FAILED: docker-host: Testcontainers will use tcp://cloud:2375 (tc.host)");

        Files.delete(properties);
        var unset = sandbox.run("preflight");

        assertThat(unset.rc()).as(unset.output()).isEqualTo(1);
        assertThat(unset.output()).contains("FAILED: docker-host:").contains("but docker info used 'unix:///colima/docker.sock'");
    }

    /// Socket paths compare resolved: a link to the socket, a linked directory, a relative link chain all reach the
    /// daemon the context names. Only another real socket fails.
    @Test
    void aSymlinkToTheSameSocket_isTheSameHost() throws Exception {
        sandbox.dockerUp();
        sandbox.env.remove("DOCKER_HOST");
        var real = Files.createDirectories(sandbox.dir.resolve("real"));
        var socket = Files.writeString(real.resolve("docker.sock"), "");
        sandbox.env.put("FAKE_CONTEXT_HOST", "unix://" + socket);
        var linkedFile = Files.createSymbolicLink(sandbox.dir.resolve("link.sock"), socket);
        var linkedDir = Files.createSymbolicLink(sandbox.dir.resolve("linkdir"), real);
        var chain = Files.createSymbolicLink(sandbox.dir.resolve("chain.sock"), Path.of("link.sock"));
        var properties = sandbox.home.resolve(".testcontainers.properties");
        for (var host : List.of(linkedFile, linkedDir.resolve("docker.sock"), chain)) {
            Files.writeString(properties, "docker.host=unix://" + host + "\n");
            var outcome = sandbox.run("preflight");

            assertThat(outcome.rc()).as(host + ": " + outcome.output()).isZero();
            assertThat(outcome.output()).contains("ok: docker-host: Testcontainers will use unix://" + host);
        }
        var other = Files.writeString(real.resolve("other.sock"), "");
        Files.writeString(properties, "docker.host=unix://" + other + "\n");
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("FAILED: docker-host:").contains("(resolved: unix://" + other + " vs unix://" + socket + ")");
    }

    /// DOCKER_HOST set: both use it, whatever the context says.
    @Test
    void dockerHost_isWhatBothUse() throws Exception {
        sandbox.dockerUp();
        sandbox.env.put("FAKE_CONTEXT_HOST", "unix:///colima/docker.sock");
        Files.writeString(sandbox.home.resolve(".testcontainers.properties"), "docker.host=unix:///other/docker.sock\n");
        var outcome = sandbox.run("preflight");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: docker-host: Testcontainers will use unix://" + sandbox.dir.resolve("docker.sock") + " (DOCKER_HOST)");
    }

    @Test
    void liveLockDirectory_fails() throws Exception {
        sandbox.dockerUp();
        var self = ProcessHandle.current().pid();
        Files.writeString(Files.createDirectories(tree.resolve(".wp-logs/build.lock")).resolve("pid"), self + "\n");

        assertThat(sandbox.run("preflight", tree.toString()).output()).contains("is held by live pid " + self);
    }

    @Test
    void deadPidLock_isNotHeld() throws Exception {
        sandbox.dockerUp();
        Files.writeString(tree.resolve(".wp-logs/build.lock"), deadPid() + "\n");
        var outcome = sandbox.run("preflight", tree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: lock:");
    }

    /// The lock contract (ops/dev/lib.sh): mkdir first, the pid after, so a directory without a usable pid is a lock
    /// being taken for 10 s, and stale only after that.
    @Test
    void pidlessLockDir_isHeld_whileYoung_andStale_afterTenSeconds() throws Exception {
        sandbox.dockerUp();
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        for (var pidFile : List.of("<none>", "", "not-a-pid\n")) {
            Files.deleteIfExists(lock.resolve("pid"));
            if (!pidFile.equals("<none>")) {
                Files.writeString(lock.resolve("pid"), pidFile);
            }
            Files.setLastModifiedTime(lock, FileTime.from(Instant.now()));
            var young = sandbox.run("preflight", tree.toString());

            assertThat(young.rc()).as(pidFile + ": " + young.output()).isEqualTo(1);
            assertThat(young.output()).contains("FAILED: lock:").contains("is held being taken (no pid yet");

            Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minusSeconds(60)));
            var old = sandbox.run("preflight", tree.toString());

            assertThat(old.rc()).as(pidFile + ": " + old.output()).isZero();
            assertThat(old.output()).contains("ok: lock:");
        }
    }

    /// The build's pid and maven's, one per line: held while any of them lives, even when the first is gone.
    @Test
    void lockWithSeveralPids_isHeld_whileAnyLives() throws Exception {
        sandbox.dockerUp();
        var self = ProcessHandle.current().pid();
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        Files.writeString(lock.resolve("pid"), deadPid() + "\n" + self + "\n");
        var held = sandbox.run("preflight", tree.toString());

        assertThat(held.rc()).as(held.output()).isEqualTo(1);
        assertThat(held.output()).contains("is held by live pid " + self);

        Files.writeString(lock.resolve("pid"), deadPid() + "\n" + deadPid() + "\n");
        var stale = sandbox.run("preflight", tree.toString());

        assertThat(stale.rc()).as("every listed pid gone, however young: " + stale.output()).isZero();
    }

    /// `lstart` records each pid's start time, line for line: a live pid whose start differs is a reused pid, not the
    /// build, so the lock is stale; the recorded start of the live process holds it.
    @Test
    void lockWithLstart_holdsOnlyForTheSameProcess() throws Exception {
        sandbox.dockerUp();
        var self = ProcessHandle.current().pid();
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        Files.writeString(lock.resolve("pid"), deadPid() + "\n" + self + "\n");
        sandbox.env.put("TZ", "America/New_York");  // the reader must compare in UTC, as the writer recorded
        var started = sandbox.ran(List.of("sh", "-c", "LC_ALL=C TZ=UTC ps -o lstart= -p " + self), dir).output();
        Files.writeString(lock.resolve("lstart"), "Mon Jan  1 00:00:00 2001\n" + started);
        var same = sandbox.run("preflight", tree.toString());

        assertThat(same.rc()).as(same.output()).isEqualTo(1);
        assertThat(same.output()).contains("is held by live pid " + self);

        Files.writeString(lock.resolve("lstart"), "Mon Jan  1 00:00:00 2001\nTue Jan  2 00:00:00 2001\n");
        var reused = sandbox.run("preflight", tree.toString());

        assertThat(reused.rc()).as(reused.output()).isZero();
        assertThat(reused.output()).contains("ok: lock:");
    }

    /// `build.guard` and `build.new-*` sit beside the lock in `.wp-logs/` and are not locks, even naming a live pid.
    @Test
    void guardAndNewFiles_areNotLocks() throws Exception {
        sandbox.dockerUp();
        var self = ProcessHandle.current().pid();
        Files.writeString(tree.resolve(".wp-logs/build.guard"), self + "\n");
        Files.writeString(Files.createDirectories(tree.resolve(".wp-logs/build.new-123")).resolve("pid"), self + "\n");
        Files.writeString(tree.resolve(".wp-logs/build.new-456"), self + "\n");
        var outcome = sandbox.run("preflight", tree.toString());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("ok: lock:");
    }

    @Test
    void usage() throws Exception {
        assertThat(sandbox.run("preflight", "a", "b").rc()).isEqualTo(2);
        assertThat(sandbox.run("preflight", dir.resolve("absent").toString()).rc()).isEqualTo(2);
    }

    private static long deadPid() throws Exception {
        var gone = HermeticProcess.builder("true").start();
        gone.waitFor();
        return gone.pid();
    }

    private static void answerPing(ServerSocketChannel server) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try (var client = server.accept()) {
                    client.read(ByteBuffer.allocate(4096));
                    client.write(ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                                 .getBytes(StandardCharsets.US_ASCII)));
                }
            }
        } catch (IOException closed) {
            // the server closed: the test is over
        }
    }
}
