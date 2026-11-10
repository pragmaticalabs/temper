package org.pragmatica.temper.worker.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerTimings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `GrantWatcher` (m1-design §1.2, maintainer ruling M1-14): the grant, the container, and every way bb-hold can end
/// before it. bb-hold's rc is ambiguous once granted (it is then argv's or the container's), so the outcome
/// branches on the `granted` line, never on the rc alone.
class GrantWatcherTest {
    private static final Duration TIMEOUT = Duration.ofHours(3);
    private static final Instant MARKER = HoldFixture.NOW.minus(Duration.ofHours(1));
    private static final String NAME = "temper-42-gate";
    private static final List<String> ID_INSPECT = List.of("docker", "inspect", "--type", "container", "-f", "{{.Id}}", NAME);

    @TempDir
    Path home;

    private HoldFixture fixture;
    private final UnitRef ref = Fixtures.ref(42, "gate");

    @BeforeEach
    void setUp() {
        fixture = new HoldFixture(home);
        setUpUnit();
    }

    private void setUpUnit() {
        ok(fixture.files.store(ref, UnitFile.MARKER, Long.toString(ok(EpochNanos.epochNanos(MARKER)))));
        ok(fixture.files.store(ref, UnitFile.IMAGE, "sha256:image"));
        ok(fixture.files.store(ref, UnitFile.FACTS_START, "{}"));
        fixture.runner.on(ID_INSPECT, 1, "", "Error: No such object: " + NAME);
        // The exit watcher is not under test: pretend one already owns the unit.
        fixture.registry.startWatching(ref, 1, "cid");
        fixture.registry.startHolding(ref, 1);
    }

    private GrantWatcher.Watch watch(Option<Promise<Integer>> exit, boolean canRetry) {
        return new GrantWatcher.Watch(ref,
                                       1,
                                      TIMEOUT,
                                      exit,
                                      canRetry
                                      ? some(GrantWatcher.Retry.retry(fixture.launch(ref), new HoldRequest("temper", 5)))
                                      : none(),
                                      none());
    }

    private GrantWatcher.Step tick(GrantWatcher.Watch watch) {
        return ok(fixture.grants.tick(watch)
                                .await());
    }

    private void err(String text) {
        ok(fixture.files.store(ref, UnitFile.BBHOLD_ERR, text));
    }

