package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The §4.4 selection and mapping: one [Cell] per check of a [RequiredChecks.Required] list. [RequiredChecks.NoneRequired]
/// and [RequiredChecks.Unknown] give none (the row shows the list's state instead). A failed check-runs read makes
/// every cell `UNKNOWN`; a failed statuses read makes `UNKNOWN` only the cells that read statuses (no app id).
public sealed interface CheckCells {
    String UNKNOWN = "UNKNOWN";
    String MISSING = "missing";
    String PENDING = "pending";
    /// A commit status `success` whose description says it was skipped: shown, never success.
    String SKIPPED_SUCCESS = "success*";
    String COMPLETED = "completed";
    Set<String> WAITING = Set.of("queued", "in_progress", "waiting", "requested", PENDING);
    Pattern SKIP = Pattern.compile("(?i)skip");

    /// The latest run: the greatest `started_at`, ties broken by the greatest `id`. A run with no `started_at` has not
    /// started yet (a queued rerun), so it is the newest and decides the cell (lead ruling 2026-10-30).
    Comparator<CheckRun> LATEST_RUN = Comparator.comparing((CheckRun run) -> run.startedAt()
                                                                                .or(Instant.MAX)).thenComparingLong(CheckRun::id);

    /// The latest status: the greatest `updated_at`, ties broken by the greatest `id`.
    Comparator<CommitStatus> LATEST_STATUS = Comparator.comparing(CommitStatus::updatedAt).thenComparingLong(CommitStatus::id);

    static List<Cell> cells(RequiredChecks required, Result<List<CheckRun>> runs, Result<List<CommitStatus>> statuses) {
        return switch (required) {
            case RequiredChecks.Required list -> required(list, runs, statuses);
            case RequiredChecks.NoneRequired _ -> List.of();
            case RequiredChecks.Unknown _ -> List.of();
        };
    }

    private static List<Cell> required(RequiredChecks.Required list,
                                       Result<List<CheckRun>> runs,
                                       Result<List<CommitStatus>> statuses) {
        var distinctRuns = runs.map(found -> byId(found, CheckRun::id));
        var distinctStatuses = statuses.map(posted -> byId(posted, CommitStatus::id));

        return list.checks()
                   .stream()
                   .map(check -> cell(check, distinctRuns, distinctStatuses))
                   .toList();
    }

    /// Pagination can list one run or status on two pages: each `id` is kept once, before any selection or count. The
    /// last copy is kept: pages are read in order, so it is the latest read of a run that changed between pages [guess].
    private static <T> List<T> byId(List<T> items, Function<T, Long> id) {
        return List.copyOf(items.stream()
                                .collect(Collectors.toMap(id,
                                                          Function.identity(),
                                                          (_, last) -> last,
                                                          LinkedHashMap::new))
                                .values());
    }

    /// Statuses are read only for a check with no app id; a pinned check is answered by its app's runs alone. FER: a
    /// failed read degrades the cell forward to `UNKNOWN`, which is never success; the poller records the failure itself
    /// as the source's state.
    private static Cell cell(RequiredCheck check, Result<List<CheckRun>> runs, Result<List<CommitStatus>> statuses) {
        return check.appId()
                    .isPresent()
               ? pinned(check, runs)
               : unpinned(check, runs, statuses);
    }

    private static Cell pinned(RequiredCheck check, Result<List<CheckRun>> runs) {
        return runs.map(found -> selected(check,
                                          found,
                                          List.of()))
                   .or(() -> unknown(check));
    }

    private static Cell unpinned(RequiredCheck check,
                                 Result<List<CheckRun>> runs,
                                 Result<List<CommitStatus>> statuses) {
        return Result.all(runs, statuses)
                     .map((found, posted) -> selected(check, found, posted))
                     .or(() -> unknown(check));
    }

    /// Runs named as the context are kept when no app id is required or when their app is the required one; the rest
    /// are `foreign`. The latest kept run and the latest status of the context are shown; the cell reads `success` only
    /// if every one shown does, and `missing` when none is.
    private static Cell selected(RequiredCheck check, List<CheckRun> runs, List<CommitStatus> statuses) {
        var named = runs.stream().filter(run -> run.name()
                                                   .equals(check.context().value())).toList();
        var kept = named.stream().filter(run -> fromRequiredApp(run, check)).toList();
        var shown = Stream.concat(Option.from(kept.stream().max(LATEST_RUN)).map(CheckCells::runValue).stream(),
                                  latestStatus(check, statuses).map(CheckCells::statusValue).stream())
                          .toList();

        return new Cell(check,
                        value(shown),
                        named.size() - kept.size(),
                        shown);
    }

    private static boolean fromRequiredApp(CheckRun run, RequiredCheck check) {
        return check.appId()
                    .isEmpty() || run.app()
                                     .equals(check.appId());
    }

    private static Option<CommitStatus> latestStatus(RequiredCheck check, List<CommitStatus> statuses) {
        return Option.from(statuses.stream()
                                   .filter(status -> status.context()
                                                           .equals(check.context().value()))
                                   .max(LATEST_STATUS));
    }

    /// A completed run shows its conclusion verbatim (`success` is GitHub's own word); a waiting one reads `pending`.
    private static String runValue(CheckRun run) {
        return COMPLETED.equals(run.status())
               ? run.conclusion()
                    .or(COMPLETED)
               : waitingOr(run.status());
    }

    private static String statusValue(CommitStatus status) {
        return Cell.SUCCESS.equals(status.state())
               ? successOrSkipped(status)
               : waitingOr(status.state());
    }

    private static String successOrSkipped(CommitStatus status) {
        return status.description()
                     .filter(description -> SKIP.matcher(description).find())
                     .map(_ -> SKIPPED_SUCCESS)
                     .or(Cell.SUCCESS);
    }

    private static String waitingOr(String value) {
        return WAITING.contains(value)
               ? PENDING
               : value;
    }

    private static String value(List<String> shown) {
        return shown.isEmpty()
               ? MISSING
               : Option.from(shown.stream().filter(value -> !Cell.SUCCESS.equals(value)).findFirst()).or(Cell.SUCCESS);
    }

    private static Cell unknown(RequiredCheck check) {
        return new Cell(check, UNKNOWN, 0, List.of());
    }

    record unused() implements CheckCells {}
}
