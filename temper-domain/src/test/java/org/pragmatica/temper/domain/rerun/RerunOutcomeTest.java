package org.pragmatica.temper.domain.rerun;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.domain.verdict.UnitOutcome;
import org.pragmatica.temper.domain.verdict.VerdictInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m2-design §5.3 and §5.6: PASSED / FAILED / NOT-RUN per class from the unit and its rows of that class; the record
/// gains `rerun-of` and `rerun(<class>)=…` and nothing else (no FLAKY, rulings R-M2-9 and R-M2-10).
class RerunOutcomeTest {
    private static final UnitName RERUN = UnitName.RERUN;

    private static Recipe.Rerun rerun(String... classes) {
        return Recipe.rerun(ok(JobId.jobId(4711)),
                            Stream.of(classes).map(name -> ok(Recipe.Rerun.Target.target(name, "core", Runner.SUREFIRE))).toList(),
                            Recipe.Rerun.Original.original(ok(JobId.jobId(4711)), "FAILED", some("tests"), List.of()));
    }

    private static TestcaseRow row(UnitName unit, String className, Outcome outcome) {
        return TestcaseRow.testcaseRow(unit, className, "case", outcome, 0.1, false);
    }

    private static UnitOutcome unit(UnitName name, Option<Integer> rc, Option<KillCause> killed, Option<String> voidReason,
                                    Option<String> errorReason, List<TestcaseRow> rows) {
        return UnitOutcome.unitOutcome(name, true, false, rc, killed, voidReason, errorReason, rows, rows.size());
    }

    private static UnitOutcome exited(UnitName name, int rc, TestcaseRow... rows) {
        return unit(name, some(rc), none(), none(), none(), List.of(rows));
    }

    private static List<String> annotations(Recipe recipe, boolean cancelled, UnitOutcome... units) {
        return RerunOutcome.annotations(recipe, VerdictInput.verdictInput(List.of(units), cancelled, false, none()));
    }