    private final CountDownLatch waitCalled = new CountDownLatch(1);
    private final CountDownLatch waitReleased = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        waitReleased.countDown();
    }

    /// A `docker wait` that blocks until the test ends (answered on a virtual thread by `FakeRunner`), then ends
    /// without an rc; the container then reads as gone, so the watcher only posts a loss and writes no unit file
    /// while the temporary directory is being removed.
    private Result<Completed> heldWait() {
        waitCalled.countDown();
        try {
            waitReleased.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Causes.cause("the test ended").result();
    }

    /// The fixture again, over a registry whose removals take 300 ms; the unit set up as in `setUp`.
    private void slowRegistry() {
        fixture = new HoldFixture(home, HoldFixture.slowRemovals(Duration.ofMillis(300)));
        setUpUnit();
    }

    /// The fixture's watcher with a longer pause before the duplicate retry.
    private GrantWatcher withPause(TimeSpan duplicateRetry) {
        var grants = fixture.grants;
        var fast = grants.timings();

        return new GrantWatcher(grants.files(),
                                grants.docker(),
                                grants.holders(),
                                grants.registry(),
                                grants.timer(),
                                grants.watcher(),
                                grants.facts(),
                                grants.outbox(),
                                grants.timers(),
                                grants.clock(),
                                new WorkerTimings.HoldTimings(fast.grantPoll(),
                                                              fast.containerWait(),
                                                              duplicateRetry,
                                                              fast.observationGap(),
                                                              fast.holderExit(),
                                                              fast.absentRecheck()),
                                grants.launcher(),
                                grants.reap());
    }

    private List<String> reapArgv() {
        return List.of(fixture.config.bbQueue()
                                     .executable(), BbQueueReap.REAP);
    }

    private Result<Completed> reapObserved() {
        fixture.events.add("reap after posting " + fixture.outbox.posted()
                                                                .size());
        return Result.success(FakeRunner.completed(0, "reap: nothing to move\n", ""));
    }

    private Message.UnitStartFailed onlyFailure() {
        assertThat(fixture.outbox.posted()).hasSize(1);
        return (Message.UnitStartFailed) fixture.outbox.posted()
                                                       .getFirst();
    }

    private static GrantWatcher.Watch next(GrantWatcher.Step step) {
        assertThat(step).isInstanceOf(GrantWatcher.Step.Next.class);
        return ((GrantWatcher.Step.Next) step).watch();
    }

    @Nested
    class Grant {
        /// The timeout counts from the grant: `start` is written when the `granted` line is first seen, and the
        /// timer is armed from it once the container exists (not from the marker, the enqueue time).
        @Test
        void grant_recordsTicketAndStartAtTheGrant_thenStartsTheContainer_andArmsTheTimerFromTheGrant() {
            err("bb-hold: queued ticket 1760-77 owner=temper priority=5\n");
            var waiting = next(tick(watch(some(Promise.promise()), false)));

            assertThat(fixture.files.exists(ref, UnitFile.START)).isFalse();

            var grantedAt = HoldFixture.NOW.plusSeconds(600);

            fixture.clock.advance(Duration.ofSeconds(600));
            err("bb-hold: queued ticket 1760-77 owner=temper priority=5\ngranted 1760-77\n");
            var granted = next(tick(waiting));

            assertThat(ok(fixture.files.text(ref, UnitFile.BBHOLD_TICKET))).isEqualTo(some("1760-77"));
            assertThat(ok(fixture.files.nanos(ref, UnitFile.START))).isEqualTo(some(ok(EpochNanos.epochNanos(grantedAt))));
            assertThat(fixture.outbox.posted()).isEmpty();

            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            assertThat(tick(granted)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(ok(fixture.files.text(ref, UnitFile.CONTAINER))).isEqualTo(some("cid"));
            assertThat(fixture.outbox.posted()).singleElement()
                                               .isInstanceOfSatisfying(Message.UnitStarted.class,
                                                                       started -> assertThat(started).extracting(Message.UnitStarted::containerId,
                                                                                                                 Message.UnitStarted::startedAt,
                                                                                                                 Message.UnitStarted::markerEpochNs,
                                                                                                                 Message.UnitStarted::imageId,
                                                                                                                 Message.UnitStarted::ticket)
                                                                                                     .containsExactly("cid",
                                                                                                                      grantedAt,
                                                                                                                      ok(EpochNanos.epochNanos(MARKER)),
                                                                                                                      "sha256:image",
                                                                                                                      some("1760-77")));
            // deadline = grant + 3 h; now = grant + 5 s
            assertThat(fixture.timers.last()
                                     .map(entry -> entry.delay()
                                                        .duration())).isEqualTo(some(TIMEOUT.minusSeconds(5)));
            assertThat(fixture.registry.held()).extracting(UnitRegistry.RunningUnit::ref)
                                               .containsExactly(ref);
            assertThat(fixture.registry.isHolding(ref)).isFalse();
        }

        /// A cancel that raced the grant: the fresh container is killed with `killed=cancel` before it is reported.
        @Test
        void grant_honoursACancelThatRacedIt() {
            fixture.registry.cancel(ref, 1);
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            tick(watch(some(Promise.promise()), false));

            assertThat(ok(fixture.files.text(ref, UnitFile.KILLED))).isEqualTo(some("cancel"));
            assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill", "cid"))).hasSize(1);
        }

        /// The container goes to the exit watcher (`docker wait`), which owns the unit from the grant on.
        @Test
        void grant_handsTheContainerToTheExitWatcher() throws InterruptedException {
            fixture.registry.stopWatching(ref);
            fixture.runner.on(List.of("docker", "wait", "cid"), _ -> heldWait())
                          .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "cid"), 1, "", "Error: No such object: cid");
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            tick(watch(some(Promise.promise()), false));

            assertThat(fixture.registry.running()).containsExactly(new UnitRegistry.RunningUnit(ref, "cid"));
            assertThat(waitCalled.await(5, TimeUnit.SECONDS)).isTrue();
        }

        /// A granted holder that is still alive may yet start the container: the wait is no deadline for it.
        @Test
        void aLiveGrantedHolder_withNoContainer_isWatchedOn_pastTheContainerWait() {
            fixture.holders.is(HolderStatus.HELD);
            err("granted 1760-77\n");
            var waiting = next(tick(watch(some(Promise.promise()), false)));

            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration()
                                          .multipliedBy(2));

            assertThat(tick(waiting)).isInstanceOf(GrantWatcher.Step.Next.class);
            assertThat(fixture.outbox.posted()).isEmpty();
        }

        /// A take-over step that fails (here `container` cannot be written) leaves the unit holding: the next tick
        /// runs the steps again, and the watch ends only once `UnitStarted` is posted.
        @Test
        void aFailedTakeOver_keepsHolding_andTheNextTickCompletesIt() throws Exception {
            var occupied = Files.createDirectories(fixture.files.path(ref, UnitFile.CONTAINER)
                                                                .resolve("occupied"));

            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            var retry = next(tick(watch(some(Promise.promise()), false)));

            assertThat(fixture.registry.isHolding(ref)).isTrue();
            assertThat(fixture.outbox.posted()).isEmpty();

            Files.delete(occupied);
            Files.delete(occupied.getParent());

            assertThat(tick(retry)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.outbox.posted()).singleElement()
                                               .isInstanceOf(Message.UnitStarted.class);
            assertThat(fixture.registry.isHolding(ref)).isFalse();
        }

        /// A container the worker still cannot take over once `containerWait` has passed since the grant runs
        /// without a timer: it is killed, and the unit ends `ERROR(infra: …)`.
        @Test
        void aTakeOverStillFailingAfterTheWait_killsTheContainer_andIsAnError() throws Exception {
            Files.createDirectories(fixture.files.path(ref, UnitFile.CONTAINER)
                                                 .resolve("occupied"));
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            var retry = next(tick(watch(some(Promise.promise()), false)));

            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration());

            assertThat(tick(retry)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill", "cid"))).hasSize(1);
            assertThat(onlyFailure().reason()).startsWith("infra: ");
            assertThat(fixture.registry.isHolding(ref)).isFalse();
        }

        /// A `UnitStarted` that cannot be built (here the facts probe keeps failing) is a take-over failure like the
        /// others: retried, and once `containerWait` has passed the container is killed and the unit is an `ERROR`.
        @Test
        void aUnitStartedThatCannotBeBuilt_isRetried_thenAbandoned() {
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");
            fixture.probe.set(() -> Causes.cause("facts unreadable").promise());

            var retry = next(tick(watch(some(Promise.promise()), false)));

            assertThat(fixture.registry.isHolding(ref)).isTrue();
            assertThat(fixture.outbox.posted()).isEmpty();

            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration());

            assertThat(tick(retry)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill", "cid"))).hasSize(1);
            assertThat(onlyFailure().reason()).isEqualTo("infra: facts unreadable");
        }

        /// The unit's registry state is gone when the abandoning step completes, not some time after it: `forget`
        /// is a chain step. The registry's removals are slowed down, as a late asynchronous callback would be, so an
        /// `onSuccessRun` would leave the unit known when the step's continuation reads the registry.
        @Test
        void anAbandonedUnit_isForgotten_whenItsStepCompletes() throws Exception {
            slowRegistry();
            Files.createDirectories(fixture.files.path(ref, UnitFile.CONTAINER)
                                                 .resolve("occupied"));
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            var retry = next(tick(watch(some(Promise.promise()), false)));

            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration());

            assertThat(ok(fixture.grants.tick(retry)
                                        .map(_ -> fixture.registry.knows(ref.job()))
                                        .await())).isFalse();
        }

        /// The abandoning step forgets attempt 1 outside the unit's turn: attempt 2, which replaced it meanwhile (here
        /// while the container is being killed), keeps the watch and timer its watch made it the owner of.
        @Test
        void anAbandonedAttempt_leavesTheWatchOfTheAttemptThatReplacedIt() throws Exception {
            var timers = new Fixtures.ManualTimers();

            abandonedWhile(() -> {
                fixture.registry.stopWatching(ref, "cid");
                fixture.registry.startWatching(ref, 2, "cid2");
                fixture.registry.replaceTimer(ref, timers.schedule(() -> {}, TimeSpan.timeSpan(1).hours()));
            });

            assertThat(fixture.registry.watches(ref, "cid2")).isTrue();
            assertThat(timers.scheduled()
                             .getFirst()
                             .cancelled()[0]).isFalse();
        }

        /// As above, for an attempt 2 whose exit processing (not a watch) made it the owner.
        @Test
        void anAbandonedAttempt_leavesTheTimerOfAnAttemptBeingCollected() throws Exception {
            var timers = new Fixtures.ManualTimers();

            abandonedWhile(() -> {
                fixture.registry.stopWatching(ref, "cid");
                fixture.registry.collection(ref, 2, Promise::promise);
                fixture.registry.replaceTimer(ref, timers.schedule(() -> {}, TimeSpan.timeSpan(1).hours()));
            });

            assertThat(fixture.registry.collections()).containsOnlyKeys(new UnitRegistry.AttemptKey(ref, 2));
            assertThat(timers.scheduled()
                             .getFirst()
                             .cancelled()[0]).isFalse();
        }

        /// Attempt 1's take-over keeps failing past `containerWait`, so its watch abandons it; `replaced` runs while
        /// its container is being killed, before the abandoning step's forget.
        private void abandonedWhile(Runnable replaced) throws Exception {
            Files.createDirectories(fixture.files.path(ref, UnitFile.CONTAINER)
                                                 .resolve("occupied"));
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "")
                          .on(List.of("docker", "kill", "cid"), _ -> {
                              replaced.run();
                              return Result.success(FakeRunner.completed(0, "", ""));
                          });

            var retry = next(tick(watch(some(Promise.promise()), false)));

            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration());

            assertThat(tick(retry)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(onlyFailure().reason()).startsWith("infra: ");
        }

        /// `UnitStarted` that cannot be posted (the link is down) is posted again by the next tick.
        @Test
        void anUnpostedUnitStarted_isPostedAgain() {
            err("granted 1760-77\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");
            fixture.outbox.down.set(true);

            var retry = next(tick(watch(some(Promise.promise()), false)));

            assertThat(fixture.registry.isHolding(ref)).isTrue();

            fixture.outbox.down.set(false);

            assertThat(tick(retry)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.outbox.posted()).singleElement()
                                               .isInstanceOf(Message.UnitStarted.class);
        }

        /// A watcher re-armed after a worker restart keeps the start the first worker recorded at the grant.
        @Test
        void reattachedWatch_keepsTheRecordedGrantStart() {
            var firstGrant = ok(EpochNanos.epochNanos(HoldFixture.NOW.minusSeconds(30)));

            ok(fixture.files.store(ref, UnitFile.START, Long.toString(firstGrant)));
            err("granted 1760-77\n");

            next(tick(GrantWatcher.Watch.watch(ref, 1, TIMEOUT)));

            assertThat(ok(fixture.files.nanos(ref, UnitFile.START))).isEqualTo(some(firstGrant));
        }
    }

    /// The same rc means different things before and after the grant.
    @Nested
    class ExitCodeAmbiguity {
        @Test
        void rc2_beforeTheGrant_isARefusal() {
            fixture.holders.is(HolderStatus.DEAD);
            err("");

            assertThat(tick(watch(some(Promise.success(2)), false))).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(onlyFailure()).extracting(Message.UnitStartFailed::verdictKind, Message.UnitStartFailed::reason)
                                     .containsExactly(StartFailureKind.ERROR, GrantWatcher.REFUSED);
            assertThat(fixture.runner.callsStartingWith(List.of("docker", "network", "rm", "temper-42-gate"))).hasSize(1);
            assertThat(fixture.registry.isHolding(ref)).isFalse();
        }

        /// After the grant, rc 2 is argv's (a failed `docker run`) or the container's, never a refusal; while the
        /// container may still appear (within `containerWait`), the watch goes on.
        @Test
        void rc2_afterTheGrant_withNoContainer_isAFailedDockerRun_onceTheWaitIsOver() {
            fixture.holders.is(HolderStatus.DEAD);
            err("granted 1760-77\nbb-hold: argv exited 2; released ticket 1760-77\n");
            var exit = some(Promise.success(2));
            var waiting = next(tick(watch(exit, false)));

            assertThat(fixture.outbox.posted()).isEmpty();

            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration());

            assertThat(tick(waiting)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(onlyFailure().reason()).startsWith("port-group: docker run failed (rc 2): ")
                                              .contains("argv exited 2")
                                              .isNotEqualTo(GrantWatcher.REFUSED);
        }

        /// A holder dead after its grant with no container may have left its ticket RUNNING (a `kill -9` cleans up
        /// nothing): `bb-queue reap` runs once, after the failure is posted (maintainer ruling A11, m1-design §14).
        @Test
        void aDeadHolder_withNoContainer_isReaped_afterTheFailureIsPosted() {
            fixture.holders.is(HolderStatus.DEAD);
            err("granted 1760-77\n");
            fixture.runner.on(reapArgv(), _ -> reapObserved());
            var waiting = next(tick(watch(none(), false)));

            assertThat(fixture.runner.callsStartingWith(reapArgv())).isEmpty();
            fixture.clock.advance(Fixtures.fastHold()
                                          .containerWait()
                                          .duration());

            assertThat(tick(waiting)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.runner.callsStartingWith(reapArgv())).containsExactly(reapArgv());
            assertThat(fixture.events).containsSubsequence("reap after posting " + 1);
        }

        /// A long `bbhold.err` is cut to its last `ERR_TAIL_CHARS`: the cause of a failed `docker run` is at the end.
        @Test
        void aFailedDockerRun_reportsTheEndOfALongBbholdErr() {
            var noise = "x".repeat(2 * GrantWatcher.ERR_TAIL_CHARS);

            assertThat(GrantWatcher.dockerRunFailed(some(125), noise + "docker: Error response from daemon: no such image"))
                .endsWith("docker: Error response from daemon: no such image")
                .doesNotContain("x".repeat(GrantWatcher.ERR_TAIL_CHARS));
        }

        /// After the grant, rc 143 with a container is a holder that left (the `HoldMonitor`'s case), not a cancel:
        /// the container is started and reported.
        @Test
        void rc143_afterTheGrant_withAContainer_isStarted_notCancelled() {
            fixture.registry.cancel(Fixtures.ref(1, "other"), 1);
            fixture.holders.is(HolderStatus.DEAD);
            err("granted 1760-77\nbb-hold: signal: released ticket 1760-77; container temper-42-gate left running\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            tick(watch(some(Promise.success(143)), false));

            assertThat(fixture.outbox.posted()).singleElement()
                                               .isInstanceOf(Message.UnitStarted.class);
        }
    }

    @Nested
    class BeforeTheGrant {
        @Test
        void aWaitingHolder_isWatchedOn() {
            err("bb-hold: queued ticket 1760-77\n");

            assertThat(tick(watch(some(Promise.promise()), false))).isInstanceOf(GrantWatcher.Step.Next.class);
            assertThat(fixture.outbox.posted()).isEmpty();
        }

        /// The holder is read before `bbhold.err`: a holder seen dead has written its last line.
        @Test
        void eachTick_readsTheHolderBeforeBbholdErr() {
            err("bb-hold: queued ticket 1760-77\n");
            fixture.events.clear();

            tick(watch(some(Promise.promise()), false));

            assertThat(fixture.events).containsExactly("holder:WAITING");
        }

        @Test
        void rc3_isRetriedOnce_thenAGrantStartsTheUnit() {
            fixture.holders.is(HolderStatus.DEAD);
            err("refusing: another bb-hold holds /home/x/work/bb-hold/temper-temper-42-gate (pid 7)\n");

            var retried = next(tick(watch(some(Promise.success(3)), true)));

            assertThat(fixture.events).contains("enqueue");
            assertThat(retried.retry().isEmpty()).isTrue();
            assertThat(fixture.outbox.posted()).isEmpty();

            fixture.holders.is(HolderStatus.HELD);
            err("granted 1760-78\n");
            fixture.runner.on(ID_INSPECT, 0, "cid\n", "");

            assertThat(tick(retried)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.outbox.posted()).singleElement()
                                               .isInstanceOf(Message.UnitStarted.class);
        }

        /// A holder that died before its grant is reaped once its failure is posted; a duplicate refusal that is
        /// retried is not (its retried holder must not race a reap).
        @Test
        void aDeadHolder_beforeTheGrant_isReaped_butNotOnTheDuplicateRetry() {
            fixture.holders.is(HolderStatus.DEAD);
            fixture.runner.on(reapArgv(), _ -> reapObserved());
            err("refusing: another bb-hold holds x\n");

            next(tick(watch(some(Promise.success(3)), true)));
            assertThat(fixture.runner.callsStartingWith(reapArgv())).isEmpty();

            err("bb-hold: queued ticket 1760-77\n");
            assertThat(tick(watch(some(Promise.success(9)), false))).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.runner.callsStartingWith(reapArgv())).containsExactly(reapArgv());
            assertThat(fixture.events).containsSubsequence("reap after posting " + 1);
        }

        @Test
        void rc3Twice_isAnError() {
            fixture.holders.is(HolderStatus.DEAD);
            err("refusing: another bb-hold holds x\n");
            var retried = next(tick(watch(some(Promise.success(3)), true)));

            fixture.launcher.exits.getLast()
                                  .succeed(3);

            assertThat(tick(retried)).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(onlyFailure().reason()).isEqualTo(GrantWatcher.DUPLICATE);
            assertThat(fixture.launcher.exits).hasSize(1);
        }

        @Test
        void rc143_withTheUnitCancelled_isCancelled() {
            fixture.registry.cancel(ref, 1);
            fixture.holders.is(HolderStatus.DEAD);
            err("bb-hold: signal: released ticket 1760-77; container temper-42-gate left running\n");

            tick(watch(some(Promise.success(143)), false));

            assertThat(onlyFailure().verdictKind()).isEqualTo(StartFailureKind.CANCELLED);
        }

        /// A signal nobody asked for (the unit is not in the cancelled set) is an error, not a cancel.
        @Test
        void rc143_withoutTheCancelledSet_isAnError() {
            fixture.holders.is(HolderStatus.DEAD);
            err("");

            tick(watch(some(Promise.success(143)), false));

            assertThat(onlyFailure()).extracting(Message.UnitStartFailed::verdictKind, Message.UnitStartFailed::reason)
                                     .containsExactly(StartFailureKind.ERROR, GrantWatcher.SIGNALLED);
        }

        @Test
        void rc9_withoutTheCancelledSet_isTheTicketLeavingTheQueue() {
            fixture.holders.is(HolderStatus.DEAD);
            err("");

            tick(watch(some(Promise.success(9)), false));

            assertThat(onlyFailure().reason()).isEqualTo(GrantWatcher.LEFT_QUEUE);
        }

        /// rc 9 is also how a cancelled ticket leaves bb-queue: with the unit cancelled it is the cancel.
        @Test
        void rc9_withTheUnitCancelled_isCancelled() {
            fixture.registry.cancel(ref, 1);
            fixture.holders.is(HolderStatus.DEAD);
            err("");

            tick(watch(some(Promise.success(9)), false));

            assertThat(onlyFailure().verdictKind()).isEqualTo(StartFailureKind.CANCELLED);
        }

        /// A watched unit whose holder files are gone has no holder: dead, so its exit is classified, never waited on.
        @Test
        void noHolderAtAll_isDead() {
            fixture.holders.status.set(none());

            tick(watch(some(Promise.success(2)), false));

            assertThat(onlyFailure().reason()).isEqualTo(GrantWatcher.REFUSED);
        }

        /// A failure report that cannot be posted fails the tick (the scheduler logs it and ticks again) and keeps the
        /// unit holding; the next tick decides and posts again.
        @Test
        void anUnpostedFailure_isPostedAgain() {
            fixture.holders.is(HolderStatus.DEAD);
            err("");
            fixture.outbox.down.set(true);
            var refused = watch(some(Promise.success(2)), false);

            Fixtures.failure(fixture.grants.tick(refused)
                                           .await());
            assertThat(fixture.registry.isHolding(ref)).isTrue();

            fixture.outbox.down.set(false);
            tick(refused);

            assertThat(onlyFailure().reason()).isEqualTo(GrantWatcher.REFUSED);
            assertThat(fixture.registry.isHolding(ref)).isFalse();
        }

        /// The watch has ended (the unit no longer holding) when the failing step completes: `stopHolding` is a chain
        /// step. The registry's removals are slowed down, as a late asynchronous callback would be, so an
        /// `onSuccessRun` would leave the unit holding when the step's continuation reads the registry.
        @Test
        void aFailedHold_isNoLongerHolding_whenItsStepCompletes() {
            slowRegistry();
            fixture.holders.is(HolderStatus.DEAD);
            err("");

            assertThat(ok(fixture.grants.tick(watch(some(Promise.success(2)), false))
                                        .map(_ -> fixture.registry.isHolding(ref))
                                        .await())).isFalse();
        }

        /// A cancelled unit is never enqueued again after a duplicate refusal, whether the cancel came before the
        /// refusal or during the pause before the retry.
        @Test
        void rc3_ofACancelledUnit_isCancelled_notRetried() {
            fixture.registry.cancel(ref, 1);
            fixture.holders.is(HolderStatus.DEAD);
            err("refusing: another bb-hold holds x\n");

            tick(watch(some(Promise.success(3)), true));

            assertThat(onlyFailure().verdictKind()).isEqualTo(StartFailureKind.CANCELLED);
            assertThat(fixture.events).doesNotContain("enqueue");
        }

        @Test
        void rc3_cancelledDuringThePause_isCancelled_notRetried() {
            fixture.holders.is(HolderStatus.DEAD);
            err("refusing: another bb-hold holds x\n");

            var refused = withPause(TimeSpan.timeSpan(1).seconds()).tick(watch(some(Promise.success(3)), true));

            fixture.registry.cancel(ref, 1);

            assertThat(ok(refused.await())).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(onlyFailure().verdictKind()).isEqualTo(StartFailureKind.CANCELLED);
            assertThat(fixture.events).doesNotContain("enqueue");
        }

        /// The one re-enqueue failing is final (`ERROR`), never retried on every tick.
        @Test
        void aFailedReEnqueue_isAnError() {
            fixture.holders.is(HolderStatus.DEAD);
            err("refusing: another bb-hold holds x\n");
            fixture.launcher.failure.set(some(Causes.cause("spawn failed")));

            assertThat(tick(watch(some(Promise.success(3)), true))).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(onlyFailure().reason()).startsWith(GrantWatcher.REENQUEUE_FAILED);
        }

        /// The watch of attempt 1 ends once the unit holds attempt 2 (attempt 1 replaced; protocol v3).
        @Test
        void aWatchOfAReplacedAttempt_endsOnceTheUnitHoldsALaterOne() {
            fixture.registry.forget(ref, 1);
            fixture.registry.startHolding(ref, 2);
            fixture.events.clear();

            assertThat(tick(watch(some(Promise.promise()), false))).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.events).isEmpty();
        }

        @Test
        void aForgottenUnit_endsTheWatch_withoutLookingAtAnything() {
            fixture.registry.forget(ref, 1);
            fixture.events.clear();

            assertThat(tick(watch(some(Promise.promise()), false))).isInstanceOf(GrantWatcher.Step.Done.class);
            assertThat(fixture.events).isEmpty();
        }
    }

    /// A watcher re-armed by `Reattach` has no rc: the `bbhold.err` line bb-hold printed for that exit decides.
    @Nested
    class AfterARestart {
        @Test
        void leftTheQueueLine_isTheTicketLeavingTheQueue() {
            fixture.holders.is(HolderStatus.DEAD);
            err("bb-hold: ticket 1760-77 left the queue while waiting (abandoned)\n");

            tick(GrantWatcher.Watch.watch(ref, 1, TIMEOUT));

            assertThat(onlyFailure().reason()).isEqualTo(GrantWatcher.LEFT_QUEUE);
        }

        @Test
        void noLine_isAnExitBeforeTheGrant() {
            fixture.holders.is(HolderStatus.DEAD);
            err("bb-hold: queued ticket 1760-77\n");

            tick(GrantWatcher.Watch.watch(ref, 1, TIMEOUT));

            assertThat(onlyFailure().reason()).isEqualTo(GrantWatcher.EXITED);
        }
    }

    /// The `UnitStarted` rebuilt from the grant's files names what is wrong with them (A11 live, 2026-10-14: a
    /// missing `start` was logged as corrupt).
    @Nested
    class StartRebuilt {
        @Test
        void unitStarted_saysMissing_notCorrupt_whenTheStartFileDoesNotExist() {
            assertThat(failureMessage()).contains(WorkerError.MissingState.FACTORY.apply(startFile())
                                                                          .message())
                                        .doesNotContain("corrupt");
        }

        @Test
        void unitStarted_saysCorrupt_whenTheStartFileDoesNotParse() {
            ok(fixture.files.store(ref, UnitFile.START, "not-nanos"));

            assertThat(failureMessage()).contains("Unit state file " + startFile() + " is corrupt")
                                        .doesNotContain("missing");
        }

        private Path startFile() {
            return fixture.files.path(ref, UnitFile.START);
        }

        /// `Result.all` composes the recorded files' failures, so the cause is asserted by its message.
        private String failureMessage() {
            return fixture.grants.unitStarted(ref, "cid")
                                 .await()
                                 .fold(Cause::message, message -> fail("expected a failure, got " + message));
        }
    }

    /// The pure classification table.
    @Nested
    class Classification {
        @Test
        void rcWins_overTheText_andTheTextDecides_withoutAnRc() {
            assertThat(GrantWatcher.beforeGrant(some(2), "bb-hold: signal: x", false, false)).isEqualTo(GrantWatcher.BeforeGrant.failed(StartFailureKind.ERROR,
                                                                                                                                       GrantWatcher.REFUSED));
            assertThat(GrantWatcher.beforeGrant(none(), "refusing: another bb-hold holds x", false, true)).isEqualTo(GrantWatcher.BeforeGrant.retry());
            assertThat(GrantWatcher.beforeGrant(none(), "usage: bb-hold\nrefusing: prio 'x' must be an integer", false, false)).isEqualTo(GrantWatcher.BeforeGrant.failed(StartFailureKind.ERROR,
                                                                                                                                                                         GrantWatcher.REFUSED));
            assertThat(GrantWatcher.beforeGrant(none(), "bb-hold: signal: released", true, false)).isEqualTo(GrantWatcher.BeforeGrant.failed(StartFailureKind.CANCELLED,
                                                                                                                                            GrantWatcher.CANCELLED));
            assertThat(GrantWatcher.beforeGrant(some(3), "", true, true)).isEqualTo(GrantWatcher.BeforeGrant.failed(StartFailureKind.CANCELLED,
                                                                                                                    GrantWatcher.CANCELLED));
            assertThat(GrantWatcher.beforeGrant(some(1), "", false, false)).isEqualTo(GrantWatcher.BeforeGrant.failed(StartFailureKind.ERROR,
                                                                                                                      GrantWatcher.EXITED + " (rc 1)"));
        }
    }
}
