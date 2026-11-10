package org.pragmatica.temper.acceptance.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.acceptance.stack.DockerAvailable;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;

import static org.assertj.core.api.Assertions.assertThat;

/// A12 (m1-design §9.2), docker tier, through the real controller and the real worker (integration finding F2): the
/// fixture project (`manifest-fixture/`, modules includes, groups, nested, failsafe, skip, inherit, meta, disabled, display,
/// display352, display353, plus the R-6 cross-module `@Nested` classes of the archive's fixture6) is submitted as a Gate
/// through the CLI.
/// The worker runs the real `PomProbe` (effective POM, online, in its auxiliary container), the build in the runner
/// image, and the real `ManifestCollector`; the controller stores and serves the manifest.
///
/// **Oracle:** the manifest the controller serves equals the classnames surefire and failsafe really wrote, read from
/// the workspace's XML independently of temper: missing = drift = ∅. This settles §2.4 rule 7 (class-level
/// `@Disabled` and conditions): surefire 3.5.4 writes skipped testcases for them, so they are required entries. It
/// also settles the reported name (T1 2026-10-14): module `display` and `DisplayIT` show surefire and failsafe 3.5.4
/// writing a class's own `@DisplayName` value, raw, as the `classname`, and the binary name otherwise; modules
/// `display352` (3.5.2, JUnit 5.11.4, pragmatica `jbct`'s pins) and `display353` (3.5.3), in the same reactor, show
/// both plugins writing the binary name of display-named classes (T1 run 2), so the manifest follows each module's
/// effective plugin version (`ReportNaming`).
///
/// **Control:** delete one class's report after the build and the same comparison names it missing.
///
/// **Mutation probes** (README.md): each `ManifestRulesTest` probe applied to temper-domain must turn this class red.
///
/// **R-11:** a module with a JUnit 4 (`junit4` overlay) or JUnit 3 (`junit3` overlay) class makes the manifest
/// Unsupported: the job ends `ERROR(manifest)`, with the module and class as the reason's detail; the Jupiter fixture
/// never needs the Vintage engine.
///
/// **Q-M1-7:** a pom the effective-POM step cannot read (`badpom` overlay) ends `ERROR(manifest)` with the detail
/// `effective-pom rc=<n>`, and the build never runs.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManifestOracleIT {
    private AcceptanceStack stack;
    private long job;
    private TemperSide side;
    private Set<String> required;
    private Set<String> optional;
    private Set<String> reported;

    @BeforeAll
    void gate() throws Exception {
        stack = AcceptanceStack.docker();
        job = stack.gate("main");
        side = stack.side(job);
        required = classes(side.manifest().entries());
        optional = classes(side.manifest().optional());
        reported = Reported.classnames(stack.workspace(job));
    }

    @Test
    void gate_passes_withAComputedManifestAndNoDisagreement() {
        assertThat(side.result().verdict()).as(side.result().toString()).isEqualTo("PASSED");
        assertThat(side.manifest().computed()).isTrue();
        assertThat(side.manifest().pruned()).isFalse();
        assertThat(side.manifest().missing()).isEmpty();
        assertThat(side.manifest().drift()).isEmpty();
        assertThat(side.manifest().notReached()).isEmpty();
    }

    @Test
    void manifest_equalsTheClassnamesSurefireAndFailsafeWrote() {
        var disagreement = Reported.compare(required, optional, reported);

        assertThat(reported).as("the build reported something").isNotEmpty();
        assertThat(disagreement.missing()).as("required, not reported").isEmpty();
        assertThat(disagreement.drift()).as("reported, not expected").isEmpty();
        Set<String> collected = side.rows().stream().map(row -> row.className()).collect(Collectors.toCollection(TreeSet::new));

        assertThat(collected)
            .as("temper's collected rows name exactly the classes on disk")
            .isEqualTo(reported);
    }

    @Test
    void fixture_exercisesEveryRule() {
        assertThat(required).as("includes: custom includes and excludes")
                            .contains("fx.includes.AlphaCheck", "fx.includes.BetaTest")
                            .doesNotContain("fx.includes.ExcludedTest", "fx.includes.GammaTests", "fx.includes.UnboundIT");
        assertThat(required).as("groups: groups=fast, excludedGroups=slow")
                            .contains("fx.groups.FastTest", "fx.groups.MixedTest", "fx.groups.PartSlowTest")
                            .doesNotContain("fx.groups.UntaggedTest", "fx.groups.SlowClassTest", "fx.groups.AllSlowTest");
        assertThat(required).as("nested: named by the outermost class's path; a @Nested-only outer is no entry")
                            .contains("fx.nested.OuterTest", "fx.nested.OuterTest$Inner", "fx.nested.OuterTest$Inner$Deep",
                                      "fx.nested.OnlyNestedTest$Child", "fx.nested.HelperHolderTest")
                            .doesNotContain("fx.nested.OnlyNestedTest", "fx.nested.HelperHolderTest$Helper");
        assertThat(required).as("inherit: inherited and interface-default tests; R-6 cross-module @Nested")
                            .contains("fx.inherit.DSubTest", "fx.inherit.EImplTest", "fx.inherit.CmJupSubTest",
                                      "fx.nested.CmAbsBase$In", "fx.nested.CmChainBase$In$Deep", "fx.inherit.CmLocalNestTest$Own")
                            .doesNotContain("fx.inherit.AbstractBaseTest");
        assertThat(required).as("meta: meta-annotated @Test, parameterized and repeated")
                            .contains("fx.meta.MetaAnnotatedTest", "fx.meta.HParamTest", "fx.meta.RepeatTest");
        assertThat(optional).as("meta: factories and templates may report nothing")
                            .contains("fx.meta.UFactoryTest", "fx.meta.IFactoryTest", "fx.meta.ZTemplateTest");
        assertThat(required).as("skip: skipTests in the module config").doesNotContain("fx.skip.SkippedModuleTest");
        assertThat(required).as("disabled (§2.4 rule 7): skipped testcases are written, so these are required")
                            .contains("fx.disabled.FDisabledTest", "fx.disabled.QDisabledMethodsTest",
                                      "fx.disabled.WindowsOnlyTest", "fx.disabled.SOuterTest$Off");
        assertThat(reported).contains("fx.disabled.FDisabledTest", "fx.disabled.WindowsOnlyTest");
        assertThat(required).as("display: a class's own @DisplayName, raw, at any depth, disabled or generated tests too")
                            .contains("Top level shown", "fx.display.DTopTest$Plain", "fx.display.DOuterTest",
                                      "Verify class tests", "fx.display.DOuterTest$Plain", "Same", "Level one", "Level two",
                                      "fx.display.DOuterTest$L1$L2Plain", "fx.display.DOuterTest$M1", "Deep shown",
                                      "a.b$c d ü ✓ <&\"x\"", " Padded name ", "Off shown", "Generated inside")
                            .doesNotContain("fx.display.DTopTest", "fx.display.DOuterTest$Shown", "fx.display.DOuterTest$Generated");
        assertThat(required).as("display: generators, inheritance and meta-annotations never rename a class")
                            .contains("fx.display.DGen_ClassTest", "fx.display.DGen_ClassTest$Inner_Gen",
                                      "fx.display.DGenNestedTest$Under_Score", "fx.display.DGenNestedTest$Sentence",
                                      "fx.display.DGenNestedTest$Meta", "fx.display.DSubOfShownTest")
                            .doesNotContain("Base shown", "Meta shown");
        assertThat(side.manifest().entries())
            .as("display352/display353: surefire and failsafe 3.5.2 and 3.5.3 write binary names, display-named or not")
            .contains(new ManifestEntryDto("display352", "fx.display352.OldTopTest", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("display352", "fx.display352.OldTopTest$Inner", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("display352", "fx.display352.OldTopTest$Plain", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("display352", "fx.display352.OldTopTest$L1$L2", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("display352", "fx.display352.OldShownIT", ManifestEntryDto.FAILSAFE),
                      new ManifestEntryDto("display352", "fx.display352.OldShownIT$Inner", ManifestEntryDto.FAILSAFE),
                      new ManifestEntryDto("display353", "fx.display353.MidTopTest", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("display353", "fx.display353.MidTopTest$Inner", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("display353", "fx.display353.MidShownIT", ManifestEntryDto.FAILSAFE));
        assertThat(required).doesNotContain("Old top shown", "Old inner shown", "Old level two", "Old IT shown", "Old IT inner",
                                            "Mid top shown", "Mid inner shown", "Mid IT shown");
        assertThat(side.manifest().entries())
            .as("failsafe bound in module failsafe only")
            .contains(new ManifestEntryDto("failsafe", "fx.failsafe.ServiceIT", ManifestEntryDto.FAILSAFE),
                      new ManifestEntryDto("failsafe", "fx.failsafe.ITService", ManifestEntryDto.FAILSAFE),
                      new ManifestEntryDto("failsafe", "fx.failsafe.ServiceITCase", ManifestEntryDto.FAILSAFE),
                      new ManifestEntryDto("failsafe", "fx.failsafe.PlainTest", ManifestEntryDto.SUREFIRE),
                      new ManifestEntryDto("failsafe", "Integration shown", ManifestEntryDto.FAILSAFE));
    }

    @Test
    void control_aClassWhoseReportIsGone_isMissing() throws Exception {
        var report = reportOf(stack.workspace(job), "TEST-fx.includes.BetaTest.xml");
        var saved = Files.readAllBytes(report);

        Files.delete(report);
        try {
            var disagreement = Reported.compare(required, optional, Reported.classnames(stack.workspace(job)));

            assertThat(disagreement.missing()).containsExactly("fx.includes.BetaTest");
            assertThat(disagreement.drift()).isEmpty();
        } finally {
            Files.write(report, saved);
        }
    }

    @Test
    void junit4Class_makesTheManifestUnsupported() throws Exception {
        assertUnsupported(stack.moduleTest("junit4", "legacy"), "fx.legacy.GJunit4Test");
    }

    @Test
    void junit3Class_makesTheManifestUnsupported() throws Exception {
        assertUnsupported(stack.moduleTest("junit3", "legacy"), "fx.legacy.RJunit3Test");
    }

    /// Q-M1-7 through the real `PomProbe`: a pom the effective-POM step cannot read fails the unit before the build.
    @Test
    void brokenPom_failsTheEffectivePomStep_andTheBuildNeverRuns() {
        var broken = stack.side(stack.gate("badpom"));

        assertThat(broken.result().verdict()).as(broken.result().toString()).isEqualTo("ERROR");
        // The worker reports `manifest: effective-pom rc=<n>`: the verdict keeps the token, the detail is carried.
        assertReason(broken, "effective-pom rc=");
        assertThat(broken.result().reasonDetail().or("")).matches("effective-pom rc=\\d+");
        assertThat(broken.rows()).as("the build never ran").isEmpty();
    }

    private void assertUnsupported(long legacyJob, String legacyClass) {
        var legacy = stack.side(legacyJob);

        assertThat(legacy.result().verdict()).as(legacy.result().toString()).isEqualTo("ERROR");
        assertReason(legacy, legacyClass);
        assertThat(legacy.result().reasonDetail().or("")).contains("module legacy");
        assertThat(legacy.manifest().computed()).isFalse();
    }

    /// `ERROR(manifest)` stays the closed-vocabulary token; the detail reaches the result record's `reasonDetail`, the
    /// unit's stored reason (`manifest: <detail>`) and the CLI `wait` line (`detail="…"`).
    private void assertReason(TemperSide side, String detail) {
        var job = side.result().job();

        assertThat(side.result().reason()).isEqualTo(Option.some("manifest"));
        assertThat(side.result().reasonDetail().or("")).as("result record").contains(detail);
        assertThat(stack.job(job).units())
            .as("stored unit reason")
            .anySatisfy(unit -> assertThat(unit.reason().or("")).startsWith("manifest: ").contains(detail));
        assertThat(stack.awaited(job, Duration.ofMinutes(1)))
            .as("CLI wait line")
            .contains("ERROR reason=manifest detail=\"")
            .contains(detail);
    }

    private static Set<String> classes(List<ManifestEntryDto> entries) {
        return entries.stream().map(ManifestEntryDto::className).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Path reportOf(Path workspace, String name) throws Exception {
        try (var walk = Files.walk(workspace)) {
            return walk.filter(file -> file.getFileName().toString().equals(name)).findFirst().orElseThrow();
        }
    }

}
