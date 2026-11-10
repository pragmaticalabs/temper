package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The rate guard's bounds (design §8; WP6 fix round): the reserve holds without a reset header (verifier b1, b2), a
/// reset is waited for between a minute and an hour, an expired count at the reserve allows exactly one re-check, and a
/// secondary limit refuses everything until its `Retry-After`. Literals throughout, so no test moves with a constant.
class RateBudgetTest {
    /// B1: remaining reported below the reserve without a reset header: refused, and re-checked an hour later.
    @Test
    void b1_reserveHolds_whenTheResetHeaderIsMissing() {
        var budget = observed(answer(200, some(5), none(), none(), ""));

        assertThat(budget.spend(T0).isFailure()).as("remaining 5 < reserve 10, no reset header").isTrue();
        assertThat(budget.spend(T0.plusSeconds(3599)).isFailure()).isTrue();
        assertThat(budget.covers(1, T0)).isFalse();
        assertThat(budget.spend(T0.plusSeconds(3600)).isSuccess()).as("one re-check after the hour").isTrue();
        assertThat(budget.spend(T0.plusSeconds(3600)).isFailure()).as("and only one").isTrue();
    }

    /// B2: a 304 at the reserve: the request is refused until the reset.
    @Test
    void b2_atTheReserve_eachRequestIsRefused_untilTheReset() {
        var reset = T0.plusSeconds(60);
        var budget = observed(answer(304, some(10), some(reset), none(), ""));

        assertThat(budget.spend(T0).isFailure()).isTrue();
        assertThat(budget.spend(reset).isSuccess()).isTrue();
    }

    /// B3: one configured base, one open PR on a stacked base, a new head: pulls, pulls/n, commits/sha, branch + rules
    /// (main), branch + rules (stacked), check runs, statuses, runs (main), scheduled runs, issues = 12.
    @Test
    void b3_need_coversAStackedBaseAndANewHeadsDate() {
        assertThat(GitHubPoller.need(1, 1, 1, 1)).isEqualTo(12);
    }

    /// A reset ten years ahead is waited for an hour at most; then one re-check, then the next hour.
    @Test
    void farFutureReset_isCappedAtAnHour_thenOneRecheck() {
        var budget = observed(answer(403, some(0), some(T0.plus(Duration.ofDays(3650))), none(), ""));

        assertThat(budget.spend(T0.plusSeconds(3599)).isFailure()).isTrue();
        assertThat(budget.until(T0, 1)).isEqualTo(T0.plusSeconds(3600));
        assertThat(budget.covers(1, T0.plusSeconds(3600))).as("an expired count is not known").isTrue();
        assertThat(budget.spend(T0.plusSeconds(3600)).isSuccess()).isTrue();
        assertThat(budget.spend(T0.plusSeconds(3600)).isFailure()).as("one request per re-check").isTrue();
        assertThat(budget.until(T0.plusSeconds(3600), 1)).isEqualTo(T0.plusSeconds(7200));
    }

    /// A reset already past (clock skew) with remaining 0 still waits a minute.
    @Test
    void pastReset_waitsAMinute() {
        var budget = observed(answer(403, some(0), some(T0.minusSeconds(60)), none(), ""));

        assertThat(budget.spend(T0.plusSeconds(59)).isFailure()).isTrue();
        assertThat(budget.until(T0, 1)).isEqualTo(T0.plusSeconds(60));
        assertThat(budget.spend(T0.plusSeconds(60)).isSuccess()).isTrue();
    }

    /// A reset within the hour is the reset itself.
    @Test
    void resetWithinTheHour_isTheReset() {
        var budget = observed(answer(200, some(10), some(T0.plusSeconds(900)), none(), ""));

        assertThat(budget.spend(T0.plusSeconds(899)).isFailure()).isTrue();
        assertThat(budget.spend(T0.plusSeconds(900)).isSuccess()).isTrue();
    }

    /// A 403 with requests remaining and a `Retry-After` refuses everything until it has passed.
    @Test
    void secondary_withRetryAfter_refusesUntilIt() {
        var budget = observed(answer(403, some(4000), some(T0.plusSeconds(1800)), some(Duration.ofSeconds(120)), "{}"));

        assertThat(budget.blocked(T0)).isTrue();
        assertThat(budget.covers(1, T0)).isFalse();
        assertThat(budget.spend(T0.plusSeconds(119)).isFailure()).isTrue();
        assertThat(budget.until(T0, 1)).isEqualTo(T0.plusSeconds(120));
        assertThat(budget.spend(T0.plusSeconds(120)).isSuccess()).isTrue();
        assertThat(budget.blocked(T0.plusSeconds(120))).isFalse();
    }

