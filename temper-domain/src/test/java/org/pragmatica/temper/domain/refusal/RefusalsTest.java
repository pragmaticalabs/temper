package org.pragmatica.temper.domain.refusal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.plan.Planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.gateUnit;
import static org.pragmatica.temper.domain.Fixtures.moduleTestUnit;
import static org.pragmatica.temper.domain.Fixtures.noPortGroup;
import static org.pragmatica.temper.domain.Fixtures.mvnUnit;
import static org.pragmatica.temper.domain.Fixtures.ok;
import static org.pragmatica.temper.domain.Fixtures.plannedUnit;
import static org.pragmatica.temper.domain.Fixtures.portGroup;

/// A1 (domain part): every refusal is asserted THROUGH `Refusals.checkSubmit` with the seeded rules, so a
/// mutation probe that deletes one element of its list, or stubs one named check to `unitResult()`, turns
/// exactly that code's test red. Each code has a passing control.
class RefusalsTest {
    private static final String FORGE_TESTS = "aether/forge/forge-tests";
    private static final String CLASS = "StreamPublishReshuffleTest";
    private static final String CLASS_PATH = FORGE_TESTS + "/src/test/java/org/pragmatica/aether/forge/" + CLASS + ".java";

    /// The seed rows of m0-design §5.2 (with the `runner` field: the class is a failsafe IT).
    private static final List<RefusalRule> SEEDED = List.of(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553",
                                                                                                          CLASS,
                                                                                                          "#1553",
                                                                                                          Option.some(TestRunner.FAILSAFE)),
                                                            RefusalRule.ItTestRequired.itTestRequired("R-FAILSAFE",
                                                                                                      "forge-tests"));

    private static final String ENABLED_SOURCE = """
        package org.pragmatica.aether.forge;

        @Tag("forge")
        class StreamPublishReshuffleTest {
            @Test
            void reshuffles() {}
        }
        """;

    private static final String DISABLED_SOURCE = """
        package org.pragmatica.aether.forge;

        @Disabled("#1553")
        @Tag("forge")
        public final class StreamPublishReshuffleTest {
            @Test
            void reshuffles() {}
        }
        """;

    private static final String COMMENTED_OUT_SOURCE = """
        package org.pragmatica.aether.forge;

        // @Disabled("#1553")
        class StreamPublishReshuffleTest {
        }
        """;

    /// The default reactor of pragmatica rc4 as F5 describes it: forge-tests is outside it.
    private static final ReactorModules WITHOUT_FORGE_TESTS = ReactorModules.reactorModules(Set.of("",
                                                                                                   "core",
                                                                                                   "aether",
                                                                                                   "aether/ember",
                                                                                                   "aether/forge"));
    private static final ReactorModules WITH_FORGE_TESTS = ReactorModules.reactorModules(Set.of("",
                                                                                                "core",
                                                                                                "aether/forge",
                                                                                                FORGE_TESTS));

    private static TreeFacts tree(String content) {
        return tree(content, Option.none());
    }

    private static TreeFacts tree(String content, Option<ReactorModules> reactor) {
        return TreeFacts.treeFacts(Map.of(CLASS, List.of(ClassSource.classSource(CLASS_PATH, content))), reactor);
    }

    private static TreeFacts reactorOnly(ReactorModules reactor) {
        return TreeFacts.treeFacts(Map.of(), Option.some(reactor));
    }

    private static Result<Unit> check(UnitSpec unit) {
        return check(unit, tree(ENABLED_SOURCE));
    }

    private static Result<Unit> check(UnitSpec unit, TreeFacts tree) {
        return Refusals.checkSubmit(new SubmitShape(false, List.of(), List.of(unit)), SEEDED, tree);
    }

    /// An `Mvn` submit as the controller shapes it: the user's arguments and the unit they plan to.
    private static SubmitShape mvnShape(boolean envFieldPresent, String... args) {
        return SubmitShape.submitShape(envFieldPresent, ok(Recipe.mvn(List.of(args))), List.of(mvnUnit(args)));
    }

    private static Result<Unit> checkMvn(String... args) {
        return Refusals.checkSubmit(mvnShape(false, args), List.of(), TreeFacts.EMPTY);
    }

    private static void assertRefused(Result<Unit> result, String code) {
        assertThat(failure(result)).isInstanceOfSatisfying(Refusal.class,
                                                           refusal -> assertThat(refusal.code()).isEqualTo(code));
    }

    private static void assertAccepted(Result<Unit> result) {
        assertThat(ok(result)).isEqualTo(Unit.unit());
    }

    /// A unit with hand-written steps (for forms the planner never produces).
    private static UnitSpec rawUnit(List<String> step) {
        return rawUnit(step, Option.none());
    }

    /// A unit with a hand-written step and effective-POM step.
    private static UnitSpec rawUnit(List<String> step, Option<List<String>> pomArgv) {
        return UnitSpec.unitSpec(UnitName.MAIN,
                                 UnitKind.MVN,
                                 List.of(step),
                                 true,
                                 Duration.ofMinutes(60),
                                 ok(MemoryMb.memoryMb(6144)),
                                 false,
                                 pomArgv.map(argv -> ManifestSpec.manifestSpec(argv, Reach.TEST, Map.of(), Option.none(), Option.none())),
                                 SideEffects.NONE);
    }

    private static List<String> pomStep(String... middle) {
        return Stream.of(List.of("mvn", "-B", ManifestSpec.EFFECTIVE_POM_GOAL),
                                          List.of(middle),
                                          List.of(Planner.FORCED_REPO_LOCAL))
                                      .flatMap(List::stream)
                                      .toList();
    }

    @Nested
    class Env {
        @Test
        void checkSubmit_refusesREnv_whenEnvFieldPresent() {
            var shape = mvnShape(true, "-pl", "core", "verify");

            assertRefused(Refusals.checkSubmit(shape, SEEDED, TreeFacts.EMPTY), "R-ENV");
        }

        @Test
        void checkSubmit_accepts_whenEnvFieldAbsent() {
            var shape = mvnShape(false, "-pl", "core", "verify");

            assertAccepted(Refusals.checkSubmit(shape, SEEDED, TreeFacts.EMPTY));
        }

        @Test
        void containerEnv_emitsOnlyAllowListedKeys_evenWhenHostEnvHasTokens() {
            var host = Map.of("HCLOUD_TOKEN", "secret", "PATH", "/usr/bin", "JAVA_HOME", "/opt/java", "LANG", "C");
            var built = ContainerEnv.build(EnvInputs.envInputs("/opt/java/openjdk",
                                                               "/opt/maven/bin:/usr/bin",
                                                               "/ws/.home",
                                                               "192.0.2.18",
                                                               Option.some("-Xmx2g"),
                                                               Option.none()));

            assertThat(ContainerEnv.ALLOWED).containsAll(built.keySet());
            assertThat(built).containsEntry("TZ", "UTC")
                             .doesNotContainKey("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX");
            assertThat(ContainerEnv.filter(host)).containsOnlyKeys("PATH", "JAVA_HOME");
        }
    }

    @Nested
    class M2 {
        @ParameterizedTest
        @ValueSource(strings = {"-Dmaven.repo.local=/x", "-Dmaven.repo.local", "--define=maven.repo.local=/x", "--settings=s.xml"})
        void checkSubmit_refusesRM2_forSingleArgumentForms(String arg) {
            assertRefused(check(mvnUnit("-pl", "core", arg, "verify")), "R-M2");
        }

        @ParameterizedTest
        @ValueSource(strings = {"-s", "--settings", "-gs", "--global-settings"})
        void checkSubmit_refusesRM2_forSettingsFiles(String option) {
            assertRefused(check(mvnUnit("-pl", "core", option, "s.xml", "verify")), "R-M2");
        }

        @Test
        void checkSubmit_refusesRM2_forSeparatedDefine() {
            assertRefused(check(mvnUnit("-pl", "core", "-D", "maven.repo.local=/x", "verify")), "R-M2");
            assertRefused(check(mvnUnit("-pl", "core", "--define", "maven.repo.local=/x", "verify")), "R-M2");
        }

        @Test
        void checkSubmit_refusesRM2_forDefineWithSpaceInOneElement() {
            assertRefused(check(mvnUnit("-pl", "core", "-D maven.repo.local=/x", "verify")), "R-M2");
        }

        @Test
        void checkSubmit_refusesRM2_whenUserSuppliesThePlannersOwnToken() {
            assertRefused(check(mvnUnit("-pl", "core", "verify", Planner.FORCED_REPO_LOCAL)), "R-M2");
        }

        @Test
        void checkSubmit_accepts_thePlannersForcedTokenAlone() {
            var unit = mvnUnit("-pl", "core", "verify");

            assertThat(unit.steps()
                           .getFirst()).endsWith(Planner.FORCED_REPO_LOCAL);
            assertAccepted(check(unit));
        }

        /// Hand-built steps (no planner suffix): the trailing-token exemption must apply only to the exact
        /// planner-forced value, never to whatever happens to be last.
        @ParameterizedTest
        @ValueSource(strings = {"-Dmaven.repo.local=/x", "-Dmaven.repo.local", "--settings=s.xml"})
        void checkSubmit_refusesRM2_whenRawStepEndsWithNonForcedToken(String last) {
            assertRefused(check(rawUnit(List.of("mvn", "-pl", "core", "verify", last))), "R-M2");
        }

        @Test
        void checkSubmit_refusesRM2_whenRawStepEndsWithExtendedForcedToken() {
            assertRefused(check(rawUnit(List.of("mvn", "-pl", "core", "verify", Planner.FORCED_REPO_LOCAL + "/elsewhere"))), "R-M2");
        }

        @Test
        void checkSubmit_accepts_rawStepEndingWithExactForcedToken() {
            assertAccepted(check(rawUnit(List.of("mvn", "-pl", "core", "verify", Planner.FORCED_REPO_LOCAL))));
        }

        /// m1-design §2.2: the effective-POM step is judged too.
        @Test
        void checkSubmit_refusesRM2_inTheEffectivePomStep() {
            var clean = List.of("mvn", "-pl", "core", "verify", Planner.FORCED_REPO_LOCAL);

            assertRefused(check(rawUnit(clean, Option.some(pomStep("-s", "s.xml")))), "R-M2");
            assertRefused(check(rawUnit(clean, Option.some(pomStep("-Dmaven.repo.local=/x")))), "R-M2");
            assertAccepted(check(rawUnit(clean, Option.some(pomStep("-pl", "core")))));
        }

        @Test
        void checkSubmit_accepts_everyPlannedEffectivePomStep() {
            assertAccepted(check(gateUnit(), reactorOnly(WITHOUT_FORGE_TESTS)));
            assertAccepted(check(moduleTestUnit(List.of("core"), Option.some("A"), Option.some("B"))));
        }
    }

    @Nested
    class Shell {
        @ParameterizedTest
        @ValueSource(strings = {"exec:exec", "exec:java", "antrun:run", "exec:exec@run-it",
                                "org.codehaus.mojo:exec-maven-plugin:3.1.0:exec", "-Dexec.executable=/bin/sh"})
        void checkSubmit_refusesRShell_forShellCapableArguments(String arg) {
            assertRefused(check(mvnUnit("-pl", "core", arg)), "R-SHELL");
        }

        @Test
        void checkSubmit_refusesRShell_whenExecutableIsNotMvn() {
            assertRefused(check(rawUnit(List.of("sh", "-c", "mvn verify"))), "R-SHELL");
        }

        @Test
        void checkSubmit_accepts_plainMavenGoals() {
            assertAccepted(check(mvnUnit("-pl", "core", "dependency:tree", "verify")));
        }

        @Test
        void checkSubmit_refusesRShell_inTheEffectivePomStep() {
            var clean = List.of("mvn", "-pl", "core", "verify", Planner.FORCED_REPO_LOCAL);

            assertRefused(check(rawUnit(clean, Option.some(pomStep("exec:exec")))), "R-SHELL");
            assertRefused(check(rawUnit(clean, Option.some(List.of("sh", "-c", "mvn help:effective-pom")))), "R-SHELL");
        }
    }

    /// R-EFFECT (m4-design §5.3, rulings R-M4-14, Q-M4-11): an argv off the build-only allowlist is refused at submit, after
    /// R-SHELL in the list.
    @Nested
    class Effect {
        @ParameterizedTest
        @ValueSource(strings = {"deploy", "release:perform", "site:deploy", "org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy", "nexus-staging:deploy", "jreleaser:full-release"})
        void checkSubmit_refusesREffect_forPublishingGoals(String goal) {
            assertRefused(check(mvnUnit(goal)), "R-EFFECT");
        }

        @Test
        void checkSubmit_refusesREffect_forCleanDeployWithoutTests() {
            assertRefused(check(mvnUnit("-B", "clean", "deploy", "-DskipTests")), "R-EFFECT");
        }

        @Test
        void checkSubmit_detailNamesTheGoalAndTheStep() {
            assertThat(failure(check(mvnUnit("release:perform"))).message())
                .contains("goal 'release:perform' is not on the build-only allowlist in step [mvn, -B, release:perform, ");
        }

        /// R-SHELL is listed first, so a step that is both is R-SHELL.
        @Test
        void checkSubmit_refusesRShell_beforeREffect() {
            assertRefused(check(mvnUnit("exec:exec", "deploy")), "R-SHELL");
        }

        @Test
        void checkSubmit_accepts_installAndThePlannersOwnPlans() {
            assertAccepted(check(mvnUnit("-pl", "core", "install")));
            assertAccepted(check(gateUnit(), reactorOnly(WITHOUT_FORGE_TESTS)));
            assertAccepted(check(moduleTestUnit(List.of("core"), Option.some("A"), Option.some("B"))));
        }

        /// Q-M4-11b: a user `-D` key off the list is R-EFFECT, the detail naming it; a listed boolean takes no value,
        /// `true` or `false`; every define form is read.
        @ParameterizedTest
        @ValueSource(strings = {"-Drelease=true install",
                                "-Drelease install",
                                "-D release=true install",
                                "--define release=true install",
                                "--define=release=true install",
                                "-Doutput=/x help:effective-pom",
                                "-Dsurefire.reportsDirectory=/x verify",
                                "-Dmdep.outputFile=/x dependency:build-classpath",
                                "-DargLine=-javaagent:/x.jar test",
                                "-DadditionalClasspathElements=/x.jar pitest:mutationCoverage",
                                "-DskipTests=/x install",
                                "-Dsurefire install",
                                "-DSkipTests install",
                                "-Djbct.skip install",
                                "-Djbct.skip=true install",
                                "-Dmaven.test.failure.ignore=true verify",
                                "-Dmaven.test.failure.ignore verify"})
        void checkSubmit_refusesREffect_forAUserPropertyOffTheList(String args) {
            assertRefused(checkMvn(args.split(" ")), "R-EFFECT");
        }

        @Test
        void checkSubmit_detailNamesTheProperty() {
            assertThat(failure(checkMvn("-Drelease=true", "install")).message())
                .contains("property 'release' is not on the build-only allowlist in the submitted arguments [-Drelease=true, install]");
            assertThat(failure(checkMvn("-DskipTests=maybe", "install")).message())
                .contains("property 'skipTests' takes true or false, got 'maybe'");
        }

        /// maintainer ruling Q-M4-11c: jbct.skip and maven.test.failure.ignore turn a gate green while examining nothing, and
        /// additionalClasspathElements puts code from outside the workspace on the classpath: each is EXTERNAL, the
        /// refusal naming the key.
        @ParameterizedTest
        @ValueSource(strings = {"jbct.skip", "maven.test.failure.ignore", "additionalClasspathElements"})
        void checkSubmit_refusesTheRuledOutKeys_naming(String key) {
            var result = checkMvn("-D" + key + "=true", "verify");

            assertRefused(result, "R-EFFECT");
            assertThat(failure(result).message()).contains("property '" + key + "' is not on the build-only allowlist");
        }

        /// Verify4 blocker 3: `-fn` / `--fail-never` makes a failing build exit 0,
        /// hiding failed jbct checks and modules whose tests never ran: R-EFFECT, the detail naming the option.
        @ParameterizedTest
        @ValueSource(strings = {"-fn", "--fail-never"})
        void checkSubmit_refusesREffect_forFailNever_naming(String option) {
            var result = checkMvn("-B", option, "verify");

            assertRefused(result, "R-EFFECT");
            assertThat(failure(result).message()).contains("option '" + option + "' makes a failing build exit 0");
        }

        /// `-fae` still fails at the end and `-ff` fails fast: neither is refused.
        @ParameterizedTest
        @ValueSource(strings = {"-fae", "--fail-at-end", "-ff", "--fail-fast"})
        void checkSubmit_accepts_failingOptionsThatStillFail(String option) {
            assertAccepted(checkMvn("-B", option, "verify"));
        }

        /// maintainer ruling Q-M4-11d: an option off the list is R-EFFECT, the detail naming the token as written; on real
        /// Maven 3.9.12, `-fail-never` and `--fail-never=true` turn a failing build green, `--fail-` is ambiguous.
        @ParameterizedTest
        @ValueSource(strings = {"-fail-never", "--fail-never=true", "--fail-", "--fail-n", "--fail-neve", "-pl=core", "-ntp"})
        void checkSubmit_refusesREffect_forAnOptionOffTheList_naming(String option) {
            var result = checkMvn("-B", option, "verify");

            assertRefused(result, "R-EFFECT");
            assertThat(failure(result).message()).contains("option '" + option + "' is not on the build-only allowlist");
        }

        /// Verify4 blocker 1: a user `-D` value holding a control, whitespace or format character could forge or hide a
        /// result line, so it is R-EFFECT, the detail naming the key (not echoing the value).
        @ParameterizedTest
        @ValueSource(ints = {0x0a, 0x0d, 0x09, 0x20, 0xa0, 0x7f, 0x85, 0x1b, 0x2028, 0x2029, 0x202e, 0x200b, 0xfeff})
        void checkSubmit_refusesREffect_forAnUnprintableValue_naming(int codePoint) {
            var result = checkMvn("-Dtest=Foo" + Character.toString(codePoint) + "finished job=99 PASSED seq=7", "test");

            assertRefused(result, "R-EFFECT");
            assertThat(failure(result).message()).contains("property 'test' has a control, whitespace or format character in its value");
        }

        /// The same holds for a boolean key and for the separate `-D key=value` form; ordinary selector values pass.
        @Test
        void checkSubmit_refusesREffect_forAnUnprintableValue_inEveryForm() {
            assertRefused(checkMvn("-D", "skipTests=true\n", "install"), "R-EFFECT");
            assertRefused(checkMvn("--define", "it.test=A\nB", "verify"), "R-EFFECT");
            assertAccepted(checkMvn("-Dtest=Foo#bar+baz,*IT", "-Dgroups=a,b", "verify"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"-DskipTests install",
                                "-DskipTests=true install",
                                "-DskipTests=FALSE install",
                                "-D skipTests install",
                                "-D skipTests=true install",
                                "--define maven.test.skip install",
                                "-Dtest=Foo*Test -Dsurefire.failIfNoSpecifiedTests=false test",
                                "-Dit.test=FooIT -Dit.failIfNoSpecifiedTests=false verify",
                                "-DfailIfNoSpecifiedTests=false -DskipITs verify",
                                "-Dember.skipTests=true -DskipITs=false install",
                                "-Dgroups=slow -DexcludedGroups=fast test",
                                "-DtargetClasses=a.B* -DtargetTests=a.BTest pitest:mutationCoverage"})
        void checkSubmit_accepts_listedUserProperties(String args) {
            assertAccepted(checkMvn(args.split(" ")));
        }

        /// The planner's own `-D` tokens (`-Dmaven.repo.local=${ws}/.m2-local` on every step, the effective-POM step's
        /// `-Doutput` of a typed recipe) are not user input and are not judged as properties.
        @Test
        void checkSubmit_accepts_thePlannersOwnProperties() {
            var unit = mvnUnit("-pl", "core", "-Dtest=X", "verify");
            var moduleTest = ok(Recipe.moduleTest(List.of("core"), Option.some("A"), Option.some("B")));
            var moduleTestUnit = moduleTestUnit(List.of("core"), Option.some("A"), Option.some("B"));

            assertThat(unit.argvs()).allSatisfy(argv -> assertThat(argv).last().isEqualTo(Planner.FORCED_REPO_LOCAL));
            assertAccepted(checkMvn("-pl", "core", "-Dtest=X", "verify"));
            assertThat(moduleTestUnit.argvs()).anySatisfy(argv -> assertThat(String.join(" ", argv)).contains("-Doutput="));
            assertAccepted(Refusals.checkSubmit(SubmitShape.submitShape(false, moduleTest, List.of(moduleTestUnit)), List.of(), TreeFacts.EMPTY));
        }

        /// Q-M4-11c: `maven.test.failure.ignore` left the user list, but the rerun's own `-Dmaven.test.failure.ignore=true`
        /// (`Planner.RERUN_SELECTION`) is a planner token: a rerun is still accepted.
        @Test
        void checkSubmit_accepts_theRerunsOwnFailureIgnore() {
            var rerun = Recipe.rerun(ok(JobId.jobId(4711)),
                                     List.of(ok(Recipe.Rerun.Target.target("org.x.FooTest", "core", "surefire"))),
                                     Recipe.Rerun.Original.original(ok(JobId.jobId(4711)), "FAILED", Option.some("tests"), List.of()));
            var rerunUnit = plannedUnit(rerun, noPortGroup());

            assertThat(rerunUnit.argvs()).anySatisfy(argv -> assertThat(argv).contains("-Dmaven.test.failure.ignore=true"));
            assertAccepted(Refusals.checkSubmit(SubmitShape.submitShape(false, rerun, List.of(rerunUnit)), List.of(), TreeFacts.EMPTY));
        }

        /// The planner's steps are judged too, the effective-POM step included.
        @Test
        void checkSubmit_refusesREffect_inTheEffectivePomStep() {
            var clean = List.of("mvn", "-pl", "core", "verify", Planner.FORCED_REPO_LOCAL);

            assertRefused(check(rawUnit(clean, Option.some(pomStep("deploy")))), "R-EFFECT");
        }
    }

    /// M1 (m1-design §1.6): bb-hold holds the port group, so a unit that needs it is no longer refused.
    @Nested
    class PortGroup {
        @Test
        void checkSubmit_accepts_unitThatNeedsThePortGroup() {
            var unit = plannedUnit(ok(Recipe.mvn(List.of("-pl", "aether/ember", "test"))), portGroup("aether/ember"));

            assertThat(unit.needsPortGroup()).isTrue();
            assertAccepted(check(unit));
        }

        @Test
        void checkSubmit_accepts_gateThatNeedsThePortGroup() {
            var unit = plannedUnit(Recipe.gate(), portGroup("aether/ember"));

            assertThat(unit.needsPortGroup()).isTrue();
            assertAccepted(check(unit, reactorOnly(WITHOUT_FORGE_TESTS)));
        }
    }

    /// R-1553 (Q-3 ruling, maintainer-required cases): refuse only if the selection COULD run the class.
    @Nested
    class R1553 {
        @Test
        void checkSubmit_refuses_noSelectionInOwningModule() {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "verify")), "R-1553");
        }

        @ParameterizedTest
        @ValueSource(strings = {"*Test", "Stream*", "*Reshuffle*", CLASS, "**/" + CLASS + "*", "org.pragmatica.aether.forge." + CLASS,
                                CLASS + "#reshuffles", "Foo*," + CLASS})
        void checkSubmit_refuses_positiveItTestGlobThatMatches(String selection) {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dit.test=" + selection)), "R-1553");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Foo*", "!" + CLASS, "!**/" + CLASS + "*", "*Test,!" + CLASS, "Other*Test"})
        void checkSubmit_accepts_itTestSelectionThatCannotMatch(String selection) {
            assertAccepted(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dit.test=" + selection)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"!" + CLASS + "#reshuffles", "Stream*,!Stream*#reshuffles", "!" + CLASS + "$Nested",
                                "%regex[.*Reshuffle.*]", CLASS + "$Nested"})
        void checkSubmit_refuses_methodNestedAndRegexSelectionsThatLeaveTheClassRunnable(String selection) {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dit.test=" + selection)), "R-1553");
        }

        @Test
        void checkSubmit_refuses_whenItTestRunsItEvenIfTestExcludesIt() {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dtest=!" + CLASS, "-Dit.test=Stream*")), "R-1553");
        }

        @Test
        void checkSubmit_accepts_whenOnlyTestSelectsIt_becauseFailsafeIgnoresTest() {
            assertAccepted(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dtest=" + CLASS, "-Dit.test=Foo*")));
        }

        @Test
        void checkSubmit_refuses_whenTestExcludesItButNoItTestSelection() {
            // failsafe runs every IT: the -Dtest exclusion does not reach it (R-1553 precedes R-FAILSAFE)
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dtest=!" + CLASS)), "R-1553");
        }

        @Test
        void checkSubmit_accepts_whenFailsafePhaseIsNotReached() {
            assertAccepted(check(mvnUnit("-pl", FORGE_TESTS, "test", "-Dit.test=" + CLASS, "-Dtest=Foo*")));
        }

        @Test
        void checkSubmit_judgesBothProperties_whenRunnerUnknown() {
            var unknownRunner = List.<RefusalRule>of(RefusalRule.ExcludeTestClass.excludeTestClass("R-1553",
                                                                                                    CLASS,
                                                                                                    "#1553",
                                                                                                    Option.none()));
            var surefireOnly = mvnShape(false, "-pl", FORGE_TESTS, "test", "-Dtest=Stream*");
            var excluded = mvnShape(false, "-pl", FORGE_TESTS, "test", "-Dtest=!" + CLASS);

            assertRefused(Refusals.checkSubmit(surefireOnly, unknownRunner, tree(ENABLED_SOURCE)), "R-1553");
            assertAccepted(Refusals.checkSubmit(excluded, unknownRunner, tree(ENABLED_SOURCE)));
        }

        @Test
        void checkSubmit_refuses_wholeReactorAndAlsoMakeDependents() {
            assertRefused(check(mvnUnit("verify")), "R-1553");
            assertRefused(check(mvnUnit("-pl", "core", "-amd", "verify")), "R-1553");
        }

        @Test
        void checkSubmit_accepts_otherModuleOrExcludedModule() {
            assertAccepted(check(mvnUnit("-pl", "core", "verify")));
            assertAccepted(check(mvnUnit("-pl", "core,!" + FORGE_TESTS, "-amd", "verify")));
        }

        @Test
        void checkSubmit_accepts_whenClassIsDisabledWithTheMarker() {
            assertAccepted(check(mvnUnit("-pl", FORGE_TESTS, "verify"), tree(DISABLED_SOURCE)));
        }

        @Test
        void checkSubmit_refuses_whenTheDisableIsCommentedOut() {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "verify"), tree(COMMENTED_OUT_SOURCE)), "R-1553");
        }

        @Test
        void checkSubmit_accepts_whenTreeHasNoSuchClass() {
            assertAccepted(check(mvnUnit("-pl", FORGE_TESTS, "verify", "-Dit.test=*"), TreeFacts.EMPTY));
        }

        @Test
        void checkSubmit_accepts_moduleTestWithNonMatchingItTest() {
            assertAccepted(check(moduleTestUnit(List.of(FORGE_TESTS), Option.some("Foo*"), Option.some("Foo*IT"))));
        }

        @Test
        void checkSubmit_refuses_moduleTestWithMatchingItTest() {
            assertRefused(check(moduleTestUnit(List.of(FORGE_TESTS), Option.some("Foo*"), Option.some("Stream*"))), "R-1553");
        }

        /// Q-M1-4 / F5: the class lives in forge-tests, outside the default reactor, so a Gate cannot run it.
        @Test
        void checkSubmit_accepts_gate_whenTheReactorExcludesTheClassModule() {
            assertAccepted(check(gateUnit(), tree(ENABLED_SOURCE, Option.some(WITHOUT_FORGE_TESTS))));
        }

        @Test
        void checkSubmit_refuses_gate_whenTheReactorIsUnknownOrHoldsTheModule() {
            assertRefused(check(gateUnit(), tree(ENABLED_SOURCE)), "R-1553");
            assertRefused(check(gateUnit(), tree(ENABLED_SOURCE, Option.some(WITH_FORGE_TESTS))), "R-1553");
        }

        @Test
        void checkSubmit_judgesAsM0_whenAProfileOrPomFileChangesTheReactor() {
            var known = tree(ENABLED_SOURCE, Option.some(WITHOUT_FORGE_TESTS));

            assertRefused(check(mvnUnit("-Pwith-e2e", "verify"), known), "R-1553");
            assertRefused(check(mvnUnit("-f", "aether/pom.xml", "verify"), known), "R-1553");
            assertAccepted(check(mvnUnit("verify"), known));
        }
    }

    @Nested
    class Failsafe {
        @Test
        void checkSubmit_refusesRFailsafe_forTestWithoutItTest() {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "-Dtest=X", "test"), TreeFacts.EMPTY), "R-FAILSAFE");
        }

        @Test
        void checkSubmit_refusesRFailsafe_forArtifactIdAndFileForms() {
            assertRefused(check(mvnUnit("-pl", ":forge-tests", "-Dtest=X", "verify"), TreeFacts.EMPTY), "R-FAILSAFE");
            assertRefused(check(mvnUnit("-f", FORGE_TESTS + "/pom.xml", "-Dtest=X", "verify"), TreeFacts.EMPTY),
                          "R-FAILSAFE");
        }

        @Test
        void checkSubmit_refusesRFailsafe_forModuleTestWithTestOnly() {
            assertRefused(check(moduleTestUnit(List.of(FORGE_TESTS), Option.some("X"), Option.none()), TreeFacts.EMPTY),
                          "R-FAILSAFE");
        }

        @Test
        void checkSubmit_refusesRFailsafe_forDefineWithSpaceInOneElement() {
            assertRefused(check(mvnUnit("-pl", FORGE_TESTS, "-D test=X", "verify"), TreeFacts.EMPTY), "R-FAILSAFE");
        }

        @Test
        void checkSubmit_accepts_whenItTestIsPresentOrModuleDiffers() {
            assertAccepted(check(mvnUnit("-pl", FORGE_TESTS, "-Dtest=X", "-Dit.test=Y", "verify"), TreeFacts.EMPTY));
            assertAccepted(check(mvnUnit("-pl", "core", "-Dtest=X", "verify"), TreeFacts.EMPTY));
        }

        /// Q-M1-5: whole-reactor, `-am` and `-amd` steps are judged against the resolved reactor.
        @Test
        void checkSubmit_refusesRFailsafe_forReactorWideStepsThatMayBuildTheModule() {
            assertRefused(check(mvnUnit("-Dtest=X", "verify"), TreeFacts.EMPTY), "R-FAILSAFE");
            assertRefused(check(mvnUnit("-Dtest=X", "verify"), reactorOnly(WITH_FORGE_TESTS)), "R-FAILSAFE");
            assertRefused(check(mvnUnit("-pl", "core", "-am", "-Dtest=X", "verify"), reactorOnly(WITH_FORGE_TESTS)), "R-FAILSAFE");
            assertRefused(check(mvnUnit("-pl", "core", "-amd", "-Dtest=X", "test"), TreeFacts.EMPTY), "R-FAILSAFE");
        }

        @Test
        void checkSubmit_accepts_reactorWideStepsThatCannotBuildTheModule() {
            assertAccepted(check(mvnUnit("-Dtest=X", "verify"), reactorOnly(WITHOUT_FORGE_TESTS)));
            assertAccepted(check(mvnUnit("-pl", "core", "-am", "-Dtest=X", "verify"), reactorOnly(WITHOUT_FORGE_TESTS)));
            assertAccepted(check(mvnUnit("-pl", "!:forge-tests", "-Dtest=X", "verify"), TreeFacts.EMPTY));
            assertAccepted(check(mvnUnit("-Dtest=X", "-Dit.test=Y", "verify"), TreeFacts.EMPTY));
            assertAccepted(check(gateUnit(), TreeFacts.EMPTY));
        }
    }

    @Nested
    class Disk {
        private static final long FLOOR = 50L * 1024 * 1024 * 1024;

        @Test
        void disk_refusesRDisk_whenEitherFilesystemIsBelowFloor() {
            assertRefused(Refusals.disk(FLOOR - 1, FLOOR, FLOOR), "R-DISK");
            assertRefused(Refusals.disk(FLOOR, FLOOR - 1, FLOOR), "R-DISK");
        }

        @Test
        void disk_accepts_atOrAboveFloor() {
            assertAccepted(Refusals.disk(FLOOR, FLOOR, FLOOR));
        }
    }

    @Test
    void checkSubmit_reportsFirstFailingCheckInListOrder() {
        var shape = mvnShape(true, "-pl", "core", "-Dmaven.repo.local=/x", "exec:exec");

        assertRefused(Refusals.checkSubmit(shape, SEEDED, TreeFacts.EMPTY), "R-ENV");
    }
}
