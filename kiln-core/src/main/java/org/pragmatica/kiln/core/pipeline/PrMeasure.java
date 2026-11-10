package org.pragmatica.kiln.core.pipeline;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;


/// One merged PR measured (#102). Machine time is in seconds, summed over the jobs of every attempt of every run that
/// belongs to the PR ([RunAttempt#belongs]):
/// - split three ways, each attempt by when it started: `queueSeconds` the merge-group runs before the first removal
///   whose reason is not `merged` (the first queue entry), `rerunSeconds` every run started at or after that removal
///   (merge-group and PR alike), `prSeconds` the PR runs before it;
/// - split by [Family]; `unknown` lists every `<workflow> / <job>` a family rule did not know;
/// - `syncSeconds`: the PR runs triggered by a base sync, that is whose head is a merge commit of the PR that is a base sync
///   (see [#measure]; a merge of another branch is not counted).
///
/// `complete` is false while a run of the PR is not completed: such a PR is measured again; a complete one never is. A PR
/// whose runs never all completed is given up at last and stored complete with `incompleteRuns` > 0, the runs that never
/// completed (their jobs count as far as they were read, in the week sums too): the tab and the JSON show it as INCOMPLETE.
///
/// A PR whose measurement cannot be made at all (its timeline is longer than the one page read, so its removals would be
/// read short) is stored complete too, with `excludedReason` naming why, no hours and no removals ([#excluded]): INCOMPLETE
/// like the former, but left out of the week sums and hours, and counted apart. It is never read again.
public record PrMeasure(long number,
                        String title,
                        Instant mergedAt,
                        String week,
                        boolean complete,
                        int runs,
                        int attempts,
                        long prSeconds,
                        long queueSeconds,
                        long rerunSeconds,
                        long fullReactorSeconds,
                        long emberSeconds,
                        long forgeSeconds,
                        long consensusSeconds,
                        long otherSeconds,
                        List<Removal> removals,
                        int baseSyncs,
                        int otherMerges,
                        long syncSeconds,
                        List<String> unknown,
                        boolean truncated,
                        int incompleteRuns,
                        String excludedReason) {
    public PrMeasure {
        excludedReason = excludedReason == null
                         ? ""
                         : excludedReason;
        removals = List.copyOf(removals);
        unknown = List.copyOf(unknown);
    }

    /// Why a PR whose timeline has a next page is [#excluded].
    public static final String TIMELINE_OVER_PAGE = "more than 100 queue events (the timeline is longer than the one page read)";

    public static PrMeasure measure(MergedPr pr, String base, List<RunAttempt> attempts, boolean complete) {
        return measure(pr, base, attempts, complete, 0);
    }

    /// A merge commit is a base sync when its headline names the base; any other merge is another merge. [guess: the
    /// headline rule. A precise rule (the second parent is on the base) costs a request per merge: GraphQL
    /// `associatedPullRequests` misses commits pushed straight to the base (#2803's sync parent 23c0bd8f has none), so
    /// only a `compare` per merge would do. The tab labels the rule.]
    public static PrMeasure measure(MergedPr pr,
                                    String base,
                                    List<RunAttempt> attempts,
                                    boolean complete,
                                    int incompleteRuns) {
        var removals = Removal.removals(pr);
        var window = removals.stream().map(Removal::at).min(Instant::compareTo);
        var merges = pr.commits().stream().filter(MergedPr.Commit::merge).toList();
        var syncs = merges.stream().filter(commit -> commit.headline()
                                                           .contains(base)).toList();
        var syncOids = syncs.stream().map(MergedPr.Commit::oid).collect(Collectors.toSet());
        var baseSyncs = syncs.size();

        return new PrMeasure(pr.number(),
                             pr.title(),
                             pr.mergedAt(),
                             week(pr.mergedAt()),
                             complete,
                             (int) attempts.stream().map(RunAttempt::run).distinct().count(),
                             attempts.size(),
                             sum(attempts, attempt -> split(attempt, Option.from(window)) == Split.PR),
                             sum(attempts, attempt -> split(attempt, Option.from(window)) == Split.QUEUE),
                             sum(attempts, attempt -> split(attempt, Option.from(window)) == Split.RERUN),
                             family(attempts, Family.FULL_REACTOR),
                             family(attempts, Family.EMBER),
                             family(attempts, Family.FORGE),
                             family(attempts, Family.CONSENSUS),
                             family(attempts, Family.OTHER),
                             removals,
                             baseSyncs,
                             merges.size() - baseSyncs,
                             sum(attempts, attempt -> pullRequestOn(attempt, syncOids)),
                             unknown(attempts),
                             pr.truncated(),
                             incompleteRuns,
                             "");
    }

    /// The record of a PR that could not be measured, and why: complete (never read again), no hours, no removals, left out
    /// of the week sums.
    public static PrMeasure excluded(MergedPr pr, String reason) {
        return new PrMeasure(pr.number(),
                             pr.title(),
                             pr.mergedAt(),
                             week(pr.mergedAt()),
                             true,
                             0,
                             0,
                             0,
                             0,
                             0,
                             0,
                             0,
                             0,
                             0,
                             0,
                             List.of(),
                             0,
                             0,
                             0,
                             List.of(),
                             pr.truncated(),
                             0,
                             reason);
    }

    /// The three-way split of one attempt.
    enum Split {
        PR,
        QUEUE,
        RERUN
    }

    static Split split(RunAttempt attempt, Option<Instant> window) {
        var rerun = window.flatMap(start -> attempt.started()
                                                   .filter(started -> !started.isBefore(start))).isPresent();

        return rerun
               ? Split.RERUN
               : attempt.event()
                        .equals(RunAttempt.MERGE_GROUP)
                 ? Split.QUEUE
                 : Split.PR;
    }

    /// `2026-W45`: the ISO week (UTC) of `at`.
    public static String week(Instant at) {
        var date = at.atOffset(ZoneOffset.UTC);

        return String.format("%d-W%02d",
                             date.get(IsoFields.WEEK_BASED_YEAR),
                             date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public long totalSeconds() {
        return prSeconds + queueSeconds + rerunSeconds;
    }

    public long removalCount(Removal.Kind kind) {
        return removals.stream()
                       .filter(removal -> removal.kind() == kind)
                       .count();
    }

    /// Kind of an INCOMPLETE PR that could not be measured at all.
    public static final String KIND_TIMELINE = "TIMELINE_OVER_PAGE";
    /// Kind of an INCOMPLETE PR given up with runs that never completed.
    public static final String KIND_RUNS = "RUNS_NEVER_COMPLETED";

    /// Whether the PR is INCOMPLETE: it could not be measured, or it gave up on runs that never completed. Either way it is
    /// [#excluded] from the week sums.
    public boolean incomplete() {
        return incompleteRuns > 0 || !excludedReason.isEmpty();
    }

    /// Whether the PR is left out of the week sums: only complete measurements are summed.
    public boolean excluded() {
        return incomplete();
    }

    /// The kind of incompleteness: [#KIND_TIMELINE], [#KIND_RUNS], or empty.
    public String excludedKind() {
        return ! excludedReason.isEmpty()
               ? KIND_TIMELINE
               : incompleteRuns > 0
                 ? KIND_RUNS
                 : "";
    }

    /// Why the PR is INCOMPLETE: `excludedReason`, or the runs that never completed; empty when it is not.
    public String why() {
        return ! excludedReason.isEmpty()
               ? excludedReason
               : incompleteRuns > 0
                 ? incompleteRuns + " run(s) never completed"
                 : "";
    }

    private static boolean pullRequestOn(RunAttempt attempt, Set<String> syncOids) {
        return attempt.event()
                      .equals(RunAttempt.PULL_REQUEST) && syncOids.contains(attempt.headSha());
    }

    private static long sum(List<RunAttempt> attempts, Predicate<RunAttempt> counted) {
        return attempts.stream()
                       .filter(counted)
                       .mapToLong(RunAttempt::seconds)
                       .sum();
    }

    private static long family(List<RunAttempt> attempts, Family family) {
        return attempts.stream()
                       .flatMap(attempt -> attempt.jobs()
                                                  .stream()
                                                  .filter(job -> Family.filed(attempt.workflow(),
                                                                              job.name())
                                                                       .family() == family))
                       .mapToLong(RunAttempt.Job::seconds)
                       .sum();
    }

    private static List<String> unknown(List<RunAttempt> attempts) {
        return attempts.stream()
                       .flatMap(attempt -> attempt.jobs()
                                                  .stream()
                                                  .filter(job -> !Family.filed(attempt.workflow(),
                                                                               job.name())
                                                                        .known())
                                                  .map(job -> attempt.workflow() + " / " + job.name()))
                       .distinct()
                       .sorted()
                       .toList();
    }
}
