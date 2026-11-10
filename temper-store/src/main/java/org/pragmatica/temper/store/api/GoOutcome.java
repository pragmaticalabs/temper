package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// An outcome to record on a consumed GO: `refused`, `merging` (with the base tip before the
/// merge call), `merged` or `merge-failed`, with an optional detail of at most 500 characters.
public record GoOutcome(String id, String outcome, Option<String> detail, Option<String> baseTipBefore) {
    public static GoOutcome goOutcome(String id, String outcome, Option<String> detail, Option<String> baseTipBefore) {
        return new GoOutcome(id, outcome, detail, baseTipBefore);
    }
}
