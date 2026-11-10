package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// The latest `verdicts` row by id for (repo, pr, sha), the one G6 reads.
public record VerdictRow(long id,
                         String verdict,
                         String by,
                         Instant at,
                         String base,
                         String reportPath,
                         String reportSha256,
                         Option<String> carriedFrom) {
    public static VerdictRow verdictRow(long id,
                                        String verdict,
                                        String by,
                                        Instant at,
                                        String base,
                                        String reportPath,
                                        String reportSha256,
                                        Option<String> carriedFrom) {
        return new VerdictRow(id, verdict, by, at, base, reportPath, reportSha256, carriedFrom);
    }
}
