package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubmitOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A9 (spec §2.3): a matching key in QUEUED, RUNNING, PASSED, FAILED or EMPTY deduplicates; VOID, ERROR,
/// CANCELLED (and REFUSED, CANCELLING) run anew; `fresh` always runs; concurrent identical submits make one job
/// (the `hashtextextended(dedup_key)` advisory lock).
@Tag("pg")
class JobStoreDedupIT {
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

    @ParameterizedTest
    @EnumSource(value = JobState.class, names = {"QUEUED", "RUNNING", "PASSED", "FAILED", "EMPTY"})
    void submit_deduplicates_whenMatchIsReusable(JobState state) {
        var key = TestJobs.key();
        var first = harness.submit(TestJobs.bySha(key, false));

        forceState(first, state);

        var again = harness.submitOutcome(TestJobs.bySha(key, false));

        assertThat(again).isEqualTo(new SubmitOutcome.Deduplicated(first, state));
    }

    @ParameterizedTest
    @EnumSource(value = JobState.class, names = {"VOID", "ERROR", "CANCELLED", "REFUSED", "CANCELLING", "SUPERSEDED"})
    void submit_runsAnew_whenMatchIsNotReusable(JobState state) {
        var key = TestJobs.key();
        var first = harness.submit(TestJobs.bySha(key, false));

        forceState(first, state);

        var again = harness.submitOutcome(TestJobs.bySha(key, false));

        assertThat(again).isInstanceOf(SubmitOutcome.Accepted.class);
        assertThat(again.job()).isNotEqualTo(first);
    }

    @Test
    void submit_deduplicatesAgainstLatestReusable_skippingNewerVoid() {
        var key = TestJobs.key();
        var passed = harness.submit(TestJobs.bySha(key, false));

        forceState(passed, JobState.PASSED);
        var voided = harness.submit(TestJobs.bySha(key, true));

        forceState(voided, JobState.VOID);

        assertThat(harness.submitOutcome(TestJobs.bySha(key, false))).isEqualTo(new SubmitOutcome.Deduplicated(passed,
                                                                                                                JobState.PASSED));
    }

    @Test
    void submit_alwaysRunsAnew_whenFresh() {
        var key = TestJobs.key();
        var first = harness.submit(TestJobs.bySha(key, false));
        var fresh = harness.submitOutcome(TestJobs.bySha(key, true));

        assertThat(fresh).isInstanceOf(SubmitOutcome.Accepted.class);
        assertThat(fresh.job()).isNotEqualTo(first);
        assertThat(harness.store.jobs().job(fresh.job()).await(PgFixture.WAIT).unwrap().map(job -> job.fresh()).or(false))
            .isTrue();
    }

    @Test
    void submit_withoutDedupKey_isRefusedByTheStore() {
        var job = TestJobs.bySha(TestJobs.key(), false);
        var keyless = NewJob.newJob(job.submittedBy(), job.priority(), false, false, job.request(), job.source(), job.recipe(),
                                    Option.none(), job.units());

        harness.store.jobs()
                     .submit(keyless)
                     .await(PgFixture.WAIT)
                     .onSuccess(outcome -> fail("expected DEDUP_KEY_REQUIRED, got " + outcome))
                     .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.General.DEDUP_KEY_REQUIRED));
    }

    /// Two identical submits racing, 40 rounds: each round must produce exactly one job. Without the advisory lock
    /// both transactions miss each other's uncommitted insert and two jobs appear.
    @Test
    void submit_concurrentIdenticalSubmits_makeOneJob() {
        var otherStore = PgStore.pgStore(PgFixture.db(harness.config), Judge.UNJUDGED);

        try {
            IntStream.range(0, 40).forEach(_ -> race(otherStore.jobs()));
        } finally {
            otherStore.close();
        }
    }

    private static void race(JobStore other) {
        var key = TestJobs.key();
        var a = harness.store.jobs().submit(TestJobs.bySha(key, false));
        var b = other.submit(TestJobs.bySha(key, false));
        var outcomes = Promise.allOf(List.of(a, b)).await(PgFixture.WAIT).unwrap();
        var ids = outcomes.stream().map(outcome -> outcome.unwrap().job()).distinct().toList();

        assertThat(ids).as("one job per key").hasSize(1);
        assertThat(jobsWithKey(key)).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.unwrap() instanceof SubmitOutcome.Deduplicated).count())
            .isEqualTo(1);
    }

    private static void forceState(JobId job, JobState state) {
        harness.raw.executor()
                   .completeQuery("UPDATE jobs SET state = $2 WHERE id = $1", job.value(), state.name())
                   .await(PgFixture.WAIT)
                   .unwrap();
    }

    private static long jobsWithKey(DedupKey key) {
        return harness.raw.executor()
                          .completeQuery("SELECT count(*) AS n FROM jobs WHERE dedup_key = $1", key.value())
                          .await(PgFixture.WAIT)
                          .unwrap()
                          .index(0)
                          .getLong("n");
    }
}
