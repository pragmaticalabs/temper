package org.pragmatica.temper.controller.review;

import java.time.Instant;

import org.pragmatica.lang.Cause;


/// Why a GitHub call of the review path failed. A message carries a status or
/// GitHub's own first line, never a credential, a header or a request.
public sealed interface ReviewError extends Cause {
    /// GitHub refused the review itself (422): the first line of its message.
    record Rejected(String firstLine, String message) implements ReviewError {
        public static Rejected rejected(String firstLine) {
            return new Rejected(firstLine, "GitHub rejected the review: " + firstLine);
        }
    }

    /// 401, or 403 that is no rate limit; or the PAT file could not be read.
    record Credential(String detail, String message) implements ReviewError {
        public static Credential credential(String detail) {
            return new Credential(detail, "reviewer credential: " + detail);
        }
    }

    /// A primary or secondary rate limit; `until` is when GitHub lets the identity call again.
    record RateLimited(Instant until, String message) implements ReviewError {
        public static RateLimited rateLimited(Instant until) {
            return new RateLimited(until, "rate limited until " + until);
        }
    }

    /// The outcome of a POST is not known (timeout, 5xx, a broken exchange): the row stays `sending`.
    record Unknown(String detail, String message) implements ReviewError {
        public static Unknown unknown(String detail) {
            return new Unknown(detail, "outcome unknown: " + detail);
        }
    }

    /// A read that failed (the App token, the status, the body).
    record ReadFailed(String detail, String message) implements ReviewError {
        public static ReadFailed readFailed(String detail) {
            return new ReadFailed(detail, "read failed: " + detail);
        }
    }
}
