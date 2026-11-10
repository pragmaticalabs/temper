package org.pragmatica.temper.cli.merge;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Option.none;


/// `temper merge-gate --queue [--repo <r>] [--base <b>]`: `GET fleet/merge-queue`, then per entry
/// the gate's own reads at the PR's current head (45 s each) and one line of advice. Only position 1 may read `merge` or
/// `update-branch`; every later position reads `next=wait` (design §4.4). `next` is advice; temper never acts on it.
/// Exit 0 even with blocked entries.
record QueueView(Evaluator evaluator, MergeApi api, MergePorts ports) {
    static final Set<Reason.Gate> CHECK_GATES = Set.of(Reason.Gate.G3, Reason.Gate.G4, Reason.Gate.G5, Reason.Gate.G10);

    static final Set<String> BASE_BLOCKERS = Set.of("base-ambiguous", "base-rewound", "fetch-mismatch");

    /// One entry with what its reads found.
    record Entry(MergeWire.Queue queue,
                 MergeWire.QueueEntry entry,
                 String head,
                 Evaluator.Evaluation evaluation,
                 String behind) {}

    static Promise<Outcome> view(CliCommand.MergeQueueView command,
                                 Evaluator evaluator,
                                 MergeApi api,
                                 MergePorts ports) {
        var viewing = new QueueView(evaluator, api, ports);

        return api.queue(command.repo(),
                         command.base(),
                         Budget.CALL)
                  .flatMap(viewing::lines)
                  .map(lines -> MergeLines.printed(lines, ExitCode.ACCEPTED));
    }

    /// Entries one after another, each on its own budget.
    private Promise<List<String>> lines(MergeWire.Queues queues) {
        return queues.queues()
                     .stream()
                     .flatMap(queue -> queue.entries()
                                            .stream()
                                            .map(entry -> new Pending(queue, entry)))
                     .reduce(Promise.success(List.of()),
                             this::appended,
                             (first, _) -> first);
    }

    record Pending(MergeWire.Queue queue, MergeWire.QueueEntry entry) {}

    private Promise<List<String>> appended(Promise<List<String>> lines, Pending pending) {
        return lines.flatMap(done -> entry(pending).map(line -> Stream.concat(done.stream(),
                                                                              Stream.of(line))
                                                                      .toList()));
    }

    private Promise<String> entry(Pending pending) {
        var budget = ports.started();

        return budget.bound()
                     .async()
                     .flatMap(bound -> api.prFresh(pending.queue().repo(),
                                                   pending.entry().pr(),
                                                   none(),
                                                   bound))
                     .fold(result -> headKnown(result, pending, budget))
                     .map(QueueView::line);
    }

    /// The first read names the current head (lead guess 2: `pr-fresh` without `sha` answers `pull` only).
    private Promise<Entry> headKnown(Result<MergeWire.PrFresh> result, Pending pending, Budget budget) {
        return result.map(fresh -> fresh.pull()
                                        .map(MergeWire.Pull::headSha))
                     .fold(cause -> Promise.success(unread(pending,
                                                           Evaluator.unavailable(cause,
                                                                                 budget,
                                                                                 Evaluator.VERSION_SKEW_ON_READ))),
                           head -> headRead(head, pending, budget));
    }

    private Promise<Entry> headRead(Option<String> head, Pending pending, Budget budget) {
        return head.map(sha -> evaluated(sha, pending, budget))
                   .or(() -> Promise.success(unread(pending,
                                                    Evaluator.Evaluation.evaluation(none(),
                                                                                    none(),
                                                                                    List.of(Evaluator.badPayload()),
                                                                                    ExitCode.FAILED))));
    }

    private Promise<Entry> evaluated(String head, Pending pending, Budget budget) {
        var repo = pending.queue().repo();

        return evaluator.evaluated(repo,
                                   pending.entry().pr(),
                                   head,
                                   budget)
                        .flatMap(evaluation -> evaluator.behind(repo,
                                                                head,
                                                                evaluation.baseTip(),
                                                                budget)
                                                        .map(behind -> new Entry(pending.queue(),
                                                                                 pending.entry(),
                                                                                 head,
                                                                                 evaluation,
                                                                                 behind)));
    }

    private static Entry unread(Pending pending, Evaluator.Evaluation evaluation) {
        return new Entry(pending.queue(), pending.entry(), Evaluator.UNKNOWN, evaluation, Evaluator.UNKNOWN);
    }

