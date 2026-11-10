package org.pragmatica.temper.domain.rerun;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.verdict.UnitOutcome;
import org.pragmatica.temper.domain.verdict.VerdictInput;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// What a rerun job's record adds to its annotations (m2-design §5.3, ruling R-M2-16): `rerun-of=<parent>`, the unit's
/// `rerun-bound=<value> (<derivation>)` when it was decided at submit (maintainer ruling on the rerun bound), then one
/// `rerun(<class>)=<outcome>` per target, in target order, a class that ran beside other target classes of its module
/// and plugin adding ` co-run in one fork with: <classes>`, then `extra-rows=<n>` when rows of non-target classes came
/// in. Nothing else: the record carries the original outcome (its recipe's `original`) and the rerun's outcome per
/// class, and draws no conclusion from the two (no FLAKY, rulings R-M2-9 and R-M2-10). The job's verdict is the normal
/// `Verdicts`' and is never touched here.
///
/// A class's outcome comes from the job's one unit (`UnitName.RERUN`) and that unit's rows of the class: rows whose
/// classname is the target's fully qualified name, or a class nested under it at any depth (`Target$Inner`, ruling
/// R-M2-16 "nested included"); a same-named class of another package, or a longer name sharing the prefix, is not it.
/// A row counts only for its most specific target (R-M2-16 as amended): with targets `Outer` and `Outer$Inner`, a row of
/// `Outer$Inner` is that target's alone, and `Outer` is judged on its own rows and on nested classes that are not
/// targets. The first rule that matches decides:
///
/// 1. `NOT-RUN(void)`, `NOT-RUN(error)`: the unit is VOID or ERROR (a unit refused at start, R-DISK, is VOID);
/// 2. `NOT-RUN(cancelled)`: the job was cancelled, by request (a rerun has no ref, so never by supersede);
/// 3. `NOT-RUN(timeout)`: the unit was killed by its timeout;
/// 4. `FAILED`: a failed or error row of the class;
/// 5. `PASSED`: at least one row of the class, none failing (ruling R-M2-16: the unit's rc is every class's);
/// 6. `NOT-RUN(empty)`: no row of the class and rc 0; `NOT-RUN(rc)`: no row of the class and rc not 0 (or unknown), a
///    build that broke before the class ran, since the step ignores test failures (ruling R-M2-16);
/// 7. `NOT-RUN(no-unit)`: the job has no unit named `rerun`; the planner always makes it, so only rows edited by hand
///    could get here [G].
///
/// Q-M2-15 names `refused` too: a unit refused at start (R-DISK) ends VOID, so it reads `NOT-RUN(void)` [G]; a rerun
/// request refused as a whole is a REFUSED job with no record, so no class of it is ever reported.
///
/// The NOT-RUN reasons are Q-M2-15's (accepted, ruling R-M2-9); a rerun that never ran is reported as neither PASSED
/// nor FAILED.
public sealed interface RerunOutcome {
    String RERUN_OF = "rerun-of=";
    String RERUN_BOUND = "rerun-bound=";
    String CLASS_PREFIX = "rerun(";
    String EXTRA_ROWS = "extra-rows=";
    String CO_RUN = " co-run in one fork with: ";
    String PASSED = "PASSED";
    String FAILED = "FAILED";
    Set<String> RAN = Set.of(PASSED, FAILED);
    List<String> RERUN_PREFIXES = List.of(RERUN_OF, RERUN_BOUND, CLASS_PREFIX, EXTRA_ROWS);

    /// The rerun annotations of a job with `recipe`, from its verdict input; none for any other recipe.
    static List<String> annotations(Recipe recipe, VerdictInput input) {
        return switch (recipe) {
            case Recipe.Rerun rerun -> rerunAnnotations(rerun, input);
            case Recipe.Mvn _, Recipe.ModuleTest _, Recipe.Gate _, Recipe.Unsupported _ -> List.of();
        };
    }

