package org.pragmatica.temper.domain.manifest;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.ManifestOutcome.Computed;
import org.pragmatica.temper.domain.manifest.ManifestOutcome.Unsupported;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.temper.domain.manifest.Poms.config;
import static org.pragmatica.temper.domain.manifest.Poms.failsafe;
import static org.pragmatica.temper.domain.manifest.Poms.failsafeAt;
import static org.pragmatica.temper.domain.manifest.Poms.install;
import static org.pragmatica.temper.domain.manifest.Poms.items;
import static org.pragmatica.temper.domain.manifest.Poms.module;
import static org.pragmatica.temper.domain.manifest.Poms.plugin;
import static org.pragmatica.temper.domain.manifest.Poms.surefire;
import static org.pragmatica.temper.domain.manifest.Poms.surefireAt;
import static org.pragmatica.temper.domain.manifest.SyntheticClass.JUNIT4_TEST;
import static org.pragmatica.temper.domain.manifest.SyntheticClass.PARAMETERIZED;
import static org.pragmatica.temper.domain.manifest.SyntheticClass.TEST;
import static org.pragmatica.temper.domain.manifest.SyntheticClass.testClass;
import static org.pragmatica.temper.domain.manifest.SyntheticClass.type;

/// The A12 unit tier (m1-design §9.2): the manifest rules over class files synthesised with
/// `java.lang.classfile` and read through the production reader, so no fixture can run as a temper test.
class ManifestRulesTest {
    private static final String TEST_FACTORY = "org.junit.jupiter.api.TestFactory";
    private static final String TEST_TEMPLATE = "org.junit.jupiter.api.TestTemplate";
    private static final String TEST_CASE = "junit.framework.TestCase";
    /// A raw `Stream`: a result type Jupiter accepts for a `@TestFactory`.
    private static final ClassDesc FACTORY_RESULT = ClassDesc.of("java.util.stream.Stream");

    private static ManifestSpec withTest(String test) {
        return ManifestSpec.manifestSpec(List.of("mvn"), Reach.INTEGRATION_TEST, Map.of("test", test), Option.some(test), Option.none());
    }

    /// One engine run: modules, their test classes (by module path), classes compiled elsewhere in the reactor,
    /// and the modules the build never reached.
    private static final class Run {
        private final List<PomModule> modules = new ArrayList<>();
        private final Map<String, List<SyntheticClass>> tests = new LinkedHashMap<>();
        private final List<SyntheticClass> reactorClasses = new ArrayList<>();
        private Set<String> notReached = Set.of();
        private ManifestSpec spec = install();

        Run module(PomModule module, SyntheticClass... classes) {
            modules.add(module);
            tests.put(module.path(), List.of(classes));
            return this;
        }

        Run reactor(SyntheticClass... classes) {
            reactorClasses.addAll(List.of(classes));
            return this;
        }

        Run notReached(String... paths) {
            notReached = Set.of(paths);
            return this;
        }

        Run spec(ManifestSpec value) {
            spec = value;
            return this;
        }

        ManifestOutcome outcome() {
            var byModule = tests.entrySet()
                                .stream()
                                .collect(Collectors.toMap(Map.Entry::getKey,
                                                          entry -> entry.getValue().stream().map(SyntheticClass::info).toList()));
            var everything = new ArrayList<ClassInfo>();
            byModule.values().forEach(everything::addAll);
            reactorClasses.stream().map(SyntheticClass::info).forEach(everything::add);

            return ManifestRules.manifest(modules, spec, byModule, ClassIndex.classIndex(everything), notReached);
        }

        Computed computed() {
            return switch (outcome()) {
                case Computed computed -> computed;
                case Unsupported unsupported -> fail("unexpected Unsupported: " + unsupported.reason());
            };
        }

        Set<String> classes() {
            return computed().entries().stream().map(ManifestEntry::className).collect(Collectors.toSet());
        }

        Set<String> optionalClasses() {
            return computed().optional().stream().map(ManifestEntry::className).collect(Collectors.toSet());
        }
    }

    @Nested
    class Selection {
        @Test
        void manifest_appliesIncludesExcludesAndGroups_perModule() {
            var classes = new Run().module(module("specs", surefire(items("includes", "**/*Spec"))),
                                           testClass("p.FooSpec"),
                                           testClass("p.BarTest"))
                                   .module(module("plain", surefire()),
                                           testClass("q.FooSpec"),
                                           testClass("q.BarTest"),
                                           testClass("q.Outer$InnerTest").memberOf("q.Outer"))
                                   .module(module("noslow", surefire(items("excludes", "**/Slow*"))),
                                           testClass("r.SlowTest"),
                                           testClass("r.FastTest"),
                                           testClass("r.Outer$StaticTest").memberOf("r.Outer"))
                                   .module(module("fast", surefire(config("groups", "fast"))),
                                           type("s.TaggedTest").taggedMethod("t", List.of("fast"), TEST),
                                           testClass("s.UntaggedTest"))
                                   .classes();

            // configured excludes replace the default **/*$*, so r's static member class is selected by its own path
            assertThat(classes).containsExactlyInAnyOrder("p.FooSpec", "q.BarTest", "r.FastTest", "r.Outer$StaticTest", "s.TaggedTest");
        }

        @Test
        void manifest_includesFailsafeClasses_onlyWhereFailsafeIsBound_withModuleAndRunner() {
            var entries = new Run().module(module("bound", surefire(), failsafe()),
                                           testClass("w.FooIT"),
                                           testClass("w.FooTest"))
                                   .module(module("unbound", surefire()),
                                           testClass("x.FooIT"),
                                           testClass("x.FooTest"))
                                   .computed()
                                   .entries();

            assertThat(entries).containsExactlyInAnyOrder(ManifestEntry.manifestEntry("bound", "w.FooIT", Runner.FAILSAFE),
                                                          ManifestEntry.manifestEntry("bound", "w.FooTest", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("unbound", "x.FooTest", Runner.SUREFIRE));
        }
    }

    @Nested
    class TestProperty {
        @Test
        void manifest_narrowsSurefireByTest_andFailsafeByItTest_toTheSelectedMethods() {
            var spec = ManifestSpec.manifestSpec(List.of("mvn"), Reach.INTEGRATION_TEST, Map.of(), Option.some("FooTest#fast*"), Option.some("BarIT"));
            var entries = new Run().module(module("m", surefire(), failsafe()),
                                           type("c.FooTest").method("fastOne", TEST).method("slow", TEST),
                                           type("c.SlowTest").method("slow", TEST),
                                           type("c.OtherTest").method("other", TEST),
                                           testClass("c.BarIT"),
                                           testClass("c.BazIT"))
                                   .spec(spec)
                                   .computed()
                                   .entries();

            assertThat(entries).containsExactlyInAnyOrder(ManifestEntry.manifestEntry("m", "c.FooTest", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("m", "c.BarIT", Runner.FAILSAFE));
        }

        @Test
        void manifest_dashDTestReplacesIncludesAndExcludes() {
            var module = module("m", surefire(Map.of("includes", ConfigValue.Items.items(List.of("**/*Spec")),
                                                     "excludes", ConfigValue.Items.items(List.of("**/Slow*")))));
            var classes = new Run().module(module, testClass("a.SlowTest"), testClass("a.FastSpec"))
                                   .spec(withTest("SlowTest"))
                                   .classes();

            assertThat(classes).containsExactly("a.SlowTest");
        }

        @Test
        void manifest_selectsANestedClass_throughItsOutermostClass_orItsOwnName() {
            var nested = new SyntheticClass[] {type("d.OuterTest"),
                                               testClass("d.OuterTest$Inner").nestedIn("d.OuterTest"),
                                               testClass("d.OtherTest$Inner").nestedIn("d.OtherTest"),
                                               type("d.OtherTest")};

            assertThat(new Run().module(module("m", surefire()), nested).spec(withTest("OuterTest")).classes())
                .containsExactly("d.OuterTest$Inner");
            assertThat(new Run().module(module("m", surefire()), nested).spec(withTest("OtherTest$Inner")).classes())
                .containsExactly("d.OtherTest$Inner");
        }

        @Test
        void manifest_selectsANestedClassByItsOwnPath_whenConfiguredExcludesAllowIt() {
            var classes = new Run().module(module("m", surefire(Map.of("includes", ConfigValue.Items.items(List.of("**/*Spec")),
                                                                       "excludes", ConfigValue.Items.items(List.of("**/Slow*"))))),
                                           type("e.Helper"),
                                           testClass("e.Helper$InnerSpec").nestedIn("e.Helper"))
                                   .classes();

            assertThat(classes).containsExactly("e.Helper$InnerSpec");
        }
    }

