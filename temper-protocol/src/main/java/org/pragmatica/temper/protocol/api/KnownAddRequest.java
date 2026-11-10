package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.known.KnownEntry;


/// `POST /api/v1/known` body (m2-design §4.4). Parsing gives a [KnownEntry] or a 400: a `testId` that is neither a
/// class nor `class#name`, a non-positive or absent `issue`, a blank `reason` or `until`, a `createdBy` outside the
/// owner alphabet. An active entry for the same `testId` is the store's 409.
public record KnownAddRequest(String testId, int issue, String reason, String until, String createdBy) {
    public static KnownAddRequest knownAddRequest(String testId,
                                                  int issue,
                                                  String reason,
                                                  String until,
                                                  String createdBy) {
        return new KnownAddRequest(testId, issue, reason, until, createdBy);
    }

    public Result<KnownEntry> toDomain() {
        return KnownEntry.knownEntry(testId, issue, reason, until, createdBy);
    }
}
