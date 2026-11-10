package org.pragmatica.temper.worker.link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.ProcFixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.WorkerAssembly;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.unit.GrantWatcher;
import org.pragmatica.temper.worker.unit.StartReply;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The M1 requests through the wired [WorkerAssembly] (m1-design §1.4, §1.5, §4.3): `Reattach` of a hold unit
/// still waiting re-arms its `GrantWatcher` (reconcile rule 3), of a held unit hands it back to the `HoldMonitor`;
/// `GcJobs` answers `GcDone`.
class RouterHoldTest {
    private static final String NAME = "temper-8-gate";
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", NAME);

    @TempDir
    Path home;

    private Path proc;
    private FakeRunner runner;
    private UnitFiles files;
    private Router router;
    private Fixtures.ManualTimers timers;
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final UnitRef ref = Fixtures.ref(8, "gate");

    @BeforeEach
    void setUp() throws Exception {
        proc = Files.createDirectories(home.resolve("proc"));
        Files.writeString(proc.resolve("loadavg"), "0.1 0.2 0.3 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemAvailable: 5 kB\n");
        runner = new FakeRunner().on(List.of("docker", "ps"), 0, "", "");
        timers = new Fixtures.ManualTimers();

        WorkerConfig config = Fixtures.config(home.resolve(".temper"), proc, home);

        files = UnitFiles.unitFiles(config.paths());
        router = ok(WorkerAssembly.assemble(config, runner, TemperJson.mapper(), outbox, Instant::now, timers)
                                  .await()).router();
        ok(files.store(ref, UnitFile.MARKER, "1760356800000000000"));
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));
        ok(files.store(ref, UnitFile.BBHOLD_ERR, "bb-hold: queued ticket 1760-4242\n"));
    }

    private void liveHolder() throws Exception {
        var process = Files.createDirectories(proc.resolve("4242"));

        Files.writeString(process.resolve("stat"), ProcFixtures.stat(4242, 77));
        Files.write(process.resolve("cmdline"), ProcFixtures.cmdline(NAME));
    }

    private UnitRegistry registry() {
        return router.registry();
    }

    @Test
    void reattach_ofAWaitingHoldUnit_rearmsItsGrantWatcher() throws Exception {
        liveHolder();
        runner.on(STATUS, 1, "", "Error: No such object: " + NAME);

        var reply = ok(router.route(new Message.Reattach(8, "gate", 1, Duration.ofHours(3)))
                             .await());

        assertThat(ok(files.text(ref, UnitFile.TIMEOUT))).isEqualTo(Option.some("PT3H"));
        assertThat(registry().isHolding(ref)).as("the watch starts only after the Ack is written")
                                             .isFalse();

        ok(followedUp(reply, new Message.Ack(Option.none())));

        assertThat(registry().isHolding(ref)).isTrue();
        assertThat(timers.scheduled()).isNotEmpty();
    }

    /// A hold unit whose holder is gone and that never got a container is lost, as in M0.
    @Test
    void reattach_ofAHoldUnitWithADeadHolderAndNoContainer_isLost() {
        runner.on(STATUS, 1, "", "Error: No such object: " + NAME);

        var cause = failure(router.route(new Message.Reattach(8, "gate", 1, Duration.ofHours(3)))
                                  .await());

        assertThat(WorkerError.code(cause)).isEqualTo("lost");
        assertThat(registry().isHolding(ref)).isFalse();
    }

    /// A held unit's container runs: it is re-armed as in M0 and handed back to the `HoldMonitor`, so a holder
    /// that died while no worker was attached is caught on the next heartbeat.
    @Test
    void reattach_ofARunningHoldUnit_isMonitoredAgain() {
        runner.on(STATUS, 0, "running", "");
        // The exit watcher is not under test: one already owns the unit, so no background collection races cleanup.
        registry().startWatching(ref, 1, NAME);

        ok(router.route(new Message.Reattach(8, "gate", 1, Duration.ofHours(3)))
                 .await());

        assertThat(registry().held()).extracting(UnitRegistry.RunningUnit::ref)
                                     .containsExactly(ref);
    }

    /// A held unit's `UnitStarted` may have been lost with the link: a `Reattach` of its running container sends it
    /// again, ticket included, after the `Ack`.
    @Test
    void reattach_ofARunningHoldUnit_resendsUnitStarted_afterTheAck() {
        runner.on(STATUS, 0, "running", "");
        registry().startWatching(ref, 1, NAME);
        ok(files.store(ref, UnitFile.START, "1760356800000000000"));
        ok(files.store(ref, UnitFile.BBHOLD_TICKET, "1760-4242\n"));

        var reply = ok(router.route(new Message.Reattach(8, "gate", 1, Duration.ofHours(3)))
                             .await());

        assertThat(outbox.posted()).isEmpty();

        ok(followedUp(reply, new Message.Ack(Option.none())));

        assertThat(outbox.posted()).singleElement()
                                   .isInstanceOfSatisfying(Message.UnitStarted.class,
                                                           started -> assertThat(started).extracting(Message.UnitStarted::containerId,
                                                                                                     Message.UnitStarted::ticket)
                                                                                         .containsExactly(NAME, Option.some("1760-4242")));
    }

    /// Verification of F4: a `Reattach` that re-sends `UnitStarted` arms the exit watcher only after the re-send, so a
    /// container that exits right away cannot post its report ahead of the start. The sleep is the window in which an
    /// exit watcher armed with the reply would already have posted the exit.
    @Test
    void reattach_ofARunningHoldUnit_armsItsExitWatcher_onlyAfterTheResentStart() throws Exception {
        runner.on(STATUS, 0, "running", "")
              .on(List.of("docker", "wait", NAME), 0, "0\n", "");
        granted();

        var reply = ok(router.route(new Message.Reattach(8, "gate", 1, Duration.ofHours(3)))
                             .await());
        Thread.sleep(500);

        assertResentBeforeTheReport(reply);
    }

    /// Verification V2-3: a held unit's container that exits between the reconnect's scan (reconcile saw it running and
    /// sent `Reattach`) and the worker's status check gets its `UnitStarted` re-sent before the exit report, as on
    /// `Collect`.
    @Test
    void reattach_ofAHoldUnitThatExitedSinceTheScan_resendsUnitStarted_beforeTheReport() throws Exception {
        runner.on(STATUS, 0, "exited", "")
              .on(List.of("docker", "wait", NAME), 0, "0\n", "");
        granted();

        assertResentBeforeTheReport(ok(router.route(new Message.Reattach(8, "gate", 1, Duration.ofHours(3)))
                                             .await()));
    }

    /// Integration F4: a held unit whose first `UnitStarted` was lost and whose container exited before the reconnect
    /// is `Collect`ed (reconcile rule 1), not re-attached. Its `UnitStarted` goes again, ticket included, after the
    /// `Ack` and before the exit report, so the controller records the start before it completes the unit.
    @Test
    void collect_ofAnExitedHoldUnit_resendsUnitStarted_beforeTheReport() throws Exception {
        runner.on(STATUS, 0, "exited", "")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.ExitCode}}", NAME), 0, "0\n", "");
        granted();

        assertResentBeforeTheReport(ok(router.route(new Message.Collect(8, "gate", 1))
                                             .await()));
    }

    /// The same with the rc already on disk (reconcile rule 1's other case).
    @Test
    void collect_ofAHoldUnitWithAnRc_resendsUnitStarted_beforeTheReport() throws Exception {
        granted();
        ok(files.store(ref, UnitFile.RC, "0"));

        assertResentBeforeTheReport(ok(router.route(new Message.Collect(8, "gate", 1))
                                             .await()));
    }

    /// Verification F-4: only a unit granted its port group (it has a `bbhold.ticket`) has a start to re-send; a
    /// collected unit without one posts its exit report alone.
    @Test
    void collect_ofAUnitWithoutATicket_resendsNoUnitStarted() throws Exception {
        ok(files.store(ref, UnitFile.START, "1760356800000000000"));
        ok(files.store(ref, UnitFile.RC, "0"));

        ok(followedUp(ok(router.route(new Message.Collect(8, "gate", 1))
                               .await()),
                      new Message.Ack(Option.none())));
        outbox.awaitPosted(Message.UnitExited.class, 1);

        assertThat(outbox.posted()).noneMatch(Message.UnitStarted.class::isInstance);
    }

    /// Verification F-4, the FER of `resentBeforeCollect`: a start that cannot be re-sent (here its `start` file is
    /// corrupt) is dropped, and the exit is reported regardless.
    @Test
    void collect_ofAHoldUnitWhoseStartCannotBeResent_stillReportsTheExit() throws Exception {
        ok(files.store(ref, UnitFile.START, "not-nanos"));
        ok(files.store(ref, UnitFile.BBHOLD_TICKET, "1760-4242\n"));
        ok(files.store(ref, UnitFile.RC, "0"));

        ok(followedUp(ok(router.route(new Message.Collect(8, "gate", 1))
                               .await()),
                      new Message.Ack(Option.none())));

        assertThat(outbox.awaitPosted(Message.UnitExited.class, 1)).hasSize(1);
        assertThat(outbox.posted()).noneMatch(Message.UnitStarted.class::isInstance);
    }

    private void granted() {
        ok(files.store(ref, UnitFile.START, "1760356800000000000"));
        ok(files.store(ref, UnitFile.BBHOLD_TICKET, "1760-4242\n"));
    }

    private void assertResentBeforeTheReport(Reply reply) throws InterruptedException {
        assertThat(outbox.posted()).isEmpty();

        ok(followedUp(reply, new Message.Ack(Option.none())));
        outbox.awaitPosted(Message.UnitExited.class, 1);

        assertThat(outbox.posted().getFirst()).isInstanceOfSatisfying(Message.UnitStarted.class,
                                                                      started -> assertThat(started).extracting(Message.UnitStarted::containerId,
                                                                                                                Message.UnitStarted::ticket)
                                                                                                    .containsExactly(NAME, Option.some("1760-4242")));
        assertThat(outbox.posted().get(1)).isInstanceOf(Message.UnitExited.class);
    }

    /// `StartUnit` of a hold unit: the grant watch is the reply's follow-up, never started before it.
    @Test
    void aHoldingReply_startsTheGrantWatch_onlyAsItsFollowUp() {
        var holding = new Message.UnitHolding(8, "gate", 1, 1, null);
        var reply = Router.replied(StartReply.startReply(holding, GrantWatcher.Watch.watch(ref, 1, Duration.ofHours(3))),
                                   router.grants());

        assertThat(registry().isHolding(ref)).isFalse();

        ok(followedUp(reply, holding));

        assertThat(registry().isHolding(ref)).isTrue();
    }

    /// The reply must be `message` with a follow-up; runs the follow-up.
    private static Result<Unit> followedUp(Reply reply, Message message) {
        assertThat(reply).isInstanceOfSatisfying(Reply.Respond.class,
                                                 respond -> assertThat(respond.message()).isEqualTo(message));
        return ((Reply.Respond) reply).followUp()
                                      .map(Reply.FollowUp::run)
                                      .or(() -> Causes.cause("no follow-up").promise())
                                      .await();
    }

    @Test
    void gcJobs_repliesGcDone() {
        var reply = ok(router.route(new Message.GcJobs(List.of(8L, 9L)))
                             .await());

        assertThat(reply).isInstanceOfSatisfying(Reply.Respond.class,
                                                 respond -> assertThat(respond.message()).isEqualTo(new Message.GcDone(List.of(9L),
                                                                                                                       List.of(Message.GcDone.Kept.kept(8,
                                                                                                                                                        "unit gate has a marker and no rc")))));
    }
}
