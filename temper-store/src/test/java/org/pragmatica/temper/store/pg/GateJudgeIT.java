package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.gate.GateEvidence;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.store.api.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// m2-design §1.7 (`GateJudgeIT`, ruling R-M2-10): two controller instances with different judges on one database, each
/// finishing Gates at one SHA. The newer judge's Gate answers and the older judge's is history; a terminal Gate counts
/// under the judge on its RESULT (the finishing controller's), never the one on its job row. Probe: read the judge of a
/// terminal candidate from `jobs` → [#midJobDeploy_countsUnderTheFinishingJudge_andIsJudgeChanged] answers under the
/// submitting judge: red.
@Tag("pg")
class GateJudgeIT {
    private static final Judge X = Judge.judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d").unwrap();
    private static final Judge Y = Judge.judge("5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80").unwrap();
    private static StoreHarness harness;
    private static Store older;
    private static Store newer;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness(PgFixture.freshDatabase(), X);
        older = harness.store;
        newer = Postgres.connect(harness.config, Y).await(PgFixture.WAIT).unwrap();
    }

    @AfterAll
    static void tearDown() {
        if (newer != null) {
            newer.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    /// The older judge's FAILED, then the newer judge's PASSED (maintainer's case): PASSED, the FAILED in `older`.
    @Test
    void twoJudges_theNewerAnswers_theOlderIsHistory() {
        var sha = TestJobs.sha();
        var failed = whollyUnder(older, sha, false);
        var passed = whollyUnder(newer, sha, true);

        var evidence = GateAnswers.evidence(harness.store, sha);

        assertThat(evidence.answer()).isEqualTo(GateAnswer.PASSED);
        assertThat(evidence.judge()).isEqualTo(some(Y));
        assertThat(evidence.answering().map(candidate -> candidate.job())).isEqualTo(some(passed));
        assertThat(GateAnswers.ids(evidence, GateEvidence::older)).containsExactly(failed);
    }

    /// Submitted and granted under X, finished under Y (a deploy while it ran): its result names Y, its grant X, so it
    /// counts under Y and is judged across the deploy (R-M2-11), with `judge-changed=1a2b3c4` on its record.
    @Test
    void midJobDeploy_countsUnderTheFinishingJudge_andIsJudgeChanged() {
        var sha = TestJobs.sha();
        var job = older.jobs().submit(TestJobs.gateBySha(sha)).await(PgFixture.WAIT).unwrap().job();

        StoreHarness.grantHead(older);
        StoreHarness.start(older, job);
        StoreHarness.complete(newer, job, TestJobs.exited(0), TestJobs.passing());

        var row = GateAnswers.rows(harness.store, sha).getFirst();
        var evidence = GateAnswers.evidence(harness.store, sha);

        assertThat(row.submitJudge()).isEqualTo(X);
        assertThat(row.grantJudges()).containsExactly(X);
        assertThat(row.resultJudge()).as("the finishing controller's").isEqualTo(some(Y));
        assertThat(row.record().map(record -> record.judge())).isEqualTo(some(some(Y.value())));
        assertThat(row.record().map(record -> record.annotations()).or(List.of())).contains("judge-changed=1a2b3c4");
        assertThat(GateAnswers.candidate(row).judge()).isEqualTo(Y);
        assertThat(evidence.answer()).isEqualTo(GateAnswer.NONE);
        assertThat(GateAnswers.ids(evidence, GateEvidence::judgeChanged)).containsExactly(job);
    }

    /// Submitted, granted, started and finished through one store: every judge is that store's.
    private static JobId whollyUnder(Store store, Sha sha, boolean passes) {
        var job = store.jobs().submit(TestJobs.gateBySha(sha)).await(PgFixture.WAIT).unwrap().job();

        StoreHarness.grantHead(store);
        StoreHarness.start(store, job);
        StoreHarness.complete(store, job, TestJobs.exited(passes ? 0 : 1), passes ? TestJobs.passing() : TestJobs.failing());
        return job;
    }
}
