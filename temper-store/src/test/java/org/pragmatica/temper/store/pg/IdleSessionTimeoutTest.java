package org.pragmatica.temper.store.pg;

import org.pragmatica.temper.store.api.StoreError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The start guard's reading of what Postgres reports for `idle_session_timeout`: disabled and at least 150 s pass,
/// anything between 0 and 150 s (in any unit Postgres prints) is refused with the value and the bound in the message.
class IdleSessionTimeoutTest {
    @Test
    void disabledAndLongValuesPass() {
        for (var value : new String[]{"0", "150s", "151s", "3min", "1h", "1d", "150000ms", "0ms", "0s"}) {
            assertThat(IdleSessionTimeout.check(value).isSuccess()).as(value).isTrue();
        }
    }

    @Test
    void shortValuesInEveryUnitAreRefused() {
        for (var value : new String[]{"1ms", "149999ms", "1s", "60s", "149s", "1min", "2min"}) {
            var refused = IdleSessionTimeout.check(value);

            assertThat(refused.isFailure()).as(value).isTrue();
            refused.onFailure(cause -> {
                assertThat(cause).isInstanceOf(StoreError.IdleSessionTimeout.class);
                assertThat(cause.message()).contains("idle_session_timeout", value, "150");
            });
        }
    }

    @Test
    void anUnreadableValueIsRefused() {
        assertThat(IdleSessionTimeout.check("soon").isFailure()).isTrue();
        assertThat(IdleSessionTimeout.check("5 fortnights").isFailure()).isTrue();
    }
}
