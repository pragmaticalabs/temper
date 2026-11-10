package org.pragmatica.temper.worker.gc;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerAvailable;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.docker.DockerSupport;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.noHolders;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The orphan network sweep against a real docker daemon: a labelled network with no container, older than the
/// guard, is removed; a network with a container, a network younger than the guard, and a network without the
/// `temper.job` label are all left alone.
///
/// The daemon may be shared (other agents' workers, their just-created unit networks), so the sweep runs over a
/// runner that lets `docker network rm` reach the daemon only for the networks THIS test made; every removal the sweep
/// attempts is recorded, and a guard that fails shows as an attempt on a network the test expects to survive.
///
/// Probes: drop the label filter (`DockerCli#networkOrphans`) and the unlabelled network is attempted; drop the
/// container count test (`DockerOutput#orphanNetworks`) and the attached one is; drop the age test and the fresh
/// one is.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class NetworkSweepDockerIT {
    private static final String IMAGE = "eclipse-temurin:25-jdk-alpine";
    private static final String JOB = "9990001";
    private static final Duration LONG_AFTER = Duration.ofHours(1);

    private final String prefix = "temper-netleak-it-" + System.nanoTime() + "-";
    private final Guarded runner = new Guarded(prefix);

    @AfterEach
    void cleanUp() throws Exception {
        DockerSupport.run("sh", "-c", "docker rm -f $(docker ps -aq --filter name=" + prefix + ") >/dev/null 2>&1 || true");
        DockerSupport.run("sh", "-c", "docker network rm $(docker network ls -q --filter name=" + prefix + ") >/dev/null 2>&1 || true");
    }

    @Test
    void sweep_removesALabelledNetworkWithNoContainer_pastTheAgeGuard() throws Exception {
        var orphan = labelled("orphan");

        sweepAt(Instant.now().plus(LONG_AFTER));

        assertThat(runner.removals).contains(orphan);
        assertThat(exists(orphan)).as("the orphan is gone").isFalse();
    }

    @Test
    void sweep_keepsALabelledNetwork_thatHoldsAContainer() throws Exception {
        var busy = labelled("busy");

        DockerSupport.run("docker", "run", "-d", "--name", busy, "--network", busy, IMAGE, "sleep", "300");
        sweepAt(Instant.now().plus(LONG_AFTER));

        assertThat(runner.removals).doesNotContain(busy);
        assertThat(exists(busy)).isTrue();
    }

    @Test
    void sweep_keepsALabelledNetwork_youngerThanTheGuard() throws Exception {
        var fresh = labelled("fresh");

        sweepAt(Instant.now());

        assertThat(runner.removals).doesNotContain(fresh);
        assertThat(exists(fresh)).isTrue();
    }

    @Test
    void sweep_keepsANetwork_withoutTheTemperJobLabel() throws Exception {
        var foreign = prefix + "foreign";

        DockerSupport.run("docker", "network", "create", foreign);
        sweepAt(Instant.now().plus(LONG_AFTER));

        assertThat(runner.removals).doesNotContain(foreign);
        assertThat(exists(foreign)).isTrue();
    }

    private String labelled(String name) throws Exception {
        var network = prefix + name;

        ok(DockerSupport.docker()
                        .networkCreate(network, JOB, name)
                        .await());
        return network;
    }

    private void sweepAt(Instant now) throws Exception {
        var docker = DockerCli.dockerCli(runner, WorkerConfig.toolEnv(System.getenv()), DockerSupport.timings());
        var paths = WorkerPaths.workerPaths(DockerSupport.scratch("netleak").resolve(".temper"));
        var files = UnitFiles.unitFiles(paths);
        InstantSource clock = () -> now;

        ok(NetworkSweep.networkSweep(docker, noHolders(files), clock, NetworkSweep.MIN_AGE)
                       .sweep()
                       .await());
    }

    private static boolean exists(String network) throws Exception {
        return HermeticProcess.builder("docker", "network", "inspect", network).redirectErrorStream(true)
                                                                         .start()
                                                                         .waitFor() == 0;
    }

    /// Records every `docker network rm` and forwards it only for this test's own networks.
    private static final class Guarded implements ProcessRunner {
        private final ProcessRunner real = ProcessRunner.processRunner();
        private final String prefix;
        final List<String> removals = new CopyOnWriteArrayList<>();

        Guarded(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Promise<Completed> run(Command command) {
            var argv = command.argv();
            var removal = argv.size() == 4 && argv.get(1).equals("network") && argv.get(2).equals("rm");

            if (!removal) {
                return real.run(command);
            }
            removals.add(argv.get(3));
            return argv.get(3).startsWith(prefix)
                   ? real.run(command)
                   : Promise.success(Completed.completed("docker", 0, new byte[0], new byte[0], false));
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return real.spawn(command);
        }
    }
}
