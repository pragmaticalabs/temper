package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.manifest.ManifestCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The exit watcher (m0-design §7.4 step 11): a wait that returns reports the exit and stops watching; a wait that
/// ends without an rc re-inspects (running → wait again, gone → `Failure{lost}` naming the unit).
class UnitWatcherTest {
    private static final List<String> WAIT = List.of("docker", "wait", "cid");
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "cid");

    @TempDir
    Path home;

    private FakeRunner runner;
    private UnitFiles files;
    private UnitRegistry registry;
    private RecordingOutbox outbox;
    private UnitWatcher watcher;
    private final UnitRef ref = Fixtures.ref(4, "main");

    @BeforeEach
    void setUp() {
        runner = new FakeRunner().on(List.of("docker", "ps"), 0, "", "")
                                 .on(List.of("docker", "logs", "cid"), 0, "x\n", "");
        files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        outbox = new RecordingOutbox();
        watching(UnitRegistry.unitRegistry());
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.CONTAINER, "cid"));
    }

    /// Wires the watcher over `units`.
    private void watching(UnitRegistry units) {
        var paths = WorkerPaths.workerPaths(home);
        var docker = DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings());

        registry = units;
        watcher = UnitWatcher.unitWatcher(docker,
                                          files,
                                          registry,
                                          UnitCollector.unitCollector(paths,
                                                                      files,
                                                                      docker,
                                                                      ReportCollector.reportCollector(paths, files),
                                                                      TestcontainersReap.testcontainersReap(docker,
                                                                                                            files,
                                                                                                            TimeSpan.timeSpan(50).millis(),
                                                                                                            TimeSpan.timeSpan(50).millis(),
                                                                                                            TimeSpan.timeSpan(10).millis(),
                                                                                                            Instant::now),
                                                                      () -> Promise.success(HostFacts.hostFacts(0, 0, 1, 0, 0, none(), none(), 1, 1, 1, Instant.EPOCH, List.of())),
                                                                      CgroupSampler.cgroupSampler(home, Instant::now),
                                                                      ManifestCollector.manifestCollector(paths, files, TemperJson.mapper()),
                                                                      TemperJson.mapper(),
                                                                      Instant::now),
                                          outbox,
                                          TimeSpan.timeSpan(1).hours(),
                                          TimeSpan.timeSpan(20).millis());
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (var wait = 0; wait < 300 && !condition.getAsBoolean(); wait++) {
            Thread.sleep(10);
        }
    }

    /// A watcher writes and reports only for its own attempt: once the unit directory names another attempt and
    /// container (attempt 2 started after attempt 1 was replaced), attempt 1's late exit leaves it untouched.
    @Test
    void watch_ofAReplacedAttempt_writesAndReportsNothing_whenTheDirectoryIsTheNextAttempts() throws Exception {
        var exited = new CountDownLatch(1);
        runner.on(WAIT, _ -> waitedFor(exited));
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));

        watcher.watch(ref, 1, "cid");
        ok(files.store(ref, UnitFile.ATTEMPT, "2"));
        ok(files.store(ref, UnitFile.CONTAINER, "cid2"));
        exited.countDown();
        eventually(() -> !runner.callsStartingWith(WAIT)
                                .isEmpty());
        Thread.sleep(300);

        assertThat(files.exists(ref, UnitFile.RC)).isFalse();
        assertThat(files.exists(ref, UnitFile.EXITED)).isFalse();
        assertThat(outbox.posted()).isEmpty();
    }

    private static Result<Completed> waitedFor(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
        return Result.success(FakeRunner.completed(0, "3\n", ""));
    }

    @Test
    void watch_reportsTheExit_andStopsWatching() throws Exception {
        runner.on(WAIT, 0, "2\n", "");

        watcher.watch(ref, 1, "cid");

        assertThat(outbox.awaitPosted(Message.UnitExited.class, 1)).singleElement()
                                                                   .satisfies(exited -> assertThat(exited.rc()).isEqualTo(2));
        eventually(() -> registry.running()
                                 .isEmpty());
        assertThat(registry.running()).isEmpty();
        assertThat(ok(files.text(ref, UnitFile.RC)).or("")).isEqualTo("2");
    }

    /// The watch ends before the report's promise resolves, not some time after it: `stopWatching` is a chain step.
    /// The logs answer late, on the runner's thread, so the chain is unresolved when its last step is attached, and
    /// the registry's removals are slow, so an `on*` callback would leave the unit watched when `reported` resolves.
    @Test
    void reported_stopsWatching_beforeItResolves() {
        runner.delayed(List.of("docker", "logs", "cid"), Duration.ofMillis(100), 0, "x\n", "");
        watching(HoldFixture.slowRemovals(Duration.ofMillis(300)));
        registry.startWatching(ref, 1, "cid");

        ok(watcher.reported(ref, 1, some(0))
                  .await());

        assertThat(registry.running()).isEmpty();
    }

    @Test
    void watch_runsOneWait_perUnit() {
        runner.on(WAIT, _ -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
            return Result.success(FakeRunner.completed(0, "0\n", ""));
        });

        watcher.watch(ref, 1, "cid");
        watcher.watch(ref, 1, "cid");

        assertThat(runner.callsStartingWith(WAIT)).hasSize(1);
        assertThat(registry.running()).extracting(UnitRegistry.RunningUnit::ref)
                                      .containsExactly(ref);
    }

    @Test
    void watch_waitsAgain_whenTheWaitEndsWithoutAnRc_andTheContainerStillRuns() throws Exception {
        var waits = new AtomicInteger();

        runner.on(STATUS, 0, "running", "")
              .on(WAIT, _ -> waits.incrementAndGet() == 1
                             ? ExecError.TimedOut.FACTORY.apply("docker", TimeSpan.timeSpan(1).hours())
                                                         .result()
                             : Result.success(FakeRunner.completed(0, "0\n", "")));

        watcher.watch(ref, 1, "cid");

        assertThat(outbox.awaitPosted(Message.UnitExited.class, 1)).hasSize(1);
        assertThat(waits.get()).isEqualTo(2);
    }

    @Test
    void watch_reportsALostUnit_namingIt_whenTheContainerVanished() throws Exception {
        runner.on(STATUS, 1, "", "Error: No such object: cid")
              .on(WAIT, 1, "", "Error response from daemon: No such container: cid");

        watcher.watch(ref, 1, "cid");

        assertThat(outbox.awaitPosted(Message.Failure.class, 1)).singleElement()
                                                                .satisfies(failure -> {
                                                                    assertThat(failure.code()).isEqualTo("lost");
                                                                    assertThat(failure.detail()).startsWith("temper-4-main: ");
                                                                    assertThat(failure.unit()).isEqualTo(some(new UnitRefDto(4, "main", 1)));
                                                                });
        eventually(() -> registry.running()
                                 .isEmpty());
        assertThat(registry.running()).isEmpty();
    }

    /// `Collect` for a unit with no recorded rc whose container is gone: the loss is reported (`Failure{lost}` naming
    /// the unit) and nothing starts watching a container that cannot exit.
    @Test
    void collectRequested_reportsALostUnit_neverWatches_whenTheContainerIsAbsent() throws Exception {
        runner.on(STATUS, 1, "", "Error: No such object: cid");

        ok(watcher.collectRequested(ref, 1)
                  .await());

        assertThat(outbox.awaitPosted(Message.Failure.class, 1)).singleElement()
                                                                .satisfies(failure -> {
                                                                    assertThat(failure.code()).isEqualTo("lost");
                                                                    assertThat(failure.detail()).startsWith("temper-4-main: ");
                                                                    assertThat(failure.unit()).isEqualTo(some(new UnitRefDto(4, "main", 1)));
                                                                });
        assertThat(registry.running()).isEmpty();
        assertThat(runner.callsStartingWith(WAIT)).isEmpty();
        assertThat(outbox.posted()).noneMatch(Message.UnitExited.class::isInstance);
    }
}
