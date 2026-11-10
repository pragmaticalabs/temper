package org.pragmatica.temper.worker.link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.WorkerAssembly;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.unit.Collected;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;


/// Protocol v3 on the worker (m4-design §4.2, §4.5): a unit request for another attempt than the unit directory holds
/// is answered `Failure{stale-attempt}` and does nothing; `DiscardUnit` removes exactly the attempt it names; the
/// worker's own unit frames carry the directory's attempt.
class RouterAttemptTest {
    private static final String CID = "f".repeat(64);
    private static final String NAME = "temper-8-main";
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", CID);
    private static final List<String> LABEL_INSPECT = List.of("docker", "inspect", "--type", "container", "-f", "{{index .Config.Labels \"temper.attempt\"}}", NAME);
    private static final List<String> REMOVE = List.of("docker", "rm", "-f", NAME);
    private static final List<String> NETWORK_REMOVE = List.of("docker", "network", "rm", NAME);

    @TempDir
    Path home;

    private FakeRunner runner;
    private RecordingOutbox outbox;
    private UnitFiles files;
    private WorkerConfig config;
    private Router router;
    private final CountDownLatch exit = new CountDownLatch(1);
    private final UnitRef ref = Fixtures.ref(8, "main");

    @BeforeEach
    void setUp() throws Exception {
        var proc = Files.createDirectories(home.resolve("proc"));

        Files.writeString(proc.resolve("loadavg"), "0.1 0.2 0.3 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemAvailable: 5 kB\n");
        runner = new FakeRunner();
        outbox = new RecordingOutbox();
        config = Fixtures.config(home.resolve(".temper"), proc, home, 0);
        files = UnitFiles.unitFiles(config.paths());
        runner.on(List.of("docker", "wait", CID), _ -> waited())
              .on(List.of("docker", "logs", CID), 0, "out\n", "")
              .on(List.of("docker", "ps"), 0, "", "")
              .on(STATUS, 0, "running", "");
        router = ok(WorkerAssembly.assemble(config, runner, TemperJson.mapper(), outbox, Instant::now, new Fixtures.ManualTimers())
                                  .await()).router();
    }

