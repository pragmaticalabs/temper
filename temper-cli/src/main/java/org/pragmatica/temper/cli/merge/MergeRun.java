package org.pragmatica.temper.cli.merge;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `temper merge <go-id> --token-file <path>` (with the lead's 11-03 amendment 2(d)):
/// 1. `POST fleet/go/{id}/consume`; 404/409 is `refused <detail>`, exit 2.
/// 2. On the Mac, from the explicit URL into the G8 cache: (a) the PR head is still the GO's SHA; (b) the base moved
///    only by empty commits since the GO's `base_tip`, and not backwards; (c) G8 in full against the current tip;
///    (d) the GO's verdict report still hashes to its registered sha256. The first refusal is recorded (`refused`),
///    printed, exit 2, and **gh is not called**; the GO stays consumed (re-gate).
/// 3. `merging` recorded with the tip just checked; unrecorded is `refused outcome-unrecorded`, no gh call.
/// 4. `gh pr merge … --match-head-commit <sha>` through [GhMerge].
/// 5. gh exit 0: `merged` recorded and printed, exit 0; gh timed out: `merge-unknown`, nothing recorded, exit 3 (an unrecorded `merged` adds `recorded=no`: the merge happened
///    and the detector records it); otherwise `merge-failed` recorded and printed, exit 3.
///
/// Lines are printed as each step completes, so a CLI that dies mid-way has already said what it did.
record MergeRun(MergeApi api, Git git, MergePorts ports, Path home, Console console, Budget budget) {
    static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    /// What step 2 found: the first refusal, and the base tip it checked (`baseTipBefore` of `merging`).
    record Recheck(Option<String> refusal, String tip) {}

    static Promise<Outcome> merge(CliCommand.MergeGo command,
                                  MergeApi api,
                                  Git git,
                                  MergePorts ports,
                                  Path home,
                                  Console console) {
        var run = new MergeRun(api, git, ports, home, console, ports.unbounded());

        return run.consumed(command.go());
    }

    private Promise<Outcome> consumed(String go) {
        return budget.bound()
                     .async()
                     .flatMap(bound -> api.consume(go, bound))
                     .fold(result -> result.fold(this::notConsumed, this::checked));
    }

    /// 404 and 409 are the controller's refusal (`GO_NOT_FOUND`, `go-used`, `go-invalidated <reason>`, `go-expired`);
    /// anything else keeps its own exit (69, 77, 13).
    private Promise<Outcome> notConsumed(Cause cause) {
        return cause instanceof GateFault.Answered answered && (answered.status() == 404 || answered.status() == 409)
               ? Promise.success(MergeLines.refused(MergeLines.kebab(answered.error())))
               : cause.promise();
    }

    private Promise<Outcome> checked(MergeWire.Consumed consumed) {
        return printed(MergeLines.consumed(consumed)).flatMap(_ -> usableChecked(consumed));
    }

    private Promise<Outcome> usableChecked(MergeWire.Consumed consumed) {
        return isUsable(consumed)
               ? rechecked(consumed)
               : refused(consumed, "source-unavailable temper " + Evaluator.BAD_PAYLOAD);
    }

    /// The reply's repo and SHAs build a URL, a cache path and git arguments: they must be what they claim to be.
    private static boolean isUsable(MergeWire.Consumed consumed) {
        return REPO.matcher(consumed.repo()).matches()
               && BaseCheck.SHA40.matcher(consumed.sha()).matches()
               && BaseCheck.SHA40.matcher(consumed.baseTip()).matches();
    }

    private Promise<Outcome> rechecked(MergeWire.Consumed consumed) {
        return recheck(consumed).flatMap(recheck -> recheck.refusal()
                                                           .map(reason -> refused(consumed, reason))
                                                           .or(() -> merging(consumed,
                                                                             recheck.tip())));
    }

    private Promise<Recheck> recheck(MergeWire.Consumed consumed) {
        var cache = BaseCheck.cache(home, consumed.repo());

        return BaseCheck.fetched(git,
                                 budget,
                                 cache,
                                 ports.url(consumed.repo()),
                                 consumed.base(),
                                 consumed.pr(),
                                 BaseCheck.runId(ports.nanos().getAsLong()))
                        .flatMap(fetched -> steps(consumed, cache, fetched).map(refusal -> new Recheck(refusal,
                                                                                                       fetched.base())))
                        .fold(result -> Promise.success(result.fold(cause -> gitRefusal(consumed, cause),
                                                                    recheck -> recheck)));
    }

    /// FER, fail closed: a git step that failed is the refusal (`source-unavailable git <step>`): no gh call.
    private static Recheck gitRefusal(MergeWire.Consumed consumed, Cause cause) {
        return new Recheck(some(Reason.gitUnavailable(GateFault.stepOf(cause)).line()),
                           consumed.baseTip());
    }

    private Promise<Option<String>> steps(MergeWire.Consumed consumed, Path cache, BaseCheck.Fetched fetched) {
        return orNext(headMoved(consumed, fetched),
                      () -> sinceGo(consumed, cache, fetched)).flatMap(found -> orNext(found,
                                                                                       () -> fullG8(consumed,
                                                                                                    cache,
                                                                                                    fetched)))
                     .flatMap(found -> orNext(found,
                                              () -> Promise.success(reportChanged(consumed))));
    }

    private static Promise<Option<String>> orNext(Option<String> found, Supplier<Promise<Option<String>>> next) {
        return found.isPresent()
               ? Promise.success(found)
               : next.get();
    }

    /// (a) `--match-head-commit` is the head guarantee; this is the early refusal.
    private static Option<String> headMoved(MergeWire.Consumed consumed, BaseCheck.Fetched fetched) {
        return consumed.sha()
                       .equals(fetched.head())
               ? none()
               : some("head-moved " + HeadCheck.pullRef(consumed.pr()) + " " + fetched.head());
    }

    /// (b) The tip may have moved since the GO only by empty first-parent commits, and only forwards.
    private Promise<Option<String>> sinceGo(MergeWire.Consumed consumed, Path cache, BaseCheck.Fetched fetched) {
        return consumed.baseTip()
                       .equals(fetched.base())
               ? Promise.success(none())
               : BaseCheck.isAncestor(git,
                                      budget,
                                      cache,
                                      consumed.baseTip(),
                                      fetched.base())
                          .flatMap(ancestor -> afterAncestry(ancestor, consumed, cache, fetched));
    }

    private Promise<Option<String>> afterAncestry(boolean ancestor,
                                                  MergeWire.Consumed consumed,
                                                  Path cache,
                                                  BaseCheck.Fetched fetched) {
        return ancestor
               ? BaseCheck.moved(git,
                                 budget,
                                 cache,
                                 consumed.baseTip(),
                                 fetched.base())
                          .map(MergeRun::movedSinceGo)
               : Promise.success(some("base-rewound"));
    }

    private static Option<String> movedSinceGo(BaseCheck.Moved moved) {
        return moved.first()
                    .map(first -> "base-moved-since-go " + moved.count()
                                 + " non-empty commits, first " + MergeLines.sha12(first));
    }

    /// (c) G8 in full for the GO's SHA against the current tip (Q7: a READ token can create a GO).
    private Promise<Option<String>> fullG8(MergeWire.Consumed consumed, Path cache, BaseCheck.Fetched fetched) {
        return BaseCheck.analysed(git,
                                  budget,
                                  cache,
                                  consumed.sha(),
                                  fetched.base())
                        .map(reasons -> Option.from(reasons.stream().findFirst()).map(reason -> "base-moved-since-go " + reason.line()));
    }

    /// (d) The report is the one Mac-side input not otherwise re-checked at merge time.
    private static Option<String> reportChanged(MergeWire.Consumed consumed) {
        return ReportCheck.isUnchanged(consumed.verdict().reportPath(),
                                       consumed.verdict().reportSha256())
               ? none()
               : some(ReportCheck.CHANGED);
    }

    /// FER: the refusal stands whether or not the controller recorded it (the GO is consumed either way, and the
    /// operator re-gates); the record's own failure is dropped.
    private Promise<Outcome> refused(MergeWire.Consumed consumed, String reason) {
        return outcome(consumed,
                       new MergeWire.OutcomeDetail("refused",
                                                   MergeLines.detail(reason))).fold(_ -> printed("refused " + reason))
                      .map(_ -> Outcome.outcome(ExitCode.REFUSED));
    }

    private Promise<Outcome> merging(MergeWire.Consumed consumed, String tip) {
        return outcome(consumed, new MergeWire.OutcomeMerging("merging", tip)).fold(result -> result.fold(_ -> unrecorded(),
                                                                                                          _ -> ghMerged(consumed,
                                                                                                                        tip)));
    }

    private Promise<Outcome> unrecorded() {
        return printed("refused outcome-unrecorded").map(_ -> Outcome.outcome(ExitCode.REFUSED));
    }

    private Promise<Outcome> ghMerged(MergeWire.Consumed consumed, String tip) {
        return ports.gh()
                    .merge(consumed.repo(),
                           consumed.pr(),
                           consumed.sha())
                    .flatMap(exit -> answered(consumed, tip, exit));
    }

    private Promise<Outcome> answered(MergeWire.Consumed consumed, String tip, GhMerge.GhExit exit) {
        return exit.isMerged()
               ? merged(consumed, tip)
               : exit.timedOutAfter()
                     .map(this::unknown)
                     .or(() -> mergeFailed(consumed, exit));
    }

    /// 2026-11-04 item 1: gh did not finish, so the merge may have happened. Nothing is recorded (the GO
    /// stays `merging`; the detector's `merged route=kiln` closes it if the PR merged), exit 3.
    private Promise<Outcome> unknown(long seconds) {
        return printed("merge-unknown gh timed out after " + seconds + "s; kiln records the outcome").map(_ -> Outcome.outcome(ExitCode.MERGE_FAILED));
    }

    /// The merge happened; an unrecorded `merged` is reported (`recorded=no`) and the exit stays 0.
    private Promise<Outcome> merged(MergeWire.Consumed consumed, String tip) {
        return outcome(consumed,
                       new MergeWire.OutcomeOnly("merged")).fold(result -> printed(mergedLines(consumed, tip, result)))
                      .map(_ -> Outcome.outcome(ExitCode.ACCEPTED));
    }

    private static String mergedLines(MergeWire.Consumed consumed, String tip, Result<Unit> recorded) {
        return String.join("\n",
                           recorded.isSuccess()
                           ? List.of(MergeLines.merged(consumed, tip))
                           : List.of(MergeLines.merged(consumed, tip), "recorded=no"));
    }

    /// FER: the failed merge is the answer whether or not it was recorded; the GO stays `merging` otherwise.
    private Promise<Outcome> mergeFailed(MergeWire.Consumed consumed, GhMerge.GhExit exit) {
        var detail = "gh exit " + exit.code() + ": " + exit.firstStderrLine();

        return outcome(consumed,
                       new MergeWire.OutcomeDetail("merge-failed",
                                                   MergeLines.detail(detail))).fold(_ -> printed("merge-failed " + detail))
                      .map(_ -> Outcome.outcome(ExitCode.MERGE_FAILED));
    }

    private Promise<Unit> outcome(MergeWire.Consumed consumed, Object body) {
        return budget.bound()
                     .async()
                     .flatMap(bound -> api.outcome(consumed.id(),
                                                   body,
                                                   bound));
    }

    private Promise<Unit> printed(String text) {
        return console.line(text)
                      .async();
    }
}
