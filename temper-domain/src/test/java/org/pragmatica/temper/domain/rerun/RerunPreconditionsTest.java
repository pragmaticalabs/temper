package org.pragmatica.temper.domain.rerun;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Recipe.Rerun.Failure;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m2-design §5.1 and §5.6: every refusal, the 1..3 class bound counted in classes, the KNOWN marks of the parent's
/// record, the parent kinds (ruling R-M2-4) and the target modules from the parent's manifest.
class RerunPreconditionsTest {
    private static final JobId PARENT = ok(JobId.jobId(4711));

    private static Failure red(String className, String name) {
        return Failure.failure(className, name, none());
    }

    private static Failure known(String className, String name) {
        return Failure.failure(className, name, some("#1679"));
    }

    private static List<ManifestEntry> manifestOf(String... classes) {
        return Stream.of(classes)
                     .map(name -> ManifestEntry.manifestEntry("core", name, Runner.SUREFIRE))
                     .toList();
    }

    private static RerunPreconditions.Parent parent(Recipe recipe,
                                                    JobState state,
                                                    String verdict,
                                                    Option<String> reason,
                                                    List<Failure> failed,
                                                    List<ManifestEntry> manifest) {
        return RerunPreconditions.Parent.parent(PARENT,
                                                state,
                                                recipe,
                                                some(RerunPreconditions.Judged.judged(verdict, reason, failed)),
                                                manifest);
    }

    private static RerunPreconditions.Parent failedGate(List<Failure> failed, List<ManifestEntry> manifest) {
        return parent(Recipe.gate(), JobState.FAILED, "FAILED", some("tests"), failed, manifest);
    }

    private static String refusalDetail(RerunPreconditions.Parent parent) {
        var cause = failure(RerunPreconditions.rerun(parent));

        assertThat(cause).isInstanceOf(Refusal.class);
        assertThat(((Refusal) cause).code()).isEqualTo("rerun");
        return ((Refusal) cause).detail();
    }

    private static List<String> targets(Recipe.Rerun rerun) {
        return rerun.targets()
                    .stream()
                    .map(Recipe.Rerun.Target::className)
                    .toList();
    }

    @Nested
    class Accepted {
        @Test
        void rerun_threeFailingClasses_accepted_sortedWithTheirModules() {
            var rerun = ok(RerunPreconditions.rerun(failedGate(List.of(red("org.x.C", "c"), red("org.x.A", "a"), red("org.x.B", "b")),
                                                               manifestOf("org.x.A", "org.x.B", "org.x.C"))));

            assertThat(targets(rerun)).containsExactly("org.x.A", "org.x.B", "org.x.C");
            assertThat(rerun.of()).isEqualTo(PARENT);
            assertThat(rerun.targets()).allSatisfy(target -> assertThat(target.module().value()).isEqualTo("core"));
            assertThat(rerun.targets()).allSatisfy(target -> assertThat(target.runner()).isEqualTo(Runner.SUREFIRE));
        }

        @Test
        void rerun_moduleTestParent_accepted() {
            var parent = parent(ok(Recipe.moduleTest(List.of("core"), none(), none())),
                                JobState.FAILED,
                                "FAILED",
                                some("tests"),
                                List.of(red("org.x.A", "a")),
                                manifestOf("org.x.A"));

            assertThat(targets(ok(RerunPreconditions.rerun(parent)))).containsExactly("org.x.A");
        }

        /// Classes, not cases: one class with four failing cases is one target.
        @Test
        void rerun_fourFailingCasesOfOneClass_isOneClass() {
            var failed = IntStream.range(0, 4).mapToObj(index -> red("org.x.A", "case" + index)).toList();

            assertThat(targets(ok(RerunPreconditions.rerun(failedGate(failed, manifestOf("org.x.A")))))).containsExactly("org.x.A");
        }

        /// A class some of whose rows are KNOWN still has an unknown failure: it is a target.
        @Test
        void rerun_partlyKnownClass_isATarget_knownClassIsNot() {
            var rerun = ok(RerunPreconditions.rerun(failedGate(List.of(known("org.x.A", "a1"),
                                                                       red("org.x.A", "a2"),
                                                                       known("org.x.K", "k")),
                                                               manifestOf("org.x.A", "org.x.K"))));

            assertThat(targets(rerun)).containsExactly("org.x.A");
        }

        /// Four failing classes of which one is all KNOWN: three targets, accepted.
        @Test
        void rerun_knownClassesDoNotCountTowardTheBound() {
            var rerun = ok(RerunPreconditions.rerun(failedGate(List.of(red("org.x.A", "a"),
                                                                       red("org.x.B", "b"),
                                                                       red("org.x.C", "c"),
                                                                       known("org.x.K", "k")),
                                                               manifestOf("org.x.A", "org.x.B", "org.x.C", "org.x.K"))));

            assertThat(targets(rerun)).containsExactly("org.x.A", "org.x.B", "org.x.C");
        }

