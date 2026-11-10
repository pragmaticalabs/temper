package org.pragmatica.temper.domain.mvn;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.SideEffects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// m4-design §5.2, maintainer ruling Q-M4-11: a table of `Mvn` argv → `NONE`/`EXTERNAL`, judged through
/// `Recipe.sideEffects()`, which reads [SideEffectRule]'s allowlist over the parsed step; and the parse of
/// [SideEffectAllowlist], which refuses a malformed line so that every step is external.
class SideEffectRuleTest {
    private static SideEffects of(String args) {
        return ok(Recipe.mvn(Arrays.asList(args.split(" ")))).sideEffects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deploy",
                            "clean deploy",
                            "-B clean deploy -DskipTests",
                            "site-deploy",
                            "org.apache.maven.plugins:maven-deploy-plugin:3.1.2:deploy",
                            "org.apache.maven.plugins:maven-deploy-plugin:deploy-file",
                            "deploy:deploy-file",
                            "release:prepare",
                            "release:perform",
                            "release:perform@cut",
                            "scm:checkin",
                            "wagon:upload",
                            "site:deploy",
                            "site:deploy@x",
                            "site:stage-deploy",
                            "jib:build",
                            "jib:build@x",
                            "docker:push",
                            "gpg:sign-and-deploy-file",
                            "nexus-staging:deploy",
                            "nexus-staging:release",
                            "central-publishing:publish",
                            "scm-publish:publish-scm",
                            "dockerfile:push",
                            "docker:build -DpushImage",
                            "jreleaser:full-release",
                            "jreleaser:deploy",
                            "jreleaser:release",
                            "cargo:deploy",
                            "k8s:apply",
                            "invoker:run"})
    void sideEffects_isExternal_forPublishingPhasesAndGoals(String args) {
        assertThat(of(args)).isEqualTo(SideEffects.EXTERNAL);
    }

    /// Fail-safe: whatever is not on the allowlist is external, publishing or not.
    @ParameterizedTest
    @ValueSource(strings = {"site",
                            "site:site",
                            "gpg:sign",
                            "jib:dockerBuild",
                            "deployment:list",
                            "versions:set",
                            "javadoc:aggregate-jar",
                            "install:install-file",
                            "evil.group:maven-surefire-plugin:3.5.4:test",
                            "-Pevil install",
                            "with-e2e",
                            "-Pverify install",
                            "-P acceptance,evil install",
                            "--activate-profiles=!with-e2e install",
                            "-Prelease install",
                            "-Pacceptance,release install",
                            "-P acceptance,release verify",
                            "DEPLOY",
                            "Install",
                            "clean Install",
                            "foo:bar",
                            "org.example:any-plugin:1.0:build",
                            "exec:exec",
                            "jar:sign",
                            "surefire:help",
                            "dependency:purge-local-repository",
                            "dependency:copy",
                            "dependency:unpack",
                            "dependency:copy-dependencies",
                            "dependency:unpack-dependencies",
                            "dependency:get",
                            "dependency:add",
                            "dependency:remove",
                            "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:purge-local-repository",
                            "dependency:purge-local-repository@x",
                            "javadoc:aggregate",
                            "javadoc:test-aggregate",
                            "source:aggregate",
                            "jbct:deploy-slices",
                            "jbct:install-slices",
                            "jacoco:dump",
                            "help:system",
                            "help:effective-settings",
                            "help:evaluate",
                            "help:describe",
                            "-Dexpression=settings.servers help:evaluate",
                            "-Dplugin=help help:describe",
                            "-DskipTests= install",
                            "-DskipTests -DskipTests=yes install",
                            "-Drelease=true install",
                            "-Ddeploy=true verify",
                            "-Dsurefire.reportsDirectory=/x verify",
                            "-Dsurefire.reportsDirectory verify",
                            "-Dit.reportsDirectory=/x verify",
                            "-Djbct.configFile=/x jbct:check",
                            "-Doutput=/x help:effective-pom",
                            "-DskipTests=/x install",
                            "-Dmaven.repo.local=/x install"})
    void sideEffects_isExternal_forAnythingNotAllowlisted(String args) {
        assertThat(of(args)).isEqualTo(SideEffects.EXTERNAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"install",
                            "verify",
                            "clean install",
                            "pre-clean post-clean validate compile test-compile package integration-test",
                            "-pl core test",
                            "jbct:check",
                            "jbct:format@x",
                            "pitest:mutationCoverage",
                            "surefire:test@x",
                            "org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test",
                            "org.apache.maven.plugins:maven-help-plugin:effective-pom",
                            "javadoc:jar",
                            "javadoc:jar@x",
                            "dependency:tree",
                            "dependency:list dependency:resolve dependency:resolve-plugins dependency:go-offline",
                            "dependency:analyze dependency:analyze-only dependency:build-classpath dependency:sources dependency:properties",
                            "clean:clean compiler:compile compiler:testCompile resources:resources resources:testResources",
                            "failsafe:integration-test failsafe:verify jar:jar jar:test-jar source:jar source:test-jar-no-fork",
                            "javadoc:javadoc javadoc:test-jar install:install help:active-profiles help:all-profiles enforcer:enforce",
                            "jacoco:report-aggregate",
                            "pitest:report-aggregate",
                            "pitest:report-aggregate-module",
                            "-Dtest= test",
                            "-Dtest=../x test",
                            "-DtargetClasses=a,b pitest:mutationCoverage",
                            "jbct:format-check jbct:lint jbct:score pitest:report",
                            "jacoco:prepare-agent jacoco:report jacoco:check jacoco:merge",
                            "-DskipTests install",
                            "-D skipTests=false -Dtest=Foo* test",
                            "-DtargetClasses=a.B* -DtargetTests=a.BTest pitest:mutationCoverage",
                            "-Pacceptance install",
                            "-P with-e2e verify",
                            "-P acceptance,with-e2e verify"})
    void sideEffects_isNone_forBuildOnlyPhasesGoalsAndProfiles(String args) {
        assertThat(of(args)).isEqualTo(SideEffects.NONE);
    }

    @Test
    void offence_namesThePhaseTheGoalOrTheProfile_goalsFirstInArgvOrder() {
        assertThat(offence("mvn", "verify", "deploy")).isEqualTo(Option.some("phase 'deploy' is not on the build-only allowlist"));
        assertThat(offence("mvn", "-Pevil", "release:perform", "deploy")).isEqualTo(Option.some("goal 'release:perform' is not on the build-only allowlist"));
        assertThat(offence("mvn", "-Pevil", "verify")).isEqualTo(Option.some("profile 'evil' is not on the build-only allowlist"));
        assertThat(offence("mvn", "verify")).isEqualTo(Option.none());
    }

    /// Element 0 is the executable and is never read as a goal.
    @Test
    void offence_ignoresTheExecutable() {
        assertThat(offence("deploy", "verify")).isEqualTo(Option.none());
    }

    /// The format: `phase`, `plugin` (prefix and coordinate forms, a goal list) and `profile` lines, any whitespace
    /// between fields; blank and `#` lines are skipped.
    @Test
    void allowlist_parsesItsLines() {
        var allowlist = SideEffectAllowlist.sideEffectAllowlist(List.of("# phase deploy",
                                                                        "",
                                                                        "  phase verify  ",
                                                                        "plugin\tjar  org.apache.maven.plugins:maven-jar-plugin jar,test-jar",
                                                                        "plugin pitest org.pitest:pitest-maven mutationCoverage",
                                                                        "profile ci",
                                                                        "property skipTests boolean",
                                                                        "property  test   selector",
                                                                        "property skipITs skip",
                                                                        "option -P --activate-profiles profiles",
                                                                        "option  -D\t--define define",
                                                                        "option -pl --projects modules narrows"));

        assertThat(allowlist.map(SideEffectAllowlist::phases)).isEqualTo(Result.success(Set.of("verify")));
        assertThat(allowlist.map(SideEffectAllowlist::profiles)).isEqualTo(Result.success(Set.of("ci")));
        assertThat(allowlist.map(SideEffectAllowlist::plugins))
            .isEqualTo(Result.success(List.of(new SideEffectAllowlist.Plugin("jar", "org.apache.maven.plugins:maven-jar-plugin", Set.of("jar", "test-jar")),
                                              new SideEffectAllowlist.Plugin("pitest", "org.pitest:pitest-maven", Set.of("mutationCoverage")))));
        assertThat(allowlist.map(SideEffectAllowlist::properties))
            .isEqualTo(Result.success(List.of(new SideEffectAllowlist.Property("skipTests", SideEffectAllowlist.PropertyKind.BOOLEAN),
                                              new SideEffectAllowlist.Property("test", SideEffectAllowlist.PropertyKind.SELECTOR),
                                              new SideEffectAllowlist.Property("skipITs", SideEffectAllowlist.PropertyKind.SKIP))));
        assertThat(allowlist.map(SideEffectAllowlist::options))
            .isEqualTo(Result.success(List.of(new SideEffectAllowlist.MvnOption("-P", "--activate-profiles", SideEffectAllowlist.ValueShape.PROFILES, false),
                                              new SideEffectAllowlist.MvnOption("-D", "--define", SideEffectAllowlist.ValueShape.DEFINE, false),
                                              new SideEffectAllowlist.MvnOption("-pl", "--projects", SideEffectAllowlist.ValueShape.MODULES, true))));
        assertThat(SideEffectRule.offence(step("verify", "jar:test-jar", "pitest:mutationCoverage", "-Pci"), allowlist)).isEqualTo(Option.none());
        assertThat(SideEffectRule.offence(step("-B", "verify"), allowlist)).as("an option off this list").isEqualTo(Option.some("option '-B' is not on the build-only allowlist"));
        assertThat(SideEffectRule.offence(step("verify", "-DskipTests", "-DskipTests=False", "-Dtest=/any/thing"), allowlist)).isEqualTo(Option.none());
        assertThat(SideEffectRule.offence(step("verify", "-DskipTests=yes"), allowlist)).isEqualTo(Option.some("property 'skipTests' takes true or false, got 'yes'"));
        assertThat(SideEffectRule.offence(step("verify", "-DskipITs", "-DskipITs=FALSE"), allowlist)).isEqualTo(Option.none());
        assertThat(SideEffectRule.offence(step("verify", "-DskipITs=yes"), allowlist)).isEqualTo(Option.some("property 'skipITs' takes true or false, got 'yes'"));
        assertThat(SideEffectRule.offence(step("verify", "-Dtests=X"), allowlist)).isEqualTo(Option.some("property 'tests' is not on the build-only allowlist"));
        assertThat(SideEffectRule.plannedStepOffence(step("verify", "-Dtests=X"), allowlist)).as("a planned step's properties are the planner's").isEqualTo(Option.none());
        assertThat(SideEffectRule.plannedStepOffence(step("deploy", "-Dtests=X"), allowlist).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("org.apache.maven.plugins:maven-jar-plugin:3.4.2:jar"), allowlist)).isEqualTo(Option.none());
        assertThat(SideEffectRule.offence(step("jar:sign"), allowlist).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("pitest:anything"), allowlist).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("deploy"), allowlist).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("bogus"), allowlist).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step(":"), allowlist).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("ci"), allowlist).isPresent()).as("a profile is not a phase").isTrue();
        assertThat(SideEffectRule.offence(step("-Pverify", "verify"), allowlist).isPresent()).as("a phase is not a profile").isTrue();
    }

    /// Fail-safe: a line of no known form fails the whole parse; there is no "every goal" form.
    @ParameterizedTest
    @ValueSource(strings = {"phase",
                            "phase deploy extra",
                            "Phase verify",
                            "profile",
                            "profile a b",
                            "plugin release org.apache.maven.plugins:maven-release-plugin",
                            "plugin dependency org.apache.maven.plugins:maven-dependency-plugin *",
                            "plugin dependency org.apache.maven.plugins:maven-dependency-plugin tree,*",
                            "plugin dependency org.apache.maven.plugins:maven-dependency-plugin tree,,list",
                            "plugin dependency maven-dependency-plugin tree",
                            "plugin dependency org.apache.maven.plugins:maven-dependency-plugin tree extra",
                            "property",
                            "property skipTests",
                            "property skipTests path",
                            "property surefire.* selector",
                            "property skipTests boolean extra",
                            "option",
                            "option -B --batch-mode",
                            "option -B --batch-mode bogus",
                            "option B --batch-mode none",
                            "option -B -batch-mode none",
                            "option --batch-mode none",
                            "option -B --batch-mode none narrow",
                            "option -B --batch-mode none narrows extra",
                            "option -B= --batch-mode none",
                            "option -B --batch-mode= none",
                            "bogus line"})
    void allowlist_refusesAMalformedLine(String line) {
        var allowlist = SideEffectAllowlist.sideEffectAllowlist(List.of("phase verify", line, "profile ci"));

        assertThat(allowlist.isSuccess()).isFalse();
        assertThat(SideEffectRule.offence(step("verify"), allowlist).or(""))
            .startsWith("the build-only allowlist does not parse (")
            .contains("Malformed allowlist line '" + String.join(" ", line.split("\\s+")) + "'")
            .endsWith("), so every step is external");
    }

    /// A planned step's argv is judged for `-fn` / `--fail-never` too (the planner never emits it; the argv is built
    /// directly), though its options are not judged against the option list: a plan that ever carried one is refused.
    @ParameterizedTest
    @ValueSource(strings = {"-fn", "--fail-never"})
    void plannedStepOffence_refusesFailNever(String option) {
        assertThat(SideEffectRule.plannedStepOffence(MvnStep.mvnStep(List.of("mvn", "-B", option, "verify",
                                                                             "-Dmaven.repo.local=/ws/.m2-local"))))
            .isEqualTo(Option.some("option '" + option + "' makes a failing build exit 0"));
    }

    /// A failed parse makes every step external, even one with no goal at all.
    @Test
    void failedParse_makesEveryStepExternal() {
        var failed = SideEffectAllowlist.sideEffectAllowlist(List.of("phase install", "bogus"));

        assertThat(SideEffectRule.offence(step("install"), failed).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("-B", "-v"), failed).isPresent()).isTrue();
        assertThat(SideEffectRule.plannedStepOffence(step("-B", "-v"), failed).isPresent()).isTrue();
        assertThat(SideEffectRule.offence(step("install"), SideEffectAllowlist.sideEffectAllowlist(List.of("phase install")))).isEqualTo(Option.none());
    }

    /// The empty list allows nothing: every `Mvn` step with a goal is external.
    @Test
    void emptyAllowlist_allowsNothing() {
        assertThat(SideEffectRule.offence(step("install"), SideEffectAllowlist.sideEffectAllowlist(List.of())).isPresent()).isTrue();
    }

    /// The shipped list parses (a bad edit fails the build), to the lifecycle and goals it names.
    @Test
    void shippedAllowlist_parses() {
        var shipped = SideEffectAllowlist.shipped();

        assertThat(shipped.isSuccess()).isTrue();
        assertThat(SideEffectRule.ALLOWLIST).isEqualTo(shipped);
        assertThat(shipped.map(SideEffectAllowlist::phases).or(Set.of())).contains("install", "verify").doesNotContain("deploy", "site-deploy");
        assertThat(shipped.map(SideEffectAllowlist::profiles).or(Set.of())).containsExactlyInAnyOrder("acceptance", "with-e2e");
        assertThat(shipped.map(SideEffectAllowlist::plugins).or(List.of()).stream().filter(plugin -> plugin.prefix().equals("dependency")).flatMap(plugin -> plugin.goals().stream()))
            .containsExactlyInAnyOrder("tree", "list", "resolve", "resolve-plugins", "go-offline", "analyze", "analyze-only", "build-classpath", "sources", "properties");
        assertThat(shipped.map(SideEffectAllowlist::properties).or(List.of()).stream().map(SideEffectAllowlist.Property::name))
            .contains("skipTests", "test", "it.test", "failIfNoSpecifiedTests", "ember.skipTests", "targetClasses", "targetTests")
            .doesNotContain("additionalClasspathElements", "argLine", "output", "maven.repo.local", "release", "jbct.skip",
                            "maven.test.failure.ignore");
    }

    /// maintainer ruling Q-M4-11d: the accepted options, exactly, each with its shape; `-pl`, `-rf`, `-f` and `-N` narrow.
    @Test
    void shippedAllowlist_optionsAreTheRuledOptionsWithTheirShapes() {
        assertThat(SideEffectAllowlist.shipped().map(SideEffectAllowlist::options).or(List.of()))
            .containsExactlyInAnyOrder(option("-B", "--batch-mode", "none"), option("-e", "--errors", "none"),
                                       option("-X", "--debug", "none"), option("-q", "--quiet", "none"),
                                       option("-o", "--offline", "none"), option("-U", "--update-snapshots", "none"),
                                       option("-V", "--show-version", "none"), option("-nsu", "--no-snapshot-updates", "none"),
                                       option("-npu", "--no-plugin-updates", "none"), option("-C", "--strict-checksums", "none"),
                                       option("-c", "--lax-checksums", "none"), option("-am", "--also-make", "none"),
                                       option("-amd", "--also-make-dependents", "none"), option("-fae", "--fail-at-end", "none"),
                                       option("-ff", "--fail-fast", "none"), option("-T", "--threads", "threads"),
                                       new SideEffectAllowlist.MvnOption("-pl", "--projects", SideEffectAllowlist.ValueShape.MODULES, true),
                                       new SideEffectAllowlist.MvnOption("-rf", "--resume-from", SideEffectAllowlist.ValueShape.MODULE, true),
                                       new SideEffectAllowlist.MvnOption("-f", "--file", SideEffectAllowlist.ValueShape.FILE, true),
                                       new SideEffectAllowlist.MvnOption("-N", "--non-recursive", SideEffectAllowlist.ValueShape.NONE, true),
                                       option("-P", "--activate-profiles", "profiles"), option("-D", "--define", "define"));
    }

    /// maintainer ruling Q-M4-11d, the verifier's bypasses (each checked on real Maven 3.9.12: every one is honoured, so each
    /// would leak) and every option off the list: EXTERNAL, the offence naming the token as written, ahead of any goal
    /// the token's would-be value reads as. `-fn` and its long spellings are named as making a failing build exit 0.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"-fail-never verify|-fail-never",
                                         "--fail-never=true verify|--fail-never=true",
                                         "-pl=a install|-pl=a",
                                         "-f=a/pom.xml install|-f=a/pom.xml",
                                         "-fa/pom.xml install|-fa/pom.xml",
                                         "-rf=b install|-rf=b",
                                         "-non-recursive install|-non-recursive",
                                         "-projects a install|-projects",
                                         "-resume-from b install|-resume-from",
                                         "-file x install|-file",
                                         "-f=x install|-f=x",
                                         "--fail-n verify|--fail-n",
                                         "--fail-neve verify|--fail-neve",
                                         "-define verify|-define",
                                         "-define install verify|-define",
                                         "-activate-profiles verify|-activate-profiles",
                                         "-activate-profiles install verify|-activate-profiles",
                                         "-define skipTests install|-define",
                                         "-activate-profiles acceptance install|-activate-profiles",
                                         "-T4 install|-T4",
                                         "--threads=4 install|--threads=4",
                                         "--define=skipTests install|--define=skipTests",
                                         "--projects=a install|--projects=a",
                                         "--resume-from=a install|--resume-from=a",
                                         "--file=pom.xml install|--file=pom.xml",
                                         "--activate-profiles=acceptance install|--activate-profiles=acceptance",
                                         "-pla install|-pla",
                                         "-dskipTests install|-dskipTests",
                                         "-s s.xml install|-s",
                                         "--settings s.xml install|--settings",
                                         "-gs s.xml install|-gs",
                                         "--global-settings s.xml install|--global-settings",
                                         "-l log.txt install|-l",
                                         "--log-file log.txt install|--log-file",
                                         "-t t.xml install|-t",
                                         "--toolchains t.xml install|--toolchains",
                                         "-gt t.xml install|-gt",
                                         "-emp x install|-emp",
                                         "-ep x install|-ep",
                                         "-b singlethreaded install|-b",
                                         "-ntp install|-ntp",
                                         "--color always install|--color",
                                         "-itr install|-itr",
                                         "-llr install|-llr",
                                         "-cpu install|-cpu",
                                         "-up install|-up",
                                         "-npr install|-npr",
                                         "-h install|-h",
                                         "-v install|-v",
                                         "--version install|--version",
                                         "--fail- install|--fail-",
                                         "--fail-at-end=true install|--fail-at-end=true",
                                         "-Bq install|-Bq",
                                         "- install|-",
                                         "-- install|--"})
    void options_offTheList_areExternal_namingTheToken(String args, String token) {
        assertThat(of(args)).isEqualTo(SideEffects.EXTERNAL);
        assertThat(userOffence(args)).isEqualTo(Option.some("option '" + token + "' is not on the build-only allowlist"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-fn", "--fail-never"})
    void options_failNever_isNamedAsTurningAFailureGreen(String option) {
        assertThat(userOffence(option + " verify")).isEqualTo(Option.some("option '" + option + "' makes a failing build exit 0"));
    }

    /// Every listed spelling, each value of its shape; `-D` and `-P` also attached. An exact listed value option consumes
    /// the next element whatever it is, as [MvnStep] does, so the goals read agree (`-T 4 deploy` is judged on `deploy`).
    @ParameterizedTest
    @ValueSource(strings = {"-B -e -X -q -o -U -V -nsu -npu -C -c -am -amd -fae -ff install",
                            "--batch-mode --errors --debug --quiet --offline --update-snapshots --show-version install",
                            "--no-snapshot-updates --no-plugin-updates --strict-checksums --lax-checksums install",
                            "--also-make --also-make-dependents --fail-at-end --fail-fast install",
                            "-T 4 install",
                            "-T 12 install",
                            "-T 1C install",
                            "--threads 16C install",
                            "-pl core install",
                            "-pl !core install",
                            "--projects core,!api,:store,!:cli,org.x:y,!org.x:z,sub/mod,a.b_c-d,0x install",
                            "-rf :store install",
                            "-rf org.x:store install",
                            "--resume-from sub/mod install",
                            "-f pom.xml install",
                            "-f sub install",
                            "--file sub/mod/pom.xml install",
                            "-N install",
                            "--non-recursive install",
                            "-P acceptance install",
                            "--activate-profiles acceptance,with-e2e install",
                            "-Pacceptance install",
                            "-D skipTests install",
                            "--define skipTests=true install",
                            "-DskipTests install"})
    void options_onTheList_areNone(String args) {
        assertThat(userOffence(args)).isEqualTo(Option.none());
        assertThat(of(args)).isEqualTo(SideEffects.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-T 4 deploy", "-pl core deploy", "-rf core deploy", "-f pom.xml deploy", "-P acceptance deploy",
                            "-D skipTests deploy", "-N deploy"})
    void options_valueIsConsumedAsMavenDoes_soTheGoalAfterItIsJudged(String args) {
        assertThat(userOffence(args)).isEqualTo(Option.some("phase 'deploy' is not on the build-only allowlist"));
    }

    /// The value shapes: `-T` an integer or `<n>C`; `-pl` module selectors (`[!]:artifactId`, `[!]groupId:artifactId`,
    /// `[!]relative/module/dir`, names `[A-Za-z0-9_.-]` starting with `[A-Za-z0-9_]`, never `..`); `-rf` one selector
    /// without `!`; `-f` a workspace-relative path. The value is never echoed.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"-T 0 install|-T|an integer or <n>C",
                                         "-T 04 install|-T|an integer or <n>C",
                                         "-T 1.5C install|-T|an integer or <n>C",
                                         "-T 4c install|-T|an integer or <n>C",
                                         "-T x install|-T|an integer or <n>C",
                                         "-T -N install|-T|an integer or <n>C",
                                         "--threads C install|--threads|an integer or <n>C",
                                         "-pl ../x install|-pl|module selectors",
                                         "-pl /etc install|-pl|module selectors",
                                         "-pl a/../b install|-pl|module selectors",
                                         "-pl a/.. install|-pl|module selectors",
                                         "-pl a..b install|-pl|module selectors",
                                         "-pl ./a install|-pl|module selectors",
                                         "-pl .a install|-pl|module selectors",
                                         "-pl a/ install|-pl|module selectors",
                                         "-pl a//b install|-pl|module selectors",
                                         "-pl a,,b install|-pl|module selectors",
                                         "-pl a, install|-pl|module selectors",
                                         "-pl , install|-pl|module selectors",
                                         "-pl -core install|-pl|module selectors",
                                         "-pl !!core install|-pl|module selectors",
                                         "-pl ?core install|-pl|module selectors",
                                         "-pl a:b:c install|-pl|module selectors",
                                         "-pl a: install|-pl|module selectors",
                                         "-pl ~/x install|-pl|module selectors",
                                         "-pl a\b install|-pl|module selectors",
                                         "--projects $x install|--projects|module selectors",
                                         "-rf a,b install|-rf|one module selector",
                                         "-rf !a install|-rf|one module selector",
                                         "-rf ../a install|-rf|one module selector",
                                         "--resume-from /a install|--resume-from|one module selector",
                                         "-f /etc/pom.xml install|-f|a relative path inside the workspace",
                                         "-f ../pom.xml install|-f|a relative path inside the workspace",
                                         "-f a/../../x install|-f|a relative path inside the workspace",
                                         "-f ~/pom.xml install|-f|a relative path inside the workspace",
                                         "-f :a install|-f|a relative path inside the workspace",
                                         "-f a//b install|-f|a relative path inside the workspace",
                                         "-f . install|-f|a relative path inside the workspace",
                                         "--file a/ install|--file|a relative path inside the workspace"})
    void options_valueOfTheWrongShape_isExternal_namingTheOption(String args, String option, String shape) {
        assertThat(of(args)).isEqualTo(SideEffects.EXTERNAL);
        assertThat(userOffence(args).or("")).startsWith("option '" + option + "' takes " + shape);
    }

    /// verify6: Maven builds the FIRST `-f`, `MvnStep.baseDir` judges inclusion against the last; a repeated `-f`/`--file`
    /// in any spelling mix is EXTERNAL, naming the repeat as written.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"-f a/pom.xml -f b/pom.xml install|-f",
                                         "-f a/pom.xml --file b/pom.xml install|--file",
                                         "--file a --file a install|--file",
                                         "-f a -T 4 -f b -f c install|-f"})
    void options_repeatedFile_isExternal_namingTheOption(String args, String repeat) {
        assertThat(of(args)).isEqualTo(SideEffects.EXTERNAL);
        assertThat(userOffence(args)).isEqualTo(Option.some("option '" + repeat
                                                            + "' is given more than once (Maven builds the first -f, temper would judge the last)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-T", "--threads", "-pl", "--projects", "-rf", "--resume-from", "-f", "--file", "-P",
                            "--activate-profiles", "-D", "--define"})
    void options_valueMissing_isExternal(String option) {
        assertThat(userOffence("install " + option)).isEqualTo(Option.some("option '" + option + "' needs a value"));
    }

    private static SideEffectAllowlist.MvnOption option(String shortName, String longName, String shape) {
        return new SideEffectAllowlist.MvnOption(shortName, longName, SideEffectAllowlist.ValueShape.valueOf(shape.toUpperCase(java.util.Locale.ROOT)), false);
    }

    private static Option<String> userOffence(String args) {
        return SideEffectRule.offence(step(args.split(" ")));
    }

    /// maintainer ruling Q-M4-11c: the accepted keys, exactly, each with its kind (skip and selector ones are marked on the
    /// job's result); jbct.skip, maven.test.failure.ignore and additionalClasspathElements are not among them.
    @Test
    void shippedAllowlist_propertiesAreTheRuledKeysWithTheirKinds() {
        assertThat(SideEffectAllowlist.shipped().map(SideEffectAllowlist::properties).or(List.of()))
            .containsExactlyInAnyOrder(property("skipTests", SideEffectAllowlist.PropertyKind.SKIP),
                                       property("maven.test.skip", SideEffectAllowlist.PropertyKind.SKIP),
                                       property("skipITs", SideEffectAllowlist.PropertyKind.SKIP),
                                       property("ember.skipTests", SideEffectAllowlist.PropertyKind.SKIP),
                                       property("test", SideEffectAllowlist.PropertyKind.SELECTOR),
                                       property("it.test", SideEffectAllowlist.PropertyKind.SELECTOR),
                                       property("groups", SideEffectAllowlist.PropertyKind.SELECTOR),
                                       property("excludedGroups", SideEffectAllowlist.PropertyKind.SELECTOR),
                                       property("targetClasses", SideEffectAllowlist.PropertyKind.SELECTOR),
                                       property("targetTests", SideEffectAllowlist.PropertyKind.SELECTOR),
                                       property("failIfNoSpecifiedTests", SideEffectAllowlist.PropertyKind.BOOLEAN),
                                       property("surefire.failIfNoSpecifiedTests", SideEffectAllowlist.PropertyKind.BOOLEAN),
                                       property("it.failIfNoSpecifiedTests", SideEffectAllowlist.PropertyKind.BOOLEAN));
    }

    private static SideEffectAllowlist.Property property(String name, SideEffectAllowlist.PropertyKind kind) {
        return new SideEffectAllowlist.Property(name, kind);
    }

    private static MvnStep step(String... args) {
        return MvnStep.mvnStep(Stream.concat(Stream.of("mvn"), Arrays.stream(args)).toList());
    }

    private static Option<String> offence(String... argv) {
        return SideEffectRule.offence(MvnStep.mvnStep(List.of(argv)));
    }
}
