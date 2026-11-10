package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `CancelUnit` of a hold unit (m1-design §1.7): the build is killed before its driver. A running container is
/// killed and bb-hold is never signalled (a signalled bb-hold leaves its container running); with no container,
/// bb-hold is signalled, then the container is looked up again by name and killed if the grant raced the signal.
class UnitCancellerHoldTest {
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "temper-42-gate");

    @TempDir
    Path home;

    private HoldFixture fixture;
    private final UnitRef ref = Fixtures.ref(42, "gate");
    private final Deque<String> states = new ArrayDeque<>();
    /// The container state once `states` is used up.
    private String steadyState = "running";

    @BeforeEach
    void setUp() {
        fixture = new HoldFixture(home);
        fixture.runner.on(STATUS, _ -> observed("status", states.isEmpty()
                                                          ? steadyState
                                                          : states.poll()))
                      .on(List.of("docker", "kill", "temper-42-gate"),
                          _ -> observed("kill killed=" + ok(fixture.files.text(ref, UnitFile.KILLED)).or("-"), ""));
    }

    private Result<Completed> observed(String event, String status) {
        fixture.events.add(event);
        return status.equals("absent")
               ? Result.success(FakeRunner.completed(1, "", "Error: No such object: temper-42-gate"))
               : Result.success(FakeRunner.completed(0, status, ""));
    }

    private UnitCanceller.CancelState cancel() {
        return cancel(fixture.holders);
    }

    private UnitCanceller.CancelState cancel(HolderProbe holders) {
        return ok(UnitCanceller.unitCanceller(fixture.docker,
                                              fixture.files,
                                              fixture.registry,
                                              holders,
                                              Fixtures.fastHold()
                                                      .holderExit())
                               .cancel(ref, 1)
                               .await());
    }

    private long holderReads() {
        return fixture.events.stream()
                             .filter(event -> event.startsWith("holder:"))
                             .count();
    }

    @Test
    void aRunningContainer_isKilled_andBbHoldIsNeverSignalled() {
        fixture.holders.is(HolderStatus.HELD);

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(fixture.events).containsExactly("holder:HELD", "status", "kill killed=cancel");
    }

    /// Waiting: `killed=cancel` first, SIGTERM, then the container looked up again by name and killed, because the
    /// grant raced the signal.
    @Test
    void aWaitingHolder_isSignalled_thenTheContainerIsRechecked_andKilledIfItAppeared() {
        fixture.holders.is(HolderStatus.WAITING);
        states.add("absent");
        states.add("running");

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(fixture.events).containsExactly("holder:WAITING",
                                                   "status",
                                                   "terminate",
                                                   "status",
                                                   "kill killed=cancel");
    }

    /// A grant that lands while the signalled holder is still alive: the container is killed at the next poll, not
    /// after the whole wait.
    @Test
    void aContainerAppearingDuringTheWait_isKilledAtOnce() {
        fixture.holders.is(HolderStatus.WAITING);
        fixture.holders.afterSignal.set(HolderStatus.WAITING);
        states.add("absent");
        states.add("absent");
        states.add("running");

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(fixture.events).last()
                                  .isEqualTo("kill killed=cancel");
        assertThat(holderReads()).isEqualTo(2);
    }

    /// A holder that cannot be read (a corrupt `bbhold.pid`) never stops the cancel: the container is killed by name.
    @Test
    void anUnreadableHolder_stillKillsTheRunningContainer() {
        ok(fixture.files.store(ref, UnitFile.BBHOLD_PID, "not a pid"));

        assertThat(cancel(HolderProbe.procHolderProbe(home.resolve("proc"), fixture.files))).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(fixture.events).containsExactly("status", "kill killed=cancel");
    }

    /// No container after the signal: `CANCELLING` while this worker's `GrantWatcher` will report the end
    /// (`UnitStartFailed{CANCELLED}` on bb-hold's exit 143), `CANCELLED` when nothing will.
    @Test
    void aWaitingHolder_withNoContainerAfterTheSignal_isCancellingOrCancelled() {
        fixture.holders.is(HolderStatus.WAITING);
        steadyState = "absent";

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLED);
        assertThat(ok(fixture.files.text(ref, UnitFile.KILLED))).isEqualTo(some("cancel"));
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();

        fixture.registry.startHolding(ref, 1);
        fixture.holders.is(HolderStatus.WAITING);

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLING);
    }

    /// A holder that outlives the wait does not stall the cancel: the container is still rechecked.
    @Test
    void aHolderThatDoesNotExit_stillGetsTheRecheck() {
        fixture.holders.is(HolderStatus.WAITING);
        fixture.holders.afterSignal.set(HolderStatus.WAITING);
        fixture.registry.startHolding(ref, 1);
        steadyState = "absent";

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLING);
        assertThat(fixture.events).last()
                                  .isEqualTo("status");
        assertThat(holderReads()).isEqualTo(UnitCanceller.EXIT_POLLS + 2);
    }

    /// A holder that cannot be signalled does not fail the cancel: the container is still watched for and the
    /// answer still given.
    @Test
    void aSignalThatFails_stillEndsTheCancel() {
        fixture.holders.is(HolderStatus.WAITING);
        fixture.holders.terminateFails.set(true);
        fixture.holders.script.add(HolderStatus.WAITING);
        fixture.holders.script.add(HolderStatus.DEAD);
        steadyState = "absent";

        assertThat(cancel()).isEqualTo(UnitCanceller.CancelState.CANCELLED);
        assertThat(ok(fixture.files.text(ref, UnitFile.KILLED))).isEqualTo(some("cancel"));
    }

    /// An unreadable holder makes the cancel a HOLD cancel even with no container: `killed=cancel` is recorded, so
    /// a container the grant may still start is attributed (a direct cancel with no container records nothing).
    @Test
    void anUnreadableHolder_withNoContainer_isCancelledAsAHoldUnit() {
        ok(fixture.files.store(ref, UnitFile.BBHOLD_PID, "not a pid"));
        steadyState = "absent";

        assertThat(cancel(HolderProbe.procHolderProbe(home.resolve("proc"), fixture.files))).isEqualTo(UnitCanceller.CancelState.CANCELLED);
        assertThat(ok(fixture.files.text(ref, UnitFile.KILLED))).isEqualTo(some("cancel"));
    }
}