    /// Surefire 3.5.4 oracle scenarios (`tneg`, `tnestedm2`, `tnestedclassplusm`, `tnestedneg`, `tnegnestedm`,
    /// `tnestedown`, `tnestedownm`): `-Dtest` with only negations runs every class, and a method pattern anywhere
    /// makes surefire judge each method by its own class, never by the class it was discovered from.
    @Nested
    class TestPropertyAgainstSurefire {
        private SyntheticClass[] fixture() {
            return new SyntheticClass[] {type("o.ATest").method("a", TEST),
                                         type("o.BTest"),
                                         type("o.BTest$Inner").nestedIn("o.BTest").method("b", TEST),
                                         type("o.BTest$Inner$Deep").nestedIn("o.BTest$Inner").method("d", TEST),
                                         testClass("o.CTest"),
                                         testClass("o.CTest$Helper").memberOf("o.CTest"),
                                         testClass("o.MyIT"),
                                         testClass("o.Plain")};
        }

        private Set<String> selected(String test) {
            return new Run().module(module("m", surefire()), fixture()).spec(withTest(test)).classes();
        }

        @Test
        void manifest_onlyNegations_selectEveryOtherClass_includingMemberAndItClasses() {
            assertThat(selected("!ATest")).containsExactlyInAnyOrder("o.BTest$Inner",
                                                                     "o.BTest$Inner$Deep",
                                                                     "o.CTest",
                                                                     "o.CTest$Helper",
                                                                     "o.MyIT",
                                                                     "o.Plain");
        }

        @Test
        void manifest_aMethodPatternOnTheOuterClass_selectsNothingInItsNestedClasses() {
            assertThat(selected("BTest#b,ATest")).containsExactly("o.ATest");
            assertThat(selected("BTest#b")).isEmpty();
            // one method pattern anywhere filters every method, so the plain BTest no longer reaches its nested classes
            assertThat(selected("BTest,ATest#a")).containsExactly("o.ATest");
            assertThat(selected("BTest,!BTest#b")).isEmpty();
        }

        @Test
        void manifest_aNegatedMethodOnTheOuterClass_removesNothingFromItsNestedClasses() {
            assertThat(selected("!BTest#b")).contains("o.BTest$Inner", "o.BTest$Inner$Deep");
            assertThat(selected("!BTest")).contains("o.BTest$Inner", "o.BTest$Inner$Deep");
        }

        /// Oracle scenario `tnegown`: without a method pattern nothing filters methods, so `BTest$Inner` still runs
        /// as discovered from the scanned `BTest`.
        @Test
        void manifest_aNegatedNestedClass_stillRunsThroughItsEnclosingClass() {
            assertThat(selected("!BTest$Inner")).contains("o.BTest$Inner", "o.BTest$Inner$Deep");
        }

        @Test
        void manifest_aNestedClassNamedByItself_reachesItsOwnNestedClasses_unlessAMethodIsNamed() {
            assertThat(selected("BTest$Inner")).containsExactlyInAnyOrder("o.BTest$Inner", "o.BTest$Inner$Deep");
            assertThat(selected("BTest$Inner#b")).containsExactly("o.BTest$Inner");
            assertThat(selected("BTest*#b")).containsExactly("o.BTest$Inner");
        }

        @Test
        void manifest_regexWithoutTheClassSuffix_selectsNothing() {
            assertThat(selected("%regex[.*ATest]")).isEmpty();
            assertThat(selected("%regex[.*ATest.class]")).containsExactly("o.ATest");
        }

        /// Surefire 3.5.4 oracle scenarios `inc` and `incneg2`.
        @Test
        void manifest_aNegatedIncludeExcludesTheClass() {
            var classes = new Run().module(module("m", surefire(items("includes", "**/*Test, !CTest"))), fixture()).classes();

            assertThat(classes).containsExactlyInAnyOrder("o.ATest", "o.BTest$Inner", "o.BTest$Inner$Deep");
        }

        /// Surefire 3.5.4 oracle scenario `incm`: the build fails, so the manifest is Unsupported.
        @Test
        void manifest_isUnsupported_forAMethodFilterInAnInclude() {
            var outcome = new Run().module(module("m", surefire(items("includes", "CTest#test", "ATest"))), fixture()).outcome();

            assertThat(outcome).isInstanceOfSatisfying(Unsupported.class,
                                                       unsupported -> assertThat(unsupported.cause()).isInstanceOf(ManifestError.RejectedPattern.class));
        }
    }

    @Nested
    class Naming {
        @Test
        void manifest_namesNestedClassesByThemselves_andMatchesThemByTheOutermostClass() {
            var classes = new Run().module(module("m", surefire()),
                                           type("t.OuterTest"),
                                           testClass("t.OuterTest$Defaults").nestedIn("t.OuterTest"),
                                           testClass("t.OuterTest$Defaults$Deeper").nestedIn("t.OuterTest$Defaults"),
                                           testClass("t.MixedTest"),
                                           testClass("t.MixedTest$Inner").nestedIn("t.MixedTest"),
                                           type("t.Helper"),
                                           testClass("t.Helper$Inner").nestedIn("t.Helper"))
                                   .classes();

            // t.OuterTest only contains @Nested classes: not an entry; t.Helper does not match the includes
            assertThat(classes).containsExactlyInAnyOrder("t.OuterTest$Defaults",
                                                          "t.OuterTest$Defaults$Deeper",
                                                          "t.MixedTest",
                                                          "t.MixedTest$Inner");
        }

        /// JUnit discovers a `@Nested` class only through `@Nested` links: a static member class is not discovered
        /// from its enclosing class, so neither is a `@Nested` class inside it (oracle scenario `default`, `WHolderTest`).
        @Test
        void manifest_doesNotReachANestedClass_throughAStaticMemberClass() {
            var classes = new Run().module(module("m", surefire()),
                                           testClass("w.HolderTest"),
                                           type("w.HolderTest$Holder").memberOf("w.HolderTest"),
                                           testClass("w.HolderTest$Holder$X").nestedIn("w.HolderTest$Holder"))
                                   .classes();

            assertThat(classes).containsExactly("w.HolderTest");
        }

