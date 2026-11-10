package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;


/// A registered `verdicts` row: its id and database time, the report fields it holds (a carry's
/// are the carried ACCEPT's), and the outstanding GOs of its (repo, pr) it invalidated with `verdict-changed`, in
/// creation order.
public record RegisteredVerdict(long id,
                                Instant at,
                                String reportPath,
                                String reportSha256,
                                List<InvalidatedGo> invalidated) {
    public RegisteredVerdict {
        invalidated = List.copyOf(invalidated);
    }

    public static RegisteredVerdict registeredVerdict(long id,
                                                      Instant at,
                                                      String reportPath,
                                                      String reportSha256,
                                                      List<InvalidatedGo> invalidated) {
        return new RegisteredVerdict(id, at, reportPath, reportSha256, invalidated);
    }
}