    /// GitHub's secondary-limit message without `Retry-After`: a minute.
    @Test
    void secondary_byItsMessage_waitsAMinute() {
        var budget = observed(answer(429, some(4000), none(), none(), "{\"message\":\"You have exceeded a Secondary Rate Limit\"}"));

        assertThat(budget.spend(T0.plusSeconds(59)).isFailure()).isTrue();
        assertThat(budget.spend(T0.plusSeconds(60)).isSuccess()).isTrue();
    }

    /// Controls: a plain 403 with requests left, a `Retry-After` on a 200, or a 403 with nothing (or no count)
    /// remaining, is no secondary limit; a 429 always is.
    @Test
    void secondary_isA429_orA403WithRequestsLeftAndRetryAfterOrTheMessage() {
        assertThat(RateBudget.secondary(answer(403, some(4000), none(), none(), "{\"message\":\"Forbidden\"}"), T0)).isEqualTo(none());
        assertThat(RateBudget.secondary(answer(200, some(4000), none(), some(Duration.ofSeconds(90)), "{}"), T0)).isEqualTo(none());
        assertThat(RateBudget.secondary(answer(403, some(0), none(), some(Duration.ofSeconds(90)), "{}"), T0)).isEqualTo(none());
        assertThat(RateBudget.secondary(answer(403, none(), none(), some(Duration.ofSeconds(90)), "{}"), T0)).isEqualTo(none());
        assertThat(RateBudget.secondary(answer(403, some(1), none(), some(Duration.ofSeconds(90)), "{}"), T0)).isEqualTo(some(Duration.ofSeconds(90)));
        assertThat(RateBudget.secondary(answer(429, none(), none(), none(), "{}"), T0)).isEqualTo(some(Duration.ofSeconds(60)));
        assertThat(RateBudget.secondary(answer(429, some(1), none(), some(Duration.ofSeconds(90)), "{}"), T0)).isEqualTo(some(Duration.ofSeconds(90)));
        assertThat(RateBudget.secondary(answer(403, some(0), none(), none(), "secondary rate limit"), T0)).isEqualTo(some(Duration.ofSeconds(60)));
        assertThat(observed(answer(403, some(4000), none(), none(), "{}")).spend(T0).isSuccess()).isTrue();
    }

    /// The wait is bounded to a minute .. an hour: a huge `Retry-After`, a date far ahead, a date past, a value that is
    /// neither, and a short one.
    @Test
    void secondary_wait_isBoundedToAMinuteAndAnHour() {
        assertThat(RateBudget.secondary(answer(403, some(1), none(), some(Duration.ofSeconds(999999999)), "{}"), T0)).isEqualTo(some(Duration.ofHours(1)));
        assertThat(RateBudget.secondary(retrying(new GhAnswer.RetryAfter.At(T0.plusSeconds(120))), T0)).isEqualTo(some(Duration.ofSeconds(120)));
        assertThat(RateBudget.secondary(retrying(new GhAnswer.RetryAfter.At(T0.plus(Duration.ofDays(9000)))), T0)).isEqualTo(some(Duration.ofHours(1)));
        assertThat(RateBudget.secondary(retrying(new GhAnswer.RetryAfter.At(T0.minusSeconds(600))), T0)).isEqualTo(some(Duration.ofSeconds(60)));
        assertThat(RateBudget.secondary(retrying(new GhAnswer.RetryAfter.Unreadable()), T0)).isEqualTo(some(Duration.ofSeconds(60)));
        assertThat(RateBudget.secondary(answer(403, some(1), none(), some(Duration.ofSeconds(5)), "{}"), T0)).isEqualTo(some(Duration.ofSeconds(60)));
        assertThat(RateBudget.secondary(answer(403, some(1), none(), some(Duration.ofSeconds(3600)), "{}"), T0)).isEqualTo(some(Duration.ofHours(1)));
        assertThat(RateBudget.secondary(answer(403, some(1), none(), some(Duration.ofSeconds(60)), "{}"), T0)).isEqualTo(some(Duration.ofSeconds(60)));
    }

