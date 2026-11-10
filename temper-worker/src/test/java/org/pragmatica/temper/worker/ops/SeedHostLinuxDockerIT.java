package org.pragmatica.temper.worker.ops;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerAvailable;
import org.pragmatica.temper.worker.docker.DockerSupport;
import org.pragmatica.temper.worker.unit.DockerRunArgs;
import org.pragmatica.temper.worker.unit.RunnerImage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.pragmatica.temper.worker.Fixtures.ok;
import static org.assertj.core.api.Assertions.assertThat;


/// `ops/seed-host.sh` (m0-design §7.3, A13) on Linux with GNU coreutils (the runner image, Ubuntu), as a non-root
/// user. Everything happens on the container's own filesystem: hard links and link counts are then real Linux
/// ones, not whatever a host shared folder reports.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class SeedHostLinuxDockerIT {
    record Outcome(int rc, String output) {}

    private static final String SEED = "/tmp/h/.temper/seed-m2";

    /// A source with nested files and a hard link INTERNAL to it (b.jar is a second name of a.jar).
    private static final String SOURCE = """
        mkdir -p /tmp/h/work/m2-seed/org/acme/lib/1.0 && cd /tmp/h/work/m2-seed/org/acme/lib/1.0 \
         && printf jar > a.jar && ln a.jar b.jar && printf pom > lib-1.0.pom \
         && test "$(stat -c %h a.jar)" = 2""";

    private String container;

    @BeforeEach
    void startContainer() throws Exception {
        var version = DockerSupport.runnerVersion();
        var script = Path.of("..", "ops", "seed-host.sh").toAbsolutePath().normalize();

        assertThat(script).isRegularFile();
        ok(RunnerImage.runnerImage(DockerSupport.docker(),
                                   WorkerPaths.workerPaths(DockerSupport.scratch("seed").resolve("build")))
                      .ensure(version)
                      .await());
        container = "temper-seed-it-" + System.nanoTime();
        DockerSupport.run("docker",
                          "run",
                          "-d",
                          "--rm",
                          "--name",
                          container,
                          "--user",
                          "1000:1000",
                          "-e",
                          "HOME=/tmp/h",
                          "-v",
                          script + ":/ops/seed-host.sh:ro",
                          DockerRunArgs.image(version),
                          "sleep",
                          "600");
        assertThat(sh("mkdir -p /tmp/h && " + SOURCE).rc()).isZero();
    }

    @AfterEach
    void removeContainer() throws Exception {
        DockerSupport.run("docker", "rm", "-f", container);
    }

    @Test
    void seed_copiesTheSource_everyFileWithOneLink_evenWhereTheSourceHasAnInternalHardLink() throws Exception {
        var seeded = sh("bash /ops/seed-host.sh");

        assertThat(seeded.rc()).as(seeded.output()).isZero();
        assertThat(sh("find " + SEED + " -type f -printf '%n %P\\n' | sort").output()).isEqualTo("""
            1 org/acme/lib/1.0/a.jar
            1 org/acme/lib/1.0/b.jar
            1 org/acme/lib/1.0/lib-1.0.pom
            """);
        assertThat(sh("diff -r /tmp/h/work/m2-seed " + SEED + " && cat " + SEED + "/org/acme/lib/1.0/b.jar").output()).isEqualTo("jar");
        assertThat(sh("ls -A /tmp/h/.temper").output()).isEqualTo("seed-m2\n");
    }

    @Test
    void seed_refusesASecondRun_withoutReplace_andKeepsTheSeed() throws Exception {
        assertThat(sh("bash /ops/seed-host.sh").rc()).isZero();
        assertThat(sh("printf marker > " + SEED + "/marker").rc()).isZero();
        var again = sh("bash /ops/seed-host.sh");

        assertThat(again.rc()).as(again.output()).isEqualTo(3);
        assertThat(again.output()).contains("exists");
        assertThat(sh("cat " + SEED + "/marker").output()).isEqualTo("marker");
    }

    @Test
    void seed_refusesASourceInsideTheTemperHome() throws Exception {
        assertThat(sh("mkdir -p /tmp/h/.temper/elsewhere && cp -r /tmp/h/work/m2-seed/. /tmp/h/.temper/elsewhere/").rc()).isZero();
        var refused = sh("bash /ops/seed-host.sh --source /tmp/h/.temper/elsewhere");

        assertThat(refused.rc()).as(refused.output()).isEqualTo(3);
        assertThat(refused.output()).contains("inside");
        assertThat(sh("test ! -e " + SEED).rc()).isZero();
    }

    /// A8: a relative `TEMPER_WORKER_HOME` would seed `./rel/.temper` under the working directory; it is refused before
    /// anything is created, as `worker-launch` and the worker refuse it.
    @Test
    void seed_refusesARelativeTemperWorkerHome_andCreatesNothing() throws Exception {
        var refused = sh("cd /tmp/h && TEMPER_WORKER_HOME=rel/.temper bash /ops/seed-host.sh");

        assertThat(refused.rc()).as(refused.output()).isEqualTo(3);
        assertThat(refused.output()).contains("absolute");
        assertThat(sh("test ! -e /tmp/h/rel && test ! -e /tmp/h/.temper").rc()).isZero();
    }

    /// The `cp -al` mistake, forced by a `cp` on PATH that hard-links: the script's own link check must refuse the
    /// copy (exit 4), leave no seed and no partial copy, and never touch the source.
    @Test
    void seed_failsTheLinkCheck_forAHardLinkedCopy_andLeavesNothing() throws Exception {
        var shim = "mkdir -p /tmp/shim && printf '#!/bin/sh\\nexec /bin/cp -l \"$@\"\\n' > /tmp/shim/cp && chmod +x /tmp/shim/cp";

        assertThat(sh(shim).rc()).isZero();
        var failed = sh("PATH=/tmp/shim:$PATH bash /ops/seed-host.sh");

        assertThat(failed.rc()).as(failed.output()).isEqualTo(4);
        assertThat(failed.output()).contains("hard-linked");
        assertThat(sh("ls -A /tmp/h/.temper").output()).isEmpty();
        assertThat(sh("stat -c %h /tmp/h/work/m2-seed/org/acme/lib/1.0/lib-1.0.pom").output()).isEqualTo("1\n");
    }

    /// `--replace` swaps in a fresh copy and leaves no `.old`/`.partial` behind; it is refused while a worker of this
    /// user runs, and when pgrep cannot tell (a failing or missing pgrep must not read as "no worker").
    @Test
    void replace_swapsTheSeed_butIsRefusedWhileAWorkerRuns_orWhenPgrepFails() throws Exception {
        assertThat(sh("bash /ops/seed-host.sh").rc()).isZero();
        assertThat(sh("printf marker > " + SEED + "/marker").rc()).isZero();
        var replaced = sh("bash /ops/seed-host.sh --replace");

        assertThat(replaced.rc()).as(replaced.output()).isZero();
        assertThat(sh("test ! -e " + SEED + "/marker").rc()).isZero();
        assertThat(sh("ls -A /tmp/h/.temper").output()).isEqualTo("seed-m2\n");
        var shim = "mkdir -p /tmp/nopgrep && printf '#!/bin/sh\\nexit 3\\n' > /tmp/nopgrep/pgrep && chmod +x /tmp/nopgrep/pgrep";

        assertThat(sh(shim).rc()).isZero();
        var unknown = sh("PATH=/tmp/nopgrep:$PATH bash /ops/seed-host.sh --replace");

        assertThat(unknown.rc()).as(unknown.output()).isEqualTo(3);
        assertThat(unknown.output()).contains("pgrep failed");
        // pgrep missing altogether: the shell's rc 127 falls into the same refusal (no separate `command -v` check).
        var noPgrep = "mkdir -p /tmp/nopgrep2 && for f in /usr/bin/* /bin/*; do [ \"${f##*/}\" = pgrep ] || ln -sf \"$f\" /tmp/nopgrep2/; done";

        assertThat(sh(noPgrep).rc()).isZero();
        var missing = sh("PATH=/tmp/nopgrep2 bash /ops/seed-host.sh --replace");

        assertThat(missing.rc()).as(missing.output()).isEqualTo(3);
        assertThat(missing.output()).contains("pgrep failed (rc 127)");
        // A process whose command line names a private worker copy, as `worker-launch run` leaves it.
        DockerSupport.run("docker",
                          "exec",
                          "-d",
                          container,
                          "bash",
                          "-c",
                          "exec -a 'java -jar /tmp/h/.temper/run/worker-1.0.42.jar' sleep 300");
        assertThat(sh("for i in $(seq 50); do pgrep -x sleep && exit 0; sleep 0.1; done; exit 1").rc()).isZero();
        var busy = sh("bash /ops/seed-host.sh --replace");

        assertThat(busy.rc()).as(busy.output()).isEqualTo(3);
        assertThat(busy.output()).contains("worker is running");
        assertThat(sh("ls -A /tmp/h/.temper").output()).isEqualTo("seed-m2\n");
    }

    @Test
    void help_printsTheHeaderOnly() throws Exception {
        var help = sh("bash /ops/seed-host.sh --help");

        assertThat(help.rc()).isZero();
        assertThat(help.output()).startsWith("# seed-host.sh")
                  .contains("# Exit codes:")
                  .endsWith("# the link-count check (nothing replaced).\n")
                  .doesNotContain("set -euo");
    }

    private Outcome sh(String script) throws IOException, InterruptedException {
        var process = HermeticProcess.builder(List.of("docker", "exec", container, "bash", "-c", script)).redirectErrorStream(true)
                                                                                                    .start();
        var output = new String(process.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), output);
    }
}