        /// Oracle scenarios `n2inh`, `n3concB`, `n3own`, `n3inhm`, `n3j4neg`: JUnit expands a test class into the
        /// `@Nested` classes declared in its superclasses too, and reports each under its own name
        /// (`N2Base$In` inside `TEST-o.N2InhNestedTest.xml`); an abstract enclosing class is no test class, so it
        /// discovers nothing, and a `@Nested` class selected by its own path needs it.
        @Test
        void manifest_reachesAnInheritedNestedClass_throughEveryConcreteSubclassOfItsEnclosingClass() {
            var run = new Run().module(module("m", surefire()),
                                       type("n.Base"),
                                       testClass("n.Base$In").nestedIn("n.Base"),
                                       type("n.InhTest").extending("n.Base"),
                                       type("n.AbsBaseTest").asAbstract(),
                                       testClass("n.AbsBaseTest$In").nestedIn("n.AbsBaseTest"),
                                       type("n.ConcTest").extending("n.AbsBaseTest"));

            assertThat(run.classes()).containsExactlyInAnyOrder("n.Base$In", "n.AbsBaseTest$In");
            assertThat(run.spec(withTest("InhTest")).classes()).containsExactly("n.Base$In");
            assertThat(run.spec(withTest("ConcTest")).classes()).containsExactly("n.AbsBaseTest$In");
            assertThat(run.spec(withTest("AbsBaseTest")).classes()).isEmpty();
            assertThat(run.spec(withTest("AbsBaseTest$In")).classes()).isEmpty();
            assertThat(run.spec(withTest("AbsBaseTest$In#test")).classes()).isEmpty();
            // the method filter judges by the nested class's own path; the class is scanned through its host
            assertThat(run.spec(withTest("!Other#m")).classes()).containsExactlyInAnyOrder("n.Base$In", "n.AbsBaseTest$In");
            assertThat(run.spec(withTest("ConcTest#test")).classes()).isEmpty();
        }

        /// R-6: host lookup follows superclasses across modules. A `@Nested` class that a subclass in module `b`
        /// reaches (through a base in `a`'s test-jar) is expected in `b`, and so is a `@Nested` class it reaches in
        /// turn; `a` expects them only through a host of its own.
        @Test
        void manifest_expectsANestedClass_inTheModuleWhoseSubclassReachesIt() {
            var base = new SyntheticClass[] {type("a.BaseTest").asAbstract(),
                                             testClass("a.BaseTest$In").nestedIn("a.BaseTest"),
                                             testClass("a.BaseTest$In$Deep").nestedIn("a.BaseTest$In")};

            assertThat(new Run().module(module("a", surefire()), base)
                                .module(module("b", surefire()), type("b.ConcreteTest").extending("a.BaseTest"))
                                .computed()
                                .entries()).extracting(ManifestEntry::module, ManifestEntry::className)
                                           .containsExactlyInAnyOrder(tuple("b", "a.BaseTest$In"), tuple("b", "a.BaseTest$In$Deep"));
            assertThat(new Run().module(module("a", surefire()), base[0], base[1], type("a.LocalTest").extending("a.BaseTest"))
                                .computed()
                                .entries()).extracting(ManifestEntry::module, ManifestEntry::className)
                                           .containsExactly(tuple("a", "a.BaseTest$In"));
        }

        /// Surefire selects no other module's class by its own path: `-Dtest=HostTest$In` runs it in `a` only,
        /// although `b`'s subclass would reach it.
        @Test
        void manifest_selectsAnotherModulesNestedClass_onlyThroughAHostOfThisModule() {
            var entries = new Run().module(module("a", surefire()),
                                           testClass("a.HostTest"),
                                           testClass("a.HostTest$In").nestedIn("a.HostTest"))
                                   .module(module("b", surefire()), type("b.SubTest").extending("a.HostTest"))
                                   .spec(withTest("HostTest$In"))
                                   .computed()
                                   .entries();

            assertThat(entries).extracting(ManifestEntry::module, ManifestEntry::className)
                               .containsExactly(tuple("a", "a.HostTest$In"));
        }

        /// Oracle scenarios `n3grp`, `n3exg`: the tags a `@Nested` class takes from its enclosing instance are those
        /// of the host that discovered it.
        @Test
        void manifest_aNestedClassTakesTheTagsOfTheHostThatDiscoversIt() {
            var fixture = new SyntheticClass[] {type("n.AbsBaseTest").asAbstract(),
                                                testClass("n.AbsBaseTest$In").nestedIn("n.AbsBaseTest"),
                                                type("n.PlainTest").extending("n.AbsBaseTest"),
                                                type("n.SlowTest").extending("n.AbsBaseTest").tagged("slow")};

            assertThat(new Run().module(module("m", surefire(config("groups", "slow"))), fixture).classes())
                .containsExactly("n.AbsBaseTest$In");
            assertThat(new Run().module(module("m", surefire(config("excludedGroups", "slow"))), fixture).classes())
                .containsExactly("n.AbsBaseTest$In");
            assertThat(new Run().module(module("m", surefire(config("groups", "slow"))), fixture).spec(withTest("PlainTest")).classes())
                .isEmpty();
        }

        /// Termination only: JUnit refuses an inner-class cycle, an outcome the engine does not model, so the class
        /// stays expected through its declared enclosing class.
        @Test
        void manifest_stopsAtAnInnerClassCycle() {
            var classes = new Run().module(module("m", surefire()),
                                           type("n.LoopTest"),
                                           testClass("n.LoopTest$In").nestedIn("n.LoopTest").extending("n.LoopTest"))
                                   .classes();

            assertThat(classes).containsExactly("n.LoopTest$In");
        }
    }

    @Nested
    class Kinds {
        @Test
        void manifest_admitsOnlyClassKindsJunitRuns() {
            var openExcludes = surefire(items("excludes", "**/Nothing*"));
            var classes = new Run().module(module("m", openExcludes),
                                           testClass("k.OuterTest"),
                                           testClass("k.OuterTest$StaticTest").memberOf("k.OuterTest"),
                                           testClass("k.OuterTest$StaticNestedTest").memberOf("k.OuterTest").annotated(SyntheticClass.NESTED),
                                           testClass("k.OuterTest$PlainInnerTest").innerOf("k.OuterTest"),
                                           testClass("k.OuterTest$PrivateTest").memberOf("k.OuterTest").asPrivateMember(),
                                           testClass("k.OuterTest$1LocalTest").asLocal())
                                   .classes();

            // a static class marked @Nested is an ordinary static member class to JUnit
            assertThat(classes).containsExactlyInAnyOrder("k.OuterTest", "k.OuterTest$StaticTest", "k.OuterTest$StaticNestedTest");
        }

        @Test
        void manifest_dropsAStaticNestedClass_underTheDefaultExcludes() {
            var classes = new Run().module(module("m", surefire()),
                                           type("k.OuterTest"),
                                           testClass("k.OuterTest$StaticNestedTest").memberOf("k.OuterTest").annotated(SyntheticClass.NESTED))
                                   .classes();

            assertThat(classes).isEmpty();
        }
    }