        /// `original` is the parent's verdict and its failed rows of the target classes, with their marks.
        @Test
        void rerun_originalCopiesTheParentsVerdictAndTargetRows() {
            var rerun = ok(RerunPreconditions.rerun(failedGate(List.of(known("org.x.A", "a1"),
                                                                       red("org.x.A", "a2"),
                                                                       known("org.x.K", "k")),
                                                               manifestOf("org.x.A", "org.x.K"))));

            var original = rerun.original().fold(() -> fail("expected a value"), present -> present);

            assertThat(original.job()).isEqualTo(PARENT);
            assertThat(original.verdict()).isEqualTo("FAILED");
            assertThat(original.reason()).isEqualTo(some("tests"));
            assertThat(original.failed()).containsExactly(known("org.x.A", "a1"), red("org.x.A", "a2"));
        }

        /// The canonical form is a function of the parent's record alone: two evaluations are equal, so a repeat
        /// request has the same dedup key.
        @Test
        void rerun_canonicalIsStable_andCarriesTheOriginal() {
            var parent = failedGate(List.of(red("org.x.B", "b"), red("org.x.A", "a")), manifestOf("org.x.A", "org.x.B"));

            var first = ok(RerunPreconditions.rerun(parent)).canonical();

            assertThat(ok(RerunPreconditions.rerun(parent)).canonical()).isEqualTo(first);
            assertThat(first).isEqualTo("{\"rerun\":{\"classes\":[{\"class\":\"org.x.A\",\"module\":\"core\",\"runner\":\"surefire\"},"
                                        + "{\"class\":\"org.x.B\",\"module\":\"core\",\"runner\":\"surefire\"}],\"of\":4711,"
                                        + "\"original\":{\"failed\":[{\"class\":\"org.x.B\",\"name\":\"b\"},{\"class\":\"org.x.A\",\"name\":\"a\"}],"
                                        + "\"job\":4711,\"reason\":\"tests\",\"verdict\":\"FAILED\"}}}");
        }

        /// A changed original (another mark) is another recipe: the original is part of the canonical form.
        @Test
        void rerun_anotherOriginal_isAnotherCanonical() {
            var unmarked = ok(RerunPreconditions.rerun(failedGate(List.of(red("org.x.A", "a"), red("org.x.A", "b")),
                                                                  manifestOf("org.x.A"))));
            var marked = ok(RerunPreconditions.rerun(failedGate(List.of(red("org.x.A", "a"), known("org.x.A", "b")),
                                                                manifestOf("org.x.A"))));

            assertThat(marked.canonical()).isNotEqualTo(unmarked.canonical());
        }

        @Test
        void rerun_failsafeClass_takesItsRunnerFromTheManifest() {
            var rerun = ok(RerunPreconditions.rerun(failedGate(List.of(red("org.x.ServiceIT", "a")),
                                                               List.of(ManifestEntry.manifestEntry("aether/ember",
                                                                                                   "org.x.ServiceIT",
                                                                                                   Runner.FAILSAFE)))));

            assertThat(rerun.targets().getFirst().runner()).isEqualTo(Runner.FAILSAFE);
            assertThat(rerun.targets().getFirst().module().value()).isEqualTo("aether/ember");
        }
    }

    @Nested
    class Refused {
        @Test
        void rerun_fourFailingClasses_refused() {
            var parent = failedGate(List.of(red("org.x.A", "a"), red("org.x.B", "b"), red("org.x.C", "c"), red("org.x.D", "d")),
                                    manifestOf("org.x.A", "org.x.B", "org.x.C", "org.x.D"));

            assertThat(refusalDetail(parent)).isEqualTo("4 failing classes (max 3)");
        }

        @Test
        void rerun_allFailingClassesKnown_refused() {
            var parent = failedGate(List.of(known("org.x.A", "a"), known("org.x.B", "b")), manifestOf("org.x.A", "org.x.B"));

            assertThat(refusalDetail(parent)).isEqualTo("all failing classes are KNOWN");
        }

        @Test
        void rerun_rerunParent_refused() {
            var parent = parent(Recipe.refusedRerun(ok(JobId.jobId(4700))),
                                JobState.FAILED,
                                "FAILED",
                                some("tests"),
                                List.of(red("org.x.A", "a")),
                                manifestOf("org.x.A"));

            assertThat(refusalDetail(parent)).isEqualTo("job 4711 is a rerun; a rerun is never rerun");
        }

        @Test
        void rerun_mvnParent_refused() {
            var parent = parent(ok(Recipe.mvn(List.of("verify"))),
                                JobState.FAILED,
                                "FAILED",
                                some("tests"),
                                List.of(red("org.x.A", "a")),
                                manifestOf("org.x.A"));

            assertThat(refusalDetail(parent)).isEqualTo("job 4711 is a mvn job; only gate and module-test jobs are rerun");
        }

