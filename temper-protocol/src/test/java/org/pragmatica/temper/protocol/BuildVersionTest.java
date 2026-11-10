package org.pragmatica.temper.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.BuildVersion;
import org.pragmatica.temper.protocol.message.Handshake;

import static org.assertj.core.api.Assertions.assertThat;

/// D-6 build identity: `<project.version>+<judge12>`, `-dirty` kept, `+unjudged` without a judge; always inside the
/// worker-launch alphabet, the worst case included.
class BuildVersionTest {
    private static final String COMMIT = "3f9c0a1b2c4d5e6f708192a3b4c5d6e7f8091a2b";

    @Test
    void buildVersion_isTheProjectVersionAndTheFirstTwelveDigitsOfTheJudge() {
        assertThat(BuildVersion.buildVersion("0.1.0-SNAPSHOT", COMMIT)
                               .unwrap()).isEqualTo("0.1.0-SNAPSHOT+3f9c0a1b2c4d");
    }

    @Test
    void buildVersion_keepsTheDirtySuffix() {
        assertThat(BuildVersion.buildVersion("0.1.0-SNAPSHOT", COMMIT + "-dirty")
                               .unwrap()).isEqualTo("0.1.0-SNAPSHOT+3f9c0a1b2c4d-dirty");
    }

    @Test
    void buildVersion_isUnjudged_withoutAJudge() {
        assertThat(BuildVersion.buildVersion("0.1.0-SNAPSHOT", "unjudged")
                               .unwrap()).isEqualTo("0.1.0-SNAPSHOT+unjudged");
    }

    /// The worst case of today's project version: a dirty judge, 33 characters, inside the 40-character alphabet.
    @Test
    void buildVersion_worstCase_fitsTheWorkerLaunchAlphabet() {
        var version = BuildVersion.buildVersion("0.1.0-SNAPSHOT", COMMIT + "-dirty")
                                  .unwrap();

        assertThat(version).hasSize(33);
        assertThat(Handshake.VERSION_ALPHABET.matcher(version)
                                             .matches()).isTrue();
    }

    /// A project version long enough to push the result past 40 characters is refused, not truncated.
    @Test
    void buildVersion_refusesAResultLongerThanTheAlphabetAllows() {
        assertThat(BuildVersion.buildVersion("0.1.0-SNAPSHOT-0123456789abcd", COMMIT + "-dirty")
                               .isFailure()).isTrue();
        assertThat(BuildVersion.buildVersion("0.1.0-SNAPSHOT-012345", COMMIT + "-dirty")
                               .unwrap()).hasSize(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pre-judge", "${temper.judge}", "", "3f9c0a1", COMMIT + "-DIRTY", COMMIT + "0",
                            "unjudged;rm"})
    void buildVersion_refusesAJudgeOutsideItsPattern(String judge) {
        assertThat(BuildVersion.buildVersion("0.1.0-SNAPSHOT", judge)
                               .isFailure()).as(judge)
                                            .isTrue();
    }

    /// `+` separates the judge, so a project version carrying one is refused (the runner version could not be recovered).
    @ParameterizedTest
    @ValueSource(strings = {"0.1.0+x", "${project.version}", "", "0.1 0", "-0.1.0"})
    void buildVersion_refusesAProjectVersionOutsideItsPattern(String projectVersion) {
        assertThat(BuildVersion.buildVersion(projectVersion, COMMIT)
                               .isFailure()).as(projectVersion)
                                            .isTrue();
    }

    /// The runner version is a docker tag (`temper/runner:<version>`): whatever the judge, it never carries the build
    /// version's `+`, and it fits docker's tag grammar.
    @ParameterizedTest
    @ValueSource(strings = {COMMIT, COMMIT + "-dirty", "unjudged"})
    void projectVersion_ofAnyBuildVersion_isADockerTag_withoutPlus(String judge) {
        var runner = BuildVersion.projectVersion(BuildVersion.buildVersion("0.1.0-SNAPSHOT", judge)
                                                             .unwrap());

        assertThat(runner).doesNotContain("+")
                          .matches("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")
                          .isEqualTo("0.1.0-SNAPSHOT");
    }

