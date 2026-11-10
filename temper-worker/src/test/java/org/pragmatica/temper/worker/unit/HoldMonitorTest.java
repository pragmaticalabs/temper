package org.pragmatica.temper.worker.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.hold.HoldAction;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.ProcFixtures;
import org.pragmatica.temper.worker.config.UnitFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `HoldMonitor` (m1-design §1.4): `VOID(port-group-lost)` plus a kill only on two dead holder observations, both
/// read BEFORE the container, and a running container. The read order is the precondition `HoldRules` cannot see.
class HoldMonitorTest {
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "cid");

    @TempDir
    Path home;

    private HoldFixture fixture;
    private final UnitRef ref = Fixtures.ref(42, "gate");
    private String containerState = "running";

    @BeforeEach
    void setUp() {
        fixture = new HoldFixture(home);
        fixture.runner.on(STATUS, _ -> observed("container", containerState))
                      .on(List.of("docker", "kill", "cid"),
                          _ -> observed("kill void=" + ok(fixture.files.text(ref, UnitFile.VOID)).or("-"), ""))
                      .on(reapArgv(), _ -> observed("reap", "reaped 1760-77: orphaned\n"));
    }

    private List<String> reapArgv() {
        return List.of(fixture.config.bbQueue()
                                     .executable(), BbQueueReap.REAP);
    }

    private Result<Completed> observed(String event, String stdout) {
        fixture.events.add(event);
        return Result.success(FakeRunner.completed(0, stdout, ""));
    }

    private HoldMonitor monitor(HolderProbe holders) {
        return HoldMonitor.holdMonitor(holders, fixture.docker, fixture.files, fixture.registry, fixture.reap, Fixtures.fastHold()
                                                                                                         .observationGap());
    }

    private HoldAction round() {
        return ok(monitor(fixture.holders).round(ref, "cid")
                                          .await());
    }

    @Test
    void twoDeadObservations_andARunningContainer_voidTheUnit_thenKillIt() {
        fixture.holders.is(HolderStatus.DEAD);

        assertThat(round()).isEqualTo(HoldAction.VOID_PORT_GROUP_LOST);
        assertThat(ok(fixture.files.text(ref, UnitFile.VOID))).isEqualTo(some("port-group-lost"));
        assertThat(fixture.events).endsWith("kill void=port-group-lost", "reap");
    }

    /// maintainer ruling A11 (m1-design §14): the dead holder's ticket is released by `bb-queue reap`, run once, after the
    /// kill, with exactly that argv (the listing's executable).
    @Test
    void aVoidedUnit_isReapedOnce_afterTheKill() {
        fixture.holders.is(HolderStatus.DEAD);

        round();

        assertThat(fixture.runner.callsStartingWith(List.of(fixture.config.bbQueue()
                                                                          .executable()))).containsExactly(reapArgv());
        assertThat(fixture.runner.indexOf(reapArgv())).isGreaterThan(fixture.runner.indexOf(List.of("docker", "kill")));
    }

    /// A failed `docker kill` (a slow daemon, a CLI timeout after the kill took effect) is still followed by the reap:
    /// the next round would read the container EXITED and decide `NONE`, so this is the only reap the dead holder
    /// gets. The kill's failure still fails the round.
    @Test
    void aFailedKill_isStillReaped_afterTheKill() {
        fixture.holders.is(HolderStatus.DEAD);
        fixture.runner.on(List.of("docker", "kill", "cid"),
                          _ -> failedKill());

        assertThat(monitor(fixture.holders).round(ref, "cid")
                                           .await()
                                           .isFailure()).as("the kill's failure fails the round")
                                                        .isTrue();
        assertThat(ok(fixture.files.text(ref, UnitFile.VOID))).isEqualTo(some("port-group-lost"));
        assertThat(fixture.events).endsWith("kill failed", "reap");
    }

    private Result<Completed> failedKill() {
        fixture.events.add("kill failed");
        return Result.success(FakeRunner.completed(1, "", "Cannot connect to the Docker daemon\n"));
    }

    /// An older bb-queue (one without the subcommand, whatever it prints or exits with): a warning, and the verdict
    /// stands.
    @Test
    void anOldBbQueue_withoutReap_leavesTheVerdictUnchanged() {
        fixture.holders.is(HolderStatus.DEAD);
        fixture.runner.on(reapArgv(), 2, "", "usage: bb-queue [N]\n");

        assertThat(round()).isEqualTo(HoldAction.VOID_PORT_GROUP_LOST);
        assertThat(ok(fixture.files.text(ref, UnitFile.VOID))).isEqualTo(some("port-group-lost"));
        assertThat(fixture.runner.callsStartingWith(reapArgv())).hasSize(1);
    }

    /// A reap that cannot run at all (missing tool, timeout) leaves the verdict unchanged too.
    @Test
    void aFailedReap_leavesTheVerdictUnchanged() {
        fixture.holders.is(HolderStatus.DEAD);
        fixture.runner.failing(reapArgv(), Causes.cause("timed out"));

        assertThat(round()).isEqualTo(HoldAction.VOID_PORT_GROUP_LOST);
        assertThat(ok(fixture.files.text(ref, UnitFile.VOID))).isEqualTo(some("port-group-lost"));
    }

    /// Holder, holder, container: never the container first (bb-hold exits because its container did).
    @Test
    void aRound_readsTheHolderTwice_beforeTheContainer() {
        fixture.holders.is(HolderStatus.HELD);

        round();

        assertThat(fixture.events).containsExactly("holder:HELD", "holder:HELD", "container");
    }

    @Test
    void aSingleDeadObservation_isNotEnough() {
        fixture.holders.is(HolderStatus.HELD);
        fixture.holders.script.add(HolderStatus.DEAD);

        assertThat(round()).isEqualTo(HoldAction.NONE);
        fixture.holders.script.add(HolderStatus.HELD);
        fixture.holders.is(HolderStatus.DEAD);

        assertThat(round()).isEqualTo(HoldAction.NONE);
        assertThat(fixture.files.exists(ref, UnitFile.VOID)).isFalse();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();
    }

    /// The clean end: the container exited, so the holder released and exited after it. Nothing is voided.
    @Test
    void aDeadHolder_ofAnExitedContainer_isTheCleanEnd() {
        fixture.holders.is(HolderStatus.DEAD);
        containerState = "exited";

        assertThat(round()).isEqualTo(HoldAction.NONE);
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();
        assertThat(fixture.runner.callsStartingWith(reapArgv())).as("a normal exit is not reaped").isEmpty();
    }

    /// The identity is pid + start ticks: a pid recycled by an unrelated process (another start time) is a dead
    /// holder, even though `/proc/<pid>` exists with a matching cmdline.
    @Test
    void aRecycledPid_countsAsDead() throws Exception {
        var proc = home.resolve("proc");
        var process = Files.createDirectories(proc.resolve("4242"));

        Files.writeString(process.resolve("stat"), ProcFixtures.stat(4242, 99));
        Files.write(process.resolve("cmdline"), ProcFixtures.cmdline("temper-42-gate"));
        ok(fixture.files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));
        ok(fixture.files.store(ref, UnitFile.BBHOLD_ERR, "granted 1760-77\n"));

        var action = ok(monitor(HolderProbe.procHolderProbe(proc, fixture.files)).round(ref, "cid")
                                                                                 .await());

        assertThat(action).isEqualTo(HoldAction.VOID_PORT_GROUP_LOST);
    }

    /// A unit with no bb-hold files has no holder to lose: it reads as held, so its running container is left alone.
    @Test
    void aUnitWithoutAHolder_isNeverVoided() {
        var action = ok(monitor(HolderProbe.procHolderProbe(home.resolve("proc"), fixture.files)).round(ref, "cid")
                                                                                                 .await());

        assertThat(action).isEqualTo(HoldAction.NONE);
        assertThat(fixture.files.exists(ref, UnitFile.VOID)).isFalse();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();
        assertThat(fixture.runner.callsStartingWith(reapArgv())).as("a non-hold unit is not reaped").isEmpty();
    }

    /// A round that cannot observe (a corrupt `bbhold.pid`) decides nothing: no void, no kill, `NONE` for the tick.
    @Test
    void tick_aFailedRound_decidesNothing() {
        ok(fixture.files.store(ref, UnitFile.BBHOLD_PID, "not a pid"));
        fixture.registry.hold(ref, "cid");

        assertThat(ok(monitor(HolderProbe.procHolderProbe(home.resolve("proc"), fixture.files)).tick()
                                                                                               .await())).containsExactly(HoldAction.NONE);
        assertThat(fixture.files.exists(ref, UnitFile.VOID)).isFalse();
        assertThat(fixture.runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();
    }

    @Test
    void tick_runsARound_forEveryHeldUnit_only() {
        fixture.holders.is(HolderStatus.DEAD);
        fixture.registry.hold(ref, "cid");

        assertThat(ok(monitor(fixture.holders).tick()
                                              .await())).containsExactly(HoldAction.VOID_PORT_GROUP_LOST);
        fixture.registry.forget(ref, 1);
        assertThat(ok(monitor(fixture.holders).tick()
                                              .await())).isEmpty();
    }
}
