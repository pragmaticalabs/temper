package org.pragmatica.kiln.core.feed;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.CommitStatus;
import org.pragmatica.kiln.core.github.Counted;
import org.pragmatica.kiln.core.github.PullRead;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The merge gate's checks kiln judges at one PR head: G2 (the pull), G3 (the required
/// list), G4 and G5 (the checks), G10 (the list read again). G4 reuses the rule [CheckFeed] judges `CHECKS_SETTLED`
/// with (R2-3): [CheckFeed#observed] keeps per suite its latest run of the context and marks the suite qualifying when
/// its workflow run is a `pull_request` run listing the PR (a fork PR's lists none), and [CheckFeed#chosen] keeps per
/// distinct workflow the run with the greatest run number, then attempt; an unpinned context also has its latest
/// commit status, a pinned one never. One implementation, two callers.
///
/// Each reason line is the contract's exact text; lines come in check order G2, G3, G4, G5, G10.
public sealed interface FreshGate {
    String G2 = "G2";
    String G3 = "G3";
    String G4 = "G4";
    String G5 = "G5";
    String G10 = "G10";
    String GREEN = "green";
    String RED = "red";
    String PENDING = "pending";
    String SKIPPED = "skipped";
    String MISSING = "missing";
    /// What GitHub concludes as passing that the gate does not (G5, NOTE 1).
    Set<String> SKIPS = Set.of("skipped", "neutral", "success*");

    /// One NO reason: the check that gives it and its exact line.
    record Reason(String check, String line) {}

    /// One required check and one chosen observation of it (none: the check is missing), with its outcome.
    record Outcome(RequiredCheck check, String outcome, Option<CheckFeed.Observation> observation) {}

    /// The distinct ids read against the first page's `total_count`, for check runs and workflow runs.
    record Counts(long checkRunsRead, long checkRunsTotal, long workflowRunsRead, long workflowRunsTotal) {
        public boolean consistent() {
            return checkRunsRead == checkRunsTotal && workflowRunsRead == workflowRunsTotal;
        }
    }

    // G2
    /// `head.sha` ≠ SHA: head-moved; not open, or merged: not-open.
    static List<Reason> pull(Sha sha, PullRead pull) {
        return Stream.concat(pull.head()
                                 .equals(sha)
                             ? Stream.<Reason> empty()
                             : Stream.of(new Reason(G2,
                                                    "head-moved pulls " + pull.head().value())),
                             pull.open()
                             ? Stream.<Reason> empty()
                             : Stream.of(new Reason(G2,
                                                    "not-open " + pull.state())))
                     .toList();
    }