    @Nested
    class Groups {
        @Test
        void manifest_dropsAClass_onlyWhenEveryTestMethodIsExcluded() {
            var classes = new Run().module(module("m", surefire(config("excludedGroups", "slow"))),
                                           type("u.MixedTest").taggedMethod("slowOne", List.of("slow"), TEST).method("fastOne", TEST),
                                           type("u.AllSlowTest").taggedMethod("a", List.of("slow"), TEST)
                                                                .taggedMethod("b", List.of("slow", "db"), TEST),
                                           testClass("u.ClassSlowTest").tagged("slow"),
                                           testClass("u.ContainerTest").tagsContainer("db", "slow"),
                                           type("u.SlowBase").asAbstract().tagged("slow"),
                                           testClass("u.InheritedTagTest").extending("u.SlowBase"),
                                           type("u.SlowOuterTest").tagged("slow"),
                                           testClass("u.SlowOuterTest$Inner").nestedIn("u.SlowOuterTest"))
                                   .classes();

            assertThat(classes).containsExactly("u.MixedTest");
        }

        @Test
        void manifest_keepsOnlyClassesWithAMethodInGroups() {
            var classes = new Run().module(module("m", surefire(config("groups", "fast,smoke"))),
                                           testClass("v.FastClassTest").tagged("fast"),
                                           type("v.MixedTest").taggedMethod("a", List.of("smoke"), TEST).method("b", TEST),
                                           testClass("v.PlainTest"))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("v.FastClassTest", "v.MixedTest");
        }

        @Test
        void manifest_inheritsTagsFromInterfacesAndClassMetaAnnotations() {
            var classes = new Run().module(module("m", surefire(config("excludedGroups", "slow"))),
                                           testClass("g.ViaInterfaceTest").implementing("g.SlowContract"),
                                           testClass("g.ViaMetaTest").annotated("g.Slow"),
                                           testClass("g.PlainTest"))
                                   .reactor(type("g.SlowContract").asInterface().tagged("slow"),
                                            type("g.Slow").asAnnotation().tagged("slow"))
                                   .classes();

            assertThat(classes).containsExactly("g.PlainTest");
        }

        @Test
        void manifest_includesANestedClass_throughItsEnclosingClassTag() {
            var classes = new Run().module(module("m", surefire(config("groups", "fast"))),
                                           type("h.OuterTest").tagged("fast"),
                                           testClass("h.OuterTest$Inner").nestedIn("h.OuterTest"),
                                           type("h.PlainTest"),
                                           testClass("h.PlainTest$Inner").nestedIn("h.PlainTest"))
                                   .classes();

            assertThat(classes).containsExactly("h.OuterTest$Inner");
        }

        @Test
        void manifest_isUnsupported_forATagExpression() {
            var outcome = new Run().module(module("m", surefire(config("groups", "fast & !slow"))), testClass("v.FastTest"))
                                   .outcome();

            assertThat(outcome).isInstanceOfSatisfying(Unsupported.class,
                                                       unsupported -> {
                                                           assertThat(unsupported.cause()).isInstanceOf(ManifestError.UnsupportedTagExpression.class);
                                                           assertThat(unsupported.reason()).isEqualTo("Unsupported tag expression in m groups: fast & !slow");
                                                       });
        }
    }

    @Nested
    class Candidates {
        @Test
        void manifest_countsInheritedTests_inConcreteClassesOnly() {
            var classes = new Run().module(module("m", surefire()),
                                           type("y.AbstractBaseTest").asAbstract().method("test", TEST),
                                           type("y.ConcreteTest").extending("y.AbstractBaseTest"),
                                           type("y.DefaultsTest").implementing("y.Contract"),
                                           type("y.OverrideTest").extending("y.AbstractBaseTest").method("test"),
                                           type("y.StaticOnlyTest").staticMethod("test", TEST))
                                   .reactor(type("y.Contract").asInterface().method("test", TEST))
                                   .classes();

            // OverrideTest overrides the base test without @Test, so JUnit runs nothing there
            assertThat(classes).containsExactlyInAnyOrder("y.ConcreteTest", "y.DefaultsTest");
        }

        @Test
        void manifest_resolvesTestAnnotations_directlyAndThroughMetaAnnotations() {
            var fastTest = type("z.FastTest").asAnnotation().annotated(TEST).tagged("fast");
            var composed = type("z.Composed").asAnnotation().annotated("z.FastTest");
            var classes = new Run().module(module("m", surefire()),
                                           fastTest,
                                           composed,
                                           type("z.MetaTest").method("m", "z.FastTest"),
                                           type("z.DeepMetaTest").method("m", "z.Composed"),
                                           type("z.ParamTest").method("m", PARAMETERIZED),
                                           type("z.NoTestsTest").method("m", "org.junit.jupiter.api.BeforeEach"))
                                   .module(module("nofast", surefire(config("excludedGroups", "fast"))),
                                           type("z2.MetaTest").method("m", "z.FastTest"))
                                   .classes();

            // z2.MetaTest carries the meta-annotation's tag "fast", which its module excludes
            assertThat(classes).containsExactlyInAnyOrder("z.MetaTest", "z.DeepMetaTest", "z.ParamTest");
        }

        @Test
        void manifest_recordsUnresolvedAnnotations_andDoesNotGuessTheyAreTests() {
            var computed = new Run().module(module("m", surefire()),
                                            type("aa.VendorTest").method("m", "com.vendor.VendorTest", "org.junit.jupiter.api.DisplayName"),
                                            type("aa.Local").asAnnotation().annotated("com.vendor.Marker"),
                                            type("aa.LocalTest").method("m", "aa.Local").method("t", TEST))
                                    .computed();

            assertThat(computed.entries()).extracting(ManifestEntry::className).containsExactly("aa.LocalTest");
            assertThat(computed.unresolvedAnnotations()).containsExactlyInAnyOrder("com.vendor.VendorTest", "com.vendor.Marker");
        }

        @Test
        void manifest_recordsUnresolvedClassLevelAnnotations() {
            var computed = new Run().module(module("m", surefire()), testClass("ab.ExtendedTest").annotated("com.vendor.Extension"))
                                    .computed();

            assertThat(computed.unresolvedAnnotations()).containsExactly("com.vendor.Extension");
        }

        @Test
        void manifest_privateMethodDoesNotHideAnInheritedTest() {
            var classes = new Run().module(module("m", surefire()),
                                           type("ac.BaseTest").asAbstract().method("test", TEST),
                                           type("ac.SubTest").extending("ac.BaseTest").privateMethod("test"))
                                   .classes();

            assertThat(classes).containsExactly("ac.SubTest");
        }

        @Test
        void manifest_aNearerInterfaceDefaultBeatsTheSuperclassInterface() {
            // C extends B implements Near; B implements Far; Near extends Far and overrides test() without @Test
            var classes = new Run().module(module("m", surefire()),
                                           type("ad.BTest").implementing("ad.Far"),
                                           type("ad.CTest").extending("ad.BTest").implementing("ad.Near"))
                                   .reactor(type("ad.Far").asInterface().method("test", TEST),
                                            type("ad.Near").asInterface().implementing("ad.Far").method("test"))
                                   .classes();

            assertThat(classes).containsExactly("ad.BTest");
        }
    }

