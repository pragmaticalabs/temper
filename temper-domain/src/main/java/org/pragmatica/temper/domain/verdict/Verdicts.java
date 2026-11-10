package org.pragmatica.temper.domain.verdict;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Verdict evaluation (spec §5.3 as corrected by the Q-8 ruling). The rules run in this order and the first
/// match wins:
///
/// 1. `VOID(reason)`: a unit has a void reason (ties go to the first unit by name).
/// 2. `ERROR(reason)`: a unit has an error reason; or `ERROR(plan)` when one class is reported by more than
///    one non-rerun unit.
/// 3. `CANCELLED`.
/// 4. `FAILED(timeout)`: a unit was killed by its timeout.
/// 5. `EMPTY`: a unit requested tests, exited **rc 0** and reported 0 testcases. A unit with rc ≠ 0 and 0
///    rows is not EMPTY: it falls through to `FAILED(rc)`.
/// 6. `FAILED(reason)`: first of `missing-classes` (needs a manifest), `tests` (a FAILED/ERROR row), `rc`
///    (a unit whose rc is not 0, or unknown).
/// 7. `PASSED`.
///
/// Rerun units (M2) never drive the verdict and are not counted: every rule and count sees primary units only.
///
/// Annotations never change the verdict: `superseded`, and `manifest-drift=<n>` when classes were reported
/// that the manifest did not expect (spec §5.2 "recorded as manifest-drift", m1-design §2.5).
///
/// Coverage (ruling R-2): missing = required − reported; drift = reported − (required ∪ optional). An optional class
/// is never missing and never drift.
public sealed interface Verdicts {
    String SUPERSEDED = "superseded";
    String MANIFEST_DRIFT = "manifest-drift=";

    static Evaluation evaluate(VerdictInput in) {
        var units = primaryUnits(in.units());
        var rows = primaryRows(units);
        var failed = rows.stream().filter(row -> row.outcome()
                                                    .isFailure()).toList();
        var drift = drift(rows, in.manifest());

        return Evaluation.evaluation(verdict(in, units, rows),
                                     counts(units, rows, in.manifest()),
                                     failed,
                                     failed.size(),
                                     annotations(in.superseded(), drift),
                                     missingOf(rows, in.manifest()),
                                     drift);
    }

    private static List<String> annotations(boolean superseded, Set<String> drift) {
        return Stream.concat(superseded
                             ? Stream.of(SUPERSEDED)
                             : Stream.empty(),
                             drift.isEmpty()
                             ? Stream.empty()
                             : Stream.of(MANIFEST_DRIFT + drift.size()))
                     .toList();
    }

    private static Set<String> missingOf(List<TestcaseRow> rows, Option<Manifest> manifest) {
        return manifest.map(expected -> missing(rows, expected))
                       .or(Set.of());
    }

    private static Verdict verdict(VerdictInput in, List<UnitOutcome> units, List<TestcaseRow> rows) {
        return compromised(in, units, rows).orElse(() -> judged(units,
                                                                rows,
                                                                in.manifest()))
                          .or(Verdict.Passed::new);
    }

    /// Rules 1-3: the run itself is not trustworthy, or was stopped.
    private static Option<Verdict> compromised(VerdictInput in, List<UnitOutcome> units, List<TestcaseRow> rows) {
        return voided(units).orElse(() -> errored(units, rows))
                     .orElse(() -> cancelled(in));
    }

    /// Rules 4-6: the run completed and is judged on its outcome.
    private static Option<Verdict> judged(List<UnitOutcome> units, List<TestcaseRow> rows, Option<Manifest> manifest) {
        return timedOut(units).orElse(() -> empty(units))
                       .orElse(() -> failed(units, rows, manifest));
    }

    private static Option<Verdict> voided(List<UnitOutcome> units) {
        return first(units.stream().flatMap(unit -> unit.voidReason()
                                                        .stream()).<Verdict> map(Verdict.Void::new));
    }

    private static Option<Verdict> errored(List<UnitOutcome> units, List<TestcaseRow> rows) {
        return first(units.stream().flatMap(unit -> unit.errorReason()
                                                        .stream()).<Verdict> map(Verdict.Error::new)).orElse(() -> planError(rows));
    }

    /// Rows name a class by its `classname`, so two distinct classes sharing a `@DisplayName` would look like one
    /// class reported twice. A unit whose manifest scans both is `ERROR(manifest: …)` instead
    /// ([org.pragmatica.temper.domain.manifest.ManifestError.SharedDisplayName]), and unit errors are checked first.
    /// Residual: two such classes in modules that no single unit scans (one unit per module) still read as `plan`,
    /// an ERROR either way, never a pass.
    private static Option<Verdict> planError(List<TestcaseRow> rows) {
        return owners(rows).stream()
                     .anyMatch(units -> units.size() > 1)
               ? some(new Verdict.Error("plan"))
               : none();
    }

