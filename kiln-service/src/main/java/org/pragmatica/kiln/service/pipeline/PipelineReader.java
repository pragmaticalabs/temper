package org.pragmatica.kiln.service.pipeline;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.Counted;
import org.pragmatica.kiln.core.github.GitHubError;
import org.pragmatica.kiln.core.github.GitHubJson;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.pipeline.MergedPr;
import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.core.pipeline.RunAttempt;
import org.pragmatica.kiln.service.flow.Steps;
import org.pragmatica.kiln.service.github.Credential;
import org.pragmatica.kiln.service.github.GitHubHttp;
import org.pragmatica.kiln.service.github.GitHubReadError;
import org.pragmatica.kiln.service.github.GitHubReads;
import org.pragmatica.kiln.service.github.RateBudget;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.kiln.service.store.PipelineStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


/// The pipeline baseline loop (#102, every [PipelineScope#PERIOD]): measures every PR merged into the scope's base from
/// its start date. Read only, with the kiln App's installation token alone (maintainer ruling): under any other credential
/// nothing is sent and the state is [PipelineStatus.Kind#NEEDS_APP]. No GitHub write of any kind.
///
/// A cycle, when the shared [RateBudget] covers the cycle's [Slice] (else `PAUSED`):
/// 1. lists, by one GraphQL `search`, the PRs merged since the newest stored merge less [#LOOKBACK] (the start date when
///    none is stored), and stores each new one's node verbatim (a merged PR never changes);
/// 2. measures the stored PRs that have no complete measurement, oldest merge first, one at a time: their PR runs (by
///    head branch, kept when the head sha is one of the PR's commits), their merge-group runs (created in the PR's queue
///    window, kept when the branch is the PR's queue branch), and the jobs of every attempt of every run. Every listing,
///    run and attempt is stored as it is read, so a cycle that stops mid-PR resumes there and never asks for a finished
///    part again: a PR costing more than one slice completes over several cycles. A run still not completed is read
///    again alone each cycle, until [#GIVE_UP] past the merge.
///
/// The cycle ends when the slice is spent (`FILLING`), the budget refuses (`PAUSED`), a read fails (`FAILED` with the
/// reason: a 403 from an App without `actions:read`, a 404, a decode error, the deadline), or nothing is left (`OK`).
/// Every failure is the state; none is swallowed. A tick is single-flight; a tick while a cycle runs is skipped.
public record PipelineReader(GitHubReads reads,
                             Slice slice,
                             PipelineStore store,
                             PipelineScope scope,
                             Supplier<Instant> clock,
                             Duration deadline,
                             AtomicReference<PipelineStatus> status,
                             AtomicBoolean running) {
    public static final Duration DEADLINE = Duration.ofMinutes(9);
    /// Search may index a merge late: each listing looks back this far past the newest stored merge.
    static final Duration LOOKBACK = Duration.ofDays(1);
    /// Queue runs are listed by creation time from just before the first queue entry to just after the merge.
    static final Duration MARGIN = Duration.ofMinutes(1);
    /// A PR whose runs have not all completed this long after its merge is given up: stored complete, its runs that never
    /// completed counted (INCOMPLETE in the tab and the JSON) and never read again. [guess: three days, the lead's number.]
    public static final Duration GIVE_UP = Duration.ofDays(3);

    static final String QUERY = "query($q: String!, $after: String) { search(query: $q, type: ISSUE, first: 25, after: $after) "
                              + "{ pageInfo { hasNextPage endCursor } nodes { ... on PullRequest { number title mergedAt "
                              + "headRefName commits(first: 250) { totalCount nodes { commit { oid messageHeadline committedDate "
                              + "parents { totalCount } } } } timelineItems(first: 100, itemTypes: [ADDED_TO_MERGE_QUEUE_EVENT, "
                              + "REMOVED_FROM_MERGE_QUEUE_EVENT, HEAD_REF_FORCE_PUSHED_EVENT]) { pageInfo { hasNextPage } nodes { __typename "
                              + "... on AddedToMergeQueueEvent { createdAt } ... on HeadRefForcePushedEvent { createdAt } "
                              + "... on RemovedFromMergeQueueEvent { createdAt reason } } } } } } }";

    static final String FORBIDDEN_HINT = " (does the kiln App have actions:read and pull_requests:read?)";

    public static PipelineReader pipelineReader(GitHubHttp http,
                                                EtagStore etags,
                                                RateBudget budget,
                                                PipelineStore store,
                                                PipelineScope scope,
                                                Supplier<Instant> clock) {
        return pipelineReader(http, etags, budget, store, scope, clock, DEADLINE);
    }

    public static PipelineReader pipelineReader(GitHubHttp http,
                                                EtagStore etags,
                                                RateBudget budget,
                                                PipelineStore store,
                                                PipelineScope scope,
                                                Supplier<Instant> clock,
                                                Duration deadline) {
        var slice = Slice.slice(http, scope.slice());

        return new PipelineReader(GitHubReads.gitHubReads(slice, etags, budget, clock),
                                  slice,
                                  store,
                                  scope,
                                  clock,
                                  deadline,
                                  new AtomicReference<>(PipelineStatus.never(clock.get())),
                                  new AtomicBoolean());
    }

    /// One loop tick: a cycle, or the current state when a cycle is still running.
    public Promise<PipelineStatus> tick() {
        return running.compareAndSet(false, true)
               ? cycle().withResult(_ -> running.set(false))
               : Promise.success(status.get());
    }

    /// The scope, the state and every stored PR.
    public Promise<PipelineView> view() {
        return store.rows()
                    .map(rows -> new PipelineView(scope,
                                                  status.get(),
                                                  rows));
    }

    /// One cycle now, bypassing the single-flight guard. Every exit settles: a synchronous throw fails it, and past the
    /// deadline it reads `FAILED(cycle timed out)`.
    public Promise<PipelineStatus> cycle() {
        var at = clock.get();

        return Result.lift(Causes::fromThrowable,
                           () -> run(at))
                     .async()
                     .flatMap(started -> started)
                     .timeout(TimeSpan.fromDuration(deadline))
                     .fold(result -> Promise.success(judged(result)))
                     .map(this::recorded);
    }

    private Promise<Outcome> run(Instant at) {
        return reads.http()
                    .mode() != Credential.Mode.APP
               ? Promise.success(new Outcome(PipelineStatus.Kind.NEEDS_APP,
                                             "kiln reads GitHub as " + reads.http()
                                                                            .mode()
                                                                            .name()
                                            + "; the baseline reads only with the kiln App"))
               : reads.budget()
                      .covers(scope.slice(),
                              at)
                 ? measured(at)
                 : GitHubReadError.BudgetSpent.FACTORY.apply(reads.budget().until(at, scope.slice())).promise();
    }

    /// A cycle's end: its kind and the reason it shows.
    record Outcome(PipelineStatus.Kind kind, String reason) {}

    private Promise<Outcome> measured(Instant at) {
        slice.reset();

        return store.rows()
                    .flatMap(rows -> listed(from(rows)))
                    .flatMap(listed -> store.listed(listed, at))
                    .flatMap(_ -> store.rows())
                    .flatMap(rows -> Steps.sequence(pending(rows),
                                                    this::measuredOne))
                    .flatMap(_ -> store.rows())
                    .map(PipelineReader::progress);
    }

    private static Outcome progress(List<PipelineStore.Row> rows) {
        var complete = rows.stream().filter(PipelineStore.Row::complete).count();

        return new Outcome(complete == rows.size()
                           ? PipelineStatus.Kind.OK
                           : PipelineStatus.Kind.FILLING,
                           complete + " of " + rows.size() + " PRs measured");
    }

    private Instant from(List<PipelineStore.Row> rows) {
        return rows.stream()
                   .map(PipelineStore.Row::mergedAt)
                   .max(Instant::compareTo)
                   .map(newest -> newest.minus(LOOKBACK))
                   .filter(start -> start.isAfter(scope.since()))
                   .orElse(scope.since());
    }

    private static List<PipelineStore.Row> pending(List<PipelineStore.Row> rows) {
        return rows.stream()
                   .filter(row -> !row.complete())
                   .toList();
    }

    // ---- listing -----------------------------------------------------------------------------------------------------
    private Promise<List<MergedPr.Listed>> listed(Instant from) {
        return listedFrom(searchQuery(from), Option.none(), List.of());
    }

    String searchQuery(Instant from) {
        return "repo:" + scope.repo()
             + " is:pr is:merged base:" + scope.base()
             + " merged:>=" + seconds(from)
             + " sort:created-asc";
    }

    private Promise<List<MergedPr.Listed>> listedFrom(String query, Option<String> after, List<MergedPr.Listed> read) {
        return body(query, after).async()
                   .flatMap(json -> reads.graphql(json, MergedPr::page))
                   .flatMap(page -> nextPage(query, page, read));
    }

    private Promise<List<MergedPr.Listed>> nextPage(String query, MergedPr.Page page, List<MergedPr.Listed> read) {
        var all = Stream.concat(read.stream(),
                                page.prs().stream()).toList();

        return page.after()
                   .map(cursor -> listedFrom(query,
                                             Option.some(cursor),
                                             all))
                   .or(Promise.success(all));
    }

    static Result<String> body(String query, Option<String> after) {
        return GitHubJson.MAPPER.writeAsString(Map.of("query",
                                                      QUERY,
                                                      "variables",
                                                      after.map(cursor -> Map.of("q", query, "after", cursor))
                                                           .or(Map.of("q", query))));
    }

    // ---- one PR ------------------------------------------------------------------------------------------------------
    private Promise<Unit> measuredOne(PipelineStore.Row row) {
        return MergedPr.one(row.node())
                       .async()
                       .flatMap(this::measuredOrExcluded)
                       .flatMap(measure -> store.measured(measure,
                                                          clock.get()));
    }

    /// A PR whose timeline has a next page is not read at all: it is recorded [PrMeasure#excluded] (complete, so never read
    /// again) and the cycle moves on to the next one. A measurement tool does not stall behind one PR.
    private Promise<PrMeasure> measuredOrExcluded(MergedPr pr) {
        return pr.timelineTruncated()
               ? Promise.success(PrMeasure.excluded(pr, PrMeasure.TIMELINE_OVER_PAGE))
               : measure(pr);
    }

    /// Every read is stored as it arrives, so a slice spent mid-PR loses nothing: the next cycle starts from
    /// [PipelineStore#progress] and asks only for what is missing. Past [#GIVE_UP] the runs still not completed are not
    /// read again.
    private Promise<PrMeasure> measure(MergedPr pr) {
        var givenUp = clock.get().isAfter(pr.mergedAt().plus(GIVE_UP));

        return store.progress(pr.number())
                    .flatMap(before -> listedRuns(pr, before, PipelineStore.Listing.PR).flatMap(_ -> listedRuns(pr,
                                                                                                                before,
                                                                                                                PipelineStore.Listing.QUEUE))
                                                 .flatMap(_ -> givenUp
                                                               ? Promise.unitPromise()
                                                               : refreshed(pr, before)))
                    .flatMap(_ -> store.progress(pr.number()))
                    .flatMap(progress -> attemptsRead(pr, progress, givenUp))
                    .flatMap(progress -> measured(pr, progress, givenUp).async());
    }

    private Promise<Unit> listedRuns(MergedPr pr, PipelineStore.Progress progress, PipelineStore.Listing listing) {
        return progress.listed(listing)
               ? Promise.unitPromise()
               : listing(pr, listing).flatMap(runs -> store.runsListed(pr.number(), listing, runs));
    }

    private Promise<List<PipelineStore.Run>> listing(MergedPr pr, PipelineStore.Listing listing) {
        return (listing == PipelineStore.Listing.PR
                ? prRuns(pr)
                : queueRuns(pr)).map(runs -> runs.stream()
                                                 .filter(run -> RunAttempt.belongs(run,
                                                                                   pr,
                                                                                   scope.base()))
                                                 .map(PipelineReader::stored)
                                                 .toList());
    }

    static PipelineStore.Run stored(WorkflowRun run) {
        return new PipelineStore.Run(run.id(),
                                     run.name(),
                                     run.event().or(""),
                                     run.headSha(),
                                     run.headBranch().or(""),
                                     Math.max(1, run.attempt()),
                                     run.completed());
    }

    private Promise<List<WorkflowRun>> prRuns(MergedPr pr) {
        return runPages("/repos/" + scope.repo()
                       + "/actions/runs?event=pull_request&branch=" + URLEncoder.encode(pr.headRef(),
                                                                                        StandardCharsets.UTF_8)
                       + "&per_page=100");
    }

    /// No queue event: the PR never entered the queue, so it has no merge-group run and nothing is asked.
    private Promise<List<WorkflowRun>> queueRuns(MergedPr pr) {
        return Option.from(pr.queue().stream().map(MergedPr.QueueEvent::at).min(Instant::compareTo))
                     .map(first -> runPages("/repos/" + scope.repo()
                                           + "/actions/runs?event=merge_group&created=" + seconds(first.minus(MARGIN))
                                           + ".." + seconds(pr.mergedAt().plus(MARGIN))
                                           + "&per_page=100"))
                     .or(Promise.success(List.of()));
    }

    private Promise<List<WorkflowRun>> runPages(String path) {
        return reads.pagesOnce(path, Counted::workflowRuns)
                    .map(pages -> pages.stream()
                                       .flatMap(page -> page.items()
                                                            .stream())
                                       .toList());
    }

    /// Each run not completed when a previous cycle read it is read again alone (`GET /actions/runs/{id}`); a completed
    /// one never is, and neither is one this cycle's listing has just read.
    private Promise<Unit> refreshed(MergedPr pr, PipelineStore.Progress before) {
        return Steps.sequence(before.runs().stream().filter(run -> !run.completed()).toList(),
                              run -> reads.parsedOnce("/repos/" + scope.repo() + "/actions/runs/" + run.run(),
                                                      WorkflowRun::decodeOne)
                                          .flatMap(fresh -> store.run(pr.number(),
                                                                      reread(run, fresh))))
                    .mapToUnit();
    }

    private static PipelineStore.Run reread(PipelineStore.Run run, WorkflowRun fresh) {
        return new PipelineStore.Run(run.run(),
                                     run.workflow(),
                                     run.event(),
                                     run.headSha(),
                                     run.headBranch(),
                                     Math.max(run.attempts(), fresh.attempt()),
                                     fresh.completed());
    }

    /// One attempt to read: `settled` when it can change no more (a later attempt exists, or its run has completed).
    record Wanted(PipelineStore.Run run, long attempt, boolean settled) {}

    /// Every attempt of every run.
    static List<Wanted> wanted(PipelineStore.Progress progress) {
        return progress.runs()
                       .stream()
                       .flatMap(run -> LongStream.rangeClosed(1,
                                                              run.attempts())
                                                 .mapToObj(attempt -> new Wanted(run,
                                                                                 attempt,
                                                                                 attempt < run.attempts() || run.completed())))
                       .toList();
    }

    /// An attempt is read when none is stored, or when the stored one was read unsettled and it has settled since
    /// (never once the PR is given up).
    static boolean due(Wanted wanted, PipelineStore.Progress progress, boolean givenUp) {
        return stored(progress, wanted).map(attempt -> !attempt.settled()
                                                       && wanted.settled()
                                                       && !givenUp)
                     .or(true);
    }

    private static Option<PipelineStore.Attempt> stored(PipelineStore.Progress progress, Wanted wanted) {
        return Option.from(progress.attempts()
                                   .stream()
                                   .filter(attempt -> attempt.run() == wanted.run()
                                                                             .run() && attempt.attempt() == wanted.attempt())
                                   .findFirst());
    }

    private Promise<PipelineStore.Progress> attemptsRead(MergedPr pr,
                                                         PipelineStore.Progress progress,
                                                         boolean givenUp) {
        return Steps.sequence(wanted(progress).stream().filter(wanted -> due(wanted, progress, givenUp)).toList(),
                              wanted -> attempt(pr, wanted))
                    .flatMap(_ -> store.progress(pr.number()));
    }

    /// A re-run attempt (`attempt` > 1) costs one request more, for its `run_started_at`: its jobs page also lists the
    /// jobs it did not re-run, and only those that started at or after it count ([RunAttempt#ran]).
    private Promise<Unit> attempt(MergedPr pr, Wanted wanted) {
        var run = wanted.run().run();
        var path = "/repos/" + scope.repo() + "/actions/runs/" + run + "/attempts/" + wanted.attempt();

        return runStarted(path,
                          wanted.attempt()).flatMap(start -> reads.parsedOnce(path
                                                                             + "/jobs?per_page=" + RunAttempt.PER_PAGE,
                                                                              json -> RunAttempt.jobs(run, json).flatMap(jobs -> RunAttempt.stored(start,
                                                                                                                                                   jobs))))
                         .flatMap(jobs -> store.attempt(pr.number(),
                                                        new PipelineStore.Attempt(run,
                                                                                  wanted.attempt(),
                                                                                  jobs,
                                                                                  wanted.settled())));
    }

    private Promise<Option<Instant>> runStarted(String path, long attempt) {
        return attempt > 1
               ? reads.parsedOnce(path, RunAttempt::runStarted)
                      .map(Option::some)
               : Promise.success(Option.none());
    }

    /// The measurement from what is stored. Complete when every run has completed and every attempt was read settled;
    /// given up, it is complete too, and counts the runs that were not.
    static Result<PrMeasure> measured(MergedPr pr, String base, PipelineStore.Progress progress, boolean givenUp) {
        var open = progress.runs().stream().filter(run -> !settled(run, progress)).count();

        return Result.allOf(progress.attempts().stream().map(attempt -> runAttempt(progress, attempt)).toList()).map(attempts -> PrMeasure.measure(pr,
                                                                                                                                                   base,
                                                                                                                                                   attempts,
                                                                                                                                                   open == 0 || givenUp,
                                                                                                                                                   givenUp
                                                                                                                                                   ? (int) open
                                                                                                                                                   : 0));
    }

    private Result<PrMeasure> measured(MergedPr pr, PipelineStore.Progress progress, boolean givenUp) {
        return measured(pr, scope.base(), progress, givenUp);
    }

    private static boolean settled(PipelineStore.Run run, PipelineStore.Progress progress) {
        return run.completed() && wanted(progress).stream()
                                        .filter(wanted -> wanted.run()
                                                                .run() == run.run())
                                        .allMatch(wanted -> stored(progress, wanted).filter(PipelineStore.Attempt::settled)
                                                                  .isPresent());
    }

    private static Result<RunAttempt> runAttempt(PipelineStore.Progress progress, PipelineStore.Attempt attempt) {
        return Option.from(progress.runs().stream().filter(candidate -> candidate.run() == attempt.run()).findFirst())
                     .map(run -> runAttempt(run, attempt))
                     .or(() -> NO_RUN.result());
    }

    /// The store's foreign key keeps an attempt with its run; this is that rule read back.
    private static final Cause NO_RUN = Causes.cause("a stored attempt has no stored run");

    private static Result<RunAttempt> runAttempt(PipelineStore.Run run, PipelineStore.Attempt attempt) {
        return Result.all(RunAttempt.storedStart(attempt.jobs()),
                          RunAttempt.jobs(attempt.run(),
                                          attempt.jobs()))
                     .map((start, jobs) -> new RunAttempt(run.run(),
                                                          attempt.attempt(),
                                                          run.workflow(),
                                                          run.event(),
                                                          run.headSha(),
                                                          run.headBranch(),
                                                          start,
                                                          jobs));
    }

    private static String seconds(Instant at) {
        return at.truncatedTo(ChronoUnit.SECONDS)
                 .toString();
    }

    // ---- state -------------------------------------------------------------------------------------------------------
    private static Outcome judged(Result<Outcome> result) {
        return result.fold(PipelineReader::failed, outcome -> outcome);
    }

    /// The slice spent is a healthy end; the budget's refusal is a pause until its reset; anything else is a failure
    /// with its reason.
    static Outcome failed(Cause cause) {
        return switch (cause) {
            case Slice.Spent _ -> new Outcome(PipelineStatus.Kind.FILLING, "slice spent; more next cycle");
            case GitHubReadError.BudgetSpent spent -> new Outcome(PipelineStatus.Kind.PAUSED,
                                                                  "GitHub budget kept in reserve until " + spent.reset());
            case CoreError.Timeout _ -> new Outcome(PipelineStatus.Kind.FAILED, "cycle timed out");
            case GitHubError.HttpStatus status when status.status() == 403 -> new Outcome(PipelineStatus.Kind.FAILED,
                                                                                          status.message() + FORBIDDEN_HINT);
            default -> new Outcome(PipelineStatus.Kind.FAILED, cause.message());
        };
    }

    private PipelineStatus recorded(Outcome outcome) {
        return status.updateAndGet(previous -> previous.then(outcome.kind(), outcome.reason(), clock.get()));
    }
}
