package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.mvn.MvnStep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.temper.domain.manifest.Poms.config;
import static org.pragmatica.temper.domain.manifest.Poms.failsafe;
import static org.pragmatica.temper.domain.manifest.Poms.install;
import static org.pragmatica.temper.domain.manifest.Poms.items;
import static org.pragmatica.temper.domain.manifest.Poms.module;
import static org.pragmatica.temper.domain.manifest.Poms.plugin;
import static org.pragmatica.temper.domain.manifest.Poms.spec;
import static org.pragmatica.temper.domain.manifest.Poms.surefire;

/// Which executions a unit reaches and how their parameters resolve (m1-design §2.4 rules 1-3).
class SelectionsTest {
    private static List<ModuleSelection> resolve(ManifestSpec spec, PomModule... modules) {
        return Selections.resolve(List.of(modules), spec)
                         .onFailure(cause -> fail(cause.message()))
                         .unwrap();
    }

    private static TestParams only(ManifestSpec spec, PomModule module) {
        var selections = resolve(spec, module);

        assertThat(selections).hasSize(1);
        return selections.getFirst().params();
    }

    private static Cause failure(ManifestSpec spec, PomModule... modules) {
        return Selections.resolve(List.of(modules), spec)
                         .fold(cause -> cause, _ -> fail("expected a failure"));
    }

    @Nested
    class Precedence {
        @Test
        void params_explicitConfigBeatsUserProperty() {
            var params = only(spec(Reach.TEST, Map.of("skipTests", "true")),
                              module("m", surefire(config("skipTests", "false"))));

            assertThat(params.skipped()).isFalse();
        }

        @Test
        void params_userPropertyBeatsModuleProperty() {
            var params = only(spec(Reach.TEST, Map.of("skipTests", "true")),
                              module("m", Map.of("skipTests", "false"), surefire()));

            assertThat(params.skipped()).isTrue();
        }

        @Test
        void params_modulePropertyBeatsTheDefault() {
            assertThat(only(install(), module("m", Map.of("skipTests", "true"), surefire())).skipped()).isTrue();
            assertThat(only(install(), module("m", surefire())).skipped()).isFalse();
        }

        @Test
        void params_leftoverExpressionIsEvaluatedAgainstTheProperties_orUnset() {
            assertThat(only(spec(Reach.TEST, Map.of("ember.skipTests", "true")),
                            module("m", surefire(config("skipTests", "${ember.skipTests}")))).skipped()).isTrue();
            assertThat(only(install(), module("m", Map.of("ember.skipTests", "true"), surefire(config("skipTests", "${ember.skipTests}"))))
                           .skipped()).isTrue();
            // configured but undefined: the mojo sees null and uses the parameter default, NOT the skipTests property
            assertThat(only(spec(Reach.TEST, Map.of("skipTests", "true")),
                            module("m", surefire(config("skipTests", "${undefined}")))).skipped()).isFalse();
        }

        @Test
        void params_groupsFollowTheSamePrecedence() {
            assertThat(only(spec(Reach.TEST, Map.of("groups", "fast")), module("m", Map.of("groups", "slow"), surefire())).groups())
                .containsExactly("fast");
            assertThat(only(spec(Reach.TEST, Map.of("groups", "fast")), module("m", surefire(config("groups", "db,smoke")))).groups())
                .containsExactlyInAnyOrder("db", "smoke");
            assertThat(only(install(), module("m", surefire(config("excludedGroups", "host")))).excludedGroups())
                .containsExactly("host");
        }

        @Test
        void params_defaultIncludesAndExcludes_unlessConfigured() {
            var defaults = only(install(), module("m", surefire()));
            var configured = only(install(), module("m", surefire(Map.of("includes", ConfigValue.Items.items(List.of("**/*Spec", "**/*Check")),
                                                                         "excludes", ConfigValue.Text.text("**/Slow*")))));

            assertThat(defaults.includes()).isEqualTo(SurefirePatterns.SUREFIRE_DEFAULT_INCLUDES);
            assertThat(defaults.excludes()).isEqualTo(SurefirePatterns.DEFAULT_EXCLUDES);
            assertThat(configured.includes()).containsExactly("**/*Spec", "**/*Check");
            assertThat(configured.excludes()).containsExactly("**/Slow*");
        }

        @Test
        void params_emptyConfiguredIncludesAndExcludes_fallBackToTheDefaults() {
            var empty = only(install(), module("m", surefire(Map.of("includes", ConfigValue.Items.items(List.of()),
                                                                    "excludes", ConfigValue.Text.text("")))));

            assertThat(empty.includes()).isEqualTo(SurefirePatterns.SUREFIRE_DEFAULT_INCLUDES);
            assertThat(empty.excludes()).isEqualTo(SurefirePatterns.DEFAULT_EXCLUDES);
        }
    }

