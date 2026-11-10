package org.pragmatica.temper.domain.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// m2-design §1.7: the four judge forms the V6 CHECK admits, and nothing else.
class JudgeTest {
    private static final String COMMIT = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";

    @ParameterizedTest
    @ValueSource(strings = {COMMIT, COMMIT + "-dirty", "unjudged", "pre-judge"})
    void judge_acceptsTheFourForms(String raw) {
        assertThat(Judge.judge(raw)
                        .map(Judge::value)
                        .unwrap()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1a2b3c4", COMMIT + "-clean", "1A2B3C4D5E6F708192A3B4C5D6E7F8091A2B3C4D", "${temper.judge}", "pre_judge"})
    void judge_refusesEverythingElse(String raw) {
        assertThat(Judge.judge(raw)
                        .isFailure()).as(raw)
                                     .isTrue();
    }

    @Test
    void shortForm_isTheSevenHexPrefix_keepsDirty_andLeavesTheWordsWhole() {
        assertThat(Judge.judge(COMMIT).unwrap().shortForm()).isEqualTo("1a2b3c4");
        assertThat(Judge.judge(COMMIT + "-dirty").unwrap().shortForm()).isEqualTo("1a2b3c4-dirty");
        assertThat(Judge.UNJUDGED.shortForm()).isEqualTo("unjudged");
        assertThat(Judge.PRE.shortForm()).isEqualTo("pre-judge");
    }

    @Test
    void constants_parseAsThemselves() {
        assertThat(Judge.judge("pre-judge").unwrap()).isEqualTo(Judge.PRE);
        assertThat(Judge.judge("unjudged").unwrap()).isEqualTo(Judge.UNJUDGED);
    }
}
