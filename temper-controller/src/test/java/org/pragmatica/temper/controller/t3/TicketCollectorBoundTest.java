package org.pragmatica.temper.controller.t3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The collector's own read bound sits strictly above the transport's 20 s bound, so the transport's `timeout` reports
/// first. Mutation: bound at or below the transport's → red.
class TicketCollectorBoundTest {
    @Test
    void theCollectorBound_isStrictlyAboveTheTransportBound() {
        assertThat(TicketCollector.READ_BOUND.millis()).isGreaterThan(CommandTransport.READ_BOUND.millis());
    }
}