        @Test
        void rerun_nonTerminalParent_refused() {
            for (var state : List.of(JobState.QUEUED, JobState.UNKNOWN, JobState.RUNNING, JobState.CANCELLING)) {
                var parent = RerunPreconditions.Parent.parent(PARENT, state, Recipe.gate(), none(), manifestOf("org.x.A"));

                assertThat(refusalDetail(parent)).as(state.name()).isEqualTo("job 4711 is " + state + ", not terminal");
            }
        }

        @Test
        void rerun_failedMissingClasses_refused() {
            var parent = parent(Recipe.gate(), JobState.FAILED, "FAILED", some("missing-classes"), List.of(red("org.x.A", "a")),
                                manifestOf("org.x.A"));

            assertThat(refusalDetail(parent)).isEqualTo("job 4711 is FAILED(missing-classes), not FAILED(tests)");
        }

        @Test
        void rerun_failedRcAndTimeout_refused() {
            for (var reason : List.of("rc", "timeout")) {
                var parent = parent(Recipe.gate(), JobState.FAILED, "FAILED", some(reason), List.of(red("org.x.A", "a")),
                                    manifestOf("org.x.A"));

                assertThat(refusalDetail(parent)).isEqualTo("job 4711 is FAILED(" + reason + "), not FAILED(tests)");
            }
        }

        @Test
        void rerun_passedOrVoidParent_refused() {
            assertThat(refusalDetail(parent(Recipe.gate(), JobState.PASSED, "PASSED", none(), List.of(), manifestOf())))
                .isEqualTo("job 4711 is PASSED, not FAILED(tests)");
            assertThat(refusalDetail(parent(Recipe.gate(), JobState.VOID, "VOID", some("disk"), List.of(red("org.x.A", "a")),
                                            manifestOf("org.x.A"))))
                .isEqualTo("job 4711 is VOID(disk), not FAILED(tests)");
        }

        /// The verdict must be FAILED, not only the reason `tests`.
        @Test
        void rerun_nonFailedVerdictWithReasonTests_refused() {
            assertThat(refusalDetail(parent(Recipe.gate(), JobState.PASSED, "PASSED", some("tests"), List.of(red("org.x.A", "a")),
                                            manifestOf("org.x.A"))))
                .isEqualTo("job 4711 is PASSED(tests), not FAILED(tests)");
        }

        /// A REFUSED parent is terminal but has no record.
        @Test
        void rerun_refusedParent_refused() {
            var parent = RerunPreconditions.Parent.parent(PARENT, JobState.REFUSED, Recipe.gate(), none(), List.of());

            assertThat(refusalDetail(parent)).isEqualTo("job 4711 is REFUSED, not FAILED(tests)");
        }

        /// Pruned at 90 d (no manifest entries), or a drift class the manifest never expected.
        @Test
        void rerun_classWithoutManifestEntry_refused() {
            var parent = failedGate(List.of(red("org.x.A", "a"), red("org.x.Drift", "d")), manifestOf("org.x.A"));

            assertThat(refusalDetail(parent)).isEqualTo("no module for org.x.Drift");
            assertThat(refusalDetail(failedGate(List.of(red("org.x.A", "a")), List.of()))).isEqualTo("no module for org.x.A");
        }

        @Test
        void rerun_classInTwoModules_refused() {
            var parent = failedGate(List.of(red("org.x.A", "a")),
                                    List.of(ManifestEntry.manifestEntry("core", "org.x.A", Runner.SUREFIRE),
                                            ManifestEntry.manifestEntry("other", "org.x.A", Runner.SUREFIRE)));

            assertThat(refusalDetail(parent)).isEqualTo("org.x.A is in 2 modules or runners");
        }

        @Test
        void rerun_moduleThatIsNotAPlPath_refused() {
            var parent = failedGate(List.of(red("org.x.A", "a")), List.of(ManifestEntry.manifestEntry("has space", "org.x.A", Runner.SUREFIRE)));

            assertThat(refusalDetail(parent)).isEqualTo("module has space of org.x.A is not a -pl path");
        }

        /// Two classes that both fail a check: the refusal is the first one's, a single refusal (never a composite).
        @Test
        void rerun_twoClassesWithoutModule_refusedByTheFirst() {
            var parent = failedGate(List.of(red("org.x.A", "a"), red("org.x.B", "b")), List.of());

            assertThat(refusalDetail(parent)).isEqualTo("no module for org.x.A");
        }

        /// A `@DisplayName` classname is not a class `-Dtest` can select.
        @Test
        void rerun_displayNamedClass_refused() {
            var parent = failedGate(List.of(red("Verify class tests", "a")), manifestOf("Verify class tests"));

            assertThat(refusalDetail(parent)).isEqualTo("Verify class tests is not a class name -Dtest can select");
        }
    }
}
