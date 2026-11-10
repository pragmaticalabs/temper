package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// What `GET fleet/reviews` shows of a `reviews` row: the GitHub review id (none until posted), the
/// state, the reviewed SHA and when the row last went `sending`.
public record ReviewListing(Option<Long> githubReviewId, String state, String sha, Instant sendingAt) {
    public static ReviewListing reviewListing(Option<Long> githubReviewId,
                                              String state,
                                              String sha,
                                              Instant sendingAt) {
        return new ReviewListing(githubReviewId, state, sha, sendingAt);
    }
}