    @Nested
    class TestProperty {
        @Test
        void testProperty_narrowsSurefireOnly_itTestNarrowsFailsafeOnly() {
            var spec = ManifestSpec.manifestSpec(List.of("mvn"), Reach.INTEGRATION_TEST, Map.of(), Option.some("FooTest"), Option.some("FooIT"));
            var selections = resolve(spec, module("m", surefire(), failsafe()));

            assertThat(selections).extracting(selection -> selection.runner() + "=" + selection.params().test().or("-"))
                                  .containsExactlyInAnyOrder("SUREFIRE=FooTest", "FAILSAFE=FooIT");
        }

        @Test
        void testProperty_fromTheUserPropertiesWhenTheSpecFieldIsAbsent() {
            var spec = spec(Reach.INTEGRATION_TEST, Map.of("it.test", "BarIT"));

            assertThat(only(spec, module("m", failsafe())).test()).isEqualTo(Option.some("BarIT"));
        }
    }

    @Nested
    class Skip {
        @Test
        void skipFlags_applyPerRunner() {
            var skipIts = spec(Reach.INTEGRATION_TEST, Map.of("skipITs", "true"));
            var selections = resolve(skipIts, module("m", surefire(), failsafe()));

            assertThat(selections).extracting(selection -> selection.runner() + "=" + selection.params().skipped())
                                  .containsExactlyInAnyOrder("SUREFIRE=false", "FAILSAFE=true");
        }

        @Test
        void skipFlags_mavenTestSkipAndSkipExecSkipBoth() {
            for (var property : List.of("maven.test.skip", "maven.test.skip.exec", "skipTests")) {
                var selections = resolve(spec(Reach.INTEGRATION_TEST, Map.of(property, "true")), module("m", surefire(), failsafe()));

                assertThat(selections).as(property).allMatch(selection -> selection.params().skipped());
            }
        }

        @Test
        void skipFlags_configuredSkipAndSkipExec() {
            assertThat(only(install(), module("m", surefire(config("skip", "true")))).skipped()).isTrue();
            assertThat(only(install(), module("m", surefire(config("skipExec", "TRUE")))).skipped()).isTrue();
            assertThat(only(install(), module("m", failsafe(config("skipITs", "true")))).skipped()).isTrue();
        }
    }

    @Nested
    class Reached {
        @Test
        void reachTest_excludesFailsafeAtItsDefaultPhase() {
            assertThat(resolve(spec(Reach.TEST), module("m", surefire(), failsafe())))
                .extracting(ModuleSelection::runner)
                .containsExactly(Runner.SUREFIRE);
            assertThat(resolve(install(), module("m", surefire(), failsafe())))
                .extracting(ModuleSelection::runner)
                .containsExactlyInAnyOrder(Runner.SUREFIRE, Runner.FAILSAFE);
        }

        @Test
        void executions_phaseNoneIsUnbound_earlierPhasesAreReached_andOtherGoalsIgnored() {
            var module = module("m",
                                plugin(Runner.SUREFIRE,
                                       Execution.execution("default-test", Option.some("none"), List.of("test"), Map.of()),
                                       Execution.execution("early", Option.some("process-test-classes"), List.of("test"), Map.of())),
                                plugin(Runner.FAILSAFE,
                                       Execution.execution("check", Option.none(), List.of("verify"), Map.of()),
                                       Execution.execution("it-in-test", Option.some("test"), List.of("integration-test"), Map.of())));
            var selections = resolve(spec(Reach.TEST), module);

            assertThat(selections).extracting(ModuleSelection::runner).containsExactlyInAnyOrder(Runner.SUREFIRE, Runner.FAILSAFE);
        }

        @Test
        void executions_pomPackagingBearsNoTests() {
            var pom = PomModule.pomModule("org.acme:parent", ".", "pom", "target/test-classes", "target/classes", "src/test/java", Map.of(),
                                          List.of(surefire()));

            assertThat(resolve(install(), pom)).isEmpty();
        }

        @Test
        void executions_unplaceablePhaseIsUnsupported() {
            var install = module("m", plugin(Runner.SUREFIRE, Execution.execution("late", Option.some("install"), List.of("test"), Map.of())));
            var clean = module("m", plugin(Runner.SUREFIRE, Execution.execution("odd", Option.some("pre-clean"), List.of("test"), Map.of())));

            assertThat(failure(install(), install)).isInstanceOfSatisfying(ManifestError.UnplaceablePhase.class,
                                                                           error -> assertThat(error.phase()).isEqualTo("install"));
            assertThat(failure(spec(Reach.TEST), clean)).isInstanceOf(ManifestError.UnplaceablePhase.class);
            // under reach TEST an install-bound execution is plainly not reached
            assertThat(resolve(spec(Reach.TEST), install)).isEmpty();
        }
    }

    @Nested
    class Unsupported {
        @Test
        void unsupported_includesFileExcludesFileAndDependenciesToScan() {
            assertThat(failure(install(), module("m", surefire(config("includesFile", "x.txt")))).message()).contains("includesFile");
            assertThat(failure(install(), module("m", surefire(items("dependenciesToScan", "org.acme:lib"))))).isInstanceOf(ManifestError.UnsupportedParameter.class);
            assertThat(failure(spec(Reach.TEST, Map.of("surefire.excludesFile", "x.txt")), module("m", surefire())).message())
                .contains("surefire.excludesFile");
            assertThat(failure(install(), module("m", Map.of("dependenciesToScan", "a:b"), surefire()))).isInstanceOf(ManifestError.UnsupportedParameter.class);
        }

