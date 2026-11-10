package org.pragmatica.kiln.core.source;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// spec §5.6: a failing source shows when it began to fail. [SourceState#continued] keeps the first `since` of a run of
/// the same failure, and starts a new one on any other transition.
class SourceStateTest {
    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant RESET = T0.plusSeconds(3600);

    @Test
    void continued_keepsTheFirstSince_ofEachRepeatedFailure() {
        assertThat(SourceState.continued(new SourceState.RateLimited(T0, T0), new SourceState.RateLimited(RESET, T1)))
            .isEqualTo(new SourceState.RateLimited(RESET, T0));
        assertThat(SourceState.continued(new SourceState.GithubDown("500", T0), new SourceState.GithubDown("502", T1)))
            .isEqualTo(new SourceState.GithubDown("502", T0));
        assertThat(SourceState.continued(new SourceState.Unreachable("a", T0), new SourceState.Unreachable("b", T1)))
            .isEqualTo(new SourceState.Unreachable("b", T0));
        assertThat(SourceState.continued(new SourceState.TemperDown("a", T0), new SourceState.TemperDown("b", T1)))
            .isEqualTo(new SourceState.TemperDown("b", T0));
        assertThat(SourceState.continued(new SourceState.TemperSchema("answer", T0), new SourceState.TemperSchema("sha", T1)))
            .isEqualTo(new SourceState.TemperSchema("sha", T0));
    }

    @Test
    void continued_startsAgain_afterOk_orAnotherKindOfFailure() {
        assertThat(SourceState.continued(new SourceState.Ok(T0), new SourceState.GithubDown("500", T1)))
            .isEqualTo(new SourceState.GithubDown("500", T1));
        assertThat(SourceState.continued(new SourceState.GithubDown("500", T0), new SourceState.RateLimited(RESET, T1)))
            .isEqualTo(new SourceState.RateLimited(RESET, T1));
        assertThat(SourceState.continued(new SourceState.TemperDown("a", T0), new SourceState.TemperSchema("answer", T1)))
            .isEqualTo(new SourceState.TemperSchema("answer", T1));
        assertThat(SourceState.continued(new SourceState.Incomplete("x", T0), new SourceState.Incomplete("y", T1)))
            .isEqualTo(new SourceState.Incomplete("y", T1));
        assertThat(SourceState.continued(new SourceState.Ok(T0), new SourceState.Ok(T1))).isEqualTo(new SourceState.Ok(T1));
    }
}
