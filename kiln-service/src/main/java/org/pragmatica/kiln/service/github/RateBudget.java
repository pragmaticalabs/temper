package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.unitResult;


/// GitHub's rate limit as its last answer reported it (design §8, Q-7), and kiln's own spend against it: kiln never
/// spends while `X-RateLimit-Remaining` is at or below the configured `reserve`, nor more than `hourlyCap` requests of
/// its own in GitHub's current rate window (the owner account's 5,000/h is shared with the whole fleet, so kiln takes a
/// bounded share). [#spend] refuses each request at the reserve or the cap, so even a cycle that needs more than
/// estimated stops there; [#covers] refuses a whole cycle whose need does not fit under either, and [#period] stretches
/// the cycle to `3600 × need / min(limit - reserve, hourlyCap)`. Before the first answer nothing is known, and nothing
/// is refused. The window is the `reset` GitHub reports: kiln's count starts at 0 when that moment passes or a new one
/// is reported, and is counted from its own requests only.
///
/// A reported count expires at its reset, bounded to [#FLOOR] .. [#WINDOW] after it was reported: a reset already past
/// (clock skew) still waits a minute, a reset far ahead (or none reported) waits at most GitHub's one-hour window. A
/// count at the reserve that has expired allows exactly one request, a cautious re-check whose answer reports the
/// count anew; until an answer does, the next is refused for another bounded window.
///
/// A secondary rate limit refuses every request until its wait has passed: `Retry-After` (seconds or a date) bounded
/// to [#FLOOR] .. [#WINDOW], or [#FLOOR] without a readable one. A 429 is always one; a 403 is one when requests remain
/// and it carries a `Retry-After` in any form, or when its message says so.
public record RateBudget(AtomicReference<Rate> rate, int hourlyCap, int reserve) {
    /// GitHub's primary window: no reported reset is waited for longer than this.
    public static final Duration WINDOW = Duration.ofHours(1);
    /// The shortest wait: for a reset already past, and for a secondary limit without `Retry-After` (GitHub: at least a
    /// minute).
    public static final Duration FLOOR = Duration.ofSeconds(60);
    private static final long HOUR = 3600;
    private static final Pattern SECONDARY = Pattern.compile("(?i)secondary rate limit");