    /// For each reported class, the units that reported it.
    private static Collection<Set<UnitName>> owners(List<TestcaseRow> rows) {
        return rows.stream()
                   .collect(Collectors.groupingBy(TestcaseRow::className,
                                                  Collectors.mapping(TestcaseRow::unit,
                                                                     Collectors.toSet())))
                   .values();
    }

    private static Option<Verdict> cancelled(VerdictInput in) {
        return in.cancelled()
               ? some(new Verdict.Cancelled())
               : none();
    }

    private static Option<Verdict> timedOut(List<UnitOutcome> units) {
        return units.stream()
                    .anyMatch(UnitOutcome::timedOut)
               ? some(new Verdict.Failed("timeout"))
               : none();
    }

    private static Option<Verdict> empty(List<UnitOutcome> units) {
        var emptyUnits = units.stream().filter(Verdicts::isEmpty).map(UnitOutcome::name).toList();

        return emptyUnits.isEmpty()
               ? none()
               : some(new Verdict.Empty(emptyUnits));
    }

    private static boolean isEmpty(UnitOutcome unit) {
        return unit.requestsTests()
               && unit.exitedClean()
               && unit.testcases()
                      .isEmpty();
    }

    private static Option<Verdict> failed(List<UnitOutcome> units, List<TestcaseRow> rows, Option<Manifest> manifest) {
        return missingClasses(rows, manifest).orElse(() -> failedTests(rows))
                             .orElse(() -> nonZeroRc(units));
    }

    private static Option<Verdict> missingClasses(List<TestcaseRow> rows, Option<Manifest> manifest) {
        return manifest.filter(expected -> !missing(rows, expected).isEmpty())
                       .map(_ -> new Verdict.Failed("missing-classes"));
    }

    private static Option<Verdict> failedTests(List<TestcaseRow> rows) {
        return rows.stream()
                   .anyMatch(row -> row.outcome()
                                       .isFailure())
               ? some(new Verdict.Failed("tests"))
               : none();
    }

    private static Option<Verdict> nonZeroRc(List<UnitOutcome> units) {
        return units.stream()
                    .anyMatch(unit -> !unit.exitedClean())
               ? some(new Verdict.Failed("rc"))
               : none();
    }

    /// Non-rerun units, sorted by name (so ties go to the first unit by name).
    private static List<UnitOutcome> primaryUnits(List<UnitOutcome> units) {
        return units.stream()
                    .filter(Predicate.not(UnitOutcome::rerun))
                    .sorted(Comparator.comparing(UnitOutcome::name,
                                                 Comparator.comparing(UnitName::value)))
                    .toList();
    }

    private static List<TestcaseRow> primaryRows(List<UnitOutcome> units) {
        return units.stream()
                    .flatMap(unit -> unit.testcases()
                                         .stream())
                    .toList();
    }

    private static Counts counts(List<UnitOutcome> units, List<TestcaseRow> rows, Option<Manifest> manifest) {
        var byOutcome = rows.stream().collect(Collectors.groupingBy(TestcaseRow::outcome, Collectors.counting()));

        return Counts.counts(rows.size(),
                             count(byOutcome, Outcome.PASSED),
                             count(byOutcome, Outcome.FAILED),
                             count(byOutcome, Outcome.ERROR),
                             count(byOutcome, Outcome.SKIPPED),
                             units.stream().mapToLong(UnitOutcome::testsAttr).sum(),
                             reported(rows).size(),
                             manifest.map(expected -> expected.required()
                                                              .size()),
                             manifest.map(expected -> missing(rows, expected).size()),
                             manifest.map(expected -> unexpected(rows, expected).size()));
    }

    private static int count(Map<Outcome, Long> byOutcome, Outcome outcome) {
        return byOutcome.getOrDefault(outcome, 0L)
                        .intValue();
    }

    private static Set<String> reported(List<TestcaseRow> rows) {
        return rows.stream()
                   .map(TestcaseRow::className)
                   .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> missing(List<TestcaseRow> rows, Manifest manifest) {
        var reported = reported(rows);

        return manifest.required()
                       .stream()
                       .filter(expected -> !reported.contains(expected))
                       .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> drift(List<TestcaseRow> rows, Option<Manifest> manifest) {
        return manifest.map(expected -> unexpected(rows, expected))
                       .or(Set.of());
    }

    private static Set<String> unexpected(List<TestcaseRow> rows, Manifest manifest) {
        return reported(rows).stream()
                       .filter(className -> !manifest.expects(className))
                       .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Option<Verdict> first(Stream<Verdict> verdicts) {
        return Option.from(verdicts.findFirst());
    }

    record unused() implements Verdicts {}
}