    @Nested
    class Optional {
        /// Surefire 3.5.4 oracle: `IFactoryTest` (an empty `@TestFactory`) reports no testcase, `UFactoryTest`
        /// (one dynamic test) does.
        @Test
        void manifest_listsAClassWithOnlyFactoryOrTemplateTests_asOptional() {
            var computed = new Run().module(module("m", surefire()),
                                            type("i.FactoryTest").methodReturning("f", FACTORY_RESULT, TEST_FACTORY),
                                            type("i.TemplateTest").method("t", TEST_TEMPLATE),
                                            type("i.ComposedFactoryTest").methodReturning("f", FACTORY_RESULT, "i.MyFactory"),
                                            type("i.MixedTest").methodReturning("f", FACTORY_RESULT, TEST_FACTORY).method("t", TEST),
                                            type("i.ParamTest").method("p", PARAMETERIZED))
                                    .reactor(type("i.MyFactory").asAnnotation().annotated(TEST_FACTORY))
                                    .computed();

            assertThat(computed.entries()).extracting(ManifestEntry::className).containsExactlyInAnyOrder("i.MixedTest", "i.ParamTest");
            assertThat(computed.optional()).extracting(ManifestEntry::className)
                                           .containsExactlyInAnyOrder("i.FactoryTest", "i.TemplateTest", "i.ComposedFactoryTest");
        }

        @Test
        void manifest_optionalClassesStillObeyThePatterns() {
            var run = new Run().module(module("m", surefire()), type("i.FactoryTest").methodReturning("f", FACTORY_RESULT, TEST_FACTORY), type("i.Factory").methodReturning("f", FACTORY_RESULT, TEST_FACTORY));

            assertThat(run.optionalClasses()).containsExactly("i.FactoryTest");
            assertThat(run.spec(withTest("Other")).optionalClasses()).isEmpty();
        }

        @Test
        void manifest_aClassSomeSelectionRequires_isNotOptional() {
            var noSlow = Execution.execution("no-slow", Option.some("test"), List.of("test"), config("excludedGroups", "slow"));
            var all = Execution.execution("all", Option.some("test"), List.of("test"), Map.of());
            var computed = new Run().module(module("m", plugin(Runner.SUREFIRE, noSlow, all)),
                                            type("i.MixedTest").methodReturning("f", FACTORY_RESULT, TEST_FACTORY).taggedMethod("t", List.of("slow"), TEST))
                                    .computed();

            assertThat(computed.entries()).extracting(ManifestEntry::className).containsExactly("i.MixedTest");
            assertThat(computed.optional()).isEmpty();
        }
    }

    /// Jupiter 5.13's method predicates (`IsTestMethod`, `IsTestTemplateMethod`, `IsTestFactoryMethod`; oracle
    /// scenarios `n2default` `N2NonVoidTest`, `n3fac`): a method whose result type its annotation rejects is
    /// skipped with a discovery warning, so it makes no class a candidate.
    @Nested
    class MethodValidity {
        private static final String REPEATED = "org.junit.jupiter.api.RepeatedTest";
        private static final ClassDesc LIST = ClassDesc.of("java.util.List");
        private static final ClassDesc STREAM = ClassDesc.of("java.util.stream.Stream");

        @Test
        void manifest_skipsATestOrTemplate_thatReturnsAValue() {
            var classes = new Run().module(module("m", surefire()),
                                           type("mv.NonVoidTest").methodReturning("x", ConstantDescs.CD_int, TEST),
                                           type("mv.NonVoidRepeatedTest").methodReturning("r", ConstantDescs.CD_int, REPEATED),
                                           type("mv.NonVoidParamTest").methodReturning("p", ConstantDescs.CD_String, PARAMETERIZED),
                                           type("mv.ComposedNonVoidTest").methodReturning("c", ConstantDescs.CD_int, "mv.FastTest"),
                                           type("mv.StaticParamTest").staticMethod("p", PARAMETERIZED),
                                           type("mv.PrivateTest").privateMethod("x", TEST),
                                           type("mv.MixedTest").methodReturning("x", ConstantDescs.CD_int, TEST).method("y", TEST))
                                   .reactor(type("mv.FastTest").asAnnotation().annotated(TEST))
                                   .classes();

            assertThat(classes).containsExactly("mv.MixedTest");
        }

        @Test
        void manifest_acceptsAFactory_onlyForADynamicNodeContainer() {
            var computed = new Run().module(module("m", surefire()),
                                            type("mv.ListTest").genericMethodReturning("f", LIST, "()Ljava/util/List<Lorg/junit/jupiter/api/DynamicTest;>;", TEST_FACTORY),
                                            type("mv.WildTest").genericMethodReturning("f", STREAM, "()Ljava/util/stream/Stream<+Lorg/junit/jupiter/api/DynamicNode;>;", TEST_FACTORY),
                                            type("mv.AnyTest").genericMethodReturning("f", STREAM, "()Ljava/util/stream/Stream<*>;", TEST_FACTORY),
                                            type("mv.RawTest").methodReturning("f", STREAM, TEST_FACTORY),
                                            type("mv.ArrayTest").methodReturning("f", ClassDesc.of("org.junit.jupiter.api.DynamicTest").arrayType(), TEST_FACTORY),
                                            type("mv.NodeTest").methodReturning("f", ClassDesc.of("org.junit.jupiter.api.DynamicContainer"), TEST_FACTORY),
                                            type("mv.ObjectTest").methodReturning("f", ConstantDescs.CD_Object, TEST_FACTORY),
                                            type("mv.VendorTest").methodReturning("f", ClassDesc.of("com.vendor.Nodes"), TEST_FACTORY),
                                            type("mv.StringTest").methodReturning("f", ConstantDescs.CD_String, TEST_FACTORY),
                                            type("mv.VoidTest").method("f", TEST_FACTORY),
                                            type("mv.IntTest").methodReturning("f", ConstantDescs.CD_int, TEST_FACTORY),
                                            type("mv.StringsTest").genericMethodReturning("f", STREAM, "()Ljava/util/stream/Stream<Ljava/lang/String;>;", TEST_FACTORY),
                                            type("mv.SuperTest").genericMethodReturning("f", LIST, "()Ljava/util/List<-Lorg/junit/jupiter/api/DynamicTest;>;", TEST_FACTORY),
                                            type("mv.ObjectsTest").genericMethodReturning("f", LIST, "()Ljava/util/List<Ljava/lang/Object;>;", TEST_FACTORY),
                                            type("mv.StringArrayTest").methodReturning("f", ConstantDescs.CD_String.arrayType(), TEST_FACTORY),
                                            type("mv.ExtendsObjectTest").genericMethodReturning("f", STREAM, "()Ljava/util/stream/Stream<+Ljava/lang/Object;>;", TEST_FACTORY),
                                            type("mv.ObjectArrayTest").methodReturning("f", ConstantDescs.CD_Object.arrayType(), TEST_FACTORY),
                                            // unclassified, so compatible: a java.lang subpackage type and a JDK type outside the lists
                                            type("mv.ReflectTest").methodReturning("f", ClassDesc.of("java.lang.reflect.Method"), TEST_FACTORY),
                                            type("mv.MapTest").methodReturning("f", ClassDesc.of("java.util.Map"), TEST_FACTORY),
                                            // the throws clause of the generic signature is not part of the result type
                                            type("mv.ThrowsTest").genericMethodReturning("f", LIST, "()Ljava/util/List<Lorg/junit/jupiter/api/DynamicTest;>;^Ljava/lang/Exception;", TEST_FACTORY))
                                    .computed();

            assertThat(computed.entries()).isEmpty();
            assertThat(computed.optional()).extracting(ManifestEntry::className)
                                           .containsExactlyInAnyOrder("mv.ListTest",
                                                                      "mv.WildTest",
                                                                      "mv.AnyTest",
                                                                      "mv.RawTest",
                                                                      "mv.ArrayTest",
                                                                      "mv.NodeTest",
                                                                      "mv.ObjectTest",
                                                                      "mv.VendorTest",
                                                                      "mv.ExtendsObjectTest",
                                                                      "mv.ObjectArrayTest",
                                                                      "mv.ReflectTest",
                                                                      "mv.MapTest",
                                                                      "mv.ThrowsTest");
        }
    }