    /// The last reported headers, each absent one keeping its last value; `at` is when `remaining` was last reported (or
    /// re-checked), and `blocked` the end of a secondary limit. `spent` is kiln's own requests in the rate window that
    /// ends at `window` (the reset the count belongs to; none until an answer reports one).
    public record Rate(Option<Integer> remaining,
                       Option<Integer> limit,
                       Option<Instant> reset,
                       Option<Instant> at,
                       Option<Instant> blocked,
                       int spent,
                       Option<Instant> window) {
        public static Rate rate(Option<Integer> remaining,
                                Option<Integer> limit,
                                Option<Instant> reset,
                                Option<Instant> at,
                                Option<Instant> blocked,
                                int spent,
                                Option<Instant> window) {
            return new Rate(remaining, limit, reset, at, blocked, spent, window);
        }

        Rate updated(GhAnswer answer, Instant now) {
            return rate(answer.remaining().orElse(remaining),
                        answer.limit().orElse(limit),
                        answer.reset().orElse(reset),
                        answer.remaining().map(_ -> now).orElse(at),
                        secondary(answer, now).map(now::plus).orElse(blocked),
                        answer.reset()
                              .flatMap(reported -> window.filter(known -> !known.equals(reported)))
                              .isPresent()
                        ? 0
                        : spent,
                        answer.reset().orElse(window));
        }

        /// The count of a window that has ended starts again at 0.
        Rate rolled(Instant now) {
            return window.filter(end -> !now.isBefore(end))
                         .isPresent()
                   ? rate(remaining, limit, reset, at, blocked, 0, none())
                   : this;
        }

        /// One request sent: refused ones change nothing; the one re-check of an expired count at the reserve renews
        /// its window, so the next request waits for the re-check's answer.
        Rate charged(Instant now, int cap, int reserve) {
            var live = rolled(now);

            return live.refusal(now, cap, reserve)
                       .isPresent()
                   ? live
                   : live.rechecked(now, reserve)
                         .counted();
        }

        private Rate counted() {
            return rate(remaining, limit, reset, at, blocked, spent + 1, window);
        }

        private Rate rechecked(Instant now, int reserve) {
            return low(reserve)
                   ? rate(remaining, limit, reset, some(now), blocked, spent, window)
                   : this;
        }

        /// The requests kiln may still send in the current window under `cap`; none while no window is known.
        /// Enforced only while the reported reset is in the future. If kiln's clock runs ahead of GitHub's, the count
        /// restarts early and may overshoot the cap by at most the skew; accepted, the reserve guard still holds.
        Option<Integer> capLeft(Instant now, int cap) {
            return window.filter(now::isBefore)
                         .map(_ -> Math.max(0, cap - spent));
        }

        /// When the reported count stops being current: its reset, bounded to [#FLOOR] .. [#WINDOW] after `at`.
        Option<Instant> expiry() {
            return at.map(seen -> bounded(seen,
                                          reset.or(seen.plus(WINDOW))));
        }

        /// The reported count while it is current.
        Option<Integer> current(Instant now) {
            return expiry().filter(now::isBefore)
                         .flatMap(_ -> remaining);
        }

        /// Until when a request is refused: a secondary limit, a current count at the reserve, or kiln's own count at
        /// the cap (until the window ends).
        Option<Instant> refusal(Instant now, int cap, int reserve) {
            return Option.from(Stream.of(blocked.filter(now::isBefore),
                                         current(now).filter(left -> left <= reserve).flatMap(_ -> expiry()),
                                         capLeft(now, cap).filter(left -> left == 0).flatMap(_ -> window))
                                     .flatMap(Option::stream)
                                     .max(Instant::compareTo));
        }

        private boolean low(int reserve) {
            return remaining.filter(left -> left <= reserve)
                            .isPresent();
        }

        private static Instant bounded(Instant seen, Instant reset) {
            return latest(seen.plus(FLOOR), earliest(seen.plus(WINDOW), reset));
        }
    }

    public static RateBudget rateBudget(int hourlyCap, int reserve) {
        return new RateBudget(new AtomicReference<>(Rate.rate(none(), none(), none(), none(), none(), 0, none())),
                              hourlyCap,
                              reserve);
    }

    /// Records an answer's rate headers, received at `now`, and hands the answer on.
    public GhAnswer observed(GhAnswer answer, Instant now) {
        rate.updateAndGet(last -> last.updated(answer, now));

        return answer;
    }

    /// One more request is allowed unless a secondary limit holds, a current count is at the reserve, or kiln's own count
    /// has reached the cap; an allowed one is counted. An expired count at the reserve allows this one request as its
    /// re-check.
    public Result<Unit> spend(Instant now) {
        return rate.getAndUpdate(last -> last.charged(now, hourlyCap, reserve))
                   .rolled(now)
                   .refusal(now, hourlyCap, reserve)
                   .map(until -> GitHubReadError.BudgetSpent.FACTORY.apply(until).<Unit> result())
                   .or(unitResult());
    }

    /// Whether a cycle of `need` requests fits above the reserve and under the cap. An expired count is not known, and a
    /// re-check runs.
    public boolean covers(int need, Instant now) {
        return ! blocked(now) && fits(need, now);
    }

    private boolean fits(int need, Instant now) {
        var last = rate.get();

        return last.current(now)
                   .map(remaining -> remaining - reserve >= need)
                   .or(true) && last.capLeft(now, hourlyCap)
                                    .map(left -> left >= need)
                                    .or(true);
    }

    /// kiln's own requests in the current window (0 once the window has ended).
    public int spent(Instant now) {
        return rate.get()
                   .rolled(now)
                   .spent();
    }

