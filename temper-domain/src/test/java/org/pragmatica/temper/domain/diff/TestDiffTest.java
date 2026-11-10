package org.pragmatica.temper.domain.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.assertj.core.api.Assertions.assertThat;

/// m2-design §3.2 (§3.5 `TestDiffTest`; rulings R-M2-9 on Q-M2-8 and R-M2-13): every section, the fold of repeated
/// keys, and the transitions that belong to no section. Probes (§3.5) and the test each turns red: key by class only →
/// [#key_isClassAndName_notTheClassAlone]; newly-passing accepting skipped → [#failingToSkipped_isSkipChanged_notNewlyPassing].
class TestDiffTest {
    @Test
    void added_isInBOnly_carryingBsOutcome_andAFailingOneIsCounted() {
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.PASSED)),
                                    List.of(row("a.A", "one", Outcome.PASSED),
                                            row("a.A", "two", Outcome.PASSED),
                                            row("b.B", "newCase", Outcome.FAILED),
                                            row("c.C", "skipped", Outcome.SKIPPED)));

        assertThat(diff.added()).containsExactly(entry("a.A", "two", KeyOutcome.PASSED),
                                                 entry("b.B", "newCase", KeyOutcome.FAILED),
                                                 entry("c.C", "skipped", KeyOutcome.SKIPPED));
        assertThat(diff.addedFailing()).isEqualTo(1);
        assertThat(clean(diff)).as("an added failing test is not clean").isFalse();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.newlyFailing()).isEmpty();
    }

    @Test
    void removed_isInAOnly_carryingAsOutcome() {
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "gone", Outcome.ERROR)),
                                    List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(diff.removed()).containsExactly(entry("a.A", "gone", KeyOutcome.FAILED));
        assertThat(diff.added()).isEmpty();
        assertThat(clean(diff)).as("a removed test is not a failure").isTrue();
    }

    @Test
    void newlyFailing_andNewlyPassing_areMovesBetweenPassedAndFailed() {
        var diff = TestDiff.compare(List.of(row("a.A", "flips", Outcome.PASSED),
                                            row("a.A", "fixed", Outcome.FAILED),
                                            row("a.A", "stays", Outcome.FAILED),
                                            row("a.A", "green", Outcome.PASSED)),
                                    List.of(row("a.A", "flips", Outcome.ERROR),
                                            row("a.A", "fixed", Outcome.PASSED),
                                            row("a.A", "stays", Outcome.FAILED),
                                            row("a.A", "green", Outcome.PASSED)));

        assertThat(diff.newlyFailing()).containsExactly(entry("a.A", "flips", KeyOutcome.FAILED));
        assertThat(diff.newlyPassing()).containsExactly(entry("a.A", "fixed", KeyOutcome.PASSED));
        assertThat(diff.skipChanged()).isEmpty();
        assertThat(clean(diff)).isFalse();
    }

    /// Q-M2-8: a failing test that is skipped next time is not a fix.
    @Test
    void failingToSkipped_isSkipChanged_notNewlyPassing() {
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.FAILED)), List.of(row("a.A", "one", Outcome.SKIPPED)));

        assertThat(diff.newlyPassing()).isEmpty();
        assertThat(diff.skipChanged()).containsExactly(SkipChange.skipChange("a.A", "one", KeyOutcome.FAILED, KeyOutcome.SKIPPED));
    }

    /// R-M2-13: passed → skipped, skipped → passed and failed → skipped are skip changes, in no section; skipped → failed
    /// is newly failing.
    @Test
    void skipChanges_areThreeMoves_andSkippedToFailedIsNewlyFailing() {
        var diff = TestDiff.compare(List.of(row("a.A", "unskippedRed", Outcome.SKIPPED),
                                            row("a.A", "unskippedGreen", Outcome.SKIPPED),
                                            row("a.A", "nowSkipped", Outcome.PASSED)),
                                    List.of(row("a.A", "unskippedRed", Outcome.FAILED),
                                            row("a.A", "unskippedGreen", Outcome.PASSED),
                                            row("a.A", "nowSkipped", Outcome.SKIPPED)));

        assertThat(diff.skipChanged()).containsExactly(SkipChange.skipChange("a.A", "nowSkipped", KeyOutcome.PASSED, KeyOutcome.SKIPPED),
                                                       SkipChange.skipChange("a.A", "unskippedGreen", KeyOutcome.SKIPPED, KeyOutcome.PASSED));
        assertThat(diff.newlyFailing()).containsExactly(entry("a.A", "unskippedRed", KeyOutcome.FAILED));
        assertThat(diff.newlyPassing()).isEmpty();
        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
    }

    /// The fold: failing if any row fails, skipped only if every row is, else passed; each repeated key counted once.
    @Test
    void repeatedKeys_areFolded_andCountedOnceAsDuplicates() {
        var diff = TestDiff.compare(List.of(row("a.A", "dyn", Outcome.PASSED),
                                            row("a.A", "dyn", Outcome.PASSED),
                                            row("a.A", "mixed", Outcome.SKIPPED),
                                            row("a.A", "mixed", Outcome.PASSED),
                                            row("a.A", "both", Outcome.PASSED),
                                            row("a.A", "both", Outcome.PASSED)),
                                    List.of(row("a.A", "dyn", Outcome.PASSED),
                                            row("a.A", "dyn", Outcome.FAILED),
                                            row("a.A", "mixed", Outcome.PASSED),
                                            row("a.A", "both", Outcome.PASSED),
                                            row("a.A", "both", Outcome.PASSED)));

        assertThat(diff.newlyFailing()).as("one failing row of two folds to failing")
                                       .containsExactly(entry("a.A", "dyn", KeyOutcome.FAILED));
        assertThat(diff.skipChanged()).as("skipped + passed folds to passed").isEmpty();
        assertThat(diff.duplicates()).as("dyn, mixed and both, once each").isEqualTo(3);
    }

    @Test
    void allSkippedRows_foldToSkipped() {
        assertThat(KeyOutcome.fold(List.of(Outcome.SKIPPED, Outcome.SKIPPED))).isEqualTo(KeyOutcome.SKIPPED);
        assertThat(KeyOutcome.fold(List.of(Outcome.SKIPPED, Outcome.ERROR))).isEqualTo(KeyOutcome.FAILED);
        assertThat(KeyOutcome.fold(List.of(Outcome.SKIPPED, Outcome.PASSED))).isEqualTo(KeyOutcome.PASSED);
    }

    /// Two names of one class are two tests: a newly failing one is not hidden by a passing sibling.
    @Test
    void key_isClassAndName_notTheClassAlone() {
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "two", Outcome.PASSED)),
                                    List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "three", Outcome.FAILED)));

        assertThat(diff.added()).containsExactly(entry("a.A", "three", KeyOutcome.FAILED));
        assertThat(diff.removed()).containsExactly(entry("a.A", "two", KeyOutcome.PASSED));
    }

    /// Two classes with one name are two tests too.
    @Test
    void key_isClassAndName_notTheNameAlone() {
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.PASSED)), List.of(row("b.B", "one", Outcome.PASSED)));

        assertThat(diff.added()).containsExactly(entry("b.B", "one", KeyOutcome.PASSED));
        assertThat(diff.removed()).containsExactly(entry("a.A", "one", KeyOutcome.PASSED));
    }

    @Test
    void sections_areInClassThenNameOrder_whateverTheRowOrder() {
        var rows = new ArrayList<>(List.of(row("b.B", "a", Outcome.FAILED),
                                           row("a.A", "z", Outcome.FAILED),
                                           row("a.A", "b", Outcome.FAILED)));

        Collections.shuffle(rows);
        var diff = TestDiff.compare(List.of(), rows);

        assertThat(diff.added()).containsExactly(entry("a.A", "b", KeyOutcome.FAILED),
                                                 entry("a.A", "z", KeyOutcome.FAILED),
                                                 entry("b.B", "a", KeyOutcome.FAILED));
    }

    @Test
    void identicalSides_areClean_withEmptySections() {
        var rows = List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "two", Outcome.FAILED), row("a.A", "three", Outcome.SKIPPED));
        var diff = TestDiff.compare(rows, rows);

        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.newlyFailing()).isEmpty();
        assertThat(diff.newlyPassing()).isEmpty();
        assertThat(diff.skipChanged()).isEmpty();
        assertThat(diff.duplicates()).isZero();
        assertThat(clean(diff)).as("a failure on both sides is not new").isTrue();
    }

    /// Rows of one key from two units fold together: the key is (class, name), not the unit.
    @Test
    void rowsOfOneKeyFromTwoUnits_foldTogether() {
        var shard = UnitName.unitName("shard-2").unwrap();
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.PASSED)),
                                    List.of(row("a.A", "one", Outcome.PASSED),
                                            TestcaseRow.testcaseRow(shard, "a.A", "one", Outcome.FAILED, 0.1, false)));

        assertThat(diff.newlyFailing()).containsExactly(entry("a.A", "one", KeyOutcome.FAILED));
        assertThat(diff.added()).isEmpty();
        assertThat(diff.duplicates()).isEqualTo(1);
    }

    /// R-M2-13: a B that ran no tests is never clean, though every set but `removed` is empty.
    @Test
    void emptyB_isNeverClean() {
        var diff = TestDiff.compare(List.of(row("a.A", "one", Outcome.PASSED)), List.of());

        assertThat(diff.removed()).containsExactly(entry("a.A", "one", KeyOutcome.PASSED));
        assertThat(diff.testcasesB()).isZero();
        assertThat(clean(diff)).isFalse();
        assertThat(clean(TestDiff.compare(List.of(), List.of(row("a.A", "one", Outcome.PASSED))))).as("an empty A is not").isTrue();
    }

    /// Clean as `diff` exits: 0 ([ExitCode#forDiff(DiffOutcome)]).
    private static boolean clean(TestDiff diff) {
        return ExitCode.forDiff(DiffOutcome.Compared.compared(diff)) == ExitCode.PASSED;
    }

    static TestcaseRow row(String className, String name, Outcome outcome) {
        return TestcaseRow.testcaseRow(UnitName.MAIN, className, name, outcome, 0.25, false);
    }

    static DiffEntry entry(String className, String name, KeyOutcome outcome) {
        return DiffEntry.diffEntry(className, name, outcome);
    }
}
