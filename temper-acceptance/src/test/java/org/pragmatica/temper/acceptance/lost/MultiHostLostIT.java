package org.pragmatica.temper.acceptance.lost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.api.UnitView;
import org.pragmatica.temper.store.api.HostName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.acceptance.lost.LostHosts.HOST_LOST;

/// m4-design §12.3, end to end on two real hosts (two [org.pragmatica.temper.acceptance.stack.LocalWorker]s behind
/// the real controller, units in the runner image): a ModuleTest runs on host X; X goes dark
/// ([org.pragmatica.temper.acceptance.stack.LocalSsh#blackhole]: the worker dies, every ssh to X times out). Proven:
///
/// - X is LOST (`link-down`) after `hostLost`, not before, in `GET /hosts` and in `temper digest`;
/// - the settle requeues the unit once (§2.3 L4) and it runs on Y as attempt 2 and PASSES; the record holds one
///   result, attempt 2, with X's attempt 1 as its one lost attempt; `SlowProbeTest` is stored once;
/// - X returns: recovery settles, reconciles (rule 0 discards the superseded attempt 1: X's unit directory is gone),
///   then X is UP with its mark cleared; the unit is not run a third time.
///
/// Both workers use the local docker daemon, so the unit's container name is the same on X and Y: Y's
/// `StartUnit(2)` removes X's attempt-1 container (the adoption guard, §4.2) before X returns. On separate hosts X's
/// discard would remove it; here X's discard is proven on X's unit directory, which only X can see.
///
/// **No double run, independently of the shared daemon** (below): executions are counted as container STARTS on a live
/// subscription to the daemon's events, opened before the job is submitted ([LostHosts.Starts]), which keeps a start
/// after the container is removed: exactly two, labelled attempt 1 then
/// attempt 2, and none after X's return. Completions are counted in the store: `SlowProbeTest` stored once, evidence
/// only under `a2`, the attempt-1 row ended `host-lost` and nothing else. A stale attempt-1 REPORT (a worker that
/// survives the outage) cannot arise here, since the blackhole kills X's worker; that path is `LateReportRejectedIT`'s
/// (controller), and a run on two real daemons is W5 (m4-design §14.2).
///
/// Probes: `Reconciler.superseded` → `false` (rule 0 off) → X's attempt-1 directory survives recovery (red); `GRANT`
/// without `attempt + 1` (P3) → Y adopts X's attempt-1 container instead of starting its own: the start count is red.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiHostLostIT {
    private AcceptanceStack stack;
    private long job;
    private HostName lostHost;
    private HostName otherHost;
    private UnitView first;
    private Instant blackholedAt;
    private HostView lostView;
    private String lostDigest;
    private boolean attemptOneDirBeforeReturn;
    private String verdict;
    private Instant restoredAt;
    private HostView recoveredView;
    private String recoveredDigest;
    private JobView finalJob;
    private ResultRecordDto record;
    private List<TestcaseDto> testcases;
    private LostHosts.Starts starts;
    private List<String> startsBeforeReturn;

    @BeforeAll
    void hostGoesDarkAndReturns() throws Exception {
        stack = AcceptanceStack.twoHosts("multi-host-lost", List.of("slow"), HOST_LOST);
        LostHosts.healthy(stack);
        starts = LostHosts.Starts.watch();
        job = stack.submitModuleTest("slow", LostHosts.MODULE);
        first = LostHosts.running(stack, job);
        lostHost = LostHosts.hostOf(first);
        otherHost = LostHosts.other(lostHost);

        blackholedAt = Instant.now();
        stack.blackhole(lostHost);
        lostView = LostHosts.lost(stack, lostHost);
        lostDigest = LostHosts.digestLine(stack, lostHost);

        verdict = stack.awaited(job, Duration.ofMinutes(15));
        attemptOneDirBeforeReturn = Files.isDirectory(attemptOneDir());
        startsBeforeReturn = starts.of(job, first.name());

        restoredAt = Instant.now();
        stack.restore(lostHost);
        recoveredView = LostHosts.recovered(stack, lostHost);
        recoveredDigest = LostHosts.digestLine(stack, lostHost);
        finalJob = stack.job(job);
        record = stack.api.result(job).await().expect("the job's record");
        testcases = stack.api.testcases(job).await().expect("the job's testcases");
    }

    @AfterAll
    void close() {
        if (starts != null) {
            starts.close();
        }
        if (stack != null) {
            stack.close();
        }
    }

    private Path attemptOneDir() {
        return stack.unitDir(lostHost, job, first.name());
    }

    @Test
    void lost_afterHostLost_notBefore_linkDown() {
        assertThat(lostView.lost().isPresent()).as(lostView.toString()).isTrue();
        var mark = lostView.lost().unwrap();
        assertThat(mark.reason()).isEqualTo("link-down");
        assertThat(Duration.between(blackholedAt, mark.at())).as("declared after hostLost").isGreaterThanOrEqualTo(HOST_LOST);
        assertThat(lostView.link()).as("the link itself is not UP").isNotEqualTo("UP");
    }

    @Test
    void digest_showsLostThenRecovery() {
        assertThat(lostDigest).contains("state=LOST").contains("LOST(link-down since=");
        assertThat(recoveredDigest).contains("link=UP state=UP").doesNotContain("LOST");
    }

    @Test
    void requeuedOnce_ranOnTheOtherHostAsAttempt2_passed() {
        var unit = finalJob.units().getFirst();
        assertThat(first.attempt()).as("the first grant").isEqualTo(1);
        assertThat(verdict).as(finalJob.toString()).contains("PASSED");
        assertThat(finalJob.state().name()).isEqualTo("PASSED");
        assertThat(unit.state().name()).isEqualTo("PASSED");
        assertThat(unit.host()).isEqualTo(some(otherHost.value()));
        assertThat(unit.attempt()).as("granted twice, never a third time").isEqualTo(2);
        assertThat(unit.lostAttempts()).as(unit.toString()).hasSize(1);
        assertThat(unit.lostAttempts().getFirst().attempt()).isEqualTo(1);
        assertThat(unit.lostAttempts().getFirst().host()).isEqualTo(lostHost.value());
        assertThat(stack.sql("SELECT host_lost_requeues || ' ' || attempt FROM units WHERE job_id = " + job)).isEqualTo("1 2");
        assertThat(stack.sql("SELECT count(*) || ' ' || min(outcome) FROM unit_attempts a JOIN units u ON u.id = a.unit_id WHERE u.job_id = " + job))
                .as("one lost attempt row").isEqualTo("1 host-lost");
    }

    @Test
    void record_holdsOneResult_attempt2_andTheLostAttempt() {
        assertThat(record.verdict()).isEqualTo("PASSED");
        assertThat(record.units()).hasSize(1);
        var unit = record.units().getFirst();
        assertThat(unit.attempt()).isEqualTo(some(2));
        assertThat(unit.host()).isEqualTo(some(otherHost.value()));
        assertThat(unit.lostAttempts()).hasSize(1);
        assertThat(testcases.stream().filter(testcase -> testcase.className().equals(LostHosts.SLOW)).count())
                .as("the slow test is stored once").isEqualTo(1);
    }

    @Test
    void recovery_discardsTheSupersededAttempt_thenUp() throws Exception {
        assertThat(attemptOneDirBeforeReturn).as("X still held attempt 1's directory while dark").isTrue();
        assertThat(attemptOneDir()).as("rule 0 discarded attempt 1 on X's return").doesNotExist();
        assertThat(LostHosts.containers(job, first.name(), 1)).as("no attempt-1 container left").isEmpty();
        assertThat(recoveredView.state()).isEqualTo("UP");
        assertThat(recoveredView.lost().isEmpty()).isTrue();
        assertThat(stack.sql("SELECT state || ' ' || (cleared_lost_at IS NOT NULL) FROM hosts WHERE name = '" + lostHost.value() + "'"))
                .as("the mark is cleared and kept").isEqualTo("UP true");
    }

    @Test
    void executions_exactlyTwoStarts_attempt1ThenAttempt2_noneAfterReturn() throws Exception {
        assertThat(startsBeforeReturn).as("container starts of the unit, by attempt label").containsExactly("1", "2");
        Thread.sleep(Duration.ofSeconds(12));
        assertThat(starts.of(job, first.name())).as("no start after X's return").containsExactly("1", "2");
    }

    @Test
    void completions_onlyAttempt2_storedOnce() {
        var unitEvidence = stack.evidenceRoot().resolve("jobs/" + job + "/units/" + first.name());
        assertThat(unitEvidence.resolve("a1")).as("attempt 1 never completed: no evidence").doesNotExist();
        assertThat(unitEvidence.resolve("a2")).as("attempt 2's evidence").isDirectory();
        assertThat(stack.sql("SELECT count(*) FROM testcases t JOIN units u ON u.id = t.unit_id WHERE u.job_id = " + job
                             + " AND t.class = '" + LostHosts.SLOW + "'")).as("stored once").isEqualTo("1");
    }

    @Test
    void noDoubleRun_afterRecovery_theUnitStaysAttempt2() throws Exception {
        Thread.sleep(Duration.ofSeconds(12));
        var unit = stack.job(job).units().getFirst();
        assertThat(unit.state().name()).isEqualTo("PASSED");
        assertThat(unit.attempt()).isEqualTo(2);
        assertThat(Files.exists(stack.unitDir(lostHost, job, first.name()))).isFalse();
        assertThat(restoredAt).isAfter(blackholedAt);
    }
}
