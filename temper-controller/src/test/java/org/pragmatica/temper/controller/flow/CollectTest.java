package org.pragmatica.temper.controller.flow;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The two fork-join collapses: [Collect#all] keeps every failure, [Collect#first] the first in list order, unwrapped.
class CollectTest {
    private static final Cause SLOW = Causes.cause("slow, listed first");
    private static final Cause FAST = Causes.cause("fast, listed second");

    /// The first-listed failure settles last, so list order, not completion order, is what is pinned.
    private static List<Promise<Integer>> twoFailures() {
        return List.of(Promise.<Integer> promise(timeSpan(200).millis(), promise -> promise.fail(SLOW)),
                       FAST.promise());
    }

    @Test
    void all_values_inOrder() {
        assertThat(Collect.all(List.of(Promise.success(1), Promise.success(2))).await().unwrap()).containsExactly(1, 2);
    }

    @Test
    void first_values_inOrder() {
        assertThat(Collect.first(List.of(Promise.success(1), Promise.success(2))).await().unwrap()).containsExactly(1, 2);
    }

    @Test
    void all_failsComposite_withEveryFailureInListOrder() {
        var cause = Collect.all(twoFailures()).await().fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(Causes.CompositeCause.class);
        assertThat(cause.message()).startsWith("Composite:")
                                   .containsSubsequence(SLOW.message(), FAST.message());
    }

    @Test
    void all_failsComposite_evenForOneFailure() {
        var cause = Collect.all(List.of(Promise.success(1), FAST.<Integer> promise())).await().fold(failure -> failure, _ -> null);

        assertThat(cause).isInstanceOf(Causes.CompositeCause.class);
    }

    @Test
    void first_failsWithTheFirstListedFailure_unwrapped() {
        var cause = Collect.first(twoFailures()).await().fold(failure -> failure, _ -> null);

        assertThat(cause).isSameAs(SLOW);
    }
}
