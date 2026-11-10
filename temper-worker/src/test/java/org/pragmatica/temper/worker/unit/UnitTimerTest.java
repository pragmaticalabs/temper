package org.pragmatica.temper.worker.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// A10 worker side (m0-design §7.5, §9): the timer fires at the deadline, fires at once on a re-attach after the
/// deadline, and the thread dumps happen before the kill.
class UnitTimerTest {
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "cid");
    private static final List<String> JCMD_LIST = List.of("docker", "exec", "cid", "java", "-cp", "/opt/temper", "RunSteps", "--jvms");
    private static final List<String> KILL = List.of("docker", "kill", "cid");

    @TempDir
    Path home;

    private FakeRunner runner;
    private UnitFiles files;
    private WorkerPaths paths;
    private Fixtures.ManualClock clock;
    private Fixtures.ManualTimers timers;
    private UnitRegistry registry;
    private UnitTimer timer;
    private final UnitRef ref = Fixtures.ref(42, "main");

    @BeforeEach
    void setUp() {
        runner = new FakeRunner();
        paths = WorkerPaths.workerPaths(home);
        files = UnitFiles.unitFiles(paths);
        clock = new Fixtures.ManualClock(T0.plus(Duration.ofMinutes(10)));
        timers = new Fixtures.ManualTimers();
        registry = UnitRegistry.unitRegistry();

        var docker = DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings());

        timer = UnitTimer.unitTimer(files,
                                    paths,
                                    docker,
                                    ThreadDumper.threadDumper(docker, TimeSpan.timeSpan(1).seconds()),
                                    timers,
                                    clock,
                                    registry);
        ok(files.store(ref, UnitFile.START, Long.toString(ok(EpochNanos.epochNanos(T0)))));
        ok(files.store(ref, UnitFile.CONTAINER, "cid"));
    }

    @Test
    void arm_schedulesTheFireAtStartPlusTimeout() {
        var deadline = ok(timer.arm(ref, Duration.ofMinutes(60)));

        assertThat(deadline).isEqualTo(T0.plus(Duration.ofMinutes(60)));
        assertThat(timers.last()
                         .map(entry -> entry.delay()
                                            .duration())
                         .or(Duration.ZERO)).isEqualTo(Duration.ofMinutes(50));
    }

    @Test
    void arm_firesImmediately_whenTheDeadlinePassedWhileNoWorkerWasAttached() {
        ok(timer.arm(ref, Duration.ofMinutes(60)));
        clock.advance(Duration.ofMinutes(70));

        ok(timer.arm(ref, Duration.ofMinutes(60)));

        assertThat(timers.scheduled()).hasSize(2);
        assertThat(timers.scheduled()
                         .getFirst()
                         .cancelled()[0]).as("the replaced timer is cancelled")
                                         .isTrue();
        assertThat(timers.scheduled()
                         .getLast()
                         .delay()
                         .duration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void arm_countsFromTheMarker_whenStartIsMissing() throws Exception {
        Files.delete(files.path(ref, UnitFile.START));
        ok(files.store(ref, UnitFile.MARKER, Long.toString(ok(EpochNanos.epochNanos(T0.plusSeconds(30))))));

        assertThat(ok(timer.arm(ref, Duration.ofMinutes(60)))).isEqualTo(T0.plusSeconds(30)
                                                                           .plus(Duration.ofMinutes(60)));
    }

    @Test
    void fire_writesKilled_dumpsEveryJvm_thenKills() {
        runner.on(STATUS, 0, "running\n", "")
              .on(JCMD_LIST, 0, "1 /opt/java/openjdk/bin/java -cp /opt/temper RunSteps 6 mvn\n57 /opt/java/openjdk/bin/java org.codehaus.plexus.classworlds.launcher.Launcher\n99 /opt/java/openjdk/bin/java sun.tools.jcmd.JCmd -l\n", "")
              .on(List.of("docker", "exec", "cid", "jcmd", "1", "Thread.print"), 0, "1:\nFull thread dump OpenJDK\n", "")
              .on(List.of("docker", "exec", "cid", "jcmd", "57", "Thread.print"), 1, "", "attach failed");

        ok(timer.fire(ref)
                .await());

        assertThat(ok(files.text(ref, UnitFile.KILLED)).or("")).isEqualTo("timeout");
        assertThat(paths.threadDumpsDir(ref)
                        .resolve("1.txt")).content()
                                          .contains("Full thread dump");
        assertThat(paths.threadDumpsDir(ref)
                        .resolve("57.err")).content()
                                           .contains("attach failed");
        assertThat(paths.threadDumpsDir(ref)
                        .resolve("99.txt")).doesNotExist();

        var kill = runner.indexOf(KILL);

        assertThat(kill).isPositive();
        assertThat(runner.indexOf(JCMD_LIST)).isLessThan(kill);
        assertThat(runner.indexOf(List.of("docker", "exec", "cid", "jcmd", "1", "Thread.print"))).isLessThan(kill);
        assertThat(runner.indexOf(List.of("docker", "exec", "cid", "jcmd", "57", "Thread.print"))).isLessThan(kill);
    }

    @Test
    void fire_stillKills_whenListingTheJvmsFails() {
        runner.on(STATUS, 0, "running", "")
              .on(JCMD_LIST, 126, "", "OCI runtime exec failed");

        ok(timer.fire(ref)
                .await());

        assertThat(paths.threadDumpsDir(ref)
                        .resolve(ThreadDumper.LISTING_ERROR)).exists();
        assertThat(runner.indexOf(KILL)).isPositive();
    }

    @Test
    void fire_leavesAUnitThatAlreadyHasAnRcAlone() {
        ok(files.store(ref, UnitFile.RC, "0"));

        ok(timer.fire(ref)
                .await());

        assertThat(runner.calls()).isEmpty();
        assertThat(files.exists(ref, UnitFile.KILLED)).isFalse();
    }

    @Test
    void fire_leavesAContainerThatExitedOnItsOwnAlone() {
        runner.on(STATUS, 0, "exited", "");

        ok(timer.fire(ref)
                .await());

        assertThat(files.exists(ref, UnitFile.KILLED)).isFalse();
        assertThat(runner.indexOf(KILL)).isEqualTo(-1);
    }

    @Test
    void fire_killsWithoutDumps_whenACancelWasRecordedFirst() {
        runner.on(STATUS, 0, "running", "");
        ok(files.store(ref, UnitFile.KILLED, "cancel"));

        ok(timer.fire(ref)
                .await());

        assertThat(ok(files.text(ref, UnitFile.KILLED)).or("")).isEqualTo("cancel");
        assertThat(runner.indexOf(JCMD_LIST)).isEqualTo(-1);
        assertThat(runner.indexOf(KILL)).isPositive();
    }

    @Test
    void scheduledTask_firesTheTimeout() throws Exception {
        runner.on(STATUS, 0, "running", "")
              .on(JCMD_LIST, 0, "", "");
        ok(timer.arm(ref, Duration.ofMinutes(1)));

        timers.last()
              .onPresent(entry -> entry.task()
                                       .run());

        for (var wait = 0; wait < 100 && runner.indexOf(KILL) < 0; wait++) {
            Thread.sleep(20);
        }

        assertThat(runner.indexOf(KILL)).isNotNegative();
    }
}