    /// `<pos> <repo>#<pr> head=<sha12> behind_by=<n> checks=<…> verdict=<…> next=<…>`.
    static String line(Entry entry) {
        var checks = checks(entry.evaluation());

        return entry.entry()
                    .position()
             + " " + entry.queue()
                          .repo()
             + "#" + entry.entry()
                          .pr()
             + " head=" + MergeLines.sha12(entry.head())
             + " behind_by=" + entry.behind()
             + " checks=" + checks
             + " verdict=" + verdict(entry.evaluation())
             + " next=" + next(entry, checks);
    }

    /// green: no G3-G5/G10 reason and temper and kiln answered; red: any `check-red`/`check-skipped`; pending: any
    /// `check-pending`; missing: the rest (missing, required-*, read-inconsistent, kiln or temper unavailable).
    static String checks(Evaluator.Evaluation evaluation) {
        var checkLines = evaluation.reasons()
                                   .stream()
                                   .filter(reason -> CHECK_GATES.contains(reason.check()))
                                   .map(Reason::line)
                                   .toList();

        return checkLines.isEmpty() && isRead(evaluation)
               ? "green"
               : checkLines.stream()
                           .anyMatch(line -> line.startsWith("check-red") || line.startsWith("check-skipped"))
                 ? "red"
                 : checkLines.stream()
                             .anyMatch(line -> line.startsWith("check-pending"))
                   ? "pending"
                   : "missing";
    }

    /// The checks were read: temper answered and named no G7 of its own (kiln unavailable).
    private static boolean isRead(Evaluator.Evaluation evaluation) {
        return evaluation.fresh()
                         .filter(fresh -> fresh.reasons()
                                               .stream()
                                               .noneMatch(reason -> Reason.Gate.G7.name().equals(reason.check())))
                         .isPresent();
    }

    static String verdict(Evaluator.Evaluation evaluation) {
        return evaluation.fresh()
                         .flatMap(MergeWire.PrFresh::verdict)
                         .map(QueueView::verdictWord)
                         .or("none");
    }

    private static String verdictWord(MergeWire.VerdictView verdict) {
        return verdict.carriedFrom()
                      .isPresent()
               ? "carried"
               : verdict.verdict();
    }

    /// Position 1, the first rule that applies; every later position waits.
    static String next(Entry entry, String checks) {
        return entry.entry()
                    .position() == 1
               ? firstNext(entry, checks)
               : "wait";
    }

    private static String firstNext(Entry entry, String checks) {
        var reasons = entry.evaluation().reasons();

        return notOpen(entry)
               ? "blocked:not-open"
               : first(reasons,
                       reason -> reason.check() == Reason.Gate.G9).map(QueueView::blocked)
                      .or(() -> baseNext(entry, checks, reasons));
    }

    private static boolean notOpen(Entry entry) {
        return entry.evaluation()
                    .reasons()
                    .stream()
                    .anyMatch(reason -> reason.word()
                                              .equals("not-open")) || entry.evaluation()
                                                                           .fresh()
                                                                           .flatMap(MergeWire.PrFresh::pull)
                                                                           .filter(pull -> !"open".equals(pull.state()))
                                                                           .isPresent();
    }

    private static String baseNext(Entry entry, String checks, List<Reason> reasons) {
        return reasons.stream()
                      .anyMatch(reason -> reason.word()
                                                .equals("base-moved"))
               ? "update-branch"
               : first(reasons,
                       reason -> BASE_BLOCKERS.contains(reason.word()) || reason.check() == Reason.Gate.G7).map(QueueView::blocked)
                      .or(() -> checksNext(entry, checks, reasons));
    }

    private static String checksNext(Entry entry, String checks, List<Reason> reasons) {
        return switch (checks) {
            case "pending" -> "wait-ci";
            case "red", "missing" -> "blocked:checks-" + checks;
            default -> verdictNext(entry, reasons);
        };
    }

    /// `verdict-base-mismatch` blocks like FIX/REJECT (lead guess 5).
    private static String verdictNext(Entry entry, List<Reason> reasons) {
        return "none".equals(verdict(entry.evaluation()))
               ? "verify"
               : first(reasons, QueueView::isVerdictBlocker).map(QueueView::blocked)
                      .or(() -> mergeOrGate(entry));
    }

    private static boolean isVerdictBlocker(Reason reason) {
        return reason.word()
                     .startsWith("verdict-") || reason.word()
                                                      .startsWith("report-");
    }

    private static String mergeOrGate(Entry entry) {
        return entry.entry()
                    .go()
                    .filter(go -> go.sha()
                                    .equals(entry.head()))
                    .map(go -> "merge go=" + go.id())
                    .or("gate");
    }

    private static Option<Reason> first(List<Reason> reasons, Predicate<Reason> test) {
        return Option.from(reasons.stream().filter(test).findFirst());
    }

    private static String blocked(Reason reason) {
        return "blocked:" + reason.word();
    }
}
