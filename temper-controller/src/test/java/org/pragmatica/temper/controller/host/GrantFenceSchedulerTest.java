package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fake.FakeWorker;
import org.pragmatica.temper.controller.scheduler.Scheduler;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.QueuedUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;
import static org.assertj.core.api.Assertions.assertThat;


/// #111: the scheduler's round bound releases a host but does not cancel its grant call, so the grant carries the host's
/// fence, `retireAt - retireWindow`, and the store refuses a COMMIT at or past it (V18's trigger; `GrantFenceIT` proves
/// that against Postgres, the fake here applies the same rule by its commit clock). A grant transaction held past the
/// round bound and past `retireAt`, after the link stopped as retired and drained, never lands: the unit stays QUEUED,
/// nothing is active on the host, nothing is started there.
///
/// Probe P111s: `Scheduler.grantOn` passes `Option.none()` instead of `grantFence(retireWindow)` → the held grant commits
/// onto the stopped host ([#heldGrant_committingPastTheFence_neverLandsOnTheRetiredHost] red).
class GrantFenceSchedulerTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Duration WINDOW = ControllerConfig.Scheduling.DEFAULT_RETIRE_WINDOW;
    /// Round bound = 5 × this: the scheduler releases a held round after 250 ms.
    private static final TimeSpan TICK_BOUND = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();

    private LinkHarness harness;
    private Scheduler scheduler;
    private Instant retireAt;

    @BeforeEach
    void setUp() throws Exception {
        retireAt = Instant.now().plus(Duration.ofHours(1));
        over(LinkHarness.retiring(retireAt));
        harness.controlledNow.set(retireAt.minus(WINDOW).minusSeconds(1));
        harness.units.commitClock = harness.controlledNow::get;
    }

    private void over(LinkHarness next) {
        harness = next;
        harness.responder = request -> switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit start -> List.of(new Message.UnitStarted(start.job(),
                                                                            start.unit(),
                                                                            1,
                                                                            "c",
                                                                            "i",
                                                                            1L,
                                                                            Instant.now(),
                                                                            LinkHarness.facts(0),
                                                                            none()));
            default -> LinkHarness.ackEverything(request);
        };
        var links = HostLinks.hostLinks(List.of(harness.link), harness.jobs);

        scheduler = Scheduler.scheduler(links,
                                        harness.units,
                                        Duration.ofSeconds(30),
                                        Duration.ofSeconds(1200),
                                        WINDOW,
                                        50 * GIB,
                                        1,
                                        this::now,
                                        TICK_BOUND);
    }

    private Instant now() {
        return Option.option(harness.controlledNow.get()).or(Instant::now);
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private FakeWorker up() throws Exception {
        harness.link.start();
        var worker = harness.nextWorker();
        var hello = hello(LinkHarness.VERSION, List.of());

        worker.send(new Message.Hello(hello.protocol(),
                                      hello.workerVersion(),
                                      hello.host(),
                                      1,
                                      1,
                                      1,
                                      32,
                                      LinkHarness.facts(0),
                                      List.of()));
        waitFor(harness.link::isUp, "link UP");

        return worker;
    }

    private void queue(long id) {
        harness.units.queue.add(QueuedUnit.queuedUnit(id, -20, Instant.now(), 6144, false, UnitKind.MVN));
        harness.units.grantable.put(id,
                                    Granted.granted(UnitRef.unitRef(JobId.jobId(id).unwrap(),
                                                                    UnitName.MAIN),
                                                    id,
                                                    UnitKind.MVN,
                                                    List.of(List.of("mvn", "-B", "verify")),
                                                    Duration.ofMinutes(60),
                                                    6144,
                                                    Sha.sha("a".repeat(40)).unwrap(),
                                                    false,
                                                    0,
                                                    none(),
                                                    Instant.now(),
                                                    1));
    }

    /// The issue's case: decided before the window, the COMMIT held past the round bound, past `retireAt` and past the
    /// link's drained stop, then released. The COMMIT is refused; the unit stays QUEUED for another host.
    @Test
    void heldGrant_committingPastTheFence_neverLandsOnTheRetiredHost() throws Exception {
        var hold = Promise.<Unit> promise();

        harness.units.grantHold = some(hold);
        var worker = up();

        queue(1);
        scheduler.tick().await(WAIT);
        assertThat(harness.units.grantCalls).as("the round was decided").hasSize(1);
        assertThat(harness.units.grantOutcomes).as("the round bound released the host; the COMMIT is still held")
                  .isEmpty();
        harness.controlledNow.set(retireAt);
        worker.expect(Message.Shutdown.class, 10);
        hold.succeed(Unit.unit());
        waitFor(() -> !harness.units.grantOutcomes.isEmpty(), "the held COMMIT ended");
        assertThat(harness.units.grantOutcomes).containsExactly("fenced");
        assertThat(harness.units.queue).extracting(QueuedUnit::unitId)
                  .as("still QUEUED, placeable elsewhere")
                  .containsExactly(1L);
        assertThat(harness.units.active.getOrDefault(LinkHarness.HOST,
                                                     List.of())).as("nothing active on the retired host")
                  .isEmpty();
        assertThat(worker.count(Message.StartUnit.class)).as("nothing started on the retired host").isZero();
    }

    /// Control: the same held COMMIT released before the fence lands, and the unit is started on the host.
    @Test
    void heldGrant_committingBeforeTheFence_lands() throws Exception {
        var hold = Promise.<Unit> promise();

        harness.units.grantHold = some(hold);
        var worker = up();

        queue(1);
        scheduler.tick().await(WAIT);
        hold.succeed(Unit.unit());
        worker.expect(Message.StartUnit.class, 10);
        assertThat(harness.units.grantFences).as("the fence is retireAt - window")
                  .containsExactly(some(retireAt.minus(WINDOW)));
        assertThat(harness.units.grantOutcomes).containsExactly("committed [1]");
        assertThat(harness.units.queue).isEmpty();
    }

    /// A host without `retireAt` passes no fence: its grants are never refused at COMMIT.
    @Test
    void hostWithoutRetireAt_passesNoFence() throws Exception {
        harness.link.stop();
        over(new LinkHarness());
        var worker = up();

        queue(1);
        scheduler.tick().await(WAIT);
        worker.expect(Message.StartUnit.class, 10);
        assertThat(harness.units.grantFences).containsExactly(Option.<Instant> none());
        assertThat(harness.units.grantOutcomes).containsExactly("committed [1]");
    }
}
