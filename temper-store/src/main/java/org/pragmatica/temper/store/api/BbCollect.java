package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// Collection state of one host. `failingSince` is the first failed attempt after the
/// last success, none while the last attempt succeeded; `lastError` is one of the strings.
public record BbCollect(String host,
                        Option<Instant> lastAttemptAt,
                        Option<Instant> lastOkAt,
                        Option<Instant> failingSince,
                        Option<String> lastError) {
    public static BbCollect bbCollect(String host,
                                      Option<Instant> lastAttemptAt,
                                      Option<Instant> lastOkAt,
                                      Option<Instant> failingSince,
                                      Option<String> lastError) {
        return new BbCollect(host, lastAttemptAt, lastOkAt, failingSince, lastError);
    }
}
