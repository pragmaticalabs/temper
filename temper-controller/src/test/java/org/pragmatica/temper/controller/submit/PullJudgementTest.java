package org.pragmatica.temper.controller.submit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// Which failures concern only the PR asked (the walk goes on) and which concern the API (the walk stops).
class PullJudgementTest {
    @ParameterizedTest
    @ValueSource(ints = {404, 410, 422})
    void perPull_true_statusAboutThePr(int status) {
        assertThat(PullJudgement.perPull(PullOriginError.Status.FACTORY.apply(status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 502, 503})
    void perPull_false_statusAboutTheApi(int status) {
        assertThat(PullJudgement.perPull(PullOriginError.Status.FACTORY.apply(status))).isFalse();
    }

    @Test
    void perPull_true_answersAboutThePr() {
        assertThat(PullJudgement.perPull(PullOriginError.Unparseable.FACTORY.apply(Causes.cause("x")))).isTrue();
        assertThat(PullJudgement.perPull(PullOriginError.General.INCOMPLETE)).isTrue();
        assertThat(PullJudgement.perPull(PullOriginError.General.OVERSIZED)).isTrue();
    }

    @Test
    void perPull_false_noAnswerOrNoGithub() {
        assertThat(PullJudgement.perPull(PullOriginError.CallFailed.FACTORY.apply(Causes.cause("x")))).isFalse();
        assertThat(PullJudgement.perPull(PullOriginError.General.NOT_GITHUB)).isFalse();
        assertThat(PullJudgement.perPull(Causes.cause("anything else"))).isFalse();
    }
}
