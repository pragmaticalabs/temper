package org.pragmatica.temper.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// [Loop]'s failure paths: a step that fails, or throws, ends the loop with a failure instead of leaving it
/// pending forever (a hung `wait` or `watch`).
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LoopTest {
    @Test
    void loop_fails_whenAStepThrows() {
        var outcome = Loop.<Integer, String> loop(0, LoopTest::throwingAtThree)
                          .await();

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).contains("step 3 broke"));
    }

    @Test
    void loop_endsWithTheDoneValue_afterManySynchronousTurns() {
        var outcome = Loop.<Integer, String> loop(0, LoopTest::countToFiftyThousand)
                          .await();

        assertThat(outcome.isSuccess()).isTrue();
        outcome.onSuccess(value -> assertThat(value).isEqualTo("done at 50000"));
    }

    private static Promise<Loop.Turn<Integer, String>> throwingAtThree(Integer state) {
        if (state == 3) {
            throw new IllegalStateException("step 3 broke");
        }
        return Promise.success(Loop.Turn.again(state + 1));
    }

    private static Promise<Loop.Turn<Integer, String>> countToFiftyThousand(Integer state) {
        return Promise.success(state < 50_000
                               ? Loop.Turn.again(state + 1)
                               : Loop.Turn.done("done at " + state));
    }
}
