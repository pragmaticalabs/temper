package org.pragmatica.temper.domain.mvn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.manifest.Reach;

import static org.assertj.core.api.Assertions.assertThat;

/// How every rule reads an mvn argv.
class MvnStepTest {
    private static final Option<ReactorModules> UNKNOWN = Option.none();
    private static final Option<ReactorModules> DEFAULT_REACTOR = Option.some(ReactorModules.reactorModules(Set.of("",
                                                                                                                  "core",
                                                                                                                  "aether",
                                                                                                                  "aether/ember")));
    private static final Option<ReactorModules> WITH_FORGE_TESTS = Option.some(ReactorModules.reactorModules(Set.of("",
                                                                                                                   "core",
                                                                                                                   "aether/forge/forge-tests")));

    private static MvnStep step(String... args) {
        var argv = new ArrayList<String>(List.of("mvn"));

        argv.addAll(List.of(args));
        return MvnStep.mvnStep(argv);
    }

    @Test
    void properties_coverEveryDefineForm_lastWins() {
        var parsed = step("-Da=1", "-D", "b=2", "--define", "c=3", "--define=d=4", "-Dflag", "-Da=5");

        assertThat(parsed.property("a")).isEqualTo(Option.some("5"));
        assertThat(parsed.property("b")).isEqualTo(Option.some("2"));
        assertThat(parsed.property("c")).isEqualTo(Option.some("3"));
        assertThat(parsed.property("d")).isEqualTo(Option.some("4"));
        assertThat(parsed.flag("flag")).isTrue();
        assertThat(parsed.property("missing")).isEqualTo(Option.none());
    }

    @Test
    void goals_excludeOptionValues() {
        var parsed = step("-B", "-pl", "core,aether/ember", "-P", "ci", "-T", "4", "-f", "x/pom.xml", "clean", "verify", "-am");

        assertThat(parsed.goals()).containsExactly("clean", "verify");
        assertThat(parsed.projects()).containsExactly("core", "aether/ember");
        assertThat(parsed.pomFiles()).containsExactly("x/pom.xml");
        assertThat(parsed.alsoMake()).isTrue();
    }

    @Test
    void profiles_coverEveryForm() {
        assertThat(step("-P", "ci,e2e", "-Pfast", "--activate-profiles", "a", "--activate-profiles=b", "verify").profiles())
            .containsExactly("ci", "e2e", "fast", "a", "b");
        assertThat(step("-Pfast", "verify").goals()).containsExactly("verify");
    }

    @Test
    void runsSurefireAndFailsafe_byPhaseAndSkipFlags() {
        assertThat(step("test").runsSurefire()).isTrue();
        assertThat(step("test").runsFailsafe()).isFalse();
        assertThat(step("verify").runsFailsafe()).isTrue();
        assertThat(step("verify", "-DskipITs").runsFailsafe()).isFalse();
        assertThat(step("verify", "-DskipITs").runsSurefire()).isTrue();
        assertThat(step("verify", "-DskipTests").requestsTests()).isFalse();
        assertThat(step("org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test").runsSurefire()).isTrue();
    }

    @Test
    void reach_isTheFurthestTestBearingPhase() {
        assertThat(step("test").reach()).isEqualTo(Option.some(Reach.TEST));
        assertThat(step("surefire:test").reach()).isEqualTo(Option.some(Reach.TEST));
        assertThat(step("package").reach()).isEqualTo(Option.some(Reach.TEST));
        assertThat(step("verify").reach()).isEqualTo(Option.some(Reach.INTEGRATION_TEST));
        assertThat(step("clean", "install").reach()).isEqualTo(Option.some(Reach.INTEGRATION_TEST));
        assertThat(step("failsafe:integration-test").reach()).isEqualTo(Option.some(Reach.INTEGRATION_TEST));
        assertThat(step("clean", "compile").reach()).isEqualTo(Option.none());
    }

    @Test
    void reactorSelectionArgs_keepEverySelectionFormWithItsValue() {
        var parsed = step("-B", "-pl", "core", "-am", "-P", "ci", "-Pfast", "-f", "x/pom.xml", "-D", "a=1", "-Db=2",
                          "--define=c=3", "-T", "4", "clean", "verify", "-amd", "--projects=q", "--file=y",
                          "--activate-profiles=z", "--also-make", "-o");

        assertThat(parsed.reactorSelectionArgs()).containsExactly("-pl", "core", "-am", "-P", "ci", "-Pfast", "-f", "x/pom.xml",
                                                                   "-D", "a=1", "-Db=2", "--define=c=3", "-amd", "--projects=q",
                                                                   "--file=y", "--activate-profiles=z", "--also-make");
        assertThat(step("clean", "install").reactorSelectionArgs()).isEmpty();
    }

