package org.pragmatica.temper.controller.submit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.JobRequest;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.rerun.RerunBounds;
import org.pragmatica.temper.domain.rerun.RerunPreconditions;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `POST /jobs/{id}/rerun` (m2-design §5.1, §5.2, §5.5; ruling R-M2-4): read the parent (its row, then its result
/// record and manifest), check the preconditions ([RerunPreconditions]), and submit the rerun job through [SubmitJob],
/// so dedup, the plan's refusals and the `queued` event go through the one submit path. The job's source is the
/// parent's SHA, inherited as is ([SourceRequest.Inherited]); its priority is the parent's unless the request names
/// one; it is never `keep`. Its CPU class is the parent's (m4-design §9.2): it re-judges the parent's failures, so it
/// runs `--dedicated-cpu` whenever the parent did, and the request's `dedicatedCpu` can only add the class.
///
/// The unit's bound (maintainer ruling on the rerun bound) is decided here, at submit, and stored on the recipe, outside its
/// canonical form: an explicit `--unit-timeout` (`timeout`, which enters the dedup key as for every submit) wins;
/// otherwise [RerunBounds] derives it over the classes the unit runs (the targets' outermost classes) from their stored
/// history ([ResultStore#classDurations], the last [#HISTORY_JOBS] terminal jobs), the parent tree's shard weights
/// ([RerunBounds#WEIGHTS_PATH] through the controller's mirror, read only when a class has no history) and the
/// configured install allowance. The bound is derived before [SubmitJob] deduplicates [G]: a repeat that will
/// deduplicate still reads the history (and the weights when needed), and a failed read fails it; dedup lives in the
/// store's submit, and a rerun request is rare and by hand.
///
/// A precondition failure is `REFUSED(rerun)`, persisted as a REFUSED job by [JobStore#recordRefused], the store call
/// every refusal goes through, and answered as [SubmitJob.Response.Refused]. An unknown parent is
/// [StoreError.JobNotFound] (404), nothing persisted.
///
/// The parent's three reads are separate statements, not one snapshot: a precondition only accepts a terminal parent,
/// whose row state, write-once result and manifest no longer change (retention may delete the manifest rows at 90 d,
/// which then reads as pruned and refuses).
@FunctionalInterface
public interface RerunJob {
    /// The history a class's duration is read from: its slowest of the last 5 terminal jobs (ruling decision 1).
    int HISTORY_JOBS = 5;

    /// The parsed request: the parent, who asks, an optional priority, `fresh`, an optional explicit unit timeout, and
    /// `--dedicated-cpu`.
    record Request(JobId parent,
                   OwnerTag submittedBy,
                   Option<Priority> priority,
                   boolean fresh,
                   Option<UnitTimeout> timeout,
                   boolean dedicatedCpu) {
        /// Without `--dedicated-cpu` (the parent's class still applies).
        public static Request request(JobId parent,
                                      OwnerTag submittedBy,
                                      Option<Priority> priority,
                                      boolean fresh,
                                      Option<UnitTimeout> timeout) {
            return new Request(parent, submittedBy, priority, fresh, timeout, false);
        }

        public static Request request(JobId parent,
                                      OwnerTag submittedBy,
                                      Option<Priority> priority,
                                      boolean fresh,
                                      Option<UnitTimeout> timeout,
                                      boolean dedicatedCpu) {
            return new Request(parent, submittedBy, priority, fresh, timeout, dedicatedCpu);
        }
    }

    /// The parent as read: its row, its result record (absent until terminal) and its manifest (absent without one).
    record ParentRows(JobRow job, Option<ResultRecord> record, Option<JobManifest> manifest) {
        static ParentRows parentRows(JobRow job, Option<ResultRecord> record, Option<JobManifest> manifest) {
            return new ParentRows(job, record, manifest);
        }
    }

    /// The steps the rerun needs.
    record Steps(JobStore jobs, ResultStore results, SubmitJob submit, TreeReader tree, Duration installAllowance) {
        public static Steps steps(JobStore jobs,
                                  ResultStore results,
                                  SubmitJob submit,
                                  TreeReader tree,
                                  Duration installAllowance) {
            return new Steps(jobs, results, submit, tree, installAllowance);
        }
    }

    Promise<SubmitJob.Response> execute(Request request);

    static RerunJob rerunJob(Steps steps) {
        return request -> rerun(steps, request);
    }

    private static Promise<SubmitJob.Response> rerun(Steps steps, Request request) {
        return parent(steps, request.parent()).flatMap(rows -> answer(steps, request, rows));
    }

    /// The row first (a 404 reads nothing else), then the record and the manifest.
    private static Promise<ParentRows> parent(Steps steps, JobId parent) {
        return steps.jobs()
                    .job(parent)
                    .flatMap(row -> row.async(StoreError.JobNotFound.FACTORY.apply(parent)))
                    .flatMap(row -> withRecordAndManifest(steps, row));
    }

    /// Two independent reads of write-once data.
    private static Promise<ParentRows> withRecordAndManifest(Steps steps, JobRow row) {
        return Promise.all(steps.results().result(row.id()),
                           steps.results().manifest(row.id()))
                      .map((record, manifest) -> ParentRows.parentRows(row, record, manifest));
    }

    private static Promise<SubmitJob.Response> answer(Steps steps, Request request, ParentRows rows) {
        return RerunPreconditions.rerun(parentFacts(rows)).fold(cause -> refused(steps, request, rows, cause),
                                                                recipe -> submitted(steps, request, rows, recipe));
    }

    private static Promise<SubmitJob.Response> submitted(Steps steps,
                                                         Request request,
                                                         ParentRows rows,
                                                         Recipe.Rerun recipe) {
        return priority(request, rows).async()
                       .flatMap(priority -> boundedRequest(steps, request, rows, recipe, priority))
                       .map(SubmitJob.Request::request)
                       .flatMap(steps.submit()::execute);
    }

    /// The bound's reads fail the request with their own cause (a broken mirror stays 503).
    private static Promise<JobRequest> boundedRequest(Steps steps,
                                                      Request request,
                                                      ParentRows rows,
                                                      Recipe.Rerun recipe,
                                                      Priority priority) {
        return bound(steps, request, rows, recipe).map(bound -> jobRequest(request,
                                                                           rows,
                                                                           recipe.withBound(bound),
                                                                           priority));
    }

    private static JobRequest jobRequest(Request request, ParentRows rows, Recipe.Rerun recipe, Priority priority) {
        return JobRequest.jobRequest(request.submittedBy(),
                                     priority,
                                     false,
                                     request.fresh(),
                                     inherited(rows),
                                     recipe,
                                     RecipeOptions.recipeOptions(request.timeout(), dedicatedCpu(request, rows)),
                                     false);
    }

    /// The parent's class, or the request's when it adds one: a rerun never drops the parent's `--dedicated-cpu`.
    private static boolean dedicatedCpu(Request request, ParentRows rows) {
        return request.dedicatedCpu() || rows.job()
                                             .dedicatedCpu();
    }

    /// The explicit timeout as given, or the derived bound.
    private static Promise<Recipe.Rerun.Bound> bound(Steps steps,
                                                     Request request,
                                                     ParentRows rows,
                                                     Recipe.Rerun recipe) {
        return request.timeout()
                      .map(RerunJob::explicit)
                      .map(Promise::success)
                      .or(() -> derived(steps, rows, recipe));
    }

    private static Recipe.Rerun.Bound explicit(UnitTimeout timeout) {
        return Recipe.Rerun.Bound.bound(timeout.value(), "unit-timeout");
    }

    /// The classes' history first; the weights are read only when a class has none.
    private static Promise<Recipe.Rerun.Bound> derived(Steps steps, ParentRows rows, Recipe.Rerun recipe) {
        var classes = recipe.outermostClasses();

        return steps.results()
                    .classDurations(classes, HISTORY_JOBS)
                    .flatMap(history -> withWeights(steps, rows, classes, history));
    }

    private static Promise<Recipe.Rerun.Bound> withWeights(Steps steps,
                                                           ParentRows rows,
                                                           List<String> classes,
                                                           Map<String, Duration> history) {
        return weightsIfNeeded(steps, rows, classes, history).map(weights -> RerunBounds.bound(classes,
                                                                                               history,
                                                                                               weights,
                                                                                               steps.installAllowance()));
    }

    /// A failed read of the weights (the mirror) fails the rerun request like any other read at submit: nothing is
    /// absorbed.
    private static Promise<Map<String, Duration>> weightsIfNeeded(Steps steps,
                                                                  ParentRows rows,
                                                                  List<String> classes,
                                                                  Map<String, Duration> history) {
        return history.keySet()
                      .containsAll(classes)
               ? Promise.success(Map.of())
               : weights(steps, rows);
    }

    /// The parent's tree's shard weights; none without a SHA (never, for an accepted parent) or without the file.
    private static Promise<Map<String, Duration>> weights(Steps steps, ParentRows rows) {
        return rows.job()
                   .sha()
                   .map(sha -> steps.tree()
                                    .file(sha, RerunBounds.WEIGHTS_PATH))
                   .or(() -> Promise.success(none()))
                   .map(RerunJob::parsedWeights);
    }

    private static Map<String, Duration> parsedWeights(Option<byte[]> file) {
        return file.map(bytes -> RerunBounds.weights(new String(bytes, StandardCharsets.UTF_8)))
                   .or(Map.of());
    }

    /// Only a precondition's refusal is persisted; any other failure (a store read) is returned as it is.
    private static Promise<SubmitJob.Response> refused(Steps steps, Request request, ParentRows rows, Cause cause) {
        return switch (cause) {
            case Refusal refusal -> persisted(steps, request, rows, refusal);
            default -> cause.promise();
        };
    }

    private static Promise<SubmitJob.Response> persisted(Steps steps,
                                                         Request request,
                                                         ParentRows rows,
                                                         Refusal refusal) {
        return priority(request, rows).async()
                       .map(priority -> refusedJob(request, rows, priority))
                       .flatMap(job -> steps.jobs()
                                            .recordRefused(job, refusal))
                       .map(job -> SubmitJob.Response.Refused.refused(refusal, job));
    }

    private static NewJob refusedJob(Request request, ParentRows rows, Priority priority) {
        return NewJob.newJob(request.submittedBy(),
                             priority,
                             false,
                             request.fresh(),
                             inherited(rows),
                             none(),
                             Recipe.refusedRerun(rows.job().id()),
                             none(),
                             List.of())
                     .dedicated(dedicatedCpu(request, rows));
    }

    private static SourceRequest inherited(ParentRows rows) {
        return SourceRequest.inherited(rows.job().id(),
                                       rows.job().sha());
    }

    /// The request's priority, else the parent's (a stored priority is always in range).
    private static Result<Priority> priority(Request request, ParentRows rows) {
        return request.priority()
                      .map(Result::success)
                      .or(() -> Priority.priority(some(rows.job().priority())));
    }

    private static RerunPreconditions.Parent parentFacts(ParentRows rows) {
        return RerunPreconditions.Parent.parent(rows.job().id(),
                                                rows.job().state(),
                                                rows.job().recipe(),
                                                rows.record().map(RerunJob::judged),
                                                rows.manifest().map(RerunJob::entries).or(List.of()));
    }

    private static RerunPreconditions.Judged judged(ResultRecord record) {
        return RerunPreconditions.Judged.judged(record.verdict(),
                                                record.reason(),
                                                record.failed()
                                                      .stream()
                                                      .map(failed -> Recipe.Rerun.Failure.failure(failed.className(),
                                                                                                  failed.name(),
                                                                                                  failed.known()))
                                                      .toList());
    }

    /// Required and optional classes alike: an optional class that failed was run, so it has a module.
    private static List<ManifestEntry> entries(JobManifest manifest) {
        return Stream.concat(manifest.entries().stream(),
                             manifest.optional().stream())
                     .toList();
    }
}
