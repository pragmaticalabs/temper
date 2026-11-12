package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `ops/bb/bb-netns-run` run for real against a fake `docker` (its `run` executes the container command locally, so the
/// canary runs too). What the isolated maven may reach of the host docker is decided here: a port-group ticket whose
/// container has no docker silently skips every Docker IT (2026-11-12, cloudbb-3 counted ~240 fewer testcases).
///
/// Probes (each red, then reverted): drop the `-v <sock>:/var/run/docker.sock` mount ->
/// [#withTheSocket_theContainerGetsTheMount_theGroup_andTheGatewayOverride]; hard-code a gateway ->
/// [#theGatewayIsThatOfTheNetworkTheContainerRunsOn] and the first; drop the `[ -n "$GW" ]` refusal ->
/// [#withoutAGateway_theRunFailsClosed_andDockerNeverRuns]; drop the canary -> [#aDockerThatCannotBeReached_failsBeforeMaven].
class BbNetnsDockerIT {
    private static final Path SCRIPT = BbFixtures.OPS_BB.resolve("bb-netns-run");

    @TempDir
    Path dir;

    private Path bin;
    private Path log;
    private Path socket;
    private Path mvn;
    private Path home;

    @BeforeEach
    void fixtures() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-netns-run needs GNU stat and /usr/bin/bash");
        bin = Files.createDirectories(dir.resolve("bin"));
        home = Files.createDirectories(dir.resolve("home"));
        log = dir.resolve("docker.log");
        socket = dir.resolve("s.sock");
        Files.writeString(log, "");
        // image inspect: present; network inspect <name>: prints $HOME/gw-<name> or fails; version: FAKE_VERSION_RC; run: executes what follows the image
        BbFixtures.executable(bin.resolve("docker"), """
            #!/bin/sh
            echo "docker $*" >> "$FAKE_LOG"
            case "$1" in
              image) exit 0 ;;
              network) for a; do n=$a; done; [ -f "$HOME/gw-$n" ] && { cat "$HOME/gw-$n"; exit 0; }; echo "Error: no such network" >&2; exit 1 ;;
              version) exit "${FAKE_VERSION_RC:-0}" ;;
              run) shift; while [ "$1" != bb-hostfs:2 ]; do shift; done; shift; exec "$@" ;;
            esac
            exit 1
            """);
        mvn = BbFixtures.executable(dir.resolve("fake-mvn"), "#!/bin/sh\necho \"MAVEN RAN $*\"\n");
        Files.writeString(home.resolve("gw-bridge"), "10.9.8.1\n");
        Files.writeString(home.resolve("gw-bb-net-x"), "10.77.0.1\n");
    }

    private void listen() throws Exception {
        var run = BbFixtures.run(List.of("python3", "-c", "import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1])", socket.toString()),
                                 new HashMap<>(Map.of("PATH", System.getenv("PATH"))), null, null);

        assertThat(run.rc()).as(run.stderr()).isZero();
    }

    private BbFixtures.Outcome netns(Map<String, String> extra) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", bin + ":" + System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("FAKE_LOG", log.toString());
        env.put("BB_DOCKER_SOCK", socket.toString());
        env.putAll(extra);
        return BbFixtures.run(List.of(SCRIPT.toString(), "t1", dir.toString(), mvn.toString(), "-B", "verify"), env, dir, null);
    }

    private String dockerLog() throws IOException {
        return Files.readString(log);
    }

    @Test
    void withTheSocket_theContainerGetsTheMount_theGroup_andTheGatewayOverride() throws Exception {
        listen();
        var gid = Files.getAttribute(socket, "unix:gid");
        var run = netns(Map.of());

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(run.stdout()).contains("MAVEN RAN -B verify");
        assertThat(dockerLog()).contains(" -v " + socket + ":/var/run/docker.sock ")
                               .contains(" --group-add " + gid + " ")
                               .contains(" -e TESTCONTAINERS_HOST_OVERRIDE=10.9.8.1 ");
    }

    @Test
    void theGatewayIsThatOfTheNetworkTheContainerRunsOn() throws Exception {
        listen();
        var run = netns(Map.of("BB_NETNS_NETWORK", "bb-net-x"));

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(dockerLog()).contains("--network bb-net-x ").contains("TESTCONTAINERS_HOST_OVERRIDE=10.77.0.1 ")
                               .doesNotContain("10.9.8.1");
    }

    @Test
    void withoutTheSocket_theContainerGetsNoDockerAndNoOverride() throws Exception {
        var run = netns(Map.of());

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(run.stdout()).contains("MAVEN RAN -B verify");
        assertThat(dockerLog()).doesNotContain("docker.sock").doesNotContain("--group-add").doesNotContain("TESTCONTAINERS_HOST_OVERRIDE")
                               .doesNotContain("network inspect").doesNotContain("docker version");
    }

    @Test
    void withoutAGateway_theRunFailsClosed_andDockerNeverRuns() throws Exception {
        listen();
        var run = netns(Map.of("BB_NETNS_NETWORK", "no-such-net"));

        assertThat(run.rc()).isEqualTo(3);
        assertThat(run.stderr()).contains("no gateway of network 'no-such-net'");
        assertThat(run.stdout()).doesNotContain("MAVEN RAN");
        assertThat(dockerLog()).doesNotContain("docker run");
    }

    @Test
    void aDockerThatCannotBeReached_failsBeforeMaven() throws Exception {
        listen();
        var run = netns(Map.of("FAKE_VERSION_RC", "1"));

        assertThat(run.rc()).isEqualTo(97);
        assertThat(run.stderr()).contains("docker unreachable inside netns");
        assertThat(run.stdout()).doesNotContain("MAVEN RAN");
    }
}