    private static List<String> rerunAnnotations(Recipe.Rerun rerun, VerdictInput input) {
        return Stream.of(Stream.of(RERUN_OF + rerun.of()),
                         rerun.bound().map(Recipe.Rerun.Bound::annotation).stream(),
                         rerun.targets().stream().map(target -> classAnnotation(rerun, target, input)),
                         extraRows(rerun, input))
                     .flatMap(Function.identity())
                     .toList();
    }

    private static String classAnnotation(Recipe.Rerun rerun, Recipe.Rerun.Target target, VerdictInput input) {
        return withCoRun(rerun, target, outcome(rerun, target, input));
    }

    private static String withCoRun(Recipe.Rerun rerun, Recipe.Rerun.Target target, String outcome) {
        return annotation(target, outcome) + coRun(rerun, target, outcome);
    }

    /// ` co-run in one fork with: <classes>` for a class that ran (PASSED or FAILED) beside other target classes of its
    /// module and plugin, which share its fork (ruling R-M2-16); nothing for a class that never ran [G]. A sibling is
    /// listed whether or not it reported rows [G]: it was in the fork's selection, and the text is about the fork.
    private static String coRun(Recipe.Rerun rerun, Recipe.Rerun.Target target, String outcome) {
        return RAN.contains(outcome)
               ? coRunText(forkSiblings(rerun, target))
               : "";
    }

    /// Nothing for a class alone in its fork.
    private static String coRunText(List<String> siblings) {
        return siblings.isEmpty()
               ? ""
               : CO_RUN + String.join(",", siblings);
    }

    private static List<String> forkSiblings(Recipe.Rerun rerun, Recipe.Rerun.Target target) {
        return rerun.targets()
                    .stream()
                    .filter(other -> sharesFork(other, target))
                    .map(Recipe.Rerun.Target::className)
                    .toList();
    }

    private static boolean sharesFork(Recipe.Rerun.Target other, Recipe.Rerun.Target target) {
        return isOther(other, target) && sameFork(other, target);
    }

    private static boolean isOther(Recipe.Rerun.Target other, Recipe.Rerun.Target target) {
        return ! other.className()
                      .equals(target.className());
    }

    private static boolean sameFork(Recipe.Rerun.Target other, Recipe.Rerun.Target target) {
        return other.runner() == target.runner() && other.module()
                                                         .equals(target.module());
    }

    /// `extra-rows=<n>`: the rows of classes that are not targets (a `@Nested` sibling under the outermost selection),
    /// ignored by every outcome and counted (ruling R-M2-16); none when there are none [G].
    private static Stream<String> extraRows(Recipe.Rerun rerun, VerdictInput input) {
        return Option.some(extraRowCount(rerun, input))
                     .filter(count -> count > 0)
                     .map(count -> EXTRA_ROWS + count)
                     .stream();
    }

    private static long extraRowCount(Recipe.Rerun rerun, VerdictInput input) {
        return rows(input).filter(row -> isExtra(rerun, row))
                   .count();
    }

    private static boolean isExtra(Recipe.Rerun rerun, TestcaseRow row) {
        return rerun.targets()
                    .stream()
                    .noneMatch(target -> isOf(row,
                                              target.className()));
    }

    /// The row's class is `className`, or a class nested under it.
    static boolean isOf(TestcaseRow row, String className) {
        return row.className()
                  .equals(className) || row.className()
                                           .startsWith(className + "$");
    }

    /// The rows of the rerun unit, the same unit every outcome reads.
    private static Stream<TestcaseRow> rows(VerdictInput input) {
        return rerunUnit(input.units()).stream()
                        .flatMap(unit -> unit.testcases()
                                             .stream());
    }

    /// Whether `annotation` is one of a rerun record's annotations (`rerun-of=…`, `rerun-bound=…`, `rerun(<class>)=…`,
    /// `extra-rows=…`).
    static boolean isRerunAnnotation(String annotation) {
        return RERUN_PREFIXES.stream().anyMatch(annotation::startsWith);
    }

