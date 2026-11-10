package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// The `sending` row of a review about to be posted (§5.3 step 8). `carry` is the proven carry's parent 2 and the PR's
/// base ref the proof used (step 5d); `proof` is the step-5 outcome that is logged (`fresh`, `carry-proven`).
public record NewReview(String repo,
                        long pr,
                        String sha,
                        String event,
                        long verdictId,
                        String as,
                        Option<Carry> carry,
                        String proof,
                        String bodySha256) {
    public record Carry(String baseTip, String baseRef) {}

    public static NewReview newReview(String repo,
                                      long pr,
                                      String sha,
                                      String event,
                                      long verdictId,
                                      String as,
                                      Option<Carry> carry,
                                      String proof,
                                      String bodySha256) {
        return new NewReview(repo, pr, sha, event, verdictId, as, carry, proof, bodySha256);
    }
}
