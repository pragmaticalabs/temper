package org.pragmatica.temper.acceptance.stack;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The start-failure path of [GateKiln]: the store's pool is closed, and a failed close rides on the original failure.
class GateKilnTest {
    @Test
    void aFailedCloseDuringAStartFailure_isSuppressedOnTheOriginalThrow() {
        var original = new IllegalStateException("kiln no longer registers GET /x");

        assertThatThrownBy(() -> GateKiln.closingOnFailure(() -> Causes.cause("pool stuck").promise(), () -> {
            throw original;
        })).isSameAs(original)
           .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
                                                         .allSatisfy(s -> assertThat(s).hasMessage("store close failed: pool stuck")));
    }

    @Test
    void aStartFailure_closesTheStore_andASuccessfulCloseSuppressesNothing() {
        var closed = new AtomicBoolean();

        assertThatThrownBy(() -> GateKiln.closingOnFailure(() -> {
            closed.set(true);
            return Promise.success(Unit.unit());
        }, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom")
           .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        assertThat(closed).isTrue();
    }

    @Test
    void aSuccessfulStart_doesNotCloseTheStore() {
        var closed = new AtomicBoolean();

        assertThat(GateKiln.closingOnFailure(() -> {
            closed.set(true);
            return Promise.success(Unit.unit());
        }, () -> "up")).isEqualTo("up");
        assertThat(closed).isFalse();
    }
}