    @Test
    void projectVersion_isThePartBeforeTheJudge() {
        assertThat(BuildVersion.projectVersion("0.1.0-SNAPSHOT+3f9c0a1b2c4d-dirty")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(BuildVersion.projectVersion("0.1.0-SNAPSHOT+unjudged")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(BuildVersion.projectVersion("it-0123456789ab")).as("a version without a judge")
                                                                  .isEqualTo("it-0123456789ab");
    }

    /// A `-dirty` build is judged (it names its commit); `+unjudged` and a version without a judge are not.
    @Test
    void judged_isFalseOnlyForUnjudged() {
        assertThat(BuildVersion.judged("0.1.0-SNAPSHOT+3f9c0a1b2c4d")).isTrue();
        assertThat(BuildVersion.judged("0.1.0-SNAPSHOT+3f9c0a1b2c4d-dirty")).isTrue();
        assertThat(BuildVersion.judged("0.1.0-SNAPSHOT+unjudged")).isFalse();
        assertThat(BuildVersion.judged("it-0123456789ab")).as("no judge at all").isFalse();
    }

    @Test
    void property_readsAFilteredLine_andRejectsABlankOrUnfilteredValue() {
        assertThat(BuildVersion.property("# x\nversion=0.1.0-SNAPSHOT\njudge=unjudged\n", "version")).isEqualTo(Option.some("0.1.0-SNAPSHOT"));
        assertThat(BuildVersion.property("version=0.1.0-SNAPSHOT\njudge=unjudged\n", "judge")).isEqualTo(Option.some("unjudged"));
        assertThat(BuildVersion.property("judge=${temper.judge}\n", "judge")).isEqualTo(Option.none());
        assertThat(BuildVersion.property("judge=\n", "judge")).isEqualTo(Option.none());
        assertThat(BuildVersion.property("version=0.1.0\n", "judge")).isEqualTo(Option.none());
        assertThat(BuildVersion.property("judgement=x\n", "judge")).as("another key with the same prefix").isEqualTo(Option.none());
    }

    private static final String JUDGED = "0.1.0-SNAPSHOT+111111111111";
    private static final String OTHER = "0.1.0-SNAPSHOT+222222222222";
    private static final String UNJUDGED = "0.1.0-SNAPSHOT+unjudged";

    @Test
    void compare_equalVersions_areSame_aPlusLessPairIncluded() {
        assertThat(BuildVersion.compare(JUDGED, Option.some(JUDGED))).isEqualTo(BuildVersion.Comparison.SAME);
        assertThat(BuildVersion.compare("it-0123456789ab", Option.some("it-0123456789ab"))).isEqualTo(BuildVersion.Comparison.SAME);
    }

    /// G1/G2 transition rules: no version, or one without `+`, predates build identity (a later flip to a refusal is a
    /// change here).
    @Test
    void compare_absentOrPlusLessPeer_predatesBuildIdentity() {
        assertThat(BuildVersion.compare(JUDGED, Option.none())).isEqualTo(BuildVersion.Comparison.PREDATES);
        assertThat(BuildVersion.compare(JUDGED, Option.some("0.1.0-SNAPSHOT"))).isEqualTo(BuildVersion.Comparison.PREDATES);
    }

    @Test
    void compare_bothJudgedAndDifferent_isSkewed_dirtyIncluded() {
        assertThat(BuildVersion.compare(JUDGED, Option.some(OTHER))).isEqualTo(BuildVersion.Comparison.SKEWED);
        assertThat(BuildVersion.compare(JUDGED, Option.some(JUDGED + "-dirty"))).isEqualTo(BuildVersion.Comparison.SKEWED);
        assertThat(BuildVersion.compare(JUDGED + "-dirty", Option.some(OTHER))).isEqualTo(BuildVersion.Comparison.SKEWED);
    }

    @Test
    void compare_anUnjudgedSide_isUnjudged() {
        assertThat(BuildVersion.compare(JUDGED, Option.some(UNJUDGED))).isEqualTo(BuildVersion.Comparison.UNJUDGED);
        assertThat(BuildVersion.compare(UNJUDGED, Option.some(OTHER))).isEqualTo(BuildVersion.Comparison.UNJUDGED);
        assertThat(BuildVersion.compare("it-0123456789ab", Option.some(OTHER))).as("an own version without a judge").isEqualTo(BuildVersion.Comparison.UNJUDGED);
    }
}
