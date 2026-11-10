package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.docker.TcContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The Testcontainers reap (m0-design §12.4): sessions present before the unit are never the unit's; the worker
/// waits for the rest to disappear and reports what is left.
class TestcontainersReapTest {
    private static final List<String> PS = List.of("docker", "ps", "-a");

    @Test
    void attributed_excludesSessionsThatExistedBeforeTheUnit() {
        var containers = List.of(TcContainer.tcContainer("a", "old"), TcContainer.tcContainer("b", "new"));

        assertThat(TestcontainersReap.attributed(containers, Set.of("old"))).containsExactly(TcContainer.tcContainer("b", "new"));
    }

    /// A11 live (2026-10-14): a container without a session id (another process's Ryuk) was every unit's leftover.
    @Test
    void attributed_neverCountsAContainerWithoutASessionId() {
        var containers = List.of(TcContainer.tcContainer("ryuk", ""), TcContainer.tcContainer("b", "new"));

        assertThat(TestcontainersReap.attributed(containers, Set.of())).containsExactly(TcContainer.tcContainer("b", "new"));
    }

    @Test
    void await_neverWaitsForAContainerWithoutASessionId(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var runner = new FakeRunner().on(PS, 0, "ryuk \n", "");
        var started = System.nanoTime();

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));

        assertThat(ok(reap(runner, files, 3_000).await(ref)
                                                 .await())).isEqualTo(TcLeftovers.tcLeftovers(List.of(), Option.none()));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    /// A voided unit's verdict waits on the reap, so the reap waits only `voidedReapWait` (A11: VOID within 30 s);
    /// what is left then is still reported.
    @Test
    void await_ofAVoidedUnit_endsAfterTheVoidedWindow_andReportsTheLeftovers(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var runner = new FakeRunner().on(PS, 0, "y new\n", "");
        var started = System.nanoTime();

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));
        ok(files.store(ref, UnitFile.VOID, "port-group-lost"));

        assertThat(ok(reap(runner, files, 3_000).await(ref)
                                                 .await())).isEqualTo(TcLeftovers.tcLeftovers(List.of(TcLeftovers.TcLeftover.tcLeftover("y", "new")),
                                                                                              Option.some(TestcontainersReap.CUT_SHORT)));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    /// A voided unit whose containers are gone within the short window reaped clean: nothing was cut short.
    @Test
    void await_ofAVoidedUnit_isClean_whenNothingIsLeft(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var runner = new FakeRunner().on(PS, 0, "", "");

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));
        ok(files.store(ref, UnitFile.VOID, "port-group-lost"));

        assertThat(ok(reap(runner, files, 3_000).await(ref)
                                                 .await())).isEqualTo(TcLeftovers.tcLeftovers(List.of(), Option.none()));
    }

    /// Verifier F3: a unit that was not voided waits the full `reapWait`, never the voided window.
    @Test
    void await_ofAnUnvoidedUnit_waitsTheFullReapWait(@TempDir Path home) {
        assertFullWindow(home, Option.none());
    }

    /// Verifier F4: a `disk` void leaves the unit running to a normal exit, so its reap waits the full `reapWait`.
    @Test
    void await_ofADiskVoidedUnit_waitsTheFullReapWait(@TempDir Path home) {
        assertFullWindow(home, Option.some("disk"));
    }

    private static void assertFullWindow(Path home, Option<String> voidReason) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var runner = new FakeRunner().on(PS, 0, "y new\n", "");
        var started = System.nanoTime();

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));
        voidReason.onPresent(reason -> ok(files.store(ref, UnitFile.VOID, reason)));

        assertThat(ok(reap(runner, files, 800).await(ref)
                                              .await())).isEqualTo(TcLeftovers.tcLeftovers(List.of(TcLeftovers.TcLeftover.tcLeftover("y", "new")), Option.none()));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(800));
    }

    /// Verifier F6: the depth does not grow with the polls. A clock that moves 10 ms per reading makes a 300 s window
    /// 30 000 polls (the old nested `flatMap` overflowed the stack at about 12 000 and never resolved); the timer
    /// delay is zero so the test takes seconds.
    @Test
    void await_completes_afterThirtyThousandPolls(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var polls = new AtomicInteger();
        var runner = new FakeRunner().on(PS, _ -> countedPoll(polls));
        var readings = new AtomicLong();
        var start = Instant.parse("2026-10-14T00:00:00Z");
        var reap = TestcontainersReap.testcontainersReap(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()),
                                                         files,
                                                         TimeSpan.timeSpan(300).seconds(),
                                                         TimeSpan.timeSpan(5).seconds(),
                                                         TimeSpan.timeSpan(0).millis(),
                                                         () -> start.plusMillis(10 * readings.getAndIncrement()));

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));

        assertThat(ok(reap.await(ref)
                          .await(TimeSpan.timeSpan(60).seconds())).containers()).hasSize(1);
        assertThat(polls.get()).isGreaterThanOrEqualTo(30_000);
    }

    private static Result<Completed> countedPoll(AtomicInteger polls) {
        polls.incrementAndGet();

        return Result.success(FakeRunner.completed(0, "y new\n", ""));
    }

    @Test
    void await_returnsNothing_onceRyukRemovedTheUnitsContainers(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var polls = new AtomicInteger();
        var runner = new FakeRunner().on(PS,
                                         _ -> Result.success(FakeRunner.completed(0,
                                                                                  polls.incrementAndGet() < 3
                                                                                  ? "x old\ny new\n"
                                                                                  : "x old\n",
                                                                                  "")));

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, "old"));

        var leftovers = ok(reap(runner, files, 5_000).await(ref)
                                                     .await());

        assertThat(leftovers).isEqualTo(TcLeftovers.tcLeftovers(List.of(), Option.none()));
        assertThat(polls.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void await_reportsLeftovers_afterTheWait(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var runner = new FakeRunner().on(PS, 0, "y new\n", "");

        ok(files.store(ref, UnitFile.TC_SESSIONS_START, ""));

        assertThat(ok(reap(runner, files, 100).await(ref)
                                              .await())).isEqualTo(TcLeftovers.tcLeftovers(List.of(TcLeftovers.TcLeftover.tcLeftover("y", "new")), Option.none()));
    }

    @Test
    void await_reportsAnUnverifiedReap_whenDockerCannotBeAsked(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var runner = new FakeRunner().on(PS, 1, "", "Cannot connect");

        assertThat(ok(reap(runner, files, 100).await(Fixtures.ref(1, "main"))
                                              .await())).satisfies(leftovers -> {
                                                            assertThat(leftovers.containers()).isEmpty();
                                                            assertThat(leftovers.unverified()
                                                                                .isPresent()).isTrue();
                                                        });
    }

    @Test
    void snapshot_recordsTheSessionsPresentNow(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(1, "main");
        var runner = new FakeRunner().on(PS, 0, "a s1\nb s2\nc s1\nd \n", "");

        ok(reap(runner, files, 100).snapshot(ref)
                                   .await());

        assertThat(Set.of(ok(files.text(ref, UnitFile.TC_SESSIONS_START)).or("")
                                                                         .split("\n"))).isEqualTo(Set.of("s1", "s2"));
    }

    private static TestcontainersReap reap(FakeRunner runner, UnitFiles files, long waitMillis) {
        return TestcontainersReap.testcontainersReap(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()),
                                                     files,
                                                     TimeSpan.timeSpan(waitMillis).millis(),
                                                     TimeSpan.timeSpan(100).millis(),
                                                     TimeSpan.timeSpan(10).millis(),
                                                     Instant::now);
    }
}
