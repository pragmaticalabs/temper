package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.RerunRow;
import org.pragmatica.temper.store.api.ResultRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m2-design §5.3, §5.4 in the store: `jobs.rerun_of` (V9) is written from a rerun recipe's `of`, a refused request
/// included; the parent's row lists its reruns (`JobRow.reruns`, one statement with the row) with their state and, once
/// finished, their record's annotations, leaving the refused request out; a rerun job's record carries `rerun-of=` and
/// `rerun(<class>)=…`, and its recipe the `original`; the parent's stored record is byte-identical afterwards.
@Tag("pg")
class RerunRowsIT {
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

    private static NewJob rerunOf(JobId parent, Sha sha, Recipe recipe, boolean refused) {
        return NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                             Priority.priority(none()).unwrap(),
                             false,
                             false,
                             SourceRequest.inherited(parent, some(sha)),
                             refused ? none() : some(PushedSource.pushedSource(none(), sha)),
                             recipe,
                             refused ? none() : some(TestJobs.key()),
                             refused ? List.of() : List.of(TestJobs.unit("rerun")));
    }

    private static Recipe.Rerun recipe(JobId parent) {
        return Recipe.rerun(parent,
                            List.of(Recipe.Rerun.Target.target("r1.ATest", "mod", Runner.SUREFIRE).unwrap()),
                            Recipe.Rerun.Original.original(parent,
                                                           "FAILED",
                                                           some("tests"),
                                                           List.of(Recipe.Rerun.Failure.failure("r1.ATest", "one", none()))));
    }

    private static JobRow row(JobId job) {
        return harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().fold(() -> fail("expected a value"), found -> found);
    }

    private static String recordJson(JobId job) {
        return harness.store.results().recordJson(job).await(PgFixture.WAIT).unwrap().fold(() -> "", text -> text);
    }

    @Test
    void rerun_isLinkedOnTheReadSide_refusedRequestIsNot_parentRecordUnchanged() {
        var sha = TestJobs.sha();
        var parent = harness.run(TestJobs.gateBySha(sha), TestJobs.exited(1), List.of(TestJobs.row("r1.ATest", "one", Outcome.FAILED)));
        var before = recordJson(parent);
        var refused = harness.store.jobs()
                             .recordRefused(rerunOf(parent, sha, Recipe.refusedRerun(parent), true),
                                            Refusal.refusal(RefusalCode.RERUN, "a refused request"))
                             .await(PgFixture.WAIT)
                             .unwrap();
        var rerun = harness.submit(rerunOf(parent, sha, recipe(parent), false));

        assertThat(row(parent).reruns()).containsExactly(RerunRow.rerunRow(rerun, JobState.QUEUED, List.of()));
        assertThat(harness.count("SELECT count(*) AS n FROM jobs WHERE rerun_of = $1", parent.value())).as("refused included").isEqualTo(2);
        assertThat(harness.count("SELECT count(*) AS n FROM jobs WHERE id = $1 AND rerun_of IS NULL AND ref IS NULL", refused.value()))
            .isZero();
        assertThat(row(rerun).ref().isEmpty()).as("a rerun never supersedes").isTrue();

        harness.grantHead();
        var ref = TestJobs.ref(rerun, "rerun");
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        harness.store.units()
               .complete(TestJobs.HOST, ref, 1, TestJobs.exited(0), List.of(TestJobs.row("rerun", "r1.ATest", "one", Outcome.PASSED)), none(), none(),
                         TestJobs::evaluate)
               .await(PgFixture.WAIT)
               .unwrap();

        var record = harness.store.results().result(rerun).await(PgFixture.WAIT).unwrap().fold(() -> fail("expected a value"), found -> found);

        assertThat(record.verdict()).isEqualTo("PASSED");
        assertThat(record.annotations()).contains("rerun-of=" + parent.value(), "rerun(r1.ATest)=PASSED");
        assertThat(record.recipe()).isInstanceOf(ResultRecord.Recipe.Rerun.class);
        assertThat(((ResultRecord.Recipe.Rerun) record.recipe()).original()
                                                              .map(ResultRecord.Recipe.Rerun.Original::verdict)).isEqualTo(some("FAILED"));
        var listed = row(parent).reruns();

        assertThat(listed).extracting(RerunRow::job, RerunRow::state).containsExactly(tuple(rerun, JobState.PASSED));
        assertThat(listed.getFirst().annotations()).as("the record's annotations")
                                                   .containsExactlyElementsOf(record.annotations())
                                                   .contains("rerun-of=" + parent.value(), "rerun(r1.ATest)=PASSED");
        assertThat(recordJson(parent)).as("the parent's record is byte-identical").isEqualTo(before);
    }

    /// A rerun request against a parent refused before resolution has no SHA to inherit: its REFUSED row has none, and
    /// reads back.
    @Test
    void refusedRequest_ofAParentWithoutSha_isPersistedWithoutSha() {
        var parent = harness.store.jobs()
                            .recordRefused(TestJobs.gateBySha(TestJobs.sha()), Refusal.refusal(RefusalCode.SOURCE, "not pushed"))
                            .await(PgFixture.WAIT)
                            .unwrap();
        var request = NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                                    Priority.priority(none()).unwrap(),
                                    false,
                                    false,
                                    SourceRequest.inherited(parent, none()),
                                    none(),
                                    Recipe.refusedRerun(parent),
                                    none(),
                                    List.of());
        var refused = harness.store.jobs()
                             .recordRefused(request, Refusal.refusal(RefusalCode.RERUN, "job is REFUSED, not FAILED(tests)"))
                             .await(PgFixture.WAIT)
                             .unwrap();
        var row = row(refused);

        assertThat(row.sha()).isEqualTo(none());
        assertThat(row.state()).isEqualTo(JobState.REFUSED);
        assertThat(row.recipe()).isEqualTo(Recipe.refusedRerun(parent));
        assertThat(harness.count("SELECT count(*) AS n FROM jobs WHERE id = $1 AND rerun_of = $2", refused.value(), parent.value()))
            .isEqualTo(1);
        assertThat(row(parent).reruns()).as("a refused request is not listed").isEmpty();
    }

    @Test
    void jobWithoutReruns_listsNone() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false), TestJobs.exited(0), TestJobs.passing());

        assertThat(row(job).reruns()).isEmpty();
    }
}