    /// The reported name (T1 2026-10-14; oracle `ManifestOracleIT`, fixture module `display` and `DisplayIT`): surefire
    /// 3.5.4 writes a class's own `@DisplayName` value as the `<testcase classname>`, raw; otherwise its binary name.
    /// Every module here declares 3.5.4 ([Poms#VERSION]); other versions are [PluginVersions].
    @Nested
    class DisplayNames {
        private static final String REPLACE_UNDERSCORES = "org.junit.jupiter.api.DisplayNameGenerator$ReplaceUnderscores";

        @Test
        void manifest_namesATopLevelClass_byItsDisplayName_andItsPlainNestedClass_byItsBinaryName() {
            var classes = new Run().module(module("m", surefire()),
                                           testClass("d.TopTest").displayName("Top level shown"),
                                           testClass("d.TopTest$Plain").nestedIn("d.TopTest"))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("Top level shown", "d.TopTest$Plain");
        }

        /// pragmatica `VerifyTest$VerifyMethodsTest` reports as `Verify class tests` (T1 missing=3 drift=3).
        @Test
        void manifest_namesANestedClass_byItsDisplayName_evenOneEqualToItsSimpleName() {
            var classes = new Run().module(module("m", surefire()),
                                           testClass("d.OuterTest"),
                                           testClass("d.OuterTest$Shown").nestedIn("d.OuterTest").displayName("Verify class tests"),
                                           testClass("d.OuterTest$Same").nestedIn("d.OuterTest").displayName("Same"),
                                           testClass("d.OuterTest$Plain").nestedIn("d.OuterTest"))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("d.OuterTest", "Verify class tests", "Same", "d.OuterTest$Plain");
        }

        @Test
        void manifest_namesEachLevelOfNesting_byItsOwnDisplayNameOnly() {
            var classes = new Run().module(module("m", surefire()),
                                           type("d.OuterTest"),
                                           testClass("d.OuterTest$L1").nestedIn("d.OuterTest").displayName("Level one"),
                                           testClass("d.OuterTest$L1$L2").nestedIn("d.OuterTest$L1").displayName("Level two"),
                                           testClass("d.OuterTest$L1$L2Plain").nestedIn("d.OuterTest$L1"),
                                           testClass("d.OuterTest$M1").nestedIn("d.OuterTest"),
                                           testClass("d.OuterTest$M1$M2").nestedIn("d.OuterTest$M1").displayName("Deep shown"))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("Level one", "Level two", "d.OuterTest$L1$L2Plain", "d.OuterTest$M1", "Deep shown");
        }

        @Test
        void manifest_keepsADisplayNameRaw_specialCharactersAndPaddingIncluded() {
            var classes = new Run().module(module("m", surefire()),
                                           type("d.OuterTest"),
                                           testClass("d.OuterTest$Special").nestedIn("d.OuterTest").displayName("a.b$c d ü ✓ <&\"x\""),
                                           testClass("d.OuterTest$Padded").nestedIn("d.OuterTest").displayName(" Padded name "))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("a.b$c d ü ✓ <&\"x\"", " Padded name ");
        }

        /// surefire reads `@DisplayName` by reflection on the class itself: a generator's names, an inherited
        /// annotation (it is not `@Inherited`) and one reached through a meta-annotation never rename the class.
        @Test
        void manifest_ignoresGeneratedInheritedAndMetaAnnotatedDisplayNames() {
            var classes = new Run().module(module("m", surefire()),
                                           testClass("d.Gen_ClassTest").displayNameGeneration(REPLACE_UNDERSCORES),
                                           testClass("d.Gen_ClassTest$Inner_Gen").nestedIn("d.Gen_ClassTest"),
                                           testClass("d.GenNestedTest"),
                                           testClass("d.GenNestedTest$Under_Score").nestedIn("d.GenNestedTest").displayNameGeneration(REPLACE_UNDERSCORES),
                                           type("d.ShownBase").asAbstract().displayName("Base shown").method("base", TEST),
                                           type("d.SubOfShownTest").extending("d.ShownBase"),
                                           testClass("d.GenNestedTest$Meta").nestedIn("d.GenNestedTest").annotated("d.MetaShown"))
                                   .reactor(type("d.MetaShown").asAnnotation().displayName("Meta shown"))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("d.Gen_ClassTest",
                                                          "d.Gen_ClassTest$Inner_Gen",
                                                          "d.GenNestedTest",
                                                          "d.GenNestedTest$Under_Score",
                                                          "d.SubOfShownTest",
                                                          "d.GenNestedTest$Meta");
        }

        @Test
        void manifest_namesAFailsafeNestedClass_byItsDisplayName_withItsRunner() {
            var entries = new Run().module(module("m", surefire(), failsafe()),
                                           testClass("d.DisplayIT"),
                                           testClass("d.DisplayIT$Shown").nestedIn("d.DisplayIT").displayName("Integration shown"))
                                   .computed()
                                   .entries();

            assertThat(entries).containsExactlyInAnyOrder(ManifestEntry.manifestEntry("m", "d.DisplayIT", Runner.FAILSAFE),
                                                          ManifestEntry.manifestEntry("m", "Integration shown", Runner.FAILSAFE));
        }

        @Test
        void manifest_namesParameterizedAndFactoryTests_byTheirClassDisplayName() {
            var computed = new Run().module(module("m", surefire()),
                                            type("d.OuterTest"),
                                            type("d.OuterTest$Generated").nestedIn("d.OuterTest").displayName("Generated inside").method("param", PARAMETERIZED),
                                            type("d.OuterTest$Factory").nestedIn("d.OuterTest").displayName("Factory inside")
                                                                       .methodReturning("f", FACTORY_RESULT, TEST_FACTORY))
                                    .computed();

            assertThat(computed.entries()).extracting(ManifestEntry::className).containsExactly("Generated inside");
            assertThat(computed.optional()).extracting(ManifestEntry::className).containsExactly("Factory inside");
        }

        /// m1-design §2.4 rule 5 (main-session ruling): a class's own blank `@DisplayName` would report an empty
        /// classname, which the protocol refuses, so the whole manifest is Unsupported, naming module and class.
        @Test
        void manifest_isUnsupported_forAnEmptyDisplayName_onATopLevelClass() {
            assertBlank(new Run().module(module("m", surefire()),
                                         testClass("d.ShownTest").displayName("Shown"),
                                         testClass("d.EmptyTest").displayName("")),
                        "d.EmptyTest");
        }

        @Test
        void manifest_isUnsupported_forAWhitespaceOnlyDisplayName_onANestedClass() {
            assertBlank(new Run().module(module("m", surefire()),
                                         testClass("d.OuterTest").displayName("Outer shown"),
                                         testClass("d.OuterTest$Spaces").nestedIn("d.OuterTest").displayName(" \t\n ")),
                        "d.OuterTest$Spaces");
            assertBlank(new Run().module(module("m", surefire()),
                                         type("d.OuterTest"),
                                         testClass("d.OuterTest$Empty").nestedIn("d.OuterTest").displayName("")),
                        "d.OuterTest$Empty");
        }

