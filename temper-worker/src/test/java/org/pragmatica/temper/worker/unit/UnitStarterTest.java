package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.manifest.ManifestCollector;
import org.pragmatica.temper.worker.manifest.PomProbe;
import org.pragmatica.temper.worker.link.Outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `StartUnit` (m0-design §7.4) against a scripted docker: A1's R-DISK row on the worker (low disk is VOID(disk)
/// and `docker run` never happens), the marker-before-run order, cancellation races, adoption and cleanup.
class UnitStarterTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;
    private static final String NAME = "temper-42-main";
    private static final List<String> ID_INSPECT = List.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}", NAME);
    private static final List<String> RUN = List.of("docker", "run");
    private static final List<String> LABEL_INSPECT = List.of("docker", "inspect", "--type", "container", "-f", "{{index .Config.Labels \"temper.attempt\"}}", NAME);
    private static final List<String> REMOVE = List.of("docker", "rm", "-f", NAME);
    private static final List<String> NETWORK_REMOVE = List.of("docker", "network", "rm", NAME);

    @TempDir
    Path home;

    private FakeRunner runner;
    private WorkerConfig config;
    private UnitFiles files;
    private UnitRegistry registry;
    private RecordingOutbox outbox;
    private Fixtures.ManualTimers timers;
    private long freeBytes;
    private final UnitRef ref = Fixtures.ref(42, "main");

    @BeforeEach
    void setUp() {
        runner = new FakeRunner();
        config = Fixtures.config(home.resolve(".temper"), home.resolve("proc"), home);
        files = UnitFiles.unitFiles(config.paths());
        registry = UnitRegistry.unitRegistry();
        outbox = new RecordingOutbox();
        timers = new Fixtures.ManualTimers();
        freeBytes = 500 * GIB;

        runner.on(ID_INSPECT, 1, "", "Error: No such object: " + NAME)
              .on(List.of("docker", "network", "inspect", "-f", "{{.Name}}", NAME), 1, "", "Error: No such network: " + NAME)
              .on(List.of("docker", "network", "inspect", "-f", "{{range .IPAM.Config}}{{.Gateway}} {{end}}", NAME),
                  0,
                  "fd00::1 192.0.2.30 \n",
                  "")
              .on(List.of("docker", "ps", "-a"), 0, "", "")
              .on(RUN, 0, "cid123\n", "");
        // The exit watcher is not under test here: pretend one already owns the unit.
        registry.startWatching(ref, 1, "cid123");
    }

    private UnitStarter starter() {
        var clock = new Fixtures.ManualClock(NOW);
        var docker = DockerCli.dockerCli(runner, config.toolEnv(), config.timings());
        var facts = (FactsProbe) () -> Promise.success(HostFacts.hostFacts(0.5, 0.4, 8, 0, 0, none(), none(), 1000, freeBytes, freeBytes, NOW, List.of()));
        var reap = TestcontainersReap.testcontainersReap(docker, files, TimeSpan.timeSpan(1).seconds(), TimeSpan.timeSpan(1).seconds(), TimeSpan.timeSpan(10).millis(), clock);
        var timer = UnitTimer.unitTimer(files,
                                        config.paths(),
                                        docker,
                                        ThreadDumper.threadDumper(docker, TimeSpan.timeSpan(1).seconds()),
                                        timers,
                                        clock,
                                        registry);
        var collector = UnitCollector.unitCollector(config.paths(),
                                                    files,
                                                    docker,
                                                    ReportCollector.reportCollector(config.paths(), files),
                                                    reap,
                                                    facts,
                                                    CgroupSampler.cgroupSampler(home, clock),
                                                    ManifestCollector.manifestCollector(config.paths(), files, TemperJson.mapper()),
                                                    TemperJson.mapper(),
                                                    clock);
        var watcher = UnitWatcher.unitWatcher(docker,
                                              files,
                                              registry,
                                              collector,
                                              outbox,
                                              TimeSpan.timeSpan(1).hours(),
                                              TimeSpan.timeSpan(1).seconds());

        return UnitStarter.unitStarter(config,
                                       files,
                                       docker,
                                       _ -> Promise.success("sha256:image"),
                                       UnitLauncher.direct(docker),
                                       facts,
                                       () -> Promise.success(UnitStarter.FreeSpace.freeSpace(freeBytes, freeBytes)),
                                       reap,
                                       registry,
                                       timer,
                                       watcher,
                                       PomProbe.pomProbe(docker, config.paths(), TimeSpan.timeSpan(10).seconds()),
                                       GrantWatcher.grantWatcher(files,
                                                                 docker,
                                                                 Fixtures.noHolders(files),
                                                                 registry,
                                                                 timer,
                                                                 watcher,
                                                                 facts,
                                                                 noOutbox(),
                                                                 timers,
                                                                 clock,
                                                                 Fixtures.fastHold(),
                                                                 (_, _) -> Promise.success(UnitLauncher.Launch.holding(HolderRecord.holderRecord(1, 1),
                                                                                                                       Promise.promise())),
                                                                 Fixtures.reap(runner)),
                                       Fixtures.noHolders(files),
                                       TemperJson.mapper(),
                                       clock);
    }

    private static Outbox noOutbox() {
        return new Outbox() {
            @Override
            public Result<org.pragmatica.lang.Unit> post(Message message) {
                return Result.unitResult();
            }

            @Override
            public Result<org.pragmatica.lang.Unit> reply(long requestId, Message message) {
                return Result.unitResult();
            }
        };
    }

    private Message start() {
        return start(1);
    }

    private Message start(int attempt) {
        return ok(starter().start(Fixtures.request(42, "main", attempt))
                           .map(StartReply::message)
                           .await());
    }

    @Test
    void start_launchesAfterTheMarker_andRepliesUnitStarted() {
        var markerExistedAtRun = new AtomicBoolean();

        var attemptAtRun = new AtomicReference<String>();

        runner.on(RUN, argv -> {
            markerExistedAtRun.set(files.exists(ref, UnitFile.MARKER));
            attemptAtRun.set(ok(files.text(ref, UnitFile.ATTEMPT)).or(""));
            return Result.success(FakeRunner.completed(0, "cid123\n", ""));
        });

        var reply = start();

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStarted.class, started -> {
            assertThat(started.containerId()).isEqualTo("cid123");
            assertThat(started.imageId()).isEqualTo("sha256:image");
            assertThat(started.markerEpochNs()).isEqualTo(ok(files.nanos(ref, UnitFile.MARKER)).or(0L));
            assertThat(started.facts()
                              .cores()).isEqualTo(8);
        });
        assertThat(markerExistedAtRun).as("marker written before docker run (spec §4.4)")
                                      .isTrue();
        assertThat(attemptAtRun).as("attempt written before the run (m4-design §4.2)")
                                .hasValue("1");
        assertThat(((Message.UnitStarted) reply).attempt()).isEqualTo(1);
        assertThat(ok(files.text(ref, UnitFile.ARGS)).or("")).contains("\"mvn\"", "maven.repo.local");
        assertThat(ok(files.text(ref, UnitFile.TIMEOUT)).or("")).isEqualTo("PT1H");
        assertThat(ok(files.text(ref, UnitFile.CONTAINER)).or("")).isEqualTo("cid123");
        assertThat(ok(files.text(ref, UnitFile.IMAGE)).or("")).isEqualTo("sha256:image");
        assertThat(ok(files.text(ref, UnitFile.NETWORK)).or("")).isEqualTo(NAME);
        assertThat(files.exists(ref, UnitFile.FACTS_START)).isTrue();
        assertThat(files.exists(ref, UnitFile.TC_SESSIONS_START)).isTrue();
        assertThat(files.exists(ref, UnitFile.START)).isTrue();

        var run = runner.callsStartingWith(RUN)
                        .getFirst();

        assertThat(run).contains("TESTCONTAINERS_HOST_OVERRIDE=192.0.2.30", "--network", NAME, "temper/runner:0.1.0", "temper.attempt=1");
        assertThat(runner.indexOf(List.of("docker", "network", "create"))).isLessThan(runner.indexOf(RUN));
        assertThat(timers.scheduled()).as("the unit timer is armed")
                                      .hasSize(1);
    }

    @Test
    void start_isVoidDisk_andNeverRunsDocker_whenFreeSpaceIsBelowTheFloor() {
        freeBytes = 10 * GIB;

        var reply = start();

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class, failed -> {
            assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.VOID);
            assertThat(failed.reason()).isEqualTo("disk");
        });
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
        assertThat(runner.callsStartingWith(List.of("docker", "network", "create"))).isEmpty();
        assertThat(files.exists(ref, UnitFile.MARKER)).isFalse();
    }

    /// The attempt guard runs first (its container lookup is the only docker call); then the cancel ends the start.
    @Test
    void start_isCancelled_withoutRunningDocker_whenACancelCameFirst() {
        registry.cancel(ref, 1);

        var reply = start();

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                 failed -> assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.CANCELLED));
        assertThat(runner.calls()).containsExactly(ID_INSPECT);
    }

    // ---- the registry is per attempt (B1: the worker process outlives a unit's attempts) ------------------------
    /// Scenario a: a reconcile's `CancelUnit(1)` (a KillOrphan) marked the unit cancelled and nobody acked it; the
    /// later `StartUnit(2)` on the same host is not answered `CANCELLED`.
    @Test
    void start_ofAttemptTwo_isNotCancelled_byACancelOfAttemptOne() {
        registry.cancel(ref, 1);
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                    started -> assertThat(started.attempt()).isEqualTo(2));
        assertThat(registry.isCancelled(ref, 1)).as("attempt 1's cancel is forgotten with attempt 1")
                                                .isFalse();
    }

    /// The guard comes before the cancel: a host holding a higher attempt refuses the start as stale, whatever this
    /// process remembers for the requested attempt.
    @Test
    void start_isStale_notCancelled_whenTheHostHoldsAHigherAttempt() {
        registry.cancel(ref, 2);
        ok(files.store(ref, UnitFile.ATTEMPT, "3"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                    failed -> assertThat(failed.reason()).isEqualTo("infra: stale-attempt"));
    }

    /// Scenario c: a live `GrantWatcher` of attempt 1 does not make `StartUnit(2)` answer `UnitHolding`; attempt 1 is
    /// replaced and its hold forgotten.
    @Test
    void start_ofAttemptTwo_isNotHolding_becauseOfAttemptOnesGrantWatcher() {
        registry.startHolding(ref, 1);
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.FACTS_START, "{}"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                    started -> assertThat(started.attempt()).isEqualTo(2));
        assertThat(registry.isHolding(ref)).isFalse();
    }

    /// Scenario b: the replace removes attempt 1's container; attempt 1's exit watcher wakes (here only once attempt 2
    /// has started, so its directory is attempt 2's). That watcher no longer owns the unit: it writes no `rc` or
    /// `exited.json` into attempt 2's directory, reports nothing, and does not keep the unit watched, so attempt 2 gets
    /// its own watcher.
    @Test
    void start_replacingALowerAttempt_silencesItsWatcher_andWatchesTheNewAttempt() throws Exception {
        var oldExit = new CountDownLatch(1);
        var testOver = new CountDownLatch(1);
        runner.on(ID_INSPECT, 0, "cidOld\n", "")
              .on(LABEL_INSPECT, 0, "1\n", "")
              .on(List.of("docker", "wait", "cidOld"), _ -> waitedFor(oldExit, "137\n"))
              .on(List.of("docker", "wait", "cid123"), _ -> waitedFor(testOver, "0\n"));
        registry.stopWatching(ref);
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.CONTAINER, "cidOld"));
        var starter = starter();
        starter.watcher()
               .watch(ref, 1, "cidOld");

        var reply = ok(starter.start(Fixtures.request(42, "main", 2))
                              .map(StartReply::message)
                              .await());

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                 started -> assertThat(started.attempt()).isEqualTo(2));
        oldExit.countDown();
        Thread.sleep(300);
        assertThat(files.exists(ref, UnitFile.RC)).as("no rc of attempt 1 in attempt 2's directory")
                                                  .isFalse();
        assertThat(files.exists(ref, UnitFile.EXITED)).isFalse();
        assertThat(outbox.posted()).as("attempt 1's exit is not reported")
                                   .noneMatch(Message.UnitExited.class::isInstance);
        assertThat(runner.callsStartingWith(List.of("docker", "wait", "cid123"))).as("attempt 2 is watched")
                                                                               .hasSize(1);
        assertThat(registry.watches(ref, "cid123")).isTrue();
        registry.forget(ref, 2);
        testOver.countDown();
        Thread.sleep(100);
    }

    /// m3: a replace forgets everything the registry held of the replaced attempt: its timer is cancelled, its watch,
    /// held container, hold and cancel are gone.
    @Test
    void start_replacingALowerAttempt_forgetsTheRegistryStateOfIt() {
        registry.stopWatching(ref);
        registry.replaceTimer(ref, timers.schedule(() -> {}, TimeSpan.timeSpan(1).hours()));
        registry.startWatching(ref, 1, "cidOld");
        registry.hold(ref, "cidOld");
        registry.cancel(ref, 1);
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(2)).isInstanceOf(Message.UnitStarted.class);
        assertThat(timers.scheduled()
                         .getFirst()
                         .cancelled()[0]).as("attempt 1's timer").isTrue();
        assertThat(registry.watches(ref, "cidOld")).isFalse();
        assertThat(registry.held()).isEmpty();
        assertThat(registry.isCancelled(ref, 1)).isFalse();
    }

    /// m4-design §4.2: `attempt` is written before the marker, so any unit with a marker has an attempt. A start that
    /// fails between the two (the Testcontainers snapshot) leaves the attempt and no marker.
    @Test
    void start_writesTheAttemptBeforeTheMarker() {
        runner.on(List.of("docker", "ps", "-a"), 1, "", "Cannot connect to the Docker daemon");

        assertThat(start(3)).isInstanceOf(Message.UnitStartFailed.class);
        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("3");
        assertThat(files.exists(ref, UnitFile.MARKER)).isFalse();
    }

    private static Result<Completed> waitedFor(CountDownLatch latch, String rc) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
        return Result.success(FakeRunner.completed(0, rc, ""));
    }

    @Test
    void start_isErrorInfra_andCleansUp_whenDockerRunFails() {
        runner.on(RUN, 125, "", "docker: Error response from daemon: bad");

        var reply = start();

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class, failed -> {
            assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.ERROR);
            assertThat(failed.reason()).startsWith("infra: ");
        });
        assertThat(runner.callsStartingWith(List.of("docker", "rm", "-f", NAME))).hasSize(1);
        assertThat(runner.callsStartingWith(List.of("docker", "network", "rm", NAME))).hasSize(1);
    }

    @Test
    void start_killsItsOwnContainer_whenACancelArrivesDuringDockerRun() {
        runner.on(RUN, argv -> {
            registry.cancel(ref, 1);
            return Result.success(FakeRunner.completed(0, "cid123\n", ""));
        });

        var reply = start();

        assertThat(reply).isInstanceOf(Message.UnitStarted.class);
        assertThat(ok(files.text(ref, UnitFile.KILLED)).or("")).isEqualTo("cancel");
        assertThat(runner.callsStartingWith(List.of("docker", "kill", "cid123"))).hasSize(1);
    }

    @Test
    void start_adoptsAContainerThatAlreadyCarriesTheUnitName() {
        runner.on(ID_INSPECT, 0, "cidExisting\n", "")
              .on(LABEL_INSPECT, 0, "1\n", "");
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        var reply = start();

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStarted.class, started -> {
            assertThat(started.containerId()).isEqualTo("cidExisting");
            assertThat(started.markerEpochNs()).isEqualTo(1760356800000000000L);
        });
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
        assertThat(ok(files.text(ref, UnitFile.CONTAINER)).or("")).isEqualTo("cidExisting");
    }

    // ---- the adoption guard (m4-design §4.4, F3; probe P10) ------------------------------------------------
    /// The same attempt, retried after a link drop: adopted, nothing removed, nothing started.
    @Test
    void start_adoptsAContainerOfTheSameAttempt() {
        runner.on(ID_INSPECT, 0, "cidExisting\n", "")
              .on(LABEL_INSPECT, 0, "2\n", "");
        ok(files.store(ref, UnitFile.ATTEMPT, "2"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStarted.class, started -> {
            assertThat(started.containerId()).isEqualTo("cidExisting");
            assertThat(started.attempt()).isEqualTo(2);
        });
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
    }

    /// Attempt 2 granted where attempt 1's container still exists: attempt 1 is removed with its network and unit
    /// directory (its stale `rc` must not leak into attempt 2), and attempt 2 starts fresh. Without the guard the old
    /// container would be adopted and reported as attempt 2's start.
    @Test
    void start_removesALowerAttempt_andStartsFresh() {
        runner.on(ID_INSPECT, 0, "cidOld\n", "")
              .on(LABEL_INSPECT, 0, "1\n", "");
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.RC, "0"));

        var reply = start(2);

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStarted.class, started -> {
            assertThat(started.containerId()).isEqualTo("cid123");
            assertThat(started.attempt()).isEqualTo(2);
        });
        assertThat(runner.indexOf(REMOVE)).as("the old container is removed before the new run")
                                          .isLessThan(runner.indexOf(RUN));
        assertThat(runner.indexOf(NETWORK_REMOVE)).isLessThan(runner.indexOf(RUN));
        assertThat(files.exists(ref, UnitFile.RC)).as("attempt 1's unit directory is gone")
                                                  .isFalse();
        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("2");
        assertThat(runner.callsStartingWith(RUN)
                         .getFirst()).contains("temper.attempt=2");
    }

    /// A container started before M4 carries no `temper.attempt` label: it is attempt 1 (V11's backfill), so a later
    /// attempt removes it.
    @Test
    void start_removesAnUnlabelledPreM4Container_forALaterAttempt() {
        runner.on(ID_INSPECT, 0, "cidOld\n", "")
              .on(LABEL_INSPECT, 0, "<no value>\n", "");
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                    started -> assertThat(started.containerId()).isEqualTo("cid123"));
        assertThat(runner.indexOf(REMOVE)).isLessThan(runner.indexOf(RUN));
    }

    /// The same running pre-M4 container, re-requested as attempt 1 after the upgrade: adopted, never killed.
    @Test
    void start_adoptsAnUnlabelledPreM4Container_forAttemptOne() {
        runner.on(ID_INSPECT, 0, "cidOld\n", "")
              .on(LABEL_INSPECT, 0, "<no value>\n", "");
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(1)).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                    started -> assertThat(started.containerId()).isEqualTo("cidOld"));
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
    }

    /// A higher attempt than the request's (only a restored database can send this): refused, nothing touched.
    @Test
    void start_refusesWhenTheHostHoldsAHigherAttempt() {
        runner.on(ID_INSPECT, 0, "cidNewer\n", "")
              .on(LABEL_INSPECT, 0, "3\n", "");
        ok(files.store(ref, UnitFile.ATTEMPT, "3"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStartFailed.class, failed -> {
            assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.ERROR);
            assertThat(failed.reason()).isEqualTo("infra: stale-attempt");
            assertThat(failed.attempt()).isEqualTo(2);
        });
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("3");
    }

    /// No container, but a unit directory of a lower attempt (it exited and was collected): it is removed, then the
    /// start proceeds.
    @Test
    void start_removesTheDirectoryOfALowerAttempt_withoutAContainer() {
        ok(files.store(ref, UnitFile.ATTEMPT, "1"));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.RC, "1"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                    started -> assertThat(started.attempt()).isEqualTo(2));
        assertThat(files.exists(ref, UnitFile.RC)).isFalse();
        assertThat(runner.indexOf(REMOVE)).isLessThan(runner.indexOf(RUN));
    }

    /// A pre-M4 directory (a marker, no `attempt`) is attempt 1 (m4-design §7): a start of attempt 1 reuses it.
    @Test
    void start_readsAPreM4DirectoryAsAttemptOne() {
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));

        assertThat(start(1)).isInstanceOf(Message.UnitStarted.class);
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
    }

    /// No container, a unit directory of a higher attempt: refused, the directory kept.
    @Test
    void start_refusesADirectoryOfAHigherAttempt() {
        ok(files.store(ref, UnitFile.ATTEMPT, "3"));

        assertThat(start(2)).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                    failed -> assertThat(failed.reason()).isEqualTo("infra: stale-attempt"));
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
        assertThat(files.exists(ref, UnitFile.ATTEMPT)).isTrue();
    }

    @Test
    void start_isErrorInfra_whenAContainerWithTheNameExistsButNoMarker() {
        runner.on(ID_INSPECT, 0, "cidForeign\n", "")
              .on(LABEL_INSPECT, 0, "1\n", "");

        assertThat(start()).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                   failed -> assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.ERROR));
        assertThat(runner.callsStartingWith(RUN)).isEmpty();
    }

    @Test
    void start_cleansUp_whenAFailureComesAfterTheLaunch_evenIfTheCleanupFails() {
        // A cancel during docker run, then the kill of the fresh container fails: a failure after the launch.
        runner.on(RUN, argv -> {
                  registry.cancel(ref, 1);
                  return Result.success(FakeRunner.completed(0, "cid123\n", ""));
              })
              .on(List.of("docker", "kill", "cid123"), 1, "", "Error response from daemon: permission denied")
              .on(List.of("docker", "rm", "-f", NAME), 1, "", "Error response from daemon: busy");

        var reply = start();

        assertThat(reply).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                 failed -> assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.ERROR));
        assertThat(runner.callsStartingWith(List.of("docker", "rm", "-f", NAME))).hasSize(1);
    }

    @Test
    void start_removesAnAdoptedContainer_whoseAdoptionFails() {
        runner.on(ID_INSPECT, 0, "cidExisting\n", "")
              .on(LABEL_INSPECT, 0, "1\n", "");
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.START, "not-a-number"));

        assertThat(start()).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                   failed -> assertThat(failed.verdictKind()).isEqualTo(StartFailureKind.ERROR));
        assertThat(runner.callsStartingWith(List.of("docker", "rm", "-f", NAME))).hasSize(1);
    }

    @Test
    void start_isErrorInfra_withoutCleanup_whenDockerCannotBeAsked() {
        runner.on(ID_INSPECT, 1, "", "Cannot connect to the Docker daemon");

        assertThat(start()).isInstanceOfSatisfying(Message.UnitStartFailed.class,
                                                   failed -> assertThat(failed.reason()).startsWith("infra: "));
        assertThat(runner.callsStartingWith(List.of("docker", "rm"))).isEmpty();
    }

    @Test
    void start_aRetriedStartJoinsTheOneInFlight() throws Exception {
        var release = new CountDownLatch(1);

        runner.on(RUN, argv -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
            return Result.success(FakeRunner.completed(0, "cid123\n", ""));
        });

        var starter = starter();
        var first = starter.start(Fixtures.request(42, "main"));

        for (var wait = 0; wait < 200 && runner.callsStartingWith(RUN)
                                               .isEmpty(); wait++) {
            Thread.sleep(10);
        }

        var second = starter.start(Fixtures.request(42, "main"));

        release.countDown();

        assertThat(ok(second.await())).isEqualTo(ok(first.await()));
        assertThat(runner.callsStartingWith(RUN)).hasSize(1);
    }
}
