package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.diff.DiffEntry;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.DiffSide;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredSide;
import org.pragmatica.temper.store.api.StoredSides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// `ResultStore.diffSides` (m2-design §3.2, §3.5 `DiffIT`) over real rows, judged by the domain's [DiffOutcome]: two
/// Gates' rows give the exact sets; a side whose rows retention deleted (the door opened as retention opens it,
/// `SET LOCAL temper.retention = 'on'`) is PRUNED. Probe: drop the pruned check ([DiffSide#obstacle]) →
/// [#prunedSide_isRefused_notReportedAsRemoved] shows every test of the pruned side as added or removed: red.
@Tag("pg")
class DiffIT {
    private static StoreHarness harness;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void twoGates_giveTheExactSets_overTheirStoredRows() {
        var sha = TestJobs.sha();
        var a = harness.run(TestJobs.gateBySha(sha),
                            TestJobs.exited(0),
                            List.of(TestJobs.row("a.A", "one", Outcome.PASSED),
                                    TestJobs.row("a.A", "gone", Outcome.PASSED),
                                    TestJobs.row("a.A", "fixed", Outcome.FAILED),
                                    TestJobs.row("a.A", "dyn", Outcome.PASSED),
                                    TestJobs.row("a.A", "dyn", Outcome.PASSED)));
        var b = harness.run(TestJobs.gateBySha(TestJobs.sha()),
                            TestJobs.exited(1),
                            List.of(TestJobs.row("a.A", "one", Outcome.FAILED),
                                    TestJobs.row("a.A", "fixed", Outcome.PASSED),
                                    TestJobs.row("a.A", "dyn", Outcome.PASSED),
                                    TestJobs.row("b.B", "new", Outcome.ERROR)));

        var sides = sides(a, b);

        assertThat(sides.a().job().id()).isEqualTo(a);
        assertThat(sides.b().job().id()).isEqualTo(b);
        assertThat(sides.a().record().map(ResultRecord::verdict).or("")).isEqualTo("FAILED");
        assertThat(sides.b().record().map(ResultRecord::verdict).or("")).isEqualTo("FAILED");
        assertThat(sides.a().testcases()).hasSize(5);

        var diff = ((DiffOutcome.Compared) outcome(sides)).diff();

        assertThat(diff.added()).containsExactly(DiffEntry.diffEntry("b.B", "new", KeyOutcome.FAILED));
        assertThat(diff.removed()).containsExactly(DiffEntry.diffEntry("a.A", "gone", KeyOutcome.PASSED));
        assertThat(diff.newlyFailing()).containsExactly(DiffEntry.diffEntry("a.A", "one", KeyOutcome.FAILED));
        assertThat(diff.newlyPassing()).containsExactly(DiffEntry.diffEntry("a.A", "fixed", KeyOutcome.PASSED));
        assertThat(diff.duplicates()).isEqualTo(1);
    }

    /// F16: retention deleted A's testcases (its record stays, write-once). Computed anyway, every test of B would read
    /// as added; the diff is refused instead.
    @Test
    void prunedSide_isRefused_notReportedAsRemoved() {
        var a = passedGate();
        var b = passedGate();

        retentionDeletes("DELETE FROM testcases WHERE job_id = $1", a);

        var sides = sides(a, b);

        assertThat(sides.a().testcases()).isEmpty();
        assertThat(sides.a().record().map(record -> record.counts().testcases()).or(0)).isEqualTo(2);
        assertThat(outcome(sides)).isEqualTo(DiffOutcome.Refused.refused(DiffObstacle.PRUNED, List.of(DiffOutcome.Side.A)));
        assertThat(outcome(sides(b, a))).isEqualTo(DiffOutcome.Refused.refused(DiffObstacle.PRUNED, List.of(DiffOutcome.Side.B)));
    }

    /// Retention deletes in batches, one transaction each: a side read between two batches is pruned too.
    @Test
    void partlyPrunedSide_isRefused() {
        var a = passedGate();

        retentionDeletes("DELETE FROM testcases WHERE ctid IN (SELECT ctid FROM testcases WHERE job_id = $1 LIMIT 1)", a);

        var sides = sides(a, passedGate());

        assertThat(sides.a().testcases()).hasSize(1);
        assertThat(outcome(sides)).isEqualTo(DiffOutcome.Refused.refused(DiffObstacle.PRUNED, List.of(DiffOutcome.Side.A)));
    }

