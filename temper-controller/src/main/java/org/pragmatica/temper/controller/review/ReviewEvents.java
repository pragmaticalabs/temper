package org.pragmatica.temper.controller.review;

import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.ReviewRow;

import tools.jackson.databind.node.JsonNodeFactory;


/// The events the review path emits, `source=controller`, `catchUp=false`.
/// `unrecorded-review`, `review-dismissed` and `review-exempted` are WP-4b's and WP-2's.
public sealed interface ReviewEvents {
    String SOURCE = "controller";
    String POSTED = "review-posted";
    String REFUSED = "review-refused";

    /// `review-posted`, subject `<repo>#<pr>`: `sha`, `event`, `as`, `verdict`, `review`.
    static List<NewFleetEvent> posted(Instant now, ReviewRow row) {
        return List.of(event(now,
                             POSTED,
                             row.repo() + "#" + row.pr(),
                             JsonNodeFactory.instance.objectNode()
                                                     .put("sha",
                                                          row.sha())
                                                     .put("event",
                                                          row.event())
                                                     .put("as",
                                                          row.as())
                                                     .put("verdict",
                                                          row.verdictId())
                                                     .put("review",
                                                          row.githubReviewId().or(0L))
                                                     .toString()));
    }

    /// `review-refused`, subject `<repo>#<pr>`: `code`, `as`; only for `SELF_REVIEW` and `VERIFIER_MISMATCH`.
    static List<NewFleetEvent> refused(Instant now, String repo, long pr, String code, String as) {
        return List.of(event(now,
                             REFUSED,
                             repo + "#" + pr,
                             JsonNodeFactory.instance.objectNode().put("code", code).put("as", as).toString()));
    }

    private static NewFleetEvent event(Instant now, String kind, String subject, String payload) {
        return NewFleetEvent.newFleetEvent(now, SOURCE, kind, subject, payload);
    }

    record unused() implements ReviewEvents {}
}