        @Test
        void unsupported_tagExpressionsAndJunit4Categories() {
            for (var expression : List.of("fast & slow", "fast|slow", "!slow", "(fast)")) {
                assertThat(failure(install(), module("m", surefire(config("excludedGroups", expression)))))
                    .as(expression)
                    .isInstanceOf(ManifestError.UnsupportedTagExpression.class);
            }

            assertThat(failure(install(), module("m", surefire(config("groups", "org.acme.SlowTests")))))
                .isInstanceOfSatisfying(ManifestError.Junit4Category.class,
                                        error -> assertThat(error.category()).isEqualTo("org.acme.SlowTests"));
            assertThat(failure(install(), module("m", surefire(items("excludedGroups", "slow", "db")))))
                .isInstanceOfSatisfying(ManifestError.UnsupportedParameter.class,
                                        error -> assertThat(error.parameter()).isEqualTo("excludedGroups"));
            assertThat(only(install(), module("m", surefire(config("groups", "fast,integration-db")))).groups())
                .containsExactlyInAnyOrder("fast", "integration-db");
        }

        /// Surefire 3.5.4 oracle: "Method filter prohibited in includes|excludes parameter" and "Exclamation mark
        /// not expected in 'exclusion'" both fail the build.
        @Test
        void unsupported_patternsSurefireRejects_methodFiltersAndNegatedExcludes() {
            assertThat(failure(install(), module("m", surefire(items("includes", "KMixedTest#k1", "ATest")))))
                .isInstanceOfSatisfying(ManifestError.RejectedPattern.class,
                                        error -> assertThat(error.message())
                                            .isEqualTo("Unsupported test configuration in m: surefire rejects the includes pattern KMixedTest#k1"));
            assertThat(failure(install(), module("m", surefire(items("excludes", "ATest#a")))))
                .isInstanceOfSatisfying(ManifestError.RejectedPattern.class, error -> assertThat(error.parameter()).isEqualTo("excludes"));
            assertThat(failure(install(), module("m", surefire(items("excludes", "!ATest")))))
                .isInstanceOfSatisfying(ManifestError.RejectedPattern.class, error -> assertThat(error.pattern()).isEqualTo("!ATest"));
        }

        /// `test` replaces the lists before surefire checks them, and a skipped execution checks nothing; a `!`
        /// include is valid.
        @Test
        void unsupported_rejectedPatternsAreHarmless_underTestOrSkip_andANegatedIncludeIsValid() {
            var methodInclude = module("m", surefire(items("includes", "KMixedTest#k1")));

            assertThat(only(spec(Reach.TEST, Map.of("test", "ATest")), methodInclude).test()).isEqualTo(Option.some("ATest"));
            assertThat(only(spec(Reach.TEST, Map.of("skipTests", "true")), methodInclude).skipped()).isTrue();
            assertThat(only(install(), module("m", surefire(items("includes", "**/*Test, !KMixedTest")))).includes())
                .containsExactly("**/*Test", "!KMixedTest");
        }

        @Test
        void unsupported_rejectedPatterns_underABlankTest_orATestFailsafeDoesNotRead() {
            assertThat(failure(spec(Reach.TEST, Map.of("test", " ")), module("m", surefire(items("includes", "A#m")))))
                .isInstanceOf(ManifestError.RejectedPattern.class);
            // -Dtest narrows surefire only: failsafe still checks its own includes
            assertThat(failure(spec(Reach.INTEGRATION_TEST, Map.of("test", "ATest")), module("m", failsafe(items("includes", "AIT#m")))))
                .isInstanceOf(ManifestError.RejectedPattern.class);
        }

        @Test
        void unsupported_rejectedPatternsInAnUnreachedExecution_areHarmless() {
            var off = Execution.execution("off", Option.some("none"), List.of("test"), items("includes", "A#m"));

            assertThat(resolve(install(), module("m", plugin(Runner.SUREFIRE, off)))).isEmpty();
        }
    }

    @Nested
    class SkipFlagForms {
        private static Map<String, String> userProperties(String... argv) {
            return MvnStep.mvnStep(List.of(argv))
                          .properties()
                          .stream()
                          .collect(Collectors.toMap(MvnStep.Property::key, MvnStep.Property::value, (_, last) -> last));
        }

        /// A bare `-DskipTests` is `true` (Maven, and `MvnStep` that builds the user properties); an explicit empty
        /// `-DskipTests=` is not a skip: surefire 3.5.4 runs every test for it (oracle scenario `skipempty`).
        @Test
        void skipFlags_bareDefineSkips_explicitEmptyValueDoesNot() {
            var bare = spec(Reach.TEST, userProperties("mvn", "test", "-DskipTests"));
            var empty = spec(Reach.TEST, userProperties("mvn", "test", "-DskipTests="));

            assertThat(only(bare, module("m", surefire())).skipped()).isTrue();
            assertThat(only(empty, module("m", surefire())).skipped()).isFalse();
        }
    }
}
