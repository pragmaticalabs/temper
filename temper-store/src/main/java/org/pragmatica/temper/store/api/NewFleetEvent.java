package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// A fleet event to insert. `sourceEpoch`/`sourceSeq` are set for kiln rows only (the
/// `UNIQUE(source, source_epoch, source_seq)` key); `payload` is a JSON object's text.
public record NewFleetEvent(Instant observedAt,
                            String source,
                            Option<String> sourceEpoch,
                            Option<Long> sourceSeq,
                            String kind,
                            String subject,
                            String payload,
                            boolean catchUp) {
    /// An event of a producer other than kiln: no source key.
    public static NewFleetEvent newFleetEvent(Instant observedAt,
                                              String source,
                                              String kind,
                                              String subject,
                                              String payload) {
        return new NewFleetEvent(observedAt, source, Option.none(), Option.none(), kind, subject, payload, false);
    }

    /// A kiln change row, keyed by kiln's epoch (with the rewind generation) and change id.
    public static NewFleetEvent kilnRow(Instant observedAt,
                                        String sourceEpoch,
                                        long sourceSeq,
                                        String kind,
                                        String subject,
                                        String payload,
                                        boolean catchUp) {
        return new NewFleetEvent(observedAt,
                                 "kiln",
                                 Option.some(sourceEpoch),
                                 Option.some(sourceSeq),
                                 kind,
                                 subject,
                                 payload,
                                 catchUp);
    }
}
