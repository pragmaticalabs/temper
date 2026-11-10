package org.pragmatica.temper.controller.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.temper.domain.gate.Judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m0-design §12.7 item 3 and D-6: the controller's expected worker version is the build's `${project.version}` and its
/// judge, `<project.version>+<judge12>`. m2-design
/// §1.7: its judge is the build's `${temper.judge}`, a commit, a `-dirty` commit or `unjudged`; anything else, and an
/// unfiltered placeholder, fails start.
class ControllerVersionTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    /// D-6: the bundled version is `<project.version>+<judge12>` of this build (surefire passes both properties), or
    /// `+unjudged` for a plain build; derived independently here, so a derivation that drops the judge goes red.
    @Test
    void bundled_isTheBuildVersionOfThisBuild() {
        var judge = System.getProperty("temper.judge");
        var suffix = judge.equals("unjudged")
                     ? "unjudged"
                     : judge.substring(0, 12) + (judge.endsWith("-dirty") ? "-dirty" : "");

        assertThat(System.getProperty("project.version")).isNotBlank();
        assertThat(ControllerVersion.bundled()
                                    .unwrap()).isEqualTo(System.getProperty("project.version") + "+" + suffix);
    }

    @Test
    void versionOf_readsTheVersionLine_andRejectsAnUnfilteredPlaceholder() {
        assertThat(ControllerVersion.versionOf("# x\nversion=0.1.0-SNAPSHOT\n")).isEqualTo(some("0.1.0-SNAPSHOT"));
        assertThat(ControllerVersion.versionOf("version=${project.version}\n")).isEqualTo(none());
        assertThat(ControllerVersion.versionOf("version=\n")).isEqualTo(none());
    }

    @Test
    void derived_isTheBuildVersionOfTheVersionAndJudgeLines() {
        assertThat(ControllerVersion.derived("version=0.1.0-SNAPSHOT\njudge=" + COMMIT + "\n")
                                    .unwrap()).isEqualTo("0.1.0-SNAPSHOT+0123456789ab");
        assertThat(ControllerVersion.derived("version=0.1.0-SNAPSHOT\njudge=" + COMMIT + "-dirty\n")
                                    .unwrap()).isEqualTo("0.1.0-SNAPSHOT+0123456789ab-dirty");
        assertThat(ControllerVersion.derived("version=0.1.0-SNAPSHOT\njudge=unjudged\n")
                                    .unwrap()).isEqualTo("0.1.0-SNAPSHOT+unjudged");
    }

    /// A missing or unfiltered line, a judge outside its pattern and a version outside the worker-launch alphabet all
    /// fail start.
    @Test
    void derived_refusesAMissingLineABadJudgeAndAVersionOutsideTheAlphabet() {
        assertThat(ControllerVersion.derived("judge=" + COMMIT + "\n")
                                    .isFailure()).as("no version").isTrue();
        assertThat(ControllerVersion.derived("version=0.1.0-SNAPSHOT\njudge=${temper.judge}\n")
                                    .isFailure()).as("unfiltered judge").isTrue();
        assertThat(ControllerVersion.derived("version=0.1.0-SNAPSHOT\njudge=pre-judge\n")
                                    .isFailure()).as("pre-judge").isTrue();
        assertThat(ControllerVersion.derived("version=0.1 0;rm\njudge=unjudged\n")
                                    .isFailure()).as("outside the alphabet").isTrue();
    }

    /// The instrument for the build's [U] (m2-design §1.7): the judge the jar carries is the `temper.judge` this
    /// reactor was built with (surefire passes the same property), `unjudged` for a plain build.
    @Test
    void judge_isTheTemperJudgeOfThisBuild() {
        assertThat(System.getProperty("temper.judge")).isNotBlank();
        assertThat(ControllerVersion.judge()
                                    .unwrap()
                                    .value()).isEqualTo(System.getProperty("temper.judge"));
    }

    @ParameterizedTest
    @ValueSource(strings = {COMMIT, COMMIT + "-dirty", "unjudged"})
    void startable_acceptsACommitADirtyCommitAndUnjudged(String raw) {
        assertThat(ControllerVersion.startable(raw)
                                    .map(Judge::value)
                                    .unwrap()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"${temper.judge}", "pre-judge", "0123456", COMMIT + "-DIRTY", "ABCDEF0123456789abcdef0123456789abcdef01",
                            COMMIT + "0", "", "unjudged ;rm"})
    void startable_refusesAnythingElse(String raw) {
        assertThat(ControllerVersion.startable(raw)
                                    .isFailure()).as(raw)
                                                 .isTrue();
    }

    /// An unfiltered `judge=${temper.judge}` line is no judge, so [ControllerVersion#judge] fails start with NO_JUDGE.
    @Test
    void judgeOf_readsTheJudgeLine_andRejectsAnUnfilteredPlaceholder() {
        assertThat(ControllerVersion.judgeOf("version=0.1.0-SNAPSHOT\njudge=" + COMMIT + "\n")).isEqualTo(some(COMMIT));
        assertThat(ControllerVersion.judgeOf("version=0.1.0-SNAPSHOT\njudge=${temper.judge}\n")).isEqualTo(none());
        assertThat(ControllerVersion.judgeOf("version=0.1.0-SNAPSHOT\n")).isEqualTo(none());
        assertThat(ControllerVersion.judgeOf("judge=\n")).isEqualTo(none());
    }
}
