package org.pragmatica.temper.acceptance.lost;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.acceptance.stack.DockerAvailable;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.UnitView;
import org.pragmatica.temper.store.api.HostName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.acceptance.lost.LostHosts.HOST_LOST;

/// m4-design §5.2, §12.2 `SideEffectNotRequeuedIT`, end to end on two real hosts: a unit with external side effects
/// on a host that goes LOST is never requeued (§2.3 L2); it ends `ERROR(host-lost)` and its job with it.
///
/// The `external` row is made at the store level, as §5.2 chose over a test-only recipe (no submittable recipe
/// declares `EXTERNAL` after R-M4-14): a real ModuleTest unit, once RUNNING on host X through the real grant and start,
/// gets `side_effects = 'external'` by SQL, which is exactly the V11 backfill of a pre-M4 row. Then X goes dark.
/// Proven: X LOST; the unit `ERROR(host-lost)` in attempt 1 with one lost-attempt row and no requeue
/// (`host_lost_requeues = 0`); the other host, UP throughout, never received it (no directory of the job in its HOME);
/// the job `ERROR(host-lost)`; X's return leaves all of it as it is.
///
/// Probe (P5): `LostSettle.notCancelled` ignores `sideEffects` → the unit is requeued and runs on the other host (red).
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SideEffectNotRequeuedIT {
    /// Any end, not only the expected ERROR: a wrong end (a requeued unit PASSED on the other host) must fail the named
    /// assertions below, not the wait.
    private static final Set<String> ENDED = Set.of("PASSED", "FAILED", "EMPTY", "VOID", "ERROR", "CANCELLED");
    private AcceptanceStack stack;
    private long job;
    private HostName lostHost;
    private HostName otherHost;
    private UnitView first;
    private String marked;
    private HostView lostView;
    private JobView settled;
    private JobView afterTicks;
    private JobView afterReturn;

    @BeforeAll
    void externalUnitOnALostHost() throws Exception {
        stack = AcceptanceStack.twoHosts("side-effect-lost", List.of("slow"), HOST_LOST);
        LostHosts.healthy(stack);
        job = stack.submitModuleTest("slow", LostHosts.MODULE);
        first = LostHosts.running(stack, job);
        lostHost = LostHosts.hostOf(first);
        otherHost = LostHosts.other(lostHost);
        marked = stack.sql("UPDATE units SET side_effects = 'external' WHERE job_id = " + job + " AND attempt = 1 AND state = 'RUNNING' RETURNING side_effects");

        stack.blackhole(lostHost);
        lostView = LostHosts.lost(stack, lostHost);
        settled = LostHosts.until(LostHosts.BOUND, "job " + job + " ended", () -> Optional.of(stack.job(job))
                                                                                                    .filter(view -> ENDED.contains(view.state().name())));
        Thread.sleep(Duration.ofSeconds(12));
        afterTicks = stack.job(job);

        stack.restore(lostHost);
        LostHosts.recovered(stack, lostHost);
        afterReturn = stack.job(job);
    }

    @AfterAll
    void close() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void precondition_theRunningUnitIsExternal_andItsHostWentLost() {
        assertThat(marked).as("the row was RUNNING in attempt 1 when marked").isEqualTo("external");
        assertThat(stack.job(job).units().getFirst().sideEffects()).isEqualTo(some("external"));
        assertThat(lostView.lost().map(loss -> loss.reason())).isEqualTo(some("link-down"));
    }

    @Test
    void external_endsErrorHostLost_neverRequeued() {
        var unit = settled.units().getFirst();
        assertThat(unit.state().name()).as(settled.toString()).isEqualTo("ERROR");
        assertThat(unit.reason()).isEqualTo(some("host-lost"));
        assertThat(unit.attempt()).as("never granted again").isEqualTo(1);
        assertThat(unit.lostAttempts()).hasSize(1);
        assertThat(unit.lostAttempts().getFirst().host()).isEqualTo(lostHost.value());
        assertThat(stack.sql("SELECT host_lost_requeues || ' ' || attempt || ' ' || state FROM units WHERE job_id = " + job))
                .as("no requeue recorded").isEqualTo("0 1 ERROR");
    }

    @Test
    void job_isErrorHostLost() {
        assertThat(settled.verdict()).isEqualTo(some("ERROR"));
        assertThat(settled.reason()).isEqualTo(some("host-lost"));
    }

    @Test
    void theOtherHost_neverReceivedIt() {
        assertThat(afterTicks.units().getFirst().attempt()).as("still attempt 1 after the LOST ticks").isEqualTo(1);
        assertThat(Files.exists(stack.workerHome(otherHost).resolve(".temper/jobs/" + job))).as("no workspace of the job on " + otherHost)
                                                                                          .isFalse();
    }

    @Test
    void theHostsReturn_leavesTheUnitEnded() {
        var unit = afterReturn.units().getFirst();
        assertThat(unit.state().name()).isEqualTo("ERROR");
        assertThat(unit.attempt()).isEqualTo(1);
        assertThat(afterReturn.state().name()).isEqualTo("ERROR");
    }
}
