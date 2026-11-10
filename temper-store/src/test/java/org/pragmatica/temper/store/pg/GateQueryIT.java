package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// `JobStore.gateCandidates` (m2-design §1.1, §1.6 `GateQueryIT`): at one SHA, only pushed, non-superseded,
/// non-REFUSED jobs of recipe `gate` are candidates. Every test puts one pushed PASSED Gate and one job that must
/// not count, a FAILED one where it has a verdict, at a fresh SHA, so each filter is its own red row:
///
/// | probe | red |
/// |---|---|
/// | drop `NOT j.unpushed` | [#unpushedRow_isNotACandidate] |
/// | drop `NOT j.superseded` | [#supersededKeepGate_isNotACandidate] |
/// | drop `j.state <> 'REFUSED'` | [#refusedGate_isNotACandidate] |
/// | drop `j.recipe_kind = 'gate'` | [#moduleTest_isNotACandidate], [#rerunJob_isNotACandidate] |
@Tag("pg")
class GateQueryIT {
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

    /// The baseline every other row is checked against: a pushed Gate that PASSED answers, with its record.
    @Test
    void pushedGate_isTheOneCandidate_withItsJudgesAndRecord() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);
        var rows = GateAnswers.rows(harness.store, sha);

        assertThat(GateAnswers.ids(rows)).containsExactly(gate);
        assertThat(rows.getFirst().submitJudge()).isEqualTo(Judge.UNJUDGED);
        assertThat(rows.getFirst().grantJudges()).containsExactly(Judge.UNJUDGED);
        assertThat(rows.getFirst().resultJudge()).isEqualTo(some(Judge.UNJUDGED));
        assertThat(rows.getFirst().record().map(record -> record.verdict())).isEqualTo(some("PASSED"));
        assertThat(rows.getFirst().job().units()).hasSize(1);
        assertThat(GateAnswers.evidence(harness.store, sha).answer()).isEqualTo(GateAnswer.PASSED);
    }

    /// `unpushed` is set by nothing in M2 (a Patch/Bundle source, M3); the row is written directly.
    @Test
    void unpushedRow_isNotACandidate() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);
        var unpushed = failedGate(sha);

        harness.execute("UPDATE jobs SET unpushed = true WHERE id = $1", unpushed.value());

        assertThat(GateAnswers.ids(GateAnswers.rows(harness.store, sha))).containsExactly(gate);
    }

    /// A `keep` Gate that ran on after its ref moved (Q-M2-3, ruled R-M2-9): its verdict does not count.
    @Test
    void supersededKeepGate_isNotACandidate() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);
        var ref = "gate-query/keep-" + TestJobs.randomHex(8);
        var kept = harness.submit(TestJobs.gateByRef(ref, sha, true));

        harness.grantHead();
        harness.start(kept);
        var newer = harness.submit(TestJobs.gateByRef(ref, TestJobs.sha(), false));
        harness.complete(kept, TestJobs.exited(1), TestJobs.failing());
        // the newer Gate would otherwise stay at the head of the queue for the next test's grant
        harness.store.jobs().requestCancel(newer).await(PgFixture.WAIT).unwrap();

        assertThat(state(kept)).isEqualTo("FAILED");
        assertThat(harness.count("SELECT count(*) AS n FROM jobs WHERE id = $1 AND superseded", kept.value())).isEqualTo(1);
        assertThat(GateAnswers.ids(GateAnswers.rows(harness.store, sha))).containsExactly(gate);
    }

    /// A REFUSED Gate never ran: it is not even unjudged.
    @Test
    void refusedGate_isNotACandidate() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);

        harness.store.jobs()
               .recordRefused(TestJobs.gateBySha(sha), Refusal.refusal(RefusalCode.R_M2, "-Dmaven.repo.local=/x"))
               .await(PgFixture.WAIT)
               .unwrap();

        assertThat(GateAnswers.ids(GateAnswers.rows(harness.store, sha))).containsExactly(gate);
    }

    @Test
    void moduleTest_isNotACandidate() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);

        harness.run(TestJobs.moduleTestBySha(sha), TestJobs.exited(1), TestJobs.failing());

        assertThat(GateAnswers.ids(GateAnswers.rows(harness.store, sha))).containsExactly(gate);
    }

    /// A rerun job (recipe kind `rerun`, WP-E) never changes the gate answer (§5.3, R-M2-10 option (i)); the kind is
    /// written directly, since M2's WP-A has no rerun recipe yet.
    @Test
    void rerunJob_isNotACandidate() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);
        var rerun = failedGate(sha);

        harness.execute("UPDATE jobs SET recipe_kind = 'rerun' WHERE id = $1", rerun.value());

        assertThat(GateAnswers.ids(GateAnswers.rows(harness.store, sha))).containsExactly(gate);
    }

    /// Newest first; a prefix returns every SHA it names (the caller refuses an ambiguous one); another prefix none.
    @Test
    void prefix_returnsTheGatesOfEverySha_itNames_newestFirst() {
        var sha = TestJobs.sha();
        var first = passedGate(sha);
        var second = failedGate(sha);
        var prefix = ShaPrefix.shaPrefix(sha.value().substring(0, 7)).unwrap();

        var rows = harness.store.jobs().gateCandidates(prefix).await(PgFixture.WAIT).unwrap();

        assertThat(GateAnswers.ids(rows)).containsSubsequence(second, first);
        assertThat(rows).allMatch(row -> row.job().sha().filter(prefix::isPrefixOf).isPresent());
        assertThat(GateAnswers.evidence(harness.store, sha).answer()).as("R-M2-5").isEqualTo(GateAnswer.CONFLICT);
    }

    /// The prefix matches the START of a SHA only: a Gate whose SHA merely contains it is not a candidate.
    @Test
    void prefix_matchesTheStartOfTheSha_only() {
        var sha = TestJobs.sha();
        var gate = passedGate(sha);
        var prefix = sha.value().substring(0, 7);
        var containing = Sha.sha("f" + prefix + TestJobs.randomHex(32)).unwrap();

        failedGate(containing);
        var rows = harness.store.jobs().gateCandidates(ShaPrefix.shaPrefix(prefix).unwrap()).await(PgFixture.WAIT).unwrap();

        assertThat(GateAnswers.ids(rows)).contains(gate);
        assertThat(rows).noneMatch(row -> row.job().sha().filter(containing::equals).isPresent());
    }

    /// A running Gate is a candidate without a result; its grant judge is there, its result judge not yet.
    @Test
    void runningGate_isACandidate_withoutAResult() {
        var sha = TestJobs.sha();
        var job = harness.submit(TestJobs.gateBySha(sha));

        harness.grantHead();
        harness.start(job);

        var rows = GateAnswers.rows(harness.store, sha);

        assertThat(GateAnswers.ids(rows)).containsExactly(job);
        assertThat(rows.getFirst().job().state()).isEqualTo(JobState.RUNNING);
        assertThat(rows.getFirst().grantJudges()).containsExactly(Judge.UNJUDGED);
        assertThat(rows.getFirst().resultJudge().isEmpty()).isTrue();
        assertThat(rows.getFirst().record().isEmpty()).isTrue();
        assertThat(GateAnswers.evidence(harness.store, sha).answer()).isEqualTo(GateAnswer.RUNNING);
        harness.complete(job, TestJobs.exited(0), TestJobs.passing());
    }

    private static JobId passedGate(Sha sha) {
        return harness.run(TestJobs.gateBySha(sha), TestJobs.exited(0), TestJobs.passing());
    }

    private static JobId failedGate(Sha sha) {
        return harness.run(TestJobs.gateBySha(sha), TestJobs.exited(1), TestJobs.failing());
    }

    private static String state(JobId job) {
        return harness.raw.executor()
                          .completeQuery("SELECT state FROM jobs WHERE id = $1", job.value())
                          .await(PgFixture.WAIT)
                          .unwrap()
                          .index(0)
                          .getString("state");
    }
}
