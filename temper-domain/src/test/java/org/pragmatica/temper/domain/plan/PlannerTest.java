package org.pragmatica.temper.domain.plan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.refusal.Refusal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.gateUnit;
import static org.pragmatica.temper.domain.Fixtures.moduleTestUnit;
import static org.pragmatica.temper.domain.Fixtures.mvnUnit;
import static org.pragmatica.temper.domain.Fixtures.noPortGroup;
import static org.pragmatica.temper.domain.Fixtures.ok;
import static org.pragmatica.temper.domain.Fixtures.plannedUnit;
import static org.pragmatica.temper.domain.Fixtures.portGroup;

/// m0-design §3.1 planner, §3.2 timeouts, test-request and port-group rules; m1-design §1.1 Gate, §1.6
/// reactor-aware port group, §2.2 manifest specs.
class PlannerTest {
    private static final String M2 = Planner.FORCED_REPO_LOCAL;
    private static final ReactorModules WITH_EMBER = ReactorModules.reactorModules(Set.of("", "core", "aether", "aether/ember"));
    private static final ReactorModules WITHOUT_EMBER = ReactorModules.reactorModules(Set.of("", "core"));

    private static ManifestSpec manifestOf(UnitSpec unit) {
        return unit.manifest()
                   .fold(() -> fail("expected a manifest"), spec -> spec);
    }

    private static UnitSpec planned(Recipe recipe, PlanDefaults defaults, Option<ReactorModules> reactor) {
        return ok(Planner.plan(recipe, RecipeOptions.NONE, defaults, reactor)).getFirst();
    }