    /// The requests left above the reserve by the current count and under the cap (never below 0); none while no count
    /// is current.
    public Option<Integer> spare(Instant now) {
        var last = rate.get();

        return last.current(now)
                   .map(remaining -> Math.max(0, remaining - reserve))
                   .map(above -> last.capLeft(now, hourlyCap)
                                     .map(left -> Math.min(above, left))
                                     .or(above));
    }

    /// `3600 × need / min(limit - reserve, hourlyCap)` seconds, never shorter than `base`; `base` until a limit is
    /// reported; [#WINDOW] when nothing is left to spend (limit at or below the reserve).
    public Duration period(int need, Duration base) {
        return stretched(need).filter(stretched -> stretched.compareTo(base) > 0)
                        .or(base);
    }

    private Option<Duration> stretched(int need) {
        return rate.get()
                   .limit()
                   .map(limit -> Math.min(limit - reserve, hourlyCap))
                   .map(effective -> effective > 0
                                     ? Duration.ofSeconds(Math.ceilDiv(HOUR * need, effective))
                                     : WINDOW);
    }

    /// When `need` requests may be sent again after `now`: the end of a secondary limit, of a current count that does
    /// not cover them above the reserve, and of a window whose cap they would pass, whichever is later; [#WINDOW] after
    /// `now` when none holds.
    public Instant until(Instant now, int need) {
        var last = rate.get();

        return Option.from(Stream.of(last.blocked().filter(now::isBefore),
                                     last.current(now)
                                         .filter(remaining -> remaining - reserve < need)
                                         .flatMap(_ -> last.expiry()),
                                     last.capLeft(now, hourlyCap)
                                         .filter(left -> left < need)
                                         .flatMap(_ -> last.window()))
                                 .flatMap(Option::stream)
                                 .max(Instant::compareTo)).or(now.plus(WINDOW));
    }

    /// GitHub reported no request left: a 403 or 429 then means the rate limit, not a refusal.
    public boolean exhausted() {
        return rate.get()
                   .remaining()
                   .filter(remaining -> remaining == 0)
                   .isPresent();
    }

    /// A secondary rate limit holds at `now`.
    public boolean blocked(Instant now) {
        return rate.get()
                   .blocked()
                   .filter(now::isBefore)
                   .isPresent();
    }

    /// The wait of a secondary limit received at `now`, none when the answer is not one: `Retry-After` bounded to
    /// [#FLOOR] .. [#WINDOW], or [#FLOOR] without a readable one.
    static Option<Duration> secondary(GhAnswer answer, Instant now) {
        return isSecondary(answer)
               ? some(bounded(answer.retryAfter().map(after -> waited(after, now)).or(FLOOR)))
               : none();
    }

    private static boolean isSecondary(GhAnswer answer) {
        return answer.status() == 429 || answer.status() == 403
                                         && (remains(answer) && answer.retryAfter()
                                                                      .isPresent() || SECONDARY.matcher(answer.body()).find());
    }

    private static Duration waited(GhAnswer.RetryAfter after, Instant now) {
        return switch (after) {
            case GhAnswer.RetryAfter.Delay(var delay) -> delay;
            case GhAnswer.RetryAfter.At(var at) -> Duration.between(now, at);
            case GhAnswer.RetryAfter.Unreadable _ -> FLOOR;
        };
    }

    private static Duration bounded(Duration wait) {
        return wait.compareTo(FLOOR) < 0
               ? FLOOR
               : shortest(wait, WINDOW);
    }

    private static Duration shortest(Duration first, Duration second) {
        return first.compareTo(second) < 0
               ? first
               : second;
    }

    private static boolean remains(GhAnswer answer) {
        return answer.remaining()
                     .filter(left -> left > 0)
                     .isPresent();
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isBefore(second)
               ? first
               : second;
    }

    private static Instant latest(Instant first, Instant second) {
        return first.isAfter(second)
               ? first
               : second;
    }
}