        /// Verification F-A (2026-10-14): an em space, an ideographic space or a line separator survives JUnit's
        /// `trim()`, but the protocol refuses it as an entry name (`Character::isWhitespace`), and a refused entry drops
        /// the whole manifest (`computed: false`). The manifest judges blank as both sides do, so it is Unsupported.
        @Test
        void manifest_isUnsupported_forADisplayNameOnlyTheProtocolJudgesBlank() {
            for (var name : List.of("\u2003", "\u3000", "\u2028", " \u2003 ")) {
                assertBlank(new Run().module(module("m", surefire()),
                                             testClass("d.EmSpaceTest").displayName(name)),
                            "d.EmSpaceTest");
            }
        }

        /// A no-break space is blank to neither side (`trim()` keeps it, `Character::isWhitespace` excludes it), so
        /// the class is named by it.
        @Test
        void manifest_namesAClass_byADisplayNameNeitherSideJudgesBlank() {
            var classes = new Run().module(module("m", surefire()),
                                           testClass("d.NoBreakTest").displayName("\u00A0"))
                                   .classes();

            assertThat(classes).containsExactly("\u00A0");
        }

        /// Verification F-C (2026-10-14): two classes with one `@DisplayName` report under one `classname`, so one
        /// class's report would satisfy both entries; the manifest is Unsupported, naming the name and both classes.
        @Test
        void manifest_isUnsupported_forTwoClassesSharingADisplayName() {
            assertShared(new Run().module(module("m", surefire()),
                                          type("d.ATest"),
                                          testClass("d.ATest$Fast").nestedIn("d.ATest").displayName("Fast path"),
                                          type("d.BTest"),
                                          testClass("d.BTest$Fast").nestedIn("d.BTest").displayName("Fast path")),
                         "Fast path",
                         "d.ATest$Fast, d.BTest$Fast");
        }

        /// A display name equal to another class's binary name is the same collision.
        @Test
        void manifest_isUnsupported_forADisplayNameEqualToAnotherClassesBinaryName() {
            assertShared(new Run().module(module("m", surefire()),
                                          testClass("d.PlainTest"),
                                          testClass("d.ShownTest").displayName("d.PlainTest")),
                         "d.PlainTest",
                         "d.PlainTest, d.ShownTest");
        }

        /// Judged over every runnable test class of the selected modules, not only the expected ones: a shard whose
        /// `test` selects one of the two would otherwise compute a manifest its sibling shard's rows contradict, and
        /// the shared name would read as one class reported by two units (`ERROR(plan)`). Across modules too, since a
        /// verdict matches rows by `classname` alone.
        @Test
        void manifest_isUnsupported_forASharedDisplayName_inAShard_andAcrossModules() {
            assertShared(new Run().module(module("m", surefire()),
                                          testClass("d.ATest").displayName("Same"),
                                          testClass("d.BTest").displayName("Same"))
                                  .spec(withTest("ATest")),
                         "Same",
                         "d.ATest, d.BTest");
            assertShared(new Run().module(module("a", surefire()),
                                          testClass("a.OneTest").displayName("Same"))
                                  .module(module("b", surefire()),
                                          testClass("b.TwoTest").displayName("Same")),
                         "Same",
                         "a.OneTest, b.TwoTest");
        }

        /// A class without a test method reports nothing, so its display name collides with nothing.
        @Test
        void manifest_ignoresASharedDisplayName_ofAClassWithoutTests() {
            var classes = new Run().module(module("m", surefire()),
                                           testClass("d.ATest").displayName("Same"),
                                           type("d.Helper").displayName("Same"))
                                   .classes();

            assertThat(classes).containsExactly("Same");
        }

        private static void assertShared(Run run, String reportedName, String classNames) {
            assertThat(run.outcome()).isInstanceOfSatisfying(Unsupported.class,
                                                             unsupported -> {
                                                                 assertThat(unsupported.cause()).isInstanceOf(ManifestError.SharedDisplayName.class);
                                                                 assertThat(unsupported.reason())
                                                                     .isEqualTo("Classes share @DisplayName '" + reportedName + "', which is not supported: "
                                                                                + classNames);
                                                             });
        }

        private static void assertBlank(Run run, String className) {
            assertThat(run.outcome()).isInstanceOfSatisfying(Unsupported.class,
                                                             unsupported -> {
                                                                 assertThat(unsupported.cause()).isInstanceOf(ManifestError.BlankDisplayName.class);
                                                                 assertThat(unsupported.reason())
                                                                     .isEqualTo("Blank @DisplayName is not supported: module m, class " + className);
                                                             });
        }
    }

    /// The reported name follows the selection's own plugin version ([ReportNaming]; T1 2026-10-14 run 2: pragmatica's
    /// `jbct` pins surefire 3.5.2, which writes `CstLinterTest$X` where 3.5.4 writes the class's `@DisplayName`).
    /// Oracle: `ManifestOracleIT`, fixture modules `display` (3.5.4), `display352` and `display353`.
    @Nested
    class PluginVersions {
        private static Set<String> classesUnder(PluginUse plugin) {
            return new Run().module(module("m", plugin),
                                    testClass("d.TopTest").displayName("Top shown"),
                                    testClass("d.TopTest$Inner").nestedIn("d.TopTest").displayName("Inner shown"),
                                    testClass("d.TopTest$Plain").nestedIn("d.TopTest"))
                            .classes();
        }

        @Test
        void manifest_namesEveryClassByItsBinaryName_from300To353_andAt360() {
            for (var version : List.of("3.0.0", "3.2.5", "3.5.2", "3.5.3", "3.6.0")) {
                assertThat(classesUnder(surefireAt(version))).as(version)
                                                             .containsExactlyInAnyOrder("d.TopTest", "d.TopTest$Inner", "d.TopTest$Plain");
            }
        }

        @Test
        void manifest_namesAClassByItsOwnDisplayName_from354To356() {
            for (var version : List.of("3.5.4", "3.5.5", "3.5.6")) {
                assertThat(classesUnder(surefireAt(version))).as(version)
                                                             .containsExactlyInAnyOrder("Top shown", "Inner shown", "d.TopTest$Plain");
            }
        }