    /// A count that covers the need exactly is no reason to wait; one short of it waits for its reset.
    @Test
    void until_waitsForTheReset_onlyWhenTheCountDoesNotCoverTheNeed() {
        var budget = observed(answer(200, some(22), some(T0.plusSeconds(900)), none(), ""));

        assertThat(budget.until(T0, 12)).as("22 - 10 covers 12").isEqualTo(T0.plusSeconds(3600));
        assertThat(budget.until(T0, 13)).isEqualTo(T0.plusSeconds(900));
    }

    /// A count of exactly the reserve, once expired, is re-checked once and waits for the re-check's answer.
    @Test
    void recheck_atExactlyTheReserve_allowsOneRequest() {
        var budget = observed(answer(200, some(10), some(T0.plusSeconds(60)), none(), ""));

        assertThat(budget.spend(T0.plusSeconds(60)).isSuccess()).isTrue();
        assertThat(budget.spend(T0.plusSeconds(60)).isFailure()).isTrue();
    }

    /// A count above the reserve expires a bounded window after it was reported, not after each request spent.
    @Test
    void countAboveTheReserve_expiresAnHourAfterItWasReported() {
        var budget = observed(answer(200, some(4000), none(), none(), ""));

        assertThat(budget.spend(T0.plusSeconds(3000)).isSuccess()).isTrue();
        assertThat(budget.covers(5000, T0.plusSeconds(3000))).isFalse();
        assertThat(budget.covers(5000, T0.plusSeconds(3600))).as("expired: not known").isTrue();
    }

    /// A cycle is refused when `count + need > hourlyCap`: 5 spent of 10 covers 5, not 6.
    @Test
    void covers_refusesACycle_thatWouldPassTheCap() {
        var budget = observed(RateBudget.rateBudget(10, 10), answer(200, some(4000), some(T0.plusSeconds(1800)), none(), ""));

        spend(budget, 5, T0);

        assertThat(budget.covers(5, T0)).as("5 + 5 = cap").isTrue();
        assertThat(budget.covers(6, T0)).as("5 + 6 > cap").isFalse();
        assertThat(budget.until(T0, 6)).as("waits for the window's end").isEqualTo(T0.plusSeconds(1800));
        assertThat(budget.spare(T0)).isEqualTo(some(5));
    }

    /// A cycle is refused when `remaining - reserve < need`, at the configured reserve.
    @Test
    void covers_refusesACycle_thatWouldPassTheReserve() {
        var budget = observed(RateBudget.rateBudget(1_000_000, 1500), answer(200, some(1510), some(T0.plusSeconds(1800)), none(), ""));

        assertThat(budget.covers(10, T0)).isTrue();
        assertThat(budget.covers(11, T0)).isFalse();
        assertThat(budget.spare(T0)).isEqualTo(some(10));
    }

    /// A single request is refused once kiln's count has reached the cap, until the window's end.
    @Test
    void spend_isRefused_atTheCap() {
        var budget = observed(RateBudget.rateBudget(3, 10), answer(200, some(4000), some(T0.plusSeconds(1800)), none(), ""));

        spend(budget, 3, T0);

        assertThat(budget.spend(T0).isFailure()).as("the 4th of 3").isTrue();
        assertThat(budget.spent(T0)).as("a refused request is not counted").isEqualTo(3);
        assertThat(budget.spend(T0.plusSeconds(1799)).isFailure()).isTrue();
    }

    /// A single request is refused while remaining is at or below the configured reserve, allowed one above.
    @Test
    void spend_isRefused_atTheConfiguredReserve() {
        var atReserve = observed(RateBudget.rateBudget(1_000_000, 1500), answer(200, some(1500), some(T0.plusSeconds(1800)), none(), ""));
        var above = observed(RateBudget.rateBudget(1_000_000, 1500), answer(200, some(1501), some(T0.plusSeconds(1800)), none(), ""));

        assertThat(atReserve.spend(T0).isFailure()).isTrue();
        assertThat(above.spend(T0).isSuccess()).isTrue();
    }