    @Test
    void plan_mvn_isOneUnitWithBatchModeAndForcedRepoLast() {
        var unit = mvnUnit("-pl", "core", "verify");

        assertThat(unit.name()).isEqualTo(UnitName.MAIN);
        assertThat(unit.kind()).isEqualTo(UnitKind.MVN);
        assertThat(unit.steps()).containsExactly(List.of("mvn", "-B", "-pl", "core", "verify", M2));
        assertThat(unit.requestsTests()).isTrue();
        assertThat(unit.timeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(M2).isEqualTo("-Dmaven.repo.local=${ws}/.m2-local");
    }

    @Test
    void plan_mvn_hasNoManifest() {
        assertThat(mvnUnit("-pl", "core", "verify").manifest()).isEqualTo(Option.none());
    }

    @Test
    void plan_moduleTest_installsThenTests() {
        var unit = moduleTestUnit(List.of("aether/ember", "core"), Option.some("Foo*,Bar"), Option.none());

        assertThat(unit.kind()).isEqualTo(UnitKind.MODULE_TEST);
        assertThat(unit.steps()).containsExactly(List.of("mvn", "-B", "-pl", "aether/ember,core", "-am", "install", "-DskipTests", M2),
                                                 List.of("mvn", "-B", "-pl", "aether/ember,core", "test", "-Dtest=Foo*,Bar", M2));
    }

    @Test
    void plan_moduleTest_withItTest_verifies() {
        var withBoth = moduleTestUnit(List.of("core"), Option.some("A"), Option.some("BIT"));
        var itOnly = moduleTestUnit(List.of("core"), Option.none(), Option.some("BIT"));

        assertThat(withBoth.steps()
                           .getLast()).containsExactly("mvn", "-B", "-pl", "core", "verify", "-Dit.test=BIT", "-Dtest=A", M2);
        assertThat(itOnly.steps()
                         .getLast()).containsExactly("mvn", "-B", "-pl", "core", "verify", "-Dit.test=BIT", M2);
    }

    @Test
    void plan_moduleTest_withoutSelection_runsAllTests() {
        assertThat(moduleTestUnit(List.of("core"), Option.none(), Option.none()).steps()
                                                                                 .getLast()).containsExactly("mvn",
                                                                                                             "-B",
                                                                                                             "-pl",
                                                                                                             "core",
                                                                                                             "test",
                                                                                                             M2);
    }

    @Test
    void plan_appliesTimeoutOverride() {
        var options = RecipeOptions.recipeOptions(Option.some(ok(UnitTimeout.unitTimeout("PT90S"))));
        var unit = ok(Planner.plan(ok(Recipe.mvn(List.of("test"))), options, noPortGroup(), Option.none())).getFirst();

        assertThat(unit.timeout()).isEqualTo(Duration.ofSeconds(90));
    }

    /// m4-design §5.1: the planner copies the recipe's declaration onto each unit, which the store writes as
    /// `units.side_effects`.
    @Test
    void plan_copiesTheRecipesSideEffects_ontoTheUnit() {
        assertThat(mvnUnit("-pl", "core", "verify").sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(mvnUnit("clean", "deploy").sideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(moduleTestUnit(List.of("core"), Option.none(), Option.none()).sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(gateUnit().sideEffects()).isEqualTo(SideEffects.NONE);
    }

    @Test
    void plan_refusesUnsupportedRecipe() {
        assertThat(failure(Planner.plan(Recipe.unsupported("mutate"), RecipeOptions.NONE, noPortGroup(), Option.none())))
            .isInstanceOfSatisfying(Refusal.class, refusal -> assertThat(refusal.code()).isEqualTo("unsupported"));
    }

    /// m1-design §1.1: the unsharded Gate.
    @Nested
    class Gate {
        @Test
        void plan_gate_isOneUnshardedCleanInstallUnitWithTheGateMemory() {
            var unit = gateUnit();

            assertThat(unit.name()).isEqualTo(UnitName.GATE);
            assertThat(unit.name()
                           .value()).isEqualTo("gate");
            assertThat(unit.kind()).isEqualTo(UnitKind.GATE_UNSHARDED);
            assertThat(unit.steps()).containsExactly(List.of("mvn", "-B", "clean", "install", M2));
            assertThat(unit.requestsTests()).isTrue();
            assertThat(unit.timeout()).isEqualTo(Duration.ofHours(3));
            assertThat(unit.memory()).isEqualTo(ok(MemoryMb.memoryMb(16384)));
        }

        @Test
        void plan_gate_hasTheEffectivePomManifestOfItsBuild() {
            var manifest = manifestOf(gateUnit());

            assertThat(manifest.pomArgv()).containsExactly("mvn",
                                                           "-B",
                                                           ManifestSpec.EFFECTIVE_POM_GOAL,
                                                           "-Doutput=${ws}/.home/.temper/effective-pom-gate.xml",
                                                           M2);
            assertThat(manifest.reach()).isEqualTo(Reach.INTEGRATION_TEST);
            assertThat(manifest.test()).isEqualTo(Option.none());
            assertThat(manifest.itTest()).isEqualTo(Option.none());
        }

        @Test
        void plan_gate_needsThePortGroup_whileEmberIsInTheReactor() {
            var defaults = portGroup("aether/ember");

            assertThat(planned(Recipe.gate(), defaults, Option.some(WITH_EMBER)).needsPortGroup()).isTrue();
            assertThat(planned(Recipe.gate(), defaults, Option.none()).needsPortGroup()).as("unknown reactor: M0 answer")
                                                                                       .isTrue();
            assertThat(planned(Recipe.gate(), defaults, Option.some(WITHOUT_EMBER)).needsPortGroup()).isFalse();
        }

        @Test
        void recipe_gate_isCanonicalAndKinded() {
            assertThat(Recipe.gate()
                             .canonical()).isEqualTo("{\"gate\":{}}");
            assertThat(Recipe.gate()
                             .kind()).isEqualTo("gate");
        }
    }

    /// m1-design §2.2: the effective-POM step reuses the main step's reactor selection.
    @Nested
    class Manifests {
        @Test
        void plan_moduleTest_manifestComesFromTheSelectionStep() {
            var manifest = manifestOf(moduleTestUnit(List.of("aether/ember", "core"), Option.some("Foo*,Bar"), Option.none()));

            assertThat(manifest.pomArgv()).containsExactly("mvn",
                                                           "-B",
                                                           ManifestSpec.EFFECTIVE_POM_GOAL,
                                                           "-Doutput=${ws}/.home/.temper/effective-pom-main.xml",
                                                           "-pl",
                                                           "aether/ember,core",
                                                           "-Dtest=Foo*,Bar",
                                                           M2);
            assertThat(manifest.reach()).isEqualTo(Reach.TEST);
            assertThat(manifest.test()).isEqualTo(Option.some("Foo*,Bar"));
            assertThat(manifest.userProperties()).containsEntry("test", "Foo*,Bar");
        }

        @Test
        void plan_moduleTest_withItTest_reachesIntegrationTest() {
            var manifest = manifestOf(moduleTestUnit(List.of("core"), Option.none(), Option.some("BIT")));

            assertThat(manifest.reach()).isEqualTo(Reach.INTEGRATION_TEST);
            assertThat(manifest.itTest()).isEqualTo(Option.some("BIT"));
        }

        @Test
        void plan_manifestPomArgv_endsWithTheSingleForcedToken() {
            var unit = moduleTestUnit(List.of("core"), Option.some("A"), Option.some("B"));
            var pomArgv = manifestOf(unit).pomArgv();

            assertThat(pomArgv.getLast()).isEqualTo(M2);
            assertThat(pomArgv.stream()
                              .filter(M2::equals)).hasSize(1);
            assertThat(unit.argvs()).containsExactly(unit.steps()
                                                         .getFirst(),
                                                     unit.steps()
                                                         .getLast(),
                                                     pomArgv);
        }

        @Test
        void effectivePomOutput_isTheWorkspaceConstantPerUnit() {
            assertThat(Planner.effectivePomOutput(UnitName.MAIN)).isEqualTo("-Doutput=${ws}/.home/.temper/effective-pom-main.xml");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"compile|false", "test|true", "package|true", "verify|true", "install|true",
                                         "install -DskipTests|false", "verify -DskipTests=true|false",
                                         "verify -DskipTests=false|true", "verify -Dmaven.test.skip|false",
                                         "surefire:test|true", "failsafe:integration-test|true", "clean compile|false",
                                         "verify -DskipITs|true"})
    void testRequest_judgesEachStep(String args, boolean requested) {
        var step = new ArrayList<String>(List.of("mvn"));

        step.addAll(List.of(args.split(" ")));
        assertThat(TestRequest.requested(List.of(step))).isEqualTo(requested);
    }

    @Test
    void testRequest_isTrue_whenAnyStepRequests() {
        assertThat(TestRequest.requested(List.of(List.of("mvn", "install", "-DskipTests"), List.of("mvn", "test")))).isTrue();
    }

    @Test
    void portGroup_needed_forListedModuleWholeReactorAndAlsoMake() {
        var modules = List.of("aether/ember", "aether/forge/forge-tests");

        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-pl", "aether/ember", "test")), modules, Option.none())).isTrue();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-pl", ":forge-tests", "verify")), modules, Option.none())).isTrue();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "verify")), modules, Option.none())).isTrue();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-pl", "core", "-amd", "test")), modules, Option.none())).isTrue();
    }

    @Test
    void portGroup_notNeeded_forOtherModulesSkippedTestsOrEmptyList() {
        var modules = List.of("aether/ember");

        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-pl", "core", "test")), modules, Option.none())).isFalse();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-DskipTests", "install")), modules, Option.none())).isFalse();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "verify")), List.of(), Option.none())).isFalse();
    }

    @Test
    void portGroup_followsTheReactor_onlyForTheDefaultReactor() {
        var modules = List.of("aether/ember");
        var without = Option.some(WITHOUT_EMBER);

        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "verify")), modules, without)).isFalse();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-pl", "core", "-amd", "test")), modules, without)).isFalse();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "-Pwith-ember", "verify")), modules, without)).as("-P: M0 answer")
                                                                                                           .isTrue();
        assertThat(PortGroupRule.needs(List.of(List.of("mvn", "verify")), modules, Option.some(WITH_EMBER))).isTrue();
    }

    @Test
    void plan_setsNeedsPortGroup_fromDefaults() {
        var unit = plannedUnit(ok(Recipe.moduleTest(List.of("aether/ember"), Option.none(), Option.none())), portGroup("aether/ember"));

        assertThat(unit.needsPortGroup()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"GATE_UNSHARDED,PT3H", "REST,PT2H", "EMBER,PT90M", "BUILD,PT45M", "MODULE_TEST,PT60M", "MVN,PT60M",
                "CONTROL,PT30M", "MUTANT,PT30M", "RERUN,PT30M"})
    void timeouts_defaultFor_pinsSpecTable(UnitKind kind, String iso) {
        assertThat(Timeouts.defaultFor(kind)).isEqualTo(Duration.parse(iso));
        assertThat(Timeouts.effective(kind, Option.none())).isEqualTo(Duration.parse(iso));
    }

    @Nested
    class RerunUnits {
        private Recipe.Rerun rerunOf(String... classesAndModules) {
            var targets = new ArrayList<Recipe.Rerun.Target>();

            for (int index = 0; index < classesAndModules.length; index += 3) {
                targets.add(ok(Recipe.Rerun.Target.target(classesAndModules[index], classesAndModules[index + 1], classesAndModules[index + 2])));
            }
            return Recipe.rerun(ok(JobId.jobId(4711)),
                                targets,
                                Recipe.Rerun.Original.original(ok(JobId.jobId(4711)),
                                                               "FAILED",
                                                               Option.some("tests"),
                                                               List.of()));
        }

        private static final List<String> TOLERANT = List.of("-Dsurefire.failIfNoSpecifiedTests=false",
                                                             "-Dit.failIfNoSpecifiedTests=false",
                                                             "-Dmaven.test.failure.ignore=true");

        /// m2-design §5.2 as amended [G]: one `RERUN` unit for every target class, ModuleTest-shaped: install the target
        /// modules, then select the classes (surefire through `-Dtest`), tolerating a module without one of them.
        @Test
        void plan_rerun_oneUnit_installThenSelectEveryClass() {
            var units = ok(Planner.plan(rerunOf("org.x.FooTest", "core", "surefire", "org.x.BarTest", "aether/ember", "surefire"),
                                        RecipeOptions.NONE,
                                        noPortGroup(),
                                        Option.none()));

            assertThat(units).hasSize(1);
            var unit = units.getFirst();

            assertThat(unit.name()).isEqualTo(UnitName.RERUN);
            assertThat(unit.kind()).isEqualTo(UnitKind.RERUN);
            assertThat(unit.timeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(unit.steps()).containsExactly(List.of("mvn", "-B", "-pl", "aether/ember,core", "-am", "install", "-DskipTests", M2),
                                                     concat(List.of("mvn", "-B", "-pl", "aether/ember,core", "test",
                                                                    "-Dtest=org.x.BarTest,org.x.FooTest"),
                                                            TOLERANT));
            assertThat(unit.manifest().isPresent()).isTrue();
        }

        /// Failsafe classes through `-Dit.test` in `verify`, surefire ones through `-Dtest` in the same step.
        @Test
        void plan_rerun_failsafeAndSurefireClasses_verifyWithBothSelections() {
            var unit = ok(Planner.plan(rerunOf("org.x.ServiceIT", "core", "failsafe", "org.x.FooTest", "core", "surefire"),
                                       RecipeOptions.NONE,
                                       noPortGroup(),
                                       Option.none())).getFirst();

            assertThat(unit.steps().get(1)).isEqualTo(concat(List.of("mvn", "-B", "-pl", "core", "verify", "-Dit.test=org.x.ServiceIT",
                                                                     "-Dtest=org.x.FooTest"),
                                                             TOLERANT));
        }

        /// Failsafe classes only: surefire selects no class (`!*`), so neither plugin runs its whole suite (R-M2-16).
        @Test
        void plan_rerun_failsafeOnly_surefireSelectsNothing() {
            var unit = ok(Planner.plan(rerunOf("org.x.ServiceIT", "core", "failsafe"), RecipeOptions.NONE, noPortGroup(), Option.none()))
                .getFirst();

            assertThat(unit.steps().get(1)).isEqualTo(concat(List.of("mvn", "-B", "-pl", "core", "verify", "-Dit.test=org.x.ServiceIT",
                                                                     "-Dtest=!*"),
                                                             TOLERANT));
            assertThat(Recipe.Rerun.NO_CLASS).isEqualTo("!*");
        }

        /// A `@Nested` class runs its outermost class's selection, once for two nested targets of one outer class.
        @Test
        void plan_rerun_nestedClasses_selectTheOutermostOnce() {
            var unit = ok(Planner.plan(rerunOf("org.x.Outer$A", "core", "surefire", "org.x.Outer$B", "core", "surefire"),
                                       RecipeOptions.NONE,
                                       noPortGroup(),
                                       Option.none())).getFirst();

            assertThat(unit.steps().get(1)).contains("-Dtest=org.x.Outer").doesNotContain("-Dtest=org.x.Outer,org.x.Outer");
        }

        private static List<String> concat(List<String> head, List<String> tail) {
            var all = new ArrayList<>(head);

            all.addAll(tail);
            all.add(M2);
            return all;
        }

        @Test
        void plan_rerun_withoutClasses_isRefused() {
            var cause = failure(Planner.plan(Recipe.refusedRerun(ok(JobId.jobId(4711))),
                                             RecipeOptions.NONE,
                                             noPortGroup(),
                                             Option.none()));

            assertThat(cause).isInstanceOf(Refusal.class);
            assertThat(((Refusal) cause).code()).isEqualTo("rerun");
        }

        @Test
        void plan_rerun_portGroupModule_needsThePortGroup() {
            var unit = ok(Planner.plan(rerunOf("org.x.EmberTest", "aether/ember", "surefire"),
                                       RecipeOptions.NONE,
                                       portGroup("aether/ember"),
                                       Option.none())).getFirst();

            assertThat(unit.needsPortGroup()).isTrue();
        }

        /// maintainer ruling on the rerun bound: the bound decided at submit is the unit's timeout.
        @Test
        void plan_rerun_withABound_boundsTheUnitByIt() {
            var bounded = rerunOf("org.x.FooTest", "core", "surefire").withBound(Recipe.Rerun.Bound.bound(Duration.ofMinutes(48),
                                                                                                         "Σ 25.1m ×1.5 + install 5m + slack 5m"));

            var unit = ok(Planner.plan(bounded, RecipeOptions.NONE, noPortGroup(), Option.none())).getFirst();

            assertThat(unit.timeout()).isEqualTo(Duration.ofMinutes(48));
        }

        /// An explicit `--unit-timeout` still wins over the derived bound.
        @Test
        void plan_rerun_explicitTimeout_winsOverTheBound() {
            var bounded = rerunOf("org.x.FooTest", "core", "surefire").withBound(Recipe.Rerun.Bound.bound(Duration.ofMinutes(48),
                                                                                                         "Σ 25.1m ×1.5 + install 5m + slack 5m"));

            var unit = ok(Planner.plan(bounded,
                                       RecipeOptions.recipeOptions(Option.some(ok(UnitTimeout.unitTimeout("PT90M")))),
                                       noPortGroup(),
                                       Option.none())).getFirst();

            assertThat(unit.timeout()).isEqualTo(Duration.ofMinutes(90));
        }
    }
}
