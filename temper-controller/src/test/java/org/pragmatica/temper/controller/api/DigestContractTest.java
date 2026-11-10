package org.pragmatica.temper.controller.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.store.api.DigestQuery;
import org.pragmatica.temper.store.api.EventCursor;

import static org.assertj.core.api.Assertions.assertThat;

/// The digest's wire constants exist twice, because `temper-store` names no protocol type (ruling R-10) and the CLI
/// sees only the protocol: the cursor pattern and the `max` bound must agree, or the CLI would accept what the
/// controller refuses.
class DigestContractTest {
    @Test
    void cursorPatternAndMaxBound_agreeBetweenProtocolAndStore() {
        assertThat(DigestView.CURSOR.pattern()).isEqualTo(EventCursor.WIRE.pattern());
        assertThat(DigestView.MAX_EVENTS).isEqualTo(DigestQuery.MAX_EVENTS);
    }
}