    // G3
    static List<Reason> required(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Unknown(var status) -> List.of(new Reason(G3, "required-unknown " + status));
            case RequiredChecks.NoneRequired _ -> List.of(new Reason(G3, "required-none"));
            case RequiredChecks.Required required -> required.checks().isEmpty()
                                                     ? List.of(new Reason(G3, "required-none"))
                                                     : List.of();
        };
    }

    // G4 (a)
    /// Distinct ids over every page against page 1's `total_count` (0 when nothing was read).
    static Counts counts(List<Counted<CheckRun>> checkRuns, List<Counted<WorkflowRun>> workflowRuns) {
        return new Counts(distinct(checkRuns, CheckRun::id),
                          total(checkRuns),
                          distinct(workflowRuns, WorkflowRun::id),
                          total(workflowRuns));
    }

    private static <T> long distinct(List<Counted<T>> pages, Function<T, Long> id) {
        return pages.stream()
                    .flatMap(page -> page.items()
                                         .stream())
                    .map(id)
                    .distinct()
                    .count();
    }

    private static <T> long total(List<Counted<T>> pages) {
        return Option.from(pages.stream().findFirst())
                     .map(Counted::total)
                     .or(0L);
    }

    static List<Reason> inconsistent(Counts counts) {
        return Stream.concat(counts.checkRunsRead() == counts.checkRunsTotal()
                             ? Stream.<Reason> empty()
                             : Stream.of(new Reason(G4,
                                                    "check-read-inconsistent check-runs " + counts.checkRunsRead()
                                                   + "/" + counts.checkRunsTotal())),
                             counts.workflowRunsRead() == counts.workflowRunsTotal()
                             ? Stream.<Reason> empty()
                             : Stream.of(new Reason(G4,
                                                    "check-read-inconsistent workflow-runs " + counts.workflowRunsRead()
                                                   + "/" + counts.workflowRunsTotal())))
                     .toList();
    }

    // G4 (b)-(e)
    /// One outcome per (required check, chosen observation), in list order; a check with none chosen is one `missing`.
    static List<Outcome> outcomes(RequiredChecks.Required list,
                                  PrNumber pr,
                                  List<CheckRun> runs,
                                  List<CommitStatus> statuses,
                                  List<WorkflowRun> headRuns) {
        var chosen = CheckFeed.chosen(CheckFeed.observed(list, pr, runs, statuses, headRuns));

        return list.checks()
                   .stream()
                   .flatMap(check -> outcomesOf(check, chosen))
                   .toList();
    }

    private static Stream<Outcome> outcomesOf(RequiredCheck check, List<CheckFeed.Observation> chosen) {
        var of = chosen.stream()
                       .filter(observation -> observation.check()
                                                         .equals(check))
                       .map(observation -> new Outcome(check,
                                                       outcome(observation),
                                                       some(observation)))
                       .toList();

        return of.isEmpty()
               ? Stream.of(new Outcome(check, MISSING, none()))
               : of.stream();
    }

    /// (d): not terminal: pending; `success`: green; skipped, neutral, or a skip-described status: skipped (G5);
    /// anything else red.
    static String outcome(CheckFeed.Observation observation) {
        return observation.conclusion()
                          .map(FreshGate::concluded)
                          .or(PENDING);
    }

    private static String concluded(String conclusion) {
        return switch (conclusion) {
            case "success" -> GREEN;
            case String skip when SKIPS.contains(skip) -> SKIPPED;
            default -> RED;
        };
    }

    /// (e) per check, then G5: the G4 lines of every check in list order (red: one per red observation, naming its suite
    /// or `status`), then the G5 lines of every check skipped and not red.
    static List<Reason> checks(List<Outcome> outcomes) {
        var checks = outcomes.stream().map(Outcome::check).distinct().toList();

        return Stream.concat(checks.stream().flatMap(check -> g4(check,
                                                                 of(check, outcomes))),
                             checks.stream().flatMap(check -> g5(check,
                                                                 of(check, outcomes))))
                     .toList();
    }

    private static List<Outcome> of(RequiredCheck check, List<Outcome> outcomes) {
        return outcomes.stream()
                       .filter(outcome -> outcome.check()
                                                 .equals(check))
                       .toList();
    }

    /// The check's verdict over its outcomes: missing, else red, else skipped, else pending, else green.
    static String verdict(List<Outcome> outcomes) {
        return Stream.of(MISSING, RED, SKIPPED, PENDING)
                     .filter(outcome -> outcomes.stream()
                                                .anyMatch(each -> each.outcome()
                                                                      .equals(outcome)))
                     .findFirst()
                     .orElse(GREEN);
    }

    private static Stream<Reason> g4(RequiredCheck check, List<Outcome> outcomes) {
        var context = check.context().value();

        return switch (verdict(outcomes)) {
            case RED -> outcomes.stream().filter(outcome -> RED.equals(outcome.outcome())).map(outcome -> new Reason(G4,
                                                                                                                     "check-red " + context
                                                                                                                    + " " + observedIn(outcome)));
            case PENDING -> Stream.of(new Reason(G4, "check-pending " + context));
            case MISSING -> Stream.of(new Reason(G4, "check-missing " + context));
            default -> Stream.empty();
        };
    }

    private static Stream<Reason> g5(RequiredCheck check, List<Outcome> outcomes) {
        return SKIPPED.equals(verdict(outcomes))
               ? Stream.of(new Reason(G5,
                                      "check-skipped " + check.context()
                                                              .value()
                                     + " (GitHub counts this as passing; merge-gate does not)"))
               : Stream.empty();
    }

    /// The suite id of a check run's observation, `status` for a commit status.
    static String observedIn(Outcome outcome) {
        return outcome.observation()
                      .map(FreshGate::observedIn)
                      .or("null");
    }

    private static String observedIn(CheckFeed.Observation observation) {
        return CheckFeed.STATUS.equals(observation.source())
               ? CheckFeed.STATUS
               : observation.suiteId()
                            .map(String::valueOf)
                            .or(observation.suite());
    }

    // G10
    /// The list read again differs: either ETag absent or different, or another (context, app) set.
    static List<Reason> again(RequiredChecks first,
                              Option<String> firstEtag,
                              RequiredChecks again,
                              Option<String> againEtag) {
        return sameEtag(firstEtag, againEtag) && contexts(first).equals(contexts(again))
               ? List.of()
               : List.of(new Reason(G10, "required-changed"));
    }

    private static boolean sameEtag(Option<String> first, Option<String> again) {
        return first.isPresent() && first.equals(again);
    }

    static Set<RequiredCheck> contexts(RequiredChecks list) {
        return switch (list) {
            case RequiredChecks.Required required -> Set.copyOf(required.checks());
            default -> Set.of();
        };
    }

    record unused() implements FreshGate {}
}
