package org.pragmatica.temper.domain.verdict;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// A2 / A8 / A10 and the Q-8 corrected order: VOID > ERROR > CANCELLED > FAILED(timeout) > EMPTY(rc 0) >
/// FAILED > PASSED, with rows built in code. Fixture-report cases are in temper-protocol's `ReportFixturesTest`.
class VerdictsTest {
    private static final UnitName A = ok(UnitName.unitName("a"));
    private static final UnitName B = ok(UnitName.unitName("b"));
    private static final UnitName MAIN = UnitName.MAIN;

    private static List<TestcaseRow> rows(UnitName unit, String className, Outcome... outcomes) {
        return IntStream.range(0, outcomes.length)
                        .mapToObj(index -> TestcaseRow.testcaseRow(unit, className, "t" + index, outcomes[index], 0.1, false))
                        .toList();
    }

    private static List<TestcaseRow> passing(UnitName unit) {
        return rows(unit, "org.example.CalcTest", Outcome.PASSED, Outcome.PASSED);
    }

    /// 9 rows, 5 of them FAILED or ERROR.
    private static List<TestcaseRow> mixed(UnitName unit) {
        return rows(unit,
                    "org.example.MixedTest",
                    Outcome.PASSED,
                    Outcome.FAILED,
                    Outcome.ERROR,
                    Outcome.SKIPPED,
                    Outcome.PASSED,
                    Outcome.FAILED,
                    Outcome.ERROR,
                    Outcome.ERROR,
                    Outcome.PASSED);
    }

    private static UnitOutcome finished(UnitName name, int rc, List<TestcaseRow> rows) {
        return UnitOutcome.unitOutcome(name, true, false, Option.some(rc), Option.none(), Option.none(), Option.none(), rows, rows.size());
    }

    private static UnitOutcome timedOut(UnitName name) {
        return UnitOutcome.unitOutcome(name,
                                       true,
                                       false,
                                       Option.some(137),
                                       Option.some(KillCause.TIMEOUT),
                                       Option.none(),
                                       Option.none(),
                                       List.of(),
                                       0);
    }

    private static UnitOutcome voided(UnitName name, String reason, List<TestcaseRow> rows) {
        return UnitOutcome.unitOutcome(name, true, false, Option.some(1), Option.none(), Option.some(reason), Option.none(), rows, 0);
    }

    private static UnitOutcome errored(UnitName name, String reason) {
        return UnitOutcome.unitOutcome(name, true, false, Option.none(), Option.none(), Option.none(), Option.some(reason), List.of(), 0);
    }

    private static Evaluation evaluate(UnitOutcome... units) {
        return Verdicts.evaluate(VerdictInput.verdictInput(List.of(units), false, false, Option.none()));
    }

    private static Evaluation evaluate(boolean cancelled, UnitOutcome... units) {
        return Verdicts.evaluate(VerdictInput.verdictInput(List.of(units), cancelled, false, Option.none()));
    }

    /// A VOID / ERROR reason as reported may carry free text after its token: the verdict shows the token (closed
    /// vocabulary), the stored unit reason keeps the text, and the result record carries the explanation.
    @Nested
    class ReasonDetail {
        @Test
        void token_isTheTextBeforeTheColon_explanationTheTextAfterIt() {
            assertThat(Verdict.token("manifest: effective-pom rc=1")).isEqualTo("manifest");
            assertThat(Verdict.explanation("manifest: effective-pom rc=1")).isEqualTo(Option.some("effective-pom rc=1"));
            assertThat(Verdict.explanation("manifest: JUnit 3/4: module legacy, class fx.G"))
                .isEqualTo(Option.some("JUnit 3/4: module legacy, class fx.G"));
        }

        @Test
        void token_isTheWholeReason_andNoExplanation_withoutAColon() {
            assertThat(Verdict.token("lost")).isEqualTo("lost");
            assertThat(Verdict.explanation("lost")).isEqualTo(Option.none());
            assertThat(Verdict.explanation("infra:  ")).isEqualTo(Option.none());
        }

        @Test
        void token_isInfra_whenBlank() {
            assertThat(Verdict.token(" : docker died")).isEqualTo(Verdict.INFRA);
        }

        @Test
        void evaluate_errorDetailIsTheToken_fullReasonKeepsTheText() {
            var verdict = evaluate(errored(MAIN, "manifest: effective-pom rc=1")).verdict();

            assertThat(verdict.detail()).isEqualTo(Option.some("manifest"));
            assertThat(verdict.fullReason()).isEqualTo(Option.some("manifest: effective-pom rc=1"));
        }

