package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The published `observedAt` is the older read time, so a frozen ruleset hash is not stamped fresh by the team read.
class GitHubWatchTest {
    private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    void observedAt_isTheOlderReadTime_soAFrozenRulesetHashLooksStale() {
        var watch = GitHubWatch.gitHubWatch();

        watch.rulesets().set(some(new GitHubWatch.Rulesets("h", List.of(7L), T0)));
        watch.team().set(some(new GitHubWatch.Team(List.of("reviewer-pragmatica"), 1, T0.plusSeconds(3600))));
        assertThat(watch.drift().observedAt()).isEqualTo(some(T0));
    }

    @Test
    void observedAt_isTheOnlyReadTime_whenOnePartIsUnread() {
        var watch = GitHubWatch.gitHubWatch();

        watch.team().set(some(new GitHubWatch.Team(List.of("reviewer-pragmatica"), 1, T0)));
        assertThat(watch.drift().observedAt()).isEqualTo(some(T0));
    }
}
