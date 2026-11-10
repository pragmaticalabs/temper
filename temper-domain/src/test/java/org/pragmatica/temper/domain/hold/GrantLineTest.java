package org.pragmatica.temper.domain.hold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// A11 unit tier (m1-design §1.4, §9.1): only a whole `granted <ticket>` line is a grant.
class GrantLineTest {
    @Test
    void ticket_isRead_fromTheGrantLine() {
        assertThat(GrantLine.ticket("granted T1\n")).isEqualTo(Option.some("T1"));
        assertThat(GrantLine.ticket("granted 20261013-101500-temper-1")).isEqualTo(Option.some("20261013-101500-temper-1"));
    }

    @Test
    void ticket_isRead_amongOtherStderrNoise() {
        var stderr = "bb-hold: queued ticket 20261013-1 (waiting for [port-group])\n"
                     + "granted 20261013-1\r\n"
                     + "bb-hold: docker wait 4f3c\n";

        assertThat(GrantLine.ticket(stderr)).isEqualTo(Option.some("20261013-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bb-hold: waiting\n", "not granted yet\n", "bb-hold: granted T1\n", "granted\n", "granted \n",
                            "granted T1 extra\n", "  granted T1\n", "granted"})
    void ticket_isNone_forNoiseAndPartialLines(String stderr) {
        assertThat(GrantLine.ticket(stderr)).isEqualTo(Option.none());
    }

    @Test
    void ticket_isTheFirstGrant() {
        assertThat(GrantLine.ticket("granted A\ngranted B\n")).isEqualTo(Option.some("A"));
    }
}
