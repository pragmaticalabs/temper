package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// A `reviews` row (§5.3). `state` is `sending`, `posted` or `failed`.
public record ReviewRow(long id,
                        String repo,
                        long pr,
                        String sha,
                        String event,
                        String state,
                        long verdictId,
                        String as,
                        int attempts,
                        Option<Long> githubReviewId,
                        Option<String> carryBaseTip) {
    public static ReviewRow reviewRow(long id,
                                      String repo,
                                      long pr,
                                      String sha,
                                      String event,
                                      String state,
                                      long verdictId,
                                      String as,
                                      int attempts,
                                      Option<Long> githubReviewId,
                                      Option<String> carryBaseTip) {
        return new ReviewRow(id, repo, pr, sha, event, state, verdictId, as, attempts, githubReviewId, carryBaseTip);
    }
}