    /// Whether `annotation` is a per-class outcome (`rerun(<class>)=…`).
    static boolean isClassOutcome(String annotation) {
        return annotation.startsWith(CLASS_PREFIX);
    }

    private static String annotation(Recipe.Rerun.Target target, String outcome) {
        return CLASS_PREFIX + target.className() + ")=" + outcome;
    }

    private static String outcome(Recipe.Rerun rerun, Recipe.Rerun.Target target, VerdictInput input) {
        return rerunUnit(input.units()).map(unit -> outcome(unit,
                                                            target.className(),
                                                            classNames(rerun),
                                                            input.cancelled()))
                        .or(notRun("no-unit"));
    }

    private static List<String> classNames(Recipe.Rerun rerun) {
        return rerun.targets()
                    .stream()
                    .map(Recipe.Rerun.Target::className)
                    .toList();
    }

    private static Option<UnitOutcome> rerunUnit(List<UnitOutcome> units) {
        return Option.from(units.stream().filter(unit -> unit.name()
                                                             .equals(UnitName.RERUN)).findFirst());
    }

    /// The rules in order; the first that matches decides.
    static String outcome(UnitOutcome unit, String className, boolean cancelled) {
        return outcome(unit, className, List.of(className), cancelled);
    }

    /// The outcome of `className` among the rerun's `targets`, whose rows it does not take.
    static String outcome(UnitOutcome unit, String className, List<String> targets, boolean cancelled) {
        return notRunReason(unit, cancelled).map(RerunOutcome::notRun)
                           .or(() -> judged(unit,
                                            rowsOf(unit, className, targets)));
    }

    private static Option<String> notRunReason(UnitOutcome unit, boolean cancelled) {
        return unit.voidReason()
                   .map(_ -> "void")
                   .orElse(() -> errored(unit))
                   .orElse(() -> when(cancelled, "cancelled"))
                   .orElse(() -> when(unit.timedOut(),
                                      "timeout"));
    }

    private static Option<String> errored(UnitOutcome unit) {
        return unit.errorReason()
                   .map(_ -> "error");
    }

    private static Option<String> when(boolean holds, String reason) {
        return holds
               ? some(reason)
               : none();
    }

    private static String judged(UnitOutcome unit, List<TestcaseRow> rows) {
        return rows.stream()
                   .anyMatch(row -> row.outcome()
                                       .isFailure())
               ? FAILED
               : unfailed(unit, rows);
    }

    private static String unfailed(UnitOutcome unit, List<TestcaseRow> rows) {
        return rows.isEmpty()
               ? noRows(unit)
               : PASSED;
    }

    private static String noRows(UnitOutcome unit) {
        return unit.exitedClean()
               ? notRun("empty")
               : notRun("rc");
    }

    private static List<TestcaseRow> rowsOf(UnitOutcome unit, String className, List<String> targets) {
        return unit.testcases()
                   .stream()
                   .filter(row -> countsFor(row, className, targets))
                   .toList();
    }

    private static boolean countsFor(TestcaseRow row, String className, List<String> targets) {
        return mostSpecificTarget(row, targets).filter(className::equals)
                                 .isPresent();
    }

    /// The target a row counts for: the most specific (longest) target it is of, so a row of `Outer$Inner` is that
    /// target's when it is one, and `Outer`'s otherwise (ruling R-M2-16, as amended); none for a row of no target.
    static Option<String> mostSpecificTarget(TestcaseRow row, List<String> targets) {
        return Option.from(targets.stream()
                                  .filter(target -> isOf(row, target))
                                  .max(Comparator.comparingInt(String::length)));
    }

    private static String notRun(String reason) {
        return "NOT-RUN(" + reason + ")";
    }

    record unused() implements RerunOutcome {}
}