        /// pragmatica's shape: one module on 3.5.2 next to one on 3.5.4 in one reactor, and failsafe on another version
        /// than surefire in one module; each selection is named by its own plugin.
        @Test
        void manifest_namesEachSelection_byItsOwnPluginVersion() {
            var entries = new Run().module(module("old", surefireAt("3.5.2")),
                                           testClass("o.OldTest").displayName("Old shown"),
                                           testClass("o.OldTest$In").nestedIn("o.OldTest").displayName("Old inner"))
                                   .module(module("new", surefireAt("3.5.4")),
                                           testClass("n.NewTest").displayName("New shown"),
                                           testClass("n.NewTest$In").nestedIn("n.NewTest").displayName("New inner"))
                                   .module(module("mixed", surefireAt("3.5.4"), failsafeAt("3.5.2")),
                                           testClass("x.ShownTest").displayName("Unit shown"),
                                           testClass("x.ShownIT").displayName("IT shown"))
                                   .computed()
                                   .entries();

            assertThat(entries).containsExactlyInAnyOrder(ManifestEntry.manifestEntry("old", "o.OldTest", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("old", "o.OldTest$In", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("new", "New shown", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("new", "New inner", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("mixed", "Unit shown", Runner.SUREFIRE),
                                                          ManifestEntry.manifestEntry("mixed", "x.ShownIT", Runner.FAILSAFE));
        }

        /// A plugin that reports binary names never reports a blank or shared display name, so neither refuses it.
        @Test
        void manifest_acceptsBlankAndSharedDisplayNames_underABinaryNamingVersion() {
            var classes = new Run().module(module("m", surefireAt("3.5.2")),
                                           testClass("d.EmptyTest").displayName(""),
                                           testClass("d.ATest").displayName("Same"),
                                           testClass("d.BTest").displayName("Same"))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("d.EmptyTest", "d.ATest", "d.BTest");
        }

        /// Fail closed: a version outside the measured bands is never guessed, however close.
        @Test
        void manifest_isUnsupported_forAnUnmeasuredOrUnparseableVersion() {
            for (var version : List.of("2.22.2", "3.0.0-M7", "3.6.0-M1", "3.5.7", "3.6.1", "4.0.0", "3.5", "3.5.4.1", "${surefire.version}")) {
                assertVersion(new Run().module(module("m", surefireAt(version)), testClass("d.ATest")),
                              "maven-surefire-plugin " + version);
            }
            assertVersion(new Run().module(module("m", surefire(), failsafeAt("3.4.1-SNAPSHOT")), testClass("d.ATest")),
                          "maven-failsafe-plugin 3.4.1-SNAPSHOT");
        }

        @Test
        void manifest_isUnsupported_forAPluginWithoutAVersion() {
            var unversioned = PluginUse.pluginUse(Runner.SUREFIRE, Option.none(), surefire().executions());

            assertVersion(new Run().module(module("m", unversioned), testClass("d.ATest")), "maven-surefire-plugin (no version)");
        }

        /// A skipped execution expects nothing, so its version refuses nothing.
        @Test
        void manifest_ignoresTheVersion_ofASkippedExecution() {
            var classes = new Run().module(module("m", surefire()), testClass("d.ATest"))
                                   .module(module("old", surefireAt("2.22.2", config("skipTests", "true"))), testClass("o.OldTest"))
                                   .classes();

            assertThat(classes).containsExactly("d.ATest");
        }

        private static void assertVersion(Run run, String pluginAndVersion) {
            assertThat(run.outcome()).isInstanceOfSatisfying(Unsupported.class,
                                                             unsupported -> {
                                                                 assertThat(unsupported.cause()).isInstanceOf(ManifestError.UnsupportedPluginVersion.class);
                                                                 assertThat(unsupported.reason())
                                                                     .isEqualTo("Unsupported test plugin version in m: " + pluginAndVersion);
                                                             });
        }
    }

    /// R-11: the rules model Jupiter only. A selected module whose test classes are JUnit 3 (the superclass chain
    /// reaches `junit.framework.TestCase`) or JUnit 4 (an `org.junit.Test` method, `@RunWith`) is refused, never
    /// guessed.
    @Nested
    class JupiterOnly {
        @Test
        void manifest_isUnsupported_forATestCaseSubclass_directlyOrThroughABase() {
            assertLegacy(new Run().module(module("m", surefire()), type("j.DirectTest").extending(TEST_CASE).method("testR")), "j.DirectTest");
            assertLegacy(new Run().module(module("m", surefire()), type("j.ViaBaseTest").extending("j.Base").method("testY"))
                                  .reactor(type("j.Base").asAbstract().extending(TEST_CASE)),
                         "j.ViaBaseTest");
        }

        @Test
        void manifest_isUnsupported_forAJunit4TestClass_ownOrInherited() {
            assertLegacy(new Run().module(module("m", surefire()), type("j4.ValidTest").method("ok", JUNIT4_TEST), type("j4.JupiterTest").method("t", TEST)),
                         "j4.ValidTest");
            assertLegacy(new Run().module(module("m", surefire()), type("j4.SubTest").extending("j4.Base").method("t", TEST))
                                  .reactor(type("j4.Base").asAbstract().method("old", JUNIT4_TEST)),
                         "j4.SubTest");
        }

        @Test
        void manifest_isUnsupported_forARunWithClass_ownOrInherited() {
            assertLegacy(new Run().module(module("m", surefire()), type("r.EnclosedTest").runWith("org.junit.experimental.runners.Enclosed")),
                         "r.EnclosedTest");
            assertLegacy(new Run().module(module("m", surefire()), type("r.InheritsRunnerTest").extending("r.CustomBase").method("t", TEST))
                                  .reactor(type("r.CustomBase").asAbstract().runWith("r.MyRunner")),
                         "r.InheritsRunnerTest");
        }

        /// Oracle scenario `vintagepublic`, its Jupiter half: Jupiter runs a package-private test class, so it is
        /// expected like a public one.
        @Test
        void manifest_expectsAPackagePrivateJupiterClass() {
            var classes = new Run().module(module("m", surefire()),
                                           type("j.HiddenJupiterTest").asPackagePrivate().method("a", TEST),
                                           type("j.PublicJupiterTest").method("a", TEST))
                                   .classes();

            assertThat(classes).containsExactlyInAnyOrder("j.HiddenJupiterTest", "j.PublicJupiterTest");
        }

        /// Only a module some selection runs is judged: a JUnit 3 class in a module no selection covers refuses
        /// nothing, and an unknown superclass (which may or may not be JUnit 3) is not guessed to be one. This pins
        /// the R-11 limit (m1-design §14): superclasses outside the reactor are not followed, so `j.VendorTest` is left
        /// out of the Computed manifest even if `com.vendor.Base` reaches `TestCase`; JUnit 3 `suite()` classes are not detected
        /// either.
        @Test
        void manifest_judgesOnlySelectedModules_andNoUnknownSuperclass() {
            var computed = new Run().module(module("m", surefire()),
                                            type("j.JupiterTest").method("t", TEST),
                                            type("j.VendorTest").extending("com.vendor.Base").method("testV"))
                                    .module(module("other", surefire(config("skipTests", "true"))),
                                            type("o.DirectTest").extending(TEST_CASE).method("testR"))
                                    .computed();

            assertThat(computed.entries()).extracting(ManifestEntry::className).containsExactly("j.JupiterTest");
        }

        private static void assertLegacy(Run run, String className) {
            assertThat(run.outcome()).isInstanceOfSatisfying(Unsupported.class,
                                                             unsupported -> assertThat(unsupported.reason())
                                                                 .isEqualTo("JUnit 3/4 tests are not supported (Jupiter only): module m, class " + className));
        }
    }

    @Nested
    class NotReached {
        @Test
        void manifest_listsNotReachedModules_thatASelectionCovers() {
            var computed = new Run().module(module("reached", surefire()), testClass("bb.FooTest"))
                                    .module(module("crashed", surefire()))
                                    .module(module("skipped", surefire(config("skipTests", "true"))))
                                    .notReached("crashed", "skipped")
                                    .computed();

            assertThat(computed.notReached()).containsExactly("crashed");
            assertThat(computed.entries()).extracting(ManifestEntry::className).containsExactly("bb.FooTest");
        }
    }
}