    @Test
    void mayInclude_handlesPathsArtifactIdsExclusionsAndPomBase() {
        assertThat(step("-pl", "aether/ember", "test").mayInclude("aether/ember", UNKNOWN)).isTrue();
        assertThat(step("-pl", "./aether/ember/", "test").mayInclude("aether/ember", UNKNOWN)).isTrue();
        assertThat(step("-pl", ":ember", "test").mayInclude("aether/ember", UNKNOWN)).isTrue();
        assertThat(step("-pl", "core", "test").mayInclude("aether/ember", UNKNOWN)).isFalse();
        assertThat(step("test").mayInclude("aether/ember", UNKNOWN)).isTrue();
        assertThat(step("-pl", "!aether/ember", "test").mayInclude("aether/ember", UNKNOWN)).isFalse();
        assertThat(step("-f", "aether/pom.xml", "test").mayInclude("aether/ember", UNKNOWN)).isTrue();
        assertThat(step("-f", "core/pom.xml", "test").mayInclude("aether/ember", UNKNOWN)).isFalse();
        assertThat(step("-f", "aether", "-pl", "ember", "test").mayInclude("aether/ember", UNKNOWN)).isTrue();
    }

    /// m1-design §1.6: the default reactor bounds what any selection can build.
    @Test
    void mayInclude_isBoundedByTheKnownDefaultReactor() {
        var forgeTests = "aether/forge/forge-tests";

        assertThat(step("verify").mayInclude(forgeTests, DEFAULT_REACTOR)).isFalse();
        assertThat(step("verify").mayInclude("aether/ember", DEFAULT_REACTOR)).isTrue();
        assertThat(step("-pl", "core", "-amd", "verify").mayInclude(forgeTests, DEFAULT_REACTOR)).isFalse();
        assertThat(step("verify").mayInclude(forgeTests, WITH_FORGE_TESTS)).isTrue();
        assertThat(step("-Pwith-e2e", "verify").mayInclude(forgeTests, DEFAULT_REACTOR)).as("-P: M0 answer")
                                                                                       .isTrue();
        assertThat(step("-f", "pom.xml", "verify").mayInclude(forgeTests, DEFAULT_REACTOR)).as("-f: M0 answer")
                                                                                          .isTrue();
        assertThat(step("verify").mayInclude(forgeTests, UNKNOWN)).as("unknown reactor: M0 answer")
                                                                 .isTrue();
    }

    @Test
    void mayIncludeSegment_judgesSelectionAndReactorWideSteps() {
        assertThat(step("-pl", ":forge-tests", "verify").mayIncludeSegment("forge-tests", WITH_FORGE_TESTS)).isTrue();
        assertThat(step("-pl", ":forge-tests", "verify").mayIncludeSegment("forge-tests", UNKNOWN)).isTrue();
        assertThat(step("-pl", ":forge-tests", "verify").mayIncludeSegment("forge-tests", DEFAULT_REACTOR)).as("as mayInclude: outside the reactor")
                                                                                                         .isFalse();
        assertThat(step("-f", "aether/forge/forge-tests/pom.xml", "verify").mayIncludeSegment("forge-tests", DEFAULT_REACTOR)).isTrue();
        assertThat(step("-pl", "core", "verify").mayIncludeSegment("forge-tests", UNKNOWN)).isFalse();
        assertThat(step("verify").mayIncludeSegment("forge-tests", UNKNOWN)).isTrue();
        assertThat(step("verify").mayIncludeSegment("forge-tests", DEFAULT_REACTOR)).isFalse();
        assertThat(step("verify").mayIncludeSegment("forge-tests", WITH_FORGE_TESTS)).isTrue();
        assertThat(step("-pl", "core", "-am", "verify").mayIncludeSegment("forge-tests", WITH_FORGE_TESTS)).isTrue();
        assertThat(step("-pl", "core", "-am", "verify").mayIncludeSegment("forge-tests", DEFAULT_REACTOR)).isFalse();
        assertThat(step("-pl", "!:forge-tests", "verify").mayIncludeSegment("forge-tests", UNKNOWN)).isFalse();
        assertThat(step("-Pe2e", "verify").mayIncludeSegment("forge-tests", DEFAULT_REACTOR)).isTrue();
    }
}
