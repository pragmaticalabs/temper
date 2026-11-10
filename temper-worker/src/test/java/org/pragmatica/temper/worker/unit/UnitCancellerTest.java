package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `CancelUnit` (spec §6.4, m0-design §7.8): the kill comes first; the unit joins the cancelled set either way.
class UnitCancellerTest {
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "cid");

    @Test
    void cancel_killsARunningContainer_recordingTheCause(@TempDir Path home) {
        var runner = new FakeRunner().on(STATUS, 0, "running", "");
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var registry = UnitRegistry.unitRegistry();
        var ref = Fixtures.ref(3, "main");

        ok(files.store(ref, UnitFile.CONTAINER, "cid"));

        var state = ok(UnitCanceller.unitCanceller(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), files, registry, Fixtures.noHolders(files), Fixtures.fastHold().holderExit())
                                    .cancel(ref, 1)
                                    .await());

        assertThat(state).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(ok(files.text(ref, UnitFile.KILLED)).or("")).isEqualTo("cancel");
        assertThat(runner.callsStartingWith(List.of("docker", "kill", "cid"))).hasSize(1);
        assertThat(registry.isCancelled(ref, 1)).isTrue();
    }

    /// §7.8 ordering, observed by the fake docker at call time: the unit is in the cancelled set when the status
    /// is looked up (a start racing the lookup sees the cancel), and `killed=cancel` is on disk when `docker kill`
    /// runs (the exit processing triggered by the kill reads it as `killedBy`).
    @Test
    void cancel_marksTheUnitCancelledBeforeTheLookup_andRecordsTheCauseBeforeTheKill(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var registry = UnitRegistry.unitRegistry();
        var ref = Fixtures.ref(3, "main");
        var events = new CopyOnWriteArrayList<String>();
        var runner = new FakeRunner().on(STATUS, _ -> observed(events, "status cancelled=" + registry.isCancelled(ref, 1), "running"))
                                     .on(List.of("docker", "kill", "cid"),
                                         _ -> observed(events, "kill killed=" + ok(files.text(ref, UnitFile.KILLED)).or("-"), ""));

        ok(files.store(ref, UnitFile.CONTAINER, "cid"));
        ok(UnitCanceller.unitCanceller(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), files, registry, Fixtures.noHolders(files), Fixtures.fastHold().holderExit())
                        .cancel(ref, 1)
                        .await());

        assertThat(events).containsExactly("status cancelled=true", "kill killed=cancel");
    }

    private static Result<Completed> observed(List<String> events, String event, String stdout) {
        events.add(event);

        return Result.success(FakeRunner.completed(0, stdout, ""));
    }

    @Test
    void cancel_isCancelled_withoutAContainer_andExited_forAFinishedOne(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var registry = UnitRegistry.unitRegistry();
        var ref = Fixtures.ref(3, "main");
        var absent = new FakeRunner().on(List.of("docker", "inspect"), 1, "", "Error: No such object: temper-3-main");

        assertThat(ok(UnitCanceller.unitCanceller(DockerCli.dockerCli(absent, Map.of(), Fixtures.fastTimings()), files, registry, Fixtures.noHolders(files), Fixtures.fastHold().holderExit())
                                   .cancel(ref, 1)
                                   .await())).isEqualTo(UnitCanceller.CancelState.CANCELLED);
        assertThat(registry.isCancelled(ref, 1)).isTrue();
        assertThat(absent.callsStartingWith(List.of("docker", "kill"))).isEmpty();

        var exited = new FakeRunner().on(List.of("docker", "inspect"), 0, "exited", "");

        assertThat(ok(UnitCanceller.unitCanceller(DockerCli.dockerCli(exited, Map.of(), Fixtures.fastTimings()), files, registry, Fixtures.noHolders(files), Fixtures.fastHold().holderExit())
                                   .cancel(ref, 1)
                                   .await())).isEqualTo(UnitCanceller.CancelState.EXITED);
        assertThat(files.exists(ref, UnitFile.KILLED)).isFalse();
    }

    @Test
    void cancel_keepsAnEarlierTimeoutCause(@TempDir Path home) {
        var runner = new FakeRunner().on(List.of("docker", "inspect"), 0, "running", "");
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var ref = Fixtures.ref(3, "main");

        ok(files.store(ref, UnitFile.KILLED, "timeout"));
        ok(UnitCanceller.unitCanceller(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), files, UnitRegistry.unitRegistry(), Fixtures.noHolders(files), Fixtures.fastHold().holderExit())
                        .cancel(ref, 1)
                        .await());

        assertThat(ok(files.text(ref, UnitFile.KILLED)).or("")).isEqualTo("timeout");
    }

    @Test
    void cancel_isCancelling_whenNoContainerExistsYetButAStartIsInFlight(@TempDir Path home) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));
        var registry = UnitRegistry.unitRegistry();
        var ref = Fixtures.ref(3, "main");
        var absent = new FakeRunner().on(List.of("docker", "inspect"), 1, "", "Error: No such object: temper-3-main");

        registry.start(ref, 1, Promise::promise);

        assertThat(ok(UnitCanceller.unitCanceller(DockerCli.dockerCli(absent, Map.of(), Fixtures.fastTimings()), files, registry, Fixtures.noHolders(files), Fixtures.fastHold().holderExit())
                                   .cancel(ref, 1)
                                   .await())).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(registry.isCancelled(ref, 1)).isTrue();
    }

    /// A cancel above the held attempt (protocol v3) promises an end only when a start of the NAMED attempt is in
    /// flight: that start sees the cancel and ends the unit `CANCELLED`. Nothing runs, so nothing is touched.
    @Test
    void cancelAhead_isCancelling_whileAStartOfTheNamedAttemptIsInFlight(@TempDir Path home) {
        var registry = UnitRegistry.unitRegistry();
        var ref = Fixtures.ref(3, "main");
        var runner = new FakeRunner();

        registry.start(ref, 2, Promise::promise);

        assertThat(canceller(runner, home, registry).cancelAhead(ref, 2)).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(registry.isCancelled(ref, 2)).isTrue();
        assertThat(runner.calls()).as("nothing of the held attempt is touched").isEmpty();
    }

    /// A start of the lower, held attempt never sees a cancel of the next one, so it promises no end: `CANCELLED`.
    @Test
    void cancelAhead_isCancelled_whenOnlyALowerAttemptsStartIsInFlight(@TempDir Path home) {
        var registry = UnitRegistry.unitRegistry();
        var ref = Fixtures.ref(3, "main");

        registry.start(ref, 1, Promise::promise);

        assertThat(canceller(new FakeRunner(), home, registry).cancelAhead(ref, 2)).isEqualTo(UnitCanceller.CancelState.CANCELLED);
        assertThat(registry.isCancelled(ref, 2)).isTrue();
    }

    private static UnitCanceller canceller(FakeRunner runner, Path home, UnitRegistry registry) {
        var files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home));

        return UnitCanceller.unitCanceller(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), files, registry, Fixtures.noHolders(files), Fixtures.fastHold().holderExit());
    }
}