        @Test
        void evaluate_voidDetailIsTheToken_fullReasonKeepsTheText() {
            var verdict = evaluate(voided(MAIN, "disk: 3 GiB free", List.of())).verdict();

            assertThat(verdict.detail()).isEqualTo(Option.some("disk"));
            assertThat(verdict.fullReason()).isEqualTo(Option.some("disk: 3 GiB free"));
        }

        @Test
        void fullReason_isTheDetail_forOtherVerdicts() {
            assertThat(new Verdict.Failed("rc").fullReason()).isEqualTo(Option.some("rc"));
            assertThat(new Verdict.Passed().fullReason()).isEqualTo(Option.none());
        }
    }

    @Nested
    class Q8Order {
        @Test
        void evaluate_isFailedRc_notEmpty_forCompileFailureInVerify() {
            var evaluation = evaluate(finished(MAIN, 1, List.of()));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("rc"));
        }

        @Test
        void evaluate_isFailedTimeout_notEmpty_forTimedOutUnitWithNoRows() {
            var evaluation = evaluate(timedOut(MAIN));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("timeout"));
        }

        @Test
        void evaluate_timeoutBeatsEmpty_acrossUnits() {
            var evaluation = evaluate(finished(A, 0, List.of()), timedOut(B));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("timeout"));
        }

        @Test
        void evaluate_emptyBeatsFailed_acrossUnits() {
            var evaluation = evaluate(finished(A, 0, List.of()), finished(B, 1, mixed(B)));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Empty(List.of(A)));
        }

        @Test
        void evaluate_cancelledBeatsTimeout() {
            assertThat(evaluate(true, timedOut(MAIN)).verdict()).isEqualTo(new Verdict.Cancelled());
        }

        @Test
        void evaluate_errorBeatsCancelled() {
            assertThat(evaluate(true, errored(MAIN, "lost")).verdict()).isEqualTo(new Verdict.Error("lost"));
        }

        @Test
        void evaluate_voidBeatsError_andKeepsObservedFailures() {
            var observed = mixed(A);
            var evaluation = evaluate(voided(A, "disk", observed), errored(B, "report"));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Void("disk"));
            assertThat(evaluation.observedFailures()).isEqualTo(5);
            assertThat(evaluation.failed()).extracting(TestcaseRow::outcome)
                                           .allMatch(Outcome::isFailure);
        }

        @Test
        void evaluate_voidTieGoesToFirstUnitByName() {
            var evaluation = evaluate(voided(B, "oom", List.of()), voided(A, "disk", List.of()));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Void("disk"));
        }

        @Test
        void evaluate_isFailedRc_whenTestsPassedButRcNonZero() {
            assertThat(evaluate(finished(MAIN, 1, passing(MAIN))).verdict()).isEqualTo(new Verdict.Failed("rc"));
        }

        @Test
        void evaluate_isPassed_whenRcZeroAndAllTestsPass() {
            assertThat(evaluate(finished(MAIN, 0, passing(MAIN))).verdict()).isEqualTo(new Verdict.Passed());
        }

        @Test
        void evaluate_isNotEmpty_whenUnitDidNotRequestTests() {
            var build = UnitOutcome.unitOutcome(MAIN, false, false, Option.some(0), Option.none(), Option.none(), Option.none(), List.of(), 0);

            assertThat(evaluate(build).verdict()).isEqualTo(new Verdict.Passed());
        }
    }

    @Test
    void evaluate_isErrorPlan_whenOneClassIsReportedByTwoUnits() {
        assertThat(evaluate(finished(A, 0, passing(A)), finished(B, 0, passing(B))).verdict()).isEqualTo(new Verdict.Error("plan"));
    }

    @Test
    void evaluate_isFailedMissingClasses_andCountsManifest() {
        var manifest = Manifest.manifest(Set.of("org.example.CalcTest", "org.example.CrashedForkTest"), Set.of());
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 0, passing(MAIN))),
                                                                     false,
                                                                     false,
                                                                     Option.some(manifest)));

                assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("missing-classes"));
        assertThat(evaluation.counts()
                             .manifest()).isEqualTo(Option.some(2));
        assertThat(evaluation.counts()
                             .missing()).isEqualTo(Option.some(1));
    }

    /// Spec §5.2 "recorded as manifest-drift": a reported class the manifest does not expect is listed, and fails
    /// no job.
    @Test
    void evaluate_isPassed_withDrift_andListsTheDrift() {
        var manifest = Manifest.manifest(Set.of("org.example.CalcTest"), Set.of());
        var reported = Stream.concat(passing(MAIN).stream(), rows(MAIN, "org.example.ExtraTest", Outcome.PASSED).stream()).toList();
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 0, reported)),
                                                                     false,
                                                                     false,
                                                                     Option.some(manifest)));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
        assertThat(evaluation.drift()).containsExactly("org.example.ExtraTest");
    }

    @Test
    void evaluate_listsMissingAndDrift_sortedByClassName() {
        var expected = IntStream.range(0, 40).mapToObj(index -> "org.example.Gone%02d".formatted(39 - index));
        var manifest = Manifest.manifest(Stream.concat(expected, Stream.of("org.example.CalcTest")).collect(Collectors.toSet()), Set.of());
        var extra = IntStream.range(0, 40)
                                              .mapToObj(index -> rows(MAIN, "org.example.Extra%02d".formatted(39 - index), Outcome.PASSED))
                                              .flatMap(List::stream);
        var rows = Stream.concat(passing(MAIN).stream(), extra).toList();
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 0, rows)), false, false, Option.some(manifest)));

        assertThat(List.copyOf(evaluation.missing())).isSorted()
                                                     .hasSize(40);
        assertThat(List.copyOf(evaluation.drift())).isSorted()
                                                   .hasSize(40);
    }

    /// T2 / A2 extension (m1-design §9.4): a crashed fork exits rc 1 and its class never reports; the manifest
    /// still expects it, so the verdict names it as missing rather than settling for `FAILED(rc)`.
    @Test
    void evaluate_crashedFork_isFailedMissingClasses_namingTheClass() {
        var manifest = Manifest.manifest(Set.of("org.example.CalcTest", "org.example.CrashedForkProbeTest"), Set.of());
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 1, passing(MAIN))),
                                                                     false,
                                                                     false,
                                                                     Option.some(manifest)));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("missing-classes"));
        assertThat(evaluation.missing()).containsExactly("org.example.CrashedForkProbeTest");
        assertThat(evaluation.counts()
                             .missing()).isEqualTo(Option.some(1));
        assertThat(evaluation.drift()).isEmpty();
    }

    @Test
    void evaluate_annotatesManifestDrift_withoutChangingVerdict() {
        var manifest = Manifest.manifest(Set.of("org.example.MixedTest"), Set.of());
        var rows = Stream.concat(passing(MAIN).stream(),
                                                  rows(MAIN, "org.example.MixedTest", Outcome.PASSED).stream())
                                          .toList();
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 0, rows)), false, true, Option.some(manifest)));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
        assertThat(evaluation.drift()).containsExactly("org.example.CalcTest");
        assertThat(evaluation.annotations()).containsExactly("superseded", "manifest-drift=1");
        assertThat(evaluation.counts()
                             .drift()).isEqualTo(Option.some(1));
    }

    /// Ruling R-2: missing = required − reported; drift = reported − (required ∪ optional).
    @Nested
    class OptionalClasses {
        private static final String REQUIRED = "org.example.CalcTest";
        private static final String OPTIONAL = "org.example.RunWithTest";

        private static Evaluation evaluated(Manifest manifest, List<TestcaseRow> rows) {
            return Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 0, rows)), false, false, Option.some(manifest)));
        }

        private static List<TestcaseRow> with(String className) {
            return Stream.concat(passing(MAIN).stream(), rows(MAIN, className, Outcome.PASSED).stream()).toList();
        }

        @Test
        void evaluate_reportingOptionalClass_isNotDrift() {
            var evaluation = evaluated(Manifest.manifest(Set.of(REQUIRED), Set.of(OPTIONAL)), with(OPTIONAL));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
            assertThat(evaluation.drift()).isEmpty();
            assertThat(evaluation.annotations()).isEmpty();
            assertThat(evaluation.counts()
                                 .drift()).isEqualTo(Option.some(0));
        }

        @Test
        void evaluate_nonReportingOptionalClass_isNotMissing() {
            var evaluation = evaluated(Manifest.manifest(Set.of(REQUIRED), Set.of(OPTIONAL)), passing(MAIN));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
            assertThat(evaluation.missing()).isEmpty();
            assertThat(evaluation.counts()
                                 .missing()).isEqualTo(Option.some(0));
            assertThat(evaluation.counts()
                                 .manifest()).isEqualTo(Option.some(1));
        }

        @Test
        void evaluate_requiredClassNotReported_isMissing() {
            var evaluation = evaluated(Manifest.manifest(Set.of(REQUIRED, "org.example.GoneTest"), Set.of(OPTIONAL)), passing(MAIN));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("missing-classes"));
            assertThat(evaluation.missing()).containsExactly("org.example.GoneTest");
        }

        @Test
        void evaluate_unknownReportedClass_isDrift() {
            var evaluation = evaluated(Manifest.manifest(Set.of(REQUIRED), Set.of(OPTIONAL)), with("org.example.StrayTest"));

            assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
            assertThat(evaluation.drift()).containsExactly("org.example.StrayTest");
            assertThat(evaluation.annotations()).containsExactly("manifest-drift=1");
        }
    }

    @Test
    void evaluate_withoutManifest_hasNoMissingDriftOrDriftCount() {
        var evaluation = evaluate(finished(MAIN, 0, passing(MAIN)));

        assertThat(evaluation.missing()).isEmpty();
        assertThat(evaluation.drift()).isEmpty();
        assertThat(evaluation.annotations()).isEmpty();
        assertThat(evaluation.counts()
                             .drift()).isEqualTo(Option.none());
    }

    @Test
    void evaluate_countsRowsNotTestsAttr() {
        var unit = UnitOutcome.unitOutcome(MAIN, true, false, Option.some(0), Option.none(), Option.none(), Option.none(),
                                           passing(MAIN), 1);
        var evaluation = evaluate(unit);

        assertThat(evaluation.counts()
                             .testcases()).isEqualTo(2);
        assertThat(evaluation.counts()
                             .testsAttr()).isEqualTo(1);
    }

    @Test
    void evaluate_isEmptyPerUnit_whenOneOfTwoUnitsReportedNoTests() {
        var evaluation = evaluate(finished(A, 0, passing(A)), finished(B, 0, List.of()));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Empty(List.of(B)));
    }

    @Test
    void evaluate_isFailedTimeoutNotEmpty_forSleepingTestKilledByTimeout() {
        assertThat(evaluate(timedOut(MAIN)).verdict()).isEqualTo(new Verdict.Failed("timeout"));
    }

    @Test
    void evaluate_isFailedRc_whenRcIsUnknown() {
        var noRc = UnitOutcome.unitOutcome(MAIN, true, false, Option.none(), Option.none(), Option.none(), Option.none(),
                                           passing(MAIN), 2);

        assertThat(evaluate(noRc).verdict()).isEqualTo(new Verdict.Failed("rc"));
    }

    @Test
    void evaluate_failedReasons_followMissingClassesThenTestsThenRc() {
        var manifest = Manifest.manifest(Set.of("org.example.MixedTest", "org.example.Gone"), Set.of());
        var withManifest = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 1, mixed(MAIN))),
                                                                       false,
                                                                       false,
                                                                       Option.some(manifest)));

        assertThat(withManifest.verdict()).isEqualTo(new Verdict.Failed("missing-classes"));
        assertThat(evaluate(finished(MAIN, 1, mixed(MAIN))).verdict()).isEqualTo(new Verdict.Failed("tests"));
        assertThat(evaluate(finished(MAIN, 1, passing(MAIN))).verdict()).isEqualTo(new Verdict.Failed("rc"));
    }

    @Test
    void evaluate_ignoresRerunUnits_forVerdictAndCounts() {
        var rerun = UnitOutcome.unitOutcome(B, true, true, Option.some(137), Option.some(KillCause.TIMEOUT), Option.none(),
                                            Option.none(), mixed(B), 9);
        var evaluation = evaluate(finished(A, 0, passing(A)), rerun);

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
        assertThat(evaluation.counts()
                             .testcases()).isEqualTo(2);
        assertThat(evaluation.observedFailures()).isZero();
    }

    @Test
    void evaluate_annotatesSuperseded_withoutChangingVerdict() {
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(finished(MAIN, 0, passing(MAIN))),
                                                                     false,
                                                                     true,
                                                                     Option.none()));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
        assertThat(evaluation.annotations()).containsExactly("superseded");
    }
}