    private Result<Completed> waited() {
        try {
            exit.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
        return Result.success(FakeRunner.completed(0, "0\n", ""));
    }

    private void unitOfAttempt(String attempt) {
        ok(files.store(ref, UnitFile.ATTEMPT, attempt));
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.START, "1760356800000000000"));
        ok(files.store(ref, UnitFile.CONTAINER, CID));
    }

    private Reply route(Message message) {
        return ok(router.route(message)
                        .await());
    }

    /// `CancelUnit` is not stale above the held attempt: it is for a start still to come ([#cancelUnit_aboveTheHeldAttempt_isRememberedForItsStart_andTouchesNothing]).
    static Stream<Message> nonCancelRequestsForAttempt2() {
        return requestsForAttempt2().filter(request -> !(request instanceof Message.CancelUnit));
    }

    static Stream<Message> requestsForAttempt2() {
        return Stream.of(new Message.Reattach(8, "main", 2, Duration.ofMinutes(90)),
                         new Message.Collect(8, "main", 2),
                         new Message.CancelUnit(8, "main", 2),
                         new Message.LogTail(8, "main", 2, 10));
    }

    // ---- stale-attempt answers ---------------------------------------------------------------------------------
    @ParameterizedTest
    @MethodSource("nonCancelRequestsForAttempt2")
    void unitRequest_isStaleAttempt_andTouchesNothing_whenTheDirectoryHoldsALowerAttempt(Message request) {
        unitOfAttempt("1");

        var cause = failure(router.route(request)
                                  .await());

        assertThat(WorkerError.code(cause)).isEqualTo("stale-attempt");
        assertThat(cause.message()).isEqualTo("Unit " + NAME + " holds attempt 1, not attempt 2");
        assertThat(runner.calls()).as("no docker call at all: no wait, kill, logs or rm")
                                  .isEmpty();
        assertThat(files.exists(ref, UnitFile.TIMEOUT)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("requestsForAttempt2")
    void unitRequest_isStaleAttempt_whenTheDirectoryHoldsAHigherAttempt(Message request) {
        unitOfAttempt("3");

        assertThat(WorkerError.code(failure(router.route(request)
                                                  .await()))).isEqualTo("stale-attempt");
        assertThat(runner.calls()).isEmpty();
    }

    /// A directory written before M4 (a marker, no `attempt`) holds attempt 1 (m4-design §7).
    @Test
    void unitRequest_readsAPreM4DirectoryAsAttemptOne() {
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.CONTAINER, CID));

        assertThat(route(new Message.CancelUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.some("CANCELLING"))));
        assertThat(WorkerError.code(failure(router.route(new Message.LogTail(8, "main", 2, 10))
                                                  .await()))).isEqualTo("stale-attempt");
    }

    /// No attempt held at all (never started here): the handler answers as before M4; a cancel racing ahead of the
    /// start is remembered for its attempt.
    @Test
    void cancelUnit_ofAUnitWithNoDirectory_isNotStale() {
        runner.on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", NAME), 1, "", "Error: No such object: " + NAME);

        assertThat(route(new Message.CancelUnit(8, "main", 5))).isEqualTo(Reply.respond(new Message.Ack(Option.some("CANCELLED"))));
        assertThat(router.registry()
                         .isCancelled(ref, 5)).isTrue();
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
    }

    /// m2: `CancelUnit(2)` while the host still holds attempt 1 (a `StartUnit(2)` replacing it may be in flight) is
    /// not stale: it is remembered for attempt 2, and nothing of attempt 1 is killed or written.
    @Test
    void cancelUnit_aboveTheHeldAttempt_isRememberedForItsStart_andTouchesNothing() {
        unitOfAttempt("1");

        assertThat(route(new Message.CancelUnit(8, "main", 2))).isEqualTo(Reply.respond(new Message.Ack(Option.some("CANCELLED"))));
        assertThat(router.registry()
                         .isCancelled(ref, 2)).isTrue();
        assertThat(router.registry()
                         .isCancelled(ref, 1)).isFalse();
        assertThat(runner.calls()).as("no kill, no rm").isEmpty();
        assertThat(files.exists(ref, UnitFile.KILLED)).isFalse();
    }

    // ---- with no unit directory the container's label is the held attempt (verifier D1) --------------------------
    @Test
    void staleCancel_ofAContainerLabelledWithAnotherAttempt_killsNothing_andCreatesNoDirectory() {
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        assertThat(WorkerError.code(failure(router.route(new Message.CancelUnit(8, "main", 1))
                                                  .await()))).isEqualTo("stale-attempt");
        assertThat(runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();
        assertThat(config.paths()
                         .unitDir(ref)).doesNotExist();
    }

    @Test
    void staleLogTail_ofAContainerLabelledWithAnotherAttempt_readsNothing_andCreatesNoDirectory() {
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        assertThat(WorkerError.code(failure(router.route(new Message.LogTail(8, "main", 1, 10))
                                                  .await()))).isEqualTo("stale-attempt");
        assertThat(runner.callsStartingWith(List.of("docker", "logs"))).isEmpty();
        assertThat(config.paths()
                         .unitDir(ref)).doesNotExist();
    }

    @Test
    void unitRequest_ofTheHeldAttempt_isHandled() {
        unitOfAttempt("2");

        assertThat(route(new Message.CancelUnit(8, "main", 2))).isEqualTo(Reply.respond(new Message.Ack(Option.some("CANCELLING"))));
    }

    // ---- UnitReportAck is attempt-scoped (an ack of a superseded report) -------------------------------------
    /// The controller acks a superseded attempt-1 report (`Superseded` is acked) while attempt 2 runs here: removing by
    /// name would kill attempt 2's container. Nothing is removed and nothing is answered.
    @Test
    void unitReportAck_ofAnotherAttempt_removesNothing() {
        unitOfAttempt("2");
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        assertThat(route(new Message.UnitReportAck(8, "main", 1))).isEqualTo(Reply.silent());
        assertThat(runner.callsStartingWith(List.of("docker", "rm"))).isEmpty();
        assertThat(runner.callsStartingWith(NETWORK_REMOVE)).isEmpty();
        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("2");
    }

    /// No unit directory, but the container's label names another attempt: nothing is removed.
    @Test
    void unitReportAck_ofAnotherAttempt_byTheContainerLabel_removesNothing() {
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        assertThat(route(new Message.UnitReportAck(8, "main", 1))).isEqualTo(Reply.silent());
        assertThat(runner.callsStartingWith(List.of("docker", "rm"))).isEmpty();
    }

    @Test
    void unitReportAck_ofTheHeldAttempt_removesTheContainerThenTheNetwork() {
        unitOfAttempt("2");
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        assertThat(route(new Message.UnitReportAck(8, "main", 2))).isEqualTo(Reply.silent());
        assertThat(runner.indexOf(List.of("docker", "rm", "-f", CID))).isNotNegative();
        assertThat(runner.indexOf(NETWORK_REMOVE)).isGreaterThan(runner.indexOf(List.of("docker", "rm", "-f", CID)));
    }

    /// The ack takes the unit's turn: while a `StartUnit` or `DiscardUnit` of the unit holds it, the ack's attempt
    /// check and removal wait, so they never interleave with a replace of the attempt.
    @Test
    void unitReportAck_waitsForTheUnitsTurn() throws Exception {
        unitOfAttempt("2");
        runner.on(LABEL_INSPECT, 0, "2\n", "");
        var turn = Promise.<Unit> promise();
        var registry = router.registry();
        registry.inTurn(ref, () -> turn);
        var held = registry.turns()
                           .get(ref);

        var ack = router.route(new Message.UnitReportAck(8, "main", 2));
        // Either the ack queues behind the held turn, or (were it not in the unit's turn) it runs to its end: wait for
        // whichever happens, then tell them apart. A queued ack cannot start before the turn it waits for resolves.
        awaitUntil(() -> registry.turns()
                                 .get(ref) != held || ack.isResolved());

        assertThat(ack.isResolved()).as("the ack waits for the unit's turn").isFalse();
        assertThat(runner.indexOf(LABEL_INSPECT)).as("its attempt is not even checked while the turn is held").isNegative();
        assertThat(runner.callsStartingWith(List.of("docker", "rm"))).as("nothing removed while the turn is held").isEmpty();

        turn.succeed(Unit.unit());

        assertThat(ok(ack.await())).isEqualTo(Reply.silent());
        assertThat(runner.indexOf(List.of("docker", "rm", "-f", CID))).isNotNegative();
    }

    /// Spins until `condition` holds: it waits for an event, never for time to pass, and fails past 10 s.
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not reached").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    // ---- the unit's own frames carry its attempt ---------------------------------------------------------------
    @Test
    void reattachedUnit_reportsItsExitInTheDirectoryAttempt() throws Exception {
        unitOfAttempt("2");

        assertThat(route(new Message.Reattach(8, "main", 2, Duration.ofMinutes(90)))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));

        exit.countDown();

        assertThat(outbox.awaitPosted(Message.UnitExited.class, 1)).singleElement()
                                                                   .satisfies(exited -> assertThat(exited.attempt()).isEqualTo(2));
        assertThat(outbox.awaitPosted(Message.UnitReport.class, 1)).singleElement()
                                                                   .satisfies(chunk -> assertThat(chunk.attempt()).isEqualTo(2));
    }

    // ---- DiscardUnit ---------------------------------------------------------------------------------------------
    @Test
    void discardUnit_removesTheNamedAttempt_containerNetworkAndDirectory() {
        unitOfAttempt("1");

        assertThat(route(new Message.DiscardUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(runner.indexOf(REMOVE)).isNotNegative();
        assertThat(runner.indexOf(NETWORK_REMOVE)).isGreaterThan(runner.indexOf(REMOVE));
        assertThat(config.paths()
                         .unitDir(ref)).doesNotExist();
    }

    @Test
    void discardUnit_leavesAnotherAttemptUntouched_andAcks() {
        unitOfAttempt("2");

        assertThat(route(new Message.DiscardUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
        assertThat(runner.callsStartingWith(NETWORK_REMOVE)).isEmpty();
        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("2");
    }

    /// Idempotent: nothing left (a repeated discard) is acked and nothing is removed.
    @Test
    void discardUnit_isIdempotent_onAMissingDirectory() {
        unitOfAttempt("1");
        route(new Message.DiscardUnit(8, "main", 1));
        var removals = runner.callsStartingWith(REMOVE)
                             .size();

        assertThat(route(new Message.DiscardUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(runner.callsStartingWith(REMOVE)).hasSize(removals);
    }

    /// A container whose directory is gone is judged by its `temper.attempt` label.
    @Test
    void discardUnit_removesALabelledContainerWithoutADirectory() {
        runner.on(LABEL_INSPECT, 0, "1\n", "");

        assertThat(route(new Message.DiscardUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(runner.callsStartingWith(REMOVE)).hasSize(1);
    }

    @Test
    void discardUnit_leavesAContainerOfAnotherAttemptWithoutADirectory() {
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        route(new Message.DiscardUnit(8, "main", 1));

        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
    }

    /// m3: what the registry held of the discarded attempt is gone; a cancel of the next attempt (raced ahead of its
    /// start) stays.
    @Test
    void discardUnit_forgetsTheRegistryStateOfTheAttempt_keepingALaterAttemptsCancel() {
        unitOfAttempt("1");
        var registry = router.registry();
        registry.startWatching(ref, 1, CID);
        registry.hold(ref, CID);
        registry.cancel(ref, 2);

        route(new Message.DiscardUnit(8, "main", 1));

        assertThat(registry.watches(ref, CID)).isFalse();
        assertThat(registry.held()).isEmpty();
        assertThat(registry.isCancelled(ref, 2)).isTrue();
    }

    /// An exit processing of the attempt still in flight is waited for before anything is removed, so it never writes
    /// into a directory being deleted; its failure does not fail the discard.
    @Test
    void discardUnit_waitsForTheAttemptsExitProcessing_beforeRemovingAnything() throws Exception {
        unitOfAttempt("1");
        var processing = Promise.<Collected>promise();
        router.registry()
              .collection(ref, 1, () -> processing);

        var discard = router.route(new Message.DiscardUnit(8, "main", 1));
        Thread.sleep(200);

        assertThat(runner.callsStartingWith(REMOVE)).as("nothing removed while the exit is processed").isEmpty();
        assertThat(config.paths()
                         .unitDir(ref)).exists();

        processing.fail(Causes.cause("collect failed"));

        assertThat(ok(discard.await())).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(config.paths()
                         .unitDir(ref)).doesNotExist();
    }

    // ---- an unreadable attempt never wedges a DiscardUnit (verifier D3) --------------------------------------------
    @Test
    void discardUnit_ofAnUnreadableAttempt_withNoLabel_discardsTheUnit() {
        unitOfAttempt("x");

        assertThat(route(new Message.DiscardUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(runner.callsStartingWith(REMOVE)).hasSize(1);
        assertThat(config.paths()
                         .unitDir(ref)).doesNotExist();
    }

    @Test
    void discardUnit_ofAnUnreadableAttempt_leavesAContainerLabelledWithAnotherAttempt() {
        unitOfAttempt("x");
        runner.on(LABEL_INSPECT, 0, "2\n", "");

        assertThat(route(new Message.DiscardUnit(8, "main", 1))).isEqualTo(Reply.respond(new Message.Ack(Option.none())));
        assertThat(runner.callsStartingWith(REMOVE)).isEmpty();
        assertThat(files.exists(ref, UnitFile.MARKER)).isTrue();
    }

    @Test
    void discardUnit_ofAnUnreadableAttempt_removesAContainerLabelledWithTheNamedAttempt() {
        unitOfAttempt("x");
        runner.on(LABEL_INSPECT, 0, "1\n", "");

        route(new Message.DiscardUnit(8, "main", 1));

        assertThat(runner.callsStartingWith(REMOVE)).hasSize(1);
        assertThat(config.paths()
                         .unitDir(ref)).doesNotExist();
    }

    // ---- DiscardUnit and StartUnit of a unit never interleave (M2, verifier D2) ----------------------------------
    /// The discard takes the unit's turn: a `StartUnit(2)` routed while `DiscardUnit(1)` is removing attempt 1 waits
    /// for it, then finds nothing to replace (one removal, the discard's), and attempt 2's directory survives.
    @Test
    void discardUnit_racingAStartOfTheNextAttempt_neverDeletesItsDirectory() throws Exception {
        unitOfAttempt("1");
        var ran = new CountDownLatch(1);
        var removals = new AtomicInteger();
        runner.on(List.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}", NAME), 1, "", "Error: No such object: " + NAME)
              .on(List.of("docker", "network", "inspect", "-f", "{{range .IPAM.Config}}{{.Gateway}} {{end}}", NAME), 0, "192.0.2.30\n", "")
              .on(REMOVE, _ -> firstWaitsFor(removals, ran))
              .on(List.of("docker", "run"), _ -> ranAs(ran, CID));

        var discard = router.route(new Message.DiscardUnit(8, "main", 1));
        for (var wait = 0; wait < 300 && runner.callsStartingWith(REMOVE).isEmpty(); wait++) {
            Thread.sleep(10);
        }
        var start = router.route(Fixtures.startUnit(8, "main", 2, List.of(List.of("verify")), Duration.ofHours(1)));

        ok(discard.await());
        assertThat(ok(start.await())).as("the start succeeds once the discard is over")
                                     .isInstanceOfSatisfying(Reply.Respond.class,
                                                             respond -> assertThat(respond.message()).isInstanceOf(Message.UnitStarted.class));
        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("2");
        assertThat(files.exists(ref, UnitFile.MARKER)).isTrue();
        assertThat(runner.callsStartingWith(REMOVE)).as("the start ran after the discard: nothing left to replace")
                                                    .hasSize(1);
    }

    /// The discard reads the directory's attempt again just before deleting it: one that names another attempt by
    /// then is kept.
    @Test
    void unitDiscard_keepsADirectoryThatNamesAnotherAttemptByTheTimeItDeletes() {
        unitOfAttempt("2");

        ok(router.starter()
                 .unitDiscard()
                 .discard(ref, 1)
                 .await());

        assertThat(ok(files.text(ref, UnitFile.ATTEMPT)).or("")).isEqualTo("2");
    }

    private static Result<Completed> firstWaitsFor(AtomicInteger removals, CountDownLatch ran) {
        if (removals.getAndIncrement() == 0) {
            try {
                ran.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }
        return Result.success(FakeRunner.completed(0, "", ""));
    }

    private static Result<Completed> ranAs(CountDownLatch ran, String containerId) {
        ran.countDown();

        return Result.success(FakeRunner.completed(0, containerId + "\n", ""));
    }

    @Test
    void discardUnit_fails_whenTheRemovalFails() {
        unitOfAttempt("1");
        runner.on(REMOVE, 1, "", "Error response from daemon: busy");

        assertThat(WorkerError.code(failure(router.route(new Message.DiscardUnit(8, "main", 1))
                                                  .await()))).isEqualTo("docker");
        assertThat(files.exists(ref, UnitFile.ATTEMPT)).as("nothing claims the attempt is gone")
                                                      .isTrue();
    }
}
