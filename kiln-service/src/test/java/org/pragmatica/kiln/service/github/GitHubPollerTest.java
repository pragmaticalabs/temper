package org.pragmatica.kiln.service.github;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.lang.Option.none;

/// The skipped set's arithmetic (WP6 fix round): a PR re-skipped keeps its latest reason, and only the github source's
/// stored state is read back.
class GitHubPollerTest {
    @Test
    void skippedAfter_keepsTheLatestReason_ofAPrSkippedAgain() {
        var after = GitHubPoller.skippedAfter(Map.of(number(9), "old", number(4), "kept"),
                                              List.of(),
                                              List.of(PullsPage.Skipped.skipped(number(9), "new", none())));

        assertThat(after).containsExactly(Map.entry(number(4), "kept"), Map.entry(number(9), "new"));
    }

    @Test
    void storedSkipped_readsTheGithubSourceOnly() {
        var mirror = SourceStatus.sourceStatus(Source.MIRROR, new SourceState.Incomplete("skipped #5: x", T0), none());
        var github = SourceStatus.sourceStatus(Source.GITHUB, new SourceState.Ok(T0), none());
        var skipped = SourceStatus.sourceStatus(Source.GITHUB, new SourceState.Incomplete("skipped #7: y", T0), none());

        assertThat(GitHubPoller.storedSkipped(List.of(mirror, github))).isEmpty();
        assertThat(GitHubPoller.storedSkipped(List.of(mirror, skipped))).containsExactly(Map.entry(number(7), "y"));
        assertThat(GitHubPoller.storedSkipped(List.of())).isEmpty();
    }

    @Test
    void late_namesTheDeadline() {
        assertThat(GitHubReadError.General.LATE.message()).isEqualTo("the cycle passed its deadline");
    }

    private static PrNumber number(long value) {
        return PrNumber.prNumber(value).unwrap();
    }

    /// WP9: a skipped note is observed when its state was recorded: a read at its own time, a failure at its start.
    @Test
    void recordedAt_isTheReadOrTheFailuresStart() {
        var at = T0;

        assertThat(List.of(new SourceState.Ok(at),
                          new SourceState.Incomplete("r", at),
                          new SourceState.Unreachable("r", at),
                          new SourceState.RateLimited(at.plusSeconds(60), at),
                          new SourceState.GithubDown("502", at),
                          new SourceState.TemperDown("r", at),
                          new SourceState.TemperSchema("f", at))).allSatisfy(state -> assertThat(GitHubPoller.recordedAt(state)).isEqualTo(at));
    }
}
