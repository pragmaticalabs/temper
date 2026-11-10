package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Option;


/// One GitHub answer as kiln needs it: the status, its `ETag`, the body (on a 304 through [GitHubReads], the last 200
/// body replayed from the ETag store), `X-RateLimit-Remaining`, `X-RateLimit-Limit` and `X-RateLimit-Reset`, and the
/// `Link: rel="next"` path. WP6 added `limit`: the cycle stretches to `3600 × need / limit` (Q-7); and `retryAfter`
/// (`Retry-After`, in either form): a secondary rate limit names how long nothing may be sent.
public record GhAnswer(int status,
                       Option<String> etag,
                       String body,
                       Option<Integer> remaining,
                       Option<Integer> limit,
                       Option<Instant> reset,
                       Option<String> next,
                       Option<GhAnswer.RetryAfter> retryAfter) {
    /// `Retry-After` as sent: seconds, an HTTP date, or a value that is neither (still a `Retry-After`).
    public sealed interface RetryAfter {
        record Delay(Duration delay) implements RetryAfter {}

        record At(Instant at) implements RetryAfter {}

        record Unreadable() implements RetryAfter {}
    }

    public static GhAnswer ghAnswer(int status,
                                    Option<String> etag,
                                    String body,
                                    Option<Integer> remaining,
                                    Option<Integer> limit,
                                    Option<Instant> reset,
                                    Option<String> next,
                                    Option<RetryAfter> retryAfter) {
        return new GhAnswer(status, etag, body, remaining, limit, reset, next, retryAfter);
    }
}
