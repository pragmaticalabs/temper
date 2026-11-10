package org.pragmatica.temper.worker.unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// m4-design §4.4: the same attempt is adopted, a lower one replaced, a higher one refused; an unlabelled
/// container is attempt 1.
class AttemptVerdictTest {
    @Test
    void verdict_adoptsTheSameAttempt() {
        assertThat(AttemptVerdict.verdict(2, 2)).isEqualTo(AttemptVerdict.ADOPT);
    }

    @Test
    void verdict_replacesALowerAttempt_includingAnUnlabelledContainer() {
        assertThat(AttemptVerdict.verdict(2, 1)).isEqualTo(AttemptVerdict.REPLACE);
        assertThat(AttemptVerdict.verdict(2, UnitStarter.UNLABELLED)).isEqualTo(AttemptVerdict.REPLACE);
    }

    /// An unlabelled container is a pre-M4 unit, attempt 1, as V11 backfills the store: `StartUnit(1)` adopts it.
    @Test
    void verdict_adoptsAnUnlabelledContainer_forAttemptOne() {
        assertThat(AttemptVerdict.verdict(1, UnitStarter.UNLABELLED)).isEqualTo(AttemptVerdict.ADOPT);
    }

    @Test
    void verdict_refusesAHigherAttempt() {
        assertThat(AttemptVerdict.verdict(2, 3)).isEqualTo(AttemptVerdict.REFUSE);
        assertThat(AttemptVerdict.verdict(1, 100)).isEqualTo(AttemptVerdict.REFUSE);
    }
}