    /// A running job has no record and only the rows of the units that finished: not terminal.
    @Test
    void runningSide_hasNoRecord_andIsNotTerminal() {
        var a = passedGate();
        var running = harness.submit(TestJobs.gateBySha(TestJobs.sha()));

        harness.grantHead();
        harness.start(running);
        var sides = sides(a, running);

        assertThat(sides.b().record().isEmpty()).isTrue();
        assertThat(sides.b().job().state()).isEqualTo(JobState.RUNNING);
        assertThat(outcome(sides)).isEqualTo(DiffOutcome.Refused.refused(DiffObstacle.NOT_TERMINAL, List.of(DiffOutcome.Side.B)));
        harness.complete(running, TestJobs.exited(0), TestJobs.passing());
    }

    @Test
    void unknownJob_failsWithJobNotFound() {
        var a = passedGate();
        var unknown = JobId.jobId(a.value() + 1_000_000).unwrap();
        var read = harness.store.results().diffSides(a, unknown).await(PgFixture.WAIT);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isEqualTo(StoreError.JobNotFound.FACTORY.apply(unknown)));
    }

    /// One job on both sides: two reads of the same rows in one snapshot, nothing changed.
    @Test
    void sameJobOnBothSides_isAnEmptyDiff() {
        var a = passedGate();
        var outcome = outcome(sides(a, a));
        var diff = ((DiffOutcome.Compared) outcome).diff();

        assertThat(ExitCode.forDiff(outcome)).isEqualTo(ExitCode.PASSED);
        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
    }

    /// `diffBase` on Postgres: an unknown job fails first, whatever the base.
    @Test
    void diffBase_unknownJob_failsWithJobNotFound() {
        var unknown = JobId.jobId(passedGate().value() + 1_000_000).unwrap();
        var read = harness.store.results()
                          .diffBase(prefix(TestJobs.sha()), unknown, _ -> Result.success(none()))
                          .await(PgFixture.WAIT);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isEqualTo(StoreError.JobNotFound.FACTORY.apply(unknown)));
    }

    /// A selection that fails fails the read (the controller's 400 for a prefix of Gates at two SHAs).
    @Test
    void diffBase_failingSelection_failsTheRead() {
        var job = passedGate();
        var refusal = Causes.cause("prefix matches two SHAs");
        var read = harness.store.results()
                          .diffBase(prefix(TestJobs.sha()), job, _ -> refusal.result())
                          .await(PgFixture.WAIT);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isEqualTo(refusal));
    }

    /// No base named: the candidates come back, no side is read; a named base comes back with both sides, A the base.
    @Test
    void diffBase_readsSideAOnlyForANamedBase() {
        var sha = TestJobs.sha();
        var base = harness.run(TestJobs.gateBySha(sha), TestJobs.exited(0), TestJobs.passing());
        var job = passedGate();
        var none = harness.store.results().diffBase(prefix(sha), job, _ -> Result.success(Option.<JobId> none())).await(PgFixture.WAIT).unwrap();
        var named = harness.store.results().diffBase(prefix(sha), job, _ -> Result.success(Option.some(base))).await(PgFixture.WAIT).unwrap();

        assertThat(none.candidates()).extracting(row -> row.job().id()).containsExactly(base);
        assertThat(none.sides().isEmpty()).isTrue();
        assertThat(named.sides().map(sides -> sides.a().job().id())).isEqualTo(Option.some(base));
        assertThat(named.sides().map(sides -> sides.b().job().id())).isEqualTo(Option.some(job));
    }

    private static ShaPrefix prefix(Sha sha) {
        return ShaPrefix.shaPrefix(sha.value()).unwrap();
    }

    private static JobId passedGate() {
        return harness.run(TestJobs.gateBySha(TestJobs.sha()),
                           TestJobs.exited(0),
                           List.of(TestJobs.row("a.A", "one", Outcome.PASSED), TestJobs.row("a.A", "two", Outcome.PASSED)));
    }

    private static StoredSides sides(JobId a, JobId b) {
        return harness.store.results().diffSides(a, b).await(PgFixture.WAIT).unwrap();
    }

    private static DiffOutcome outcome(StoredSides sides) {
        return DiffOutcome.decide(side(sides.a()), side(sides.b()));
    }

    private static DiffSide side(StoredSide stored) {
        return DiffSide.diffSide(stored.job().id(),
                                 stored.job().state(),
                                 stored.record().map(ResultRecord::counts),
                                 stored.testcases());
    }

    /// As retention deletes: inside a transaction that opened the write-once door (V1 `temper_write_once`).
    private static void retentionDeletes(String sql, JobId job) {
        harness.raw.inTransaction(tx -> tx.completeQuery("SET LOCAL temper.retention = 'on'")
                                          .flatMap(_ -> tx.completeQuery(sql, job.value())))
                   .await(PgFixture.WAIT)
                   .unwrap();
    }
}
