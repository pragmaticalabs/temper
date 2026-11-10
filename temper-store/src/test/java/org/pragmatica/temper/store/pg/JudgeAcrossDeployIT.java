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

/// Ruling R-M2-11 (m2-design §1.7, `JudgeAcrossDeployIT`): "across a deploy" is a unit's grant-time judge differing
/// from the finishing judge, never the submit-time one. Two controller instances, X the build before a deploy and Y
/// the build after it, on one database.
///
/// | probe | red |
/// |---|---|
/// | compare the submit judge instead of the grant judge | [#queuedUnderX_grantedAndFinishedUnderY_countsUnderY] |
/// | drop the exclusion of judge-changed Gates | [#grantedUnderX_finishedUnderY_isJudgeChanged_andExcluded] answers CONFLICT |
/// | drop `granted_judge = $4` from `GRANT` | [#grantedUnderX_finishedUnderY_isJudgeChanged_andExcluded]: not marked |
@Tag("pg")
class JudgeAcrossDeployIT {
    private static final Judge X = Judge.judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d").unwrap();
    private static final Judge Y = Judge.judge("5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80").unwrap();
    private static StoreHarness harness;
    private static Store before;
    private static Store after;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness(PgFixture.freshDatabase(), X);
        before = harness.store;
        after = Postgres.connect(harness.config, Y).await(PgFixture.WAIT).unwrap();
    }

    @AfterAll
    static void tearDown() {
        if (after != null) {
            after.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    /// Case 1 (maintainer): a Gate that only WAITED across the deploy ran wholly under Y. No `judge-changed`; it counts under Y
    /// and answers, the older X Gate being history.
    @Test
    void queuedUnderX_grantedAndFinishedUnderY_countsUnderY() {
        var sha = TestJobs.sha();
        var old = whollyUnder(before, sha, true);
        var job = before.jobs().submit(TestJobs.gateBySha(sha)).await(PgFixture.WAIT).unwrap().job();

        StoreHarness.grantHead(after);
        StoreHarness.start(after, job);
        StoreHarness.complete(after, job, TestJobs.exited(1), TestJobs.failing());

        var evidence = GateAnswers.evidence(harness.store, sha);

        assertThat(annotations(sha, job)).noneMatch(annotation -> annotation.startsWith(Judge.CHANGED_ANNOTATION));
        assertThat(evidence.answer()).isEqualTo(GateAnswer.FAILED);
        assertThat(evidence.judge()).isEqualTo(some(Y));
        assertThat(evidence.answering().map(candidate -> candidate.job())).isEqualTo(some(job));
        assertThat(GateAnswers.ids(evidence, GateEvidence::older)).containsExactly(old);
        assertThat(evidence.judgeChanged()).isEmpty();
    }

    /// Case 2 (maintainer): a Gate granted under X and finished under Y is judged across the deploy: `judge-changed=1a2b3c4` on
    /// its record, shown in history, and left out of the answer and of the CONFLICT set. With a same-judge (Y) Gate of
    /// the opposite verdict at the SHA, the answer is that Gate's verdict, not CONFLICT.
    @Test
    void grantedUnderX_finishedUnderY_isJudgeChanged_andExcluded() {
        var sha = TestJobs.sha();
        var passed = whollyUnder(after, sha, true);
        var job = before.jobs().submit(TestJobs.gateBySha(sha)).await(PgFixture.WAIT).unwrap().job();

        StoreHarness.grantHead(before);
        StoreHarness.start(after, job);
        StoreHarness.complete(after, job, TestJobs.exited(1), TestJobs.failing());

        var evidence = GateAnswers.evidence(harness.store, sha);

        assertThat(annotations(sha, job)).contains("judge-changed=1a2b3c4");
        assertThat(evidence.answer()).as("not CONFLICT").isEqualTo(GateAnswer.PASSED);
        assertThat(evidence.answering().map(candidate -> candidate.job())).isEqualTo(some(passed));
        assertThat(GateAnswers.ids(evidence, GateEvidence::judgeChanged)).containsExactly(job);
        assertThat(evidence.conflict()).isEmpty();
    }

    /// A requeue clears the grant judge with its attempt, so a unit granted under X, requeued (it never ran), then
    /// granted under Y counts under Y: its first attempt was never a judgement.
    @Test
    void requeuedAcrossTheDeploy_countsUnderTheBuildThatGrantsItAgain() {
        var sha = TestJobs.sha();
        var job = before.jobs().submit(TestJobs.gateBySha(sha)).await(PgFixture.WAIT).unwrap().job();

        StoreHarness.grantHead(before);
        after.units().requeue(TestJobs.HOST, TestJobs.main(job), 1).await(PgFixture.WAIT).unwrap();
        StoreHarness.grantHead(after);
        StoreHarness.start(after, job);
        StoreHarness.complete(after, job, TestJobs.exited(0), TestJobs.passing());

        var evidence = GateAnswers.evidence(harness.store, sha);

        assertThat(annotations(sha, job)).noneMatch(annotation -> annotation.startsWith(Judge.CHANGED_ANNOTATION));
        assertThat(evidence.answer()).isEqualTo(GateAnswer.PASSED);
        assertThat(evidence.judge()).isEqualTo(some(Y));
    }

    private static List<String> annotations(Sha sha, JobId job) {
        return GateAnswers.rows(harness.store, sha)
                          .stream()
                          .filter(row -> row.job().id().equals(job))
                          .findFirst()
                          .flatMap(row -> row.record().toOptional())
                          .map(record -> record.annotations())
                          .orElse(List.of());
    }

    private static JobId whollyUnder(Store store, Sha sha, boolean passes) {
        var job = store.jobs().submit(TestJobs.gateBySha(sha)).await(PgFixture.WAIT).unwrap().job();

        StoreHarness.grantHead(store);
        StoreHarness.start(store, job);
        StoreHarness.complete(store, job, TestJobs.exited(passes ? 0 : 1), passes ? TestJobs.passing() : TestJobs.failing());
        return job;
    }
}
