package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The verifier's attack (f) (WP6 round 3, ruling 3): a 403 or 429 with requests left, a `Retry-After` in seconds or as
/// an HTTP date, and a message that names no secondary limit. Either way the cycle sends one request, reads
/// RATE-LIMITED until the `Retry-After`, and a tick inside the wait sends nothing.
@Tag("pg")
class RetryAfterIT {
    private static final String OTHER = "{\"message\":\"Too many requests, please wait\"}";

    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        quiet(fake, List.of(2L), List.of(sha(2)));
        poller = GitHubFixtures.poller(fake, store, now, none());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    void f_secondsRetryAfter_otherMessage_stopsAndHolds(int status) {
        stopsAndHolds(status, "120");
    }

    /// Red at 34446bb4: the date read as absent, the state GITHUB-DOWN, and 6 requests on each tick.
    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    void f_httpDateRetryAfter_otherMessage_stopsAndHolds(int status) {
        stopsAndHolds(status, "Fri, 30 Oct 2026 10:02:00 GMT");
    }

    private void stopsAndHolds(int status, String retryAfter) {
        fake.answer(PULLS, status, OTHER);
        fake.retryAfter(PULLS, retryAfter);
        fake.rate(4000, 5000, T0.plusSeconds(1800), false);

        assertThat(await(poller.tick())).isEqualTo(some(new SourceState.RateLimited(T0.plusSeconds(120), T0)));
        assertThat(fake.requests()).hasSize(1);

        now.set(T0.plusSeconds(60));
        assertThat(await(poller.tick())).isEqualTo(none());
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(120), T0));
        assertThat(fake.requests()).as("nothing is sent inside the wait").hasSize(1);
    }
}