    /// The count starts at 0 when the window's reset passes, and when GitHub reports a new reset.
    @Test
    void count_startsAgain_whenTheWindowResets() {
        var reset = T0.plusSeconds(1800);
        var budget = observed(RateBudget.rateBudget(2, 10), answer(200, some(4000), some(reset), none(), ""));

        spend(budget, 2, T0);
        assertThat(budget.spend(T0).isFailure()).isTrue();
        assertThat(budget.spent(reset)).as("the window has ended").isZero();
        assertThat(budget.spend(reset).isSuccess()).isTrue();
        assertThat(budget.spent(reset)).isEqualTo(1);

        budget.observed(answer(200, some(4999), some(reset.plusSeconds(3600)), none(), ""), reset);
        assertThat(budget.spent(reset)).as("the first answer of the new window keeps what was sent since").isEqualTo(1);

        budget.observed(answer(200, some(4998), some(reset.plusSeconds(7200)), none(), ""), reset);
        assertThat(budget.spent(reset)).as("a different reset reported: a new window").isZero();
        spend(budget, 2, reset);
        assertThat(budget.spend(reset).isFailure()).isTrue();

        budget.observed(answer(200, some(4000), some(reset.plusSeconds(7200)), none(), ""), reset);
        assertThat(budget.spent(reset)).as("the same reset again keeps the count").isEqualTo(2);
    }

    /// Before the first answer no window is known: nothing is refused for the cap.
    @Test
    void beforeTheFirstAnswer_theCapDoesNotRefuse() {
        var budget = RateBudget.rateBudget(1, 10);

        spend(budget, 3, T0);

        assertThat(budget.covers(100, T0)).isTrue();
    }

    /// `3600 x need / min(limit - reserve, cap)`, never shorter than the base period; an exhausted budget waits an hour.
    @Test
    void period_stretchesAgainstTheEffectiveBudget() {
        var base = Duration.ofSeconds(60);
        var byLimit = observed(RateBudget.rateBudget(1_000_000, 1500), answer(200, some(4000), none(), none(), ""));
        var byCap = observed(RateBudget.rateBudget(1000, 1500), answer(200, some(4000), none(), none(), ""));
        var spent = observed(RateBudget.rateBudget(1_000_000, 5000), answer(200, some(4000), none(), none(), ""));

        assertThat(byLimit.period(100, base)).as("limit 5000 - reserve 1500 = 3500 < cap").isEqualTo(Duration.ofSeconds(103));
        assertThat(byCap.period(100, base)).as("cap 1000 < 3500").isEqualTo(Duration.ofSeconds(360));
        assertThat(byCap.period(10, base)).as("never shorter than the base").isEqualTo(base);
        assertThat(spent.period(1, base)).as("limit - reserve = 0").isEqualTo(Duration.ofHours(1));
        assertThat(RateBudget.rateBudget(1000, 1500).period(100, base)).as("no limit known").isEqualTo(base);
    }

    private static void spend(RateBudget budget, int times, Instant at) {
        for (var i = 0; i < times; i++) {
            assertThat(budget.spend(at).isSuccess()).isTrue();
        }
    }

    private static RateBudget observed(RateBudget budget, GhAnswer answer) {
        budget.observed(answer, T0);
        return budget;
    }

    private static RateBudget observed(GhAnswer answer) {
        var budget = RateBudget.rateBudget(1_000_000, 10);

        budget.observed(answer, T0);
        return budget;
    }

    /// CI interface T1 WP2 (R3-5): the re-read is capped by what is left above the reserve, never below 0; unknown
    /// before the first count, and again once the count expires.
    @Test
    void spare_isTheCountAboveTheReserve_whileCurrent() {
        var budget = RateBudget.rateBudget(1_000_000, 10);
        var at = Instant.parse("2026-11-03T10:00:00Z");

        assertThat(budget.spare(at)).isEqualTo(none());
        budget.observed(answer(200, some(25), some(at.plusSeconds(600)), none(), "{}"), at);
        assertThat(budget.spare(at)).isEqualTo(some(25 - 10));
        budget.observed(answer(200, some(4), some(at.plusSeconds(600)), none(), "{}"), at);
        assertThat(budget.spare(at)).isEqualTo(some(0));
        assertThat(budget.spare(at.plusSeconds(601))).isEqualTo(none());
    }

    private static GhAnswer answer(int status,
                                   Option<Integer> remaining,
                                   Option<Instant> reset,
                                   Option<Duration> retryAfter,
                                   String body) {
        return new GhAnswer(status,
                            none(),
                            body,
                            remaining,
                            some(5000),
                            reset,
                            none(),
                            retryAfter.map(GhAnswer.RetryAfter.Delay::new));
    }

    /// A 403 with requests left and this `Retry-After`.
    private static GhAnswer retrying(GhAnswer.RetryAfter after) {
        return new GhAnswer(403, none(), "{}", some(1), some(5000), none(), none(), some(after));
    }
}
