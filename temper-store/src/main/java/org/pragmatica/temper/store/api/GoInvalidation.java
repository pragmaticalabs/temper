package org.pragmatica.temper.store.api;

/// A GO the `t2:detector` invalidates: `reason` (`checks-changed` | `base-moved`) and
/// `causeSeq`, the seq of the fleet row that matched (the `go.invalidated_by_seq` column, the event's `causeSeq`).
public record GoInvalidation(OpenGo go, String reason, long causeSeq) {
    public static GoInvalidation goInvalidation(OpenGo go, String reason, long causeSeq) {
        return new GoInvalidation(go, reason, causeSeq);
    }
}
