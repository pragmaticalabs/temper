package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.submit.RerunJob;
import org.pragmatica.temper.controller.submit.SubmitJob;
import org.pragmatica.temper.controller.submit.TreeReader;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.TreeFacts;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.rerun.RerunBounds;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.store.api.BaseRead;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.StoredSides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The rerun use case ([RerunJob], m2-design §5.1, §5.2, §5.5) through its route over in-memory stores: what it submits
/// (the parent's SHA inherited as is, its priority unless one is asked, `fresh`, the rerun recipe and its units), what it
/// persists when a precondition refuses (a REFUSED rerun row naming the parent), and what it never persists (a 404, or a
/// store read that failed). The failing store answers later, on another thread, as Postgres does.
class RerunJobTest {
    private static final long PARENT = 4711;
    private static final String SHA = ApiFixture.SHA;
    private static final Counts COUNTS = Counts.counts(12, 11, 1, 0, 0, 12, 2, none(), none(), none());

    private final ApiFixture fixture = new ApiFixture(List.of());

    private static JobRow parentRow(Recipe recipe, JobState state, int priority) {
        return JobRow.jobRow(JobId.jobId(PARENT).unwrap(), OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), priority, false, false,
                             false, none(), some(Sha.sha(SHA).unwrap()), recipe, none(), state, none(), none(), false, none(), none(),
                             some(Instant.now()), some(state.name()), some("tests"), List.of(), List.of());
    }

    private static ResultRecord failedTests() {
        return failedTests(List.of(ResultRecord.Failed.failed("org.x.FooTest", "works", none())));
    }

    private static ResultRecord failedTests(List<ResultRecord.Failed> failed) {
        return ResultRecord.resultRecord(PARENT,
                                         "FAILED",
                                         some("tests"),
                                         none(),
                                         ResultRecord.Source.source(none(), some(SHA)),
                                         ResultRecord.Recipe.Gate.gate(),
                                         List.of(),
                                         COUNTS,
                                         failed,
                                         failed.size(),
                                         List.of(),
                                         none(),
                                         ResultRecord.Evidence.evidence(false, none(), none()),
                                         ResultRecord.Timings.timings(none(), none()),
                                         none());
    }

    private static JobManifest manifest() {
        return JobManifest.jobManifest(true,
                                       false,
                                       List.of(ManifestEntry.manifestEntry("core", "org.x.FooTest", Runner.SUREFIRE)),
                                       List.of(),
                                       List.of(),
                                       0);
    }

    /// A FAILED(tests) Gate parent whose failed classes are `classes`, each in module `core` (surefire).
    private void failedGateParent(List<String> classes) {
        failedGateParent(7);
        fixture.results.records.put(JobId.jobId(PARENT).unwrap(),
                                    failedTests(classes.stream().map(name -> ResultRecord.Failed.failed(name, "works", none())).toList()));
        fixture.results.manifest = some(JobManifest.jobManifest(true,
                                                                false,
                                                                classes.stream().map(name -> ManifestEntry.manifestEntry("core", name, Runner.SUREFIRE)).toList(),
                                                                List.of(),
                                                                List.of(),
                                                                0));
    }

    private void failedGateParent(int priority) {
        fixture.jobs.rows.put(JobId.jobId(PARENT).unwrap(), parentRow(Recipe.gate(), JobState.FAILED, priority));
        fixture.results.records.put(JobId.jobId(PARENT).unwrap(), failedTests());
        fixture.results.manifest = some(manifest());
    }

    private ApiResponse rerun(String body) {
        return fixture.authed(HttpMethod.POST, "/api/v1/jobs/" + PARENT + "/rerun", body);
    }

    @Test
    void rerun_accepted_submitsTheRerunJob_inheritingShaAndPriority() {
        failedGateParent(7);

        var response = rerun("{\"submittedBy\":\"cto\"}");

        assertThat(response.status()).as(response.bodyText()).isEqualTo(HttpStatus.OK);
        assertThat(response.bodyText()).isEqualTo("{\"job\":101,\"state\":\"QUEUED\",\"deduplicated\":false}");
        var job = fixture.jobs.submitted.getFirst();

        assertThat(job.submittedBy().value()).isEqualTo("cto");
        assertThat(job.priority()).isEqualTo(Priority.priority(some(7)).unwrap());
        assertThat(job.keep()).isFalse();
        assertThat(job.fresh()).isFalse();
        assertThat(job.request()).isEqualTo(SourceRequest.inherited(JobId.jobId(PARENT).unwrap(), some(Sha.sha(SHA).unwrap())));
        assertThat(job.source().map(source -> source.sha().value())).isEqualTo(some(SHA));
        assertThat(job.source().flatMap(source -> source.ref())).as("no ref: never supersedes").isEqualTo(none());
        assertThat(job.recipe()).isInstanceOf(Recipe.Rerun.class);
        assertThat(((Recipe.Rerun) job.recipe()).targets()).extracting(Recipe.Rerun.Target::className).containsExactly("org.x.FooTest");
        assertThat(job.units()).extracting(unit -> unit.name().value()).containsExactly("rerun");
        assertThat(job.units()).extracting(UnitSpec::kind).containsExactly(UnitKind.RERUN);
        assertThat(job.dedupKey().isPresent()).isTrue();
    }

    /// An optional class (ruling R-2) that failed was run, so its module comes from the manifest's optional entries.
    @Test
    void rerun_ofAFailedOptionalClass_takesItsModuleFromTheOptionalEntries() {
        failedGateParent(7);
        fixture.results.manifest = some(JobManifest.jobManifest(true,
                                                                false,
                                                                List.of(),
                                                                List.of(ManifestEntry.manifestEntry("core", "org.x.FooTest", Runner.SUREFIRE)),
                                                                List.of(),
                                                                0));

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.OK);
        assertThat(((Recipe.Rerun) fixture.jobs.submitted.getFirst().recipe()).targets()).extracting(target -> target.module().value())
                                                                                         .containsExactly("core");
    }

    @Test
    void rerun_withPriorityAndFresh_usesThem() {
        failedGateParent(7);

        assertThat(rerun("{\"submittedBy\":\"cto\",\"priority\":3,\"fresh\":true}").status()).isEqualTo(HttpStatus.OK);
        var job = fixture.jobs.submitted.getFirst();

        assertThat(job.priority()).isEqualTo(Priority.priority(some(3)).unwrap());
        assertThat(job.fresh()).isTrue();
    }

    /// maintainer ruling on the rerun bound: with history for the class, the bound is derived from it (the last 5 jobs), not
    /// from the weights, which are then not read; it is the unit's timeout and rides on the recipe for the record.
    @Test
    void rerun_withHistory_derivesTheBoundFromIt_asTheUnitTimeout() {
        failedGateParent(7);
        fixture.results.durations.put("org.x.FooTest", Duration.ofMinutes(20));
        fixture.files.put(RerunBounds.WEIGHTS_PATH, "FooTest 6000\n");

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.OK);
        var job = fixture.jobs.submitted.getFirst();

        assertThat(fixture.results.historyReads).containsExactly(Map.entry(List.of("org.x.FooTest"), 5));
        assertThat(fixture.fileReads).as("every class has history: the weights are not read").isEmpty();
        assertThat(job.units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofMinutes(40));
        assertThat(((Recipe.Rerun) job.recipe()).bound()
                                                .map(Recipe.Rerun.Bound::annotation)).isEqualTo(some("rerun-bound=40m (Σ 20.0m ×1.5 + install 5m + slack 5m)"));
    }

    /// A `@Nested` target runs its whole outermost class, so the bound is derived over the outermost class, once: its
    /// history (which includes its nested classes' rows) and never the nested class's own slice.
    @Test
    void rerun_nestedTarget_derivesOverTheOutermostClass_once() {
        failedGateParent(List.of("org.x.FooTest", "org.x.FooTest$Inner"));
        fixture.results.durations.put("org.x.FooTest", Duration.ofMinutes(20));
        fixture.results.durations.put("org.x.FooTest$Inner", Duration.ofMinutes(50));

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.OK);
        var job = fixture.jobs.submitted.getFirst();

        assertThat(((Recipe.Rerun) job.recipe()).targets()).extracting(Recipe.Rerun.Target::className)
                                                         .containsExactly("org.x.FooTest", "org.x.FooTest$Inner");
        assertThat(fixture.results.historyReads).containsExactly(Map.entry(List.of("org.x.FooTest"), 5));
        assertThat(job.units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofMinutes(40));
    }

    /// One class with history, one without: the weights are read for the second, and history still wins for the first.
    @Test
    void rerun_mixedHistory_readsTheWeights_historyStillWins() {
        failedGateParent(List.of("org.x.BarTest", "org.x.FooTest"));
        fixture.results.durations.put("org.x.FooTest", Duration.ofMinutes(10));
        fixture.files.put(RerunBounds.WEIGHTS_PATH, "FooTest 6000\nBarTest 600\n");

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.OK);
        var job = fixture.jobs.submitted.getFirst();

        assertThat(fixture.fileReads).containsExactly(SHA + ":" + RerunBounds.WEIGHTS_PATH);
        assertThat(((Recipe.Rerun) job.recipe()).bound()
                                                .map(Recipe.Rerun.Bound::annotation)).isEqualTo(some("rerun-bound=40m (Σ 20.0m ×1.5 + install 5m + slack 5m)"));
    }

    /// An explicit unit timeout below a minute prints exactly, in seconds.
    @Test
    void rerun_withUnitTimeoutInSeconds_printsItExactly() {
        failedGateParent(7);

        assertThat(rerun("{\"submittedBy\":\"cto\",\"timeout\":\"PT90S\"}").status()).isEqualTo(HttpStatus.OK);
        var job = fixture.jobs.submitted.getFirst();

        assertThat(job.units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofSeconds(90));
        assertThat(((Recipe.Rerun) job.recipe()).bound()
                                                .map(Recipe.Rerun.Bound::annotation)).isEqualTo(some("rerun-bound=90s (unit-timeout)"));
    }

    /// Without history, the weights file at the parent's SHA, read through the tree reader (the controller's mirror).
    @Test
    void rerun_withoutHistory_usesTheWeightsAtTheParentSha() {
        failedGateParent(7);
        fixture.files.put(RerunBounds.WEIGHTS_PATH, "# seconds\nFooTest 1200\n");

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.OK);
        var job = fixture.jobs.submitted.getFirst();

        assertThat(fixture.fileReads).containsExactly(SHA + ":" + RerunBounds.WEIGHTS_PATH);
        assertThat(job.units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofMinutes(40));
    }

    /// An explicit unit timeout overrides the derived bound, is printed as such, and enters the dedup key as submit's does.
    @Test
    void rerun_withUnitTimeout_overridesTheBound_andEntersTheDedupKey() {
        failedGateParent(7);
        fixture.results.durations.put("org.x.FooTest", Duration.ofMinutes(20));

        assertThat(rerun("{\"submittedBy\":\"cto\",\"timeout\":\"PT90M\"}").status()).isEqualTo(HttpStatus.OK);
        rerun("{\"submittedBy\":\"cto\"}");
        var explicit = fixture.jobs.submitted.get(0);

        assertThat(explicit.units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofMinutes(90));
        assertThat(((Recipe.Rerun) explicit.recipe()).bound()
                                                     .map(Recipe.Rerun.Bound::annotation)).isEqualTo(some("rerun-bound=90m (unit-timeout)"));
        assertThat(explicit.dedupKey()).isNotEqualTo(fixture.jobs.submitted.get(1).dedupKey());
    }

    /// m4-design §9.2: a rerun of a `--dedicated-cpu` parent is dedicated whatever its own request says; the class is
    /// on the stored job and in its dedup key (§9.3).
    @Test
    void rerun_ofADedicatedParent_inheritsDedicatedCpu_whateverTheRequestSays() {
        failedGateParent(7);
        fixture.jobs.rows.put(JobId.jobId(PARENT).unwrap(), parentRow(Recipe.gate(), JobState.FAILED, 7).dedicated(true));

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.OK);
        assertThat(rerun("{\"submittedBy\":\"cto\",\"dedicatedCpu\":false}").status()).isEqualTo(HttpStatus.OK);

        assertThat(fixture.jobs.submitted).extracting(job -> job.dedicatedCpu()).containsExactly(true, true);
        assertThat(fixture.jobs.submitted.get(0).dedupKey()).isEqualTo(fixture.jobs.submitted.get(1).dedupKey());
    }

    /// `--dedicated-cpu` on a rerun of a plain parent adds the class; a plain rerun of it stays plain, with another key.
    @Test
    void rerun_ofAPlainParent_isDedicatedOnlyWhenAsked() {
        failedGateParent(7);

        rerun("{\"submittedBy\":\"cto\"}");
        rerun("{\"submittedBy\":\"cto\",\"dedicatedCpu\":true}");

        assertThat(fixture.jobs.submitted).extracting(job -> job.dedicatedCpu()).containsExactly(false, true);
        assertThat(fixture.jobs.submitted.get(0).dedupKey()).isNotEqualTo(fixture.jobs.submitted.get(1).dedupKey());
    }

    /// A refused rerun row of a dedicated parent records the class too.
    @Test
    void rerun_refused_ofADedicatedParent_recordsDedicatedCpu() {
        fixture.jobs.rows.put(JobId.jobId(PARENT).unwrap(),
                              parentRow(Recipe.mvn(List.of("verify")).unwrap(), JobState.FAILED, 5).dedicated(true));

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(fixture.jobs.refused).extracting(job -> job.dedicatedCpu()).containsExactly(true);
    }

    /// The weights cannot be read (the mirror is broken): the request fails (503) as a read at submit does; nothing is
    /// submitted or persisted.
    @Test
    void rerun_weightsUnreadable_failsTheRequest_submitsNothing() {
        failedGateParent(7);
        fixture.filesDown = true;

        assertThat(rerun("{\"submittedBy\":\"cto\"}").status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fixture.jobs.submitted).isEmpty();
        assertThat(fixture.jobs.refused).isEmpty();
    }

    @Test
    void rerun_withABadUnitTimeout_is400_andSubmitsNothing() {
        failedGateParent(7);

        assertThat(rerun("{\"submittedBy\":\"cto\",\"timeout\":\"PT30S\"}").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fixture.jobs.submitted).isEmpty();
    }

    /// The derived bound is not in the dedup key: it follows the history, so the same request made after more history
    /// deduplicates onto the first rerun.
    @Test
    void rerun_derivedBound_isNotInTheDedupKey() {
        failedGateParent(7);

        rerun("{\"submittedBy\":\"cto\"}");
        fixture.results.durations.put("org.x.FooTest", Duration.ofMinutes(60));
        rerun("{\"submittedBy\":\"cto\"}");

        assertThat(fixture.jobs.submitted.get(0).units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofMinutes(30));
        assertThat(fixture.jobs.submitted.get(1).units()).extracting(UnitSpec::timeout).containsExactly(Duration.ofMinutes(100));
        assertThat(fixture.jobs.submitted.get(0).dedupKey()).isEqualTo(fixture.jobs.submitted.get(1).dedupKey());
    }

    /// Two equal requests make equal dedup keys: the store answers the second with the first (exit 10's path).
    @Test
    void rerun_repeated_hasTheSameDedupKey() {
        failedGateParent(7);

        rerun("{\"submittedBy\":\"cto\"}");
        rerun("{\"submittedBy\":\"other\",\"priority\":9}");

        assertThat(fixture.jobs.submitted).hasSize(2);
        assertThat(fixture.jobs.submitted.get(0).dedupKey()).isEqualTo(fixture.jobs.submitted.get(1).dedupKey());
    }

    @Test
    void rerun_refused_persistsARefusedRerunRowNamingTheParent() {
        fixture.jobs.rows.put(JobId.jobId(PARENT).unwrap(), parentRow(Recipe.mvn(List.of("verify")).unwrap(), JobState.FAILED, 5));

        var response = rerun("{\"submittedBy\":\"cto\"}");

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.bodyText()).contains("\"refused\":\"rerun\"", "only gate and module-test jobs are rerun", "\"job\":101");
        assertThat(fixture.jobs.submitted).isEmpty();
        var refused = fixture.jobs.refused.getFirst();

        assertThat(refused.recipe()).isEqualTo(Recipe.refusedRerun(JobId.jobId(PARENT).unwrap()));
        assertThat(refused.request()).isEqualTo(SourceRequest.inherited(JobId.jobId(PARENT).unwrap(), some(Sha.sha(SHA).unwrap())));
        assertThat(refused.priority()).isEqualTo(Priority.priority(some(5)).unwrap());
        assertThat(refused.dedupKey()).isEqualTo(none());
        assertThat(refused.units()).isEmpty();
        assertThat(fixture.jobs.refusals.getFirst().code()).isEqualTo("rerun");
    }

    @Test
    void rerun_ofAnUnknownJob_is404_andPersistsNothing() {
        var response = rerun("{\"submittedBy\":\"cto\"}");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fixture.jobs.submitted).isEmpty();
        assertThat(fixture.jobs.refused).isEmpty();
    }

    /// A store read that fails is returned (in the fork-join's composite), never persisted as a refusal.
    @Test
    void rerun_storeReadFails_isReturned_notPersistedAsARefusal() {
        var jobs = new FakeStores.Jobs();
        var failing = Causes.cause("result store down");
        var results = new FailingResults(failing);

        jobs.rows.put(JobId.jobId(PARENT).unwrap(), parentRow(Recipe.gate(), JobState.FAILED, 5));
        SubmitJob submit = _ -> fail("submitted");

        var answer = RerunJob.rerunJob(RerunJob.Steps.steps(jobs, results, submit, new UnreadTree(), Duration.ofMinutes(5)))
                             .execute(RerunJob.Request.request(JobId.jobId(PARENT).unwrap(),
                                                               OwnerTag.ownerTag("cto").unwrap(),
                                                               none(),
                                                               false,
                                                               none()))
                             .await();

        Cause cause = answer.fold(failed -> failed, _ -> fail("rerun answered"));

        assertThat(cause).isNotInstanceOf(Refusal.class);
        assertThat(cause.message()).contains(failing.message());
        assertThat(jobs.refused).isEmpty();
    }

    /// Results whose record read fails, later, on another thread; the manifest read answers none.
    private record FailingResults(Cause failure) implements ResultStore {
        @Override
        public Promise<Option<ResultRecord>> result(JobId id) {
            return FakeStores.later(20, failure.promise());
        }

        @Override
        public Promise<List<TestcaseRow>> testcases(JobId id, Option<Outcome> outcome) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Option<String>> recordJson(JobId id) {
            return Promise.success(none());
        }

        @Override
        public Promise<List<JobId>> finishedSince(Instant since) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Option<JobManifest>> manifest(JobId id) {
            return FakeStores.later(5, Promise.success(none()));
        }

        @Override
        public Promise<Map<String, Duration>> classDurations(List<String> classNames, int lastJobs) {
            return failure.promise();
        }

        @Override
        public Promise<StoredSides> diffSides(JobId a, JobId b) {
            return failure.promise();
        }

        @Override
        public Promise<BaseRead> diffBase(ShaPrefix sha, JobId job, Fn1<Result<Option<JobId>>, List<GateRow>> choose) {
            return failure.promise();
        }
    }

    /// A tree that must not be read: the parent's reads fail first.
    private static final class UnreadTree implements TreeReader {
        @Override
        public Promise<TreeFacts> facts(Sha sha, Set<String> names) {
            return fail("tree read");
        }

        @Override
        public Promise<Option<byte[]>> file(Sha sha, String path) {
            return fail("tree read");
        }
    }
}