    /// maintainer ruling on the rerun bound: the bound decided at submit is printed right after `rerun-of`, and the CLI keeps it.
    @Test
    void annotations_withABound_printItAfterRerunOf() {
        var bounded = rerun("org.x.A").withBound(Recipe.Rerun.Bound.bound(Duration.ofMinutes(48),
                                                                           "Σ 25.1m ×1.5 + install 5m + slack 5m"));

        var annotations = annotations(bounded, false, exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.PASSED)));

        assertThat(annotations).containsExactly("rerun-of=4711",
                                                "rerun-bound=48m (Σ 25.1m ×1.5 + install 5m + slack 5m)",
                                                "rerun(org.x.A)=PASSED");
        assertThat(RerunOutcome.isRerunAnnotation(annotations.get(1))).isTrue();
    }

    @Test
    void annotations_passedAndFailed_perClassInTargetOrder() {
        var annotations = annotations(rerun("org.x.B", "org.x.A"),
                                      false,
                                      exited(RERUN, 1, row(RERUN, "org.x.A", Outcome.FAILED), row(RERUN, "org.x.B", Outcome.PASSED)));

        assertThat(annotations).containsExactly("rerun-of=4711",
                                                "rerun(org.x.A)=FAILED co-run in one fork with: org.x.B",
                                                "rerun(org.x.B)=PASSED co-run in one fork with: org.x.A");
    }

    /// Exactly the rerun annotations: nothing derived from the original and the rerun together (no FLAKY).
    @Test
    void annotations_onlyRerunOfAndPerClass_neverFlaky() {
        var annotations = annotations(rerun("org.x.A"), false, exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.PASSED)));

        assertThat(annotations).hasSize(2).allMatch(RerunOutcome::isRerunAnnotation);
        assertThat(String.join(" ", annotations)).doesNotContainIgnoringCase("flaky");
    }

    @Test
    void annotations_notARerun_none() {
        assertThat(annotations(Recipe.gate(), false, exited(UnitName.GATE, 1))).isEmpty();
    }

    @Test
    void outcome_errorRowIsFailed() {
        assertThat(RerunOutcome.outcome(exited(RERUN, 1, row(RERUN, "org.x.A", Outcome.ERROR)), "org.x.A", false))
            .isEqualTo("FAILED");
    }

    /// A VOID unit never reads as FAILED, even with a failing row in it.
    @Test
    void outcome_voidUnit_notRunVoid() {
        var voided = unit(RERUN, none(), none(), some("disk"), none(), List.of(row(RERUN, "org.x.A", Outcome.FAILED)));

        assertThat(RerunOutcome.outcome(voided, "org.x.A", false)).isEqualTo("NOT-RUN(void)");
    }

    @Test
    void outcome_errorUnit_notRunError() {
        var errored = unit(RERUN, some(0), none(), none(), some("report"), List.of(row(RERUN, "org.x.A", Outcome.PASSED)));

        assertThat(RerunOutcome.outcome(errored, "org.x.A", false)).isEqualTo("NOT-RUN(error)");
    }

    /// Zero rows of the class is never PASSED, even with rc 0.
    @Test
    void outcome_zeroRowsRcZero_notRunEmpty() {
        assertThat(RerunOutcome.outcome(exited(RERUN, 0), "org.x.A", false)).isEqualTo("NOT-RUN(empty)");
        assertThat(RerunOutcome.outcome(exited(RERUN, 0, row(RERUN, "org.x.Other", Outcome.PASSED)), "org.x.A", false))
            .as("rows of another class do not count")
            .isEqualTo("NOT-RUN(empty)");
    }

    @Test
    void outcome_timeout_notRunTimeout() {
        var timedOut = unit(RERUN, some(137), some(KillCause.TIMEOUT), none(), none(), List.of(row(RERUN, "org.x.A", Outcome.PASSED)));

        assertThat(RerunOutcome.outcome(timedOut, "org.x.A", false)).isEqualTo("NOT-RUN(timeout)");
    }

    @Test
    void outcome_cancelled_notRunCancelled_evenAfterAPassingRow() {
        assertThat(RerunOutcome.outcome(exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.PASSED)), "org.x.A", true))
            .isEqualTo("NOT-RUN(cancelled)");
        assertThat(RerunOutcome.outcome(unit(RERUN, none(), none(), none(), none(), List.of()), "org.x.A", true))
            .isEqualTo("NOT-RUN(cancelled)");
    }

    /// The one unit's rc is every class's: another class's failure makes it 1, so a class's own passing rows decide.
    @Test
    void outcome_passingRowsUnderANonZeroRc_isPassed() {
        assertThat(RerunOutcome.outcome(exited(RERUN, 1, row(RERUN, "org.x.A", Outcome.PASSED), row(RERUN, "org.x.B", Outcome.FAILED)),
                                        "org.x.A",
                                        false))
            .isEqualTo("PASSED");
    }

    /// No row of the class and rc not 0 (or unknown): the build broke before the class ran.
    @Test
    void outcome_noRowsUnderANonZeroRc_notRunRc() {
        assertThat(RerunOutcome.outcome(exited(RERUN, 1), "org.x.A", false)).isEqualTo("NOT-RUN(rc)");
        assertThat(RerunOutcome.outcome(unit(RERUN, none(), none(), none(), none(), List.of()), "org.x.A", false))
            .as("rc unknown")
            .isEqualTo("NOT-RUN(rc)");
    }

    /// The planner always makes the one unit; a job without it reports `NOT-RUN(no-unit)`, never a verdict.
    @Test
    void annotations_withoutTheRerunUnit_notRunNoUnit() {
        assertThat(annotations(rerun("org.x.A"), false, exited(UnitName.MAIN, 0, row(UnitName.MAIN, "org.x.A", Outcome.PASSED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.A)=NOT-RUN(no-unit)");
    }

    @Test
    void outcome_skippedOnlyRows_isPassedWhenRcZero() {
        assertThat(RerunOutcome.outcome(exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.SKIPPED)), "org.x.A", false))
            .isEqualTo("PASSED");
    }

    /// A `@Nested` target is judged on its own rows, under its outermost class's selection.
    @Test
    void annotations_nestedTargets_judgedOnOwnRows() {
        var recipe = rerun("org.x.Outer$A", "org.x.Outer$B");

        assertThat(annotations(recipe,
                               false,
                               exited(RERUN,
                                      1,
                                      row(RERUN, "org.x.Outer$A", Outcome.FAILED),
                                      row(RERUN, "org.x.Outer$B", Outcome.PASSED))))
            .containsExactly("rerun-of=4711",
                             "rerun(org.x.Outer$A)=FAILED co-run in one fork with: org.x.Outer$B",
                             "rerun(org.x.Outer$B)=PASSED co-run in one fork with: org.x.Outer$A");
    }

    /// The class's rows are its own and those of classes nested under it; a longer name sharing the prefix is another
    /// class, and its rows are counted as extra (R-M2-16).
    @Test
    void annotations_nestedRowsCount_prefixRowsAreExtra() {
        assertThat(annotations(rerun("org.x.Outer"),
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.Outer$Inner", Outcome.PASSED), row(RERUN, "org.x.OuterX", Outcome.FAILED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.Outer)=PASSED", "extra-rows=1");
    }

    /// Fully qualified names: a same-named class of another package is not the target, and its red row is extra.
    @Test
    void annotations_sameSimpleNameInAnotherPackage_isNotTheTarget() {
        assertThat(annotations(rerun("org.x.FooTest"),
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.FooTest", Outcome.PASSED), row(RERUN, "org.y.FooTest", Outcome.FAILED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.FooTest)=PASSED", "extra-rows=1");
    }

    /// Three classes in one fork: each lists the other two, comma-separated, siblings with no rows included.
    @Test
    void annotations_threeClassesInOneFork_listTheOtherTwo() {
        assertThat(annotations(rerun("org.x.A", "org.x.B", "org.x.C"),
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.PASSED), row(RERUN, "org.x.B", Outcome.FAILED))))
            .containsExactly("rerun-of=4711",
                             "rerun(org.x.A)=PASSED co-run in one fork with: org.x.B,org.x.C",
                             "rerun(org.x.B)=FAILED co-run in one fork with: org.x.A,org.x.C",
                             "rerun(org.x.C)=NOT-RUN(empty)");
    }

    /// Nesting at any depth: a class's only row three levels down is its row.
    @Test
    void annotations_rowNestedThreeLevelsDown_countsForTheTarget() {
        assertThat(annotations(rerun("org.x.O"), false, exited(RERUN, 0, row(RERUN, "org.x.O$A$B$C", Outcome.PASSED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.O)=PASSED");
    }

    /// A failing nested row fails its target, even beside a passing own row.
    @Test
    void annotations_failingNestedRow_failsTheTarget() {
        assertThat(annotations(rerun("org.x.Outer$A"),
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.Outer$A", Outcome.PASSED), row(RERUN, "org.x.Outer$A$Deep", Outcome.FAILED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.Outer$A)=FAILED");
    }

    /// A nested target's extra rows are judged against the target itself, not its outermost class: the outer class, a
    /// sibling and a longer name sharing the prefix are all extra.
    @Test
    void annotations_nestedTarget_outerAndSiblingRowsAreExtra() {
        assertThat(annotations(rerun("org.x.Outer$A"),
                               false,
                               exited(RERUN,
                                      0,
                                      row(RERUN, "org.x.Outer$A", Outcome.PASSED),
                                      row(RERUN, "org.x.Outer", Outcome.PASSED),
                                      row(RERUN, "org.x.Outer$B", Outcome.PASSED),
                                      row(RERUN, "org.x.Outer$AB", Outcome.FAILED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.Outer$A)=PASSED", "extra-rows=3");
    }

    /// R-M2-16 as amended: a row counts only for its most specific target. With targets `Outer` and `Outer$Inner`, a
    /// red `Outer$Inner` fails only itself; `Outer` is judged on its own passing row.
    @Test
    void annotations_outerAndNestedTargets_eachRowCountsForTheMostSpecific() {
        assertThat(annotations(rerun("org.x.Outer", "org.x.Outer$Inner"),
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.Outer", Outcome.PASSED), row(RERUN, "org.x.Outer$Inner", Outcome.FAILED))))
            .containsExactly("rerun-of=4711",
                             "rerun(org.x.Outer)=PASSED co-run in one fork with: org.x.Outer$Inner",
                             "rerun(org.x.Outer$Inner)=FAILED co-run in one fork with: org.x.Outer");
    }

    /// Deeper: with targets `Outer` and `Outer$A$B`, a failing `Outer$A$B$C` row counts for `Outer$A$B` only, and is not
    /// extra; `Outer` keeps its own passing row.
    @Test
    void annotations_deepNestedRow_countsForTheDeepestEnclosingTarget_only() {
        assertThat(annotations(rerun("org.x.Outer", "org.x.Outer$A$B"),
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.Outer", Outcome.PASSED), row(RERUN, "org.x.Outer$A$B$C", Outcome.FAILED))))
            .containsExactly("rerun-of=4711",
                             "rerun(org.x.Outer)=PASSED co-run in one fork with: org.x.Outer$A$B",
                             "rerun(org.x.Outer$A$B)=FAILED co-run in one fork with: org.x.Outer");
    }

    /// A target with zero rows is never PASSED (R-M2-16).
    @Test
    void annotations_zeroRowTarget_notRunEmpty() {
        assertThat(annotations(rerun("org.x.A"), false, exited(RERUN, 0)))
            .containsExactly("rerun-of=4711", "rerun(org.x.A)=NOT-RUN(empty)");
    }

    /// Classes of other modules or plugins do not share a fork; a class that never ran names no fork siblings.
    @Test
    void annotations_coRun_onlyForClassesThatRanInOneFork() {
        var recipe = Recipe.rerun(ok(JobId.jobId(4711)),
                                  List.of(ok(Recipe.Rerun.Target.target("org.x.A", "core", Runner.SUREFIRE)),
                                          ok(Recipe.Rerun.Target.target("org.x.B", "other", Runner.SUREFIRE)),
                                          ok(Recipe.Rerun.Target.target("org.x.CIT", "core", Runner.FAILSAFE))),
                                  Recipe.Rerun.Original.original(ok(JobId.jobId(4711)), "FAILED", some("tests"), List.of()));

        assertThat(annotations(recipe,
                               false,
                               exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.PASSED), row(RERUN, "org.x.B", Outcome.PASSED))))
            .containsExactly("rerun-of=4711", "rerun(org.x.A)=PASSED", "rerun(org.x.B)=PASSED", "rerun(org.x.CIT)=NOT-RUN(empty)");
        assertThat(annotations(rerun("org.x.A", "org.x.B"), true, exited(RERUN, 0, row(RERUN, "org.x.A", Outcome.PASSED))))
            .as("cancelled: never ran")
            .containsExactly("rerun-of=4711", "rerun(org.x.A)=NOT-RUN(cancelled)", "rerun(org.x.B)=NOT-RUN(cancelled)");
    }

    @Test
    void isRerunAnnotation_coversEveryRerunAnnotation_only() {
        assertThat(List.of("rerun-of=1", "rerun(a.B)=PASSED", "extra-rows=3")).allMatch(RerunOutcome::isRerunAnnotation);
        assertThat(List.of("KNOWN(#1)", "evidence=missing", "superseded")).noneMatch(RerunOutcome::isRerunAnnotation);
    }
}
