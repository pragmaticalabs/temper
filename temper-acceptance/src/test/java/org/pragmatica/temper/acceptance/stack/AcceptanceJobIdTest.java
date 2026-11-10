package org.pragmatica.temper.acceptance.stack;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A4: the acceptance job-id offset is what keeps acceptance unit containers (`temper-<job>-<unit>`) apart from the
/// live controller's on a shared host; `ForeignMirrorIT` asserts the stack applies it to a real job.
class AcceptanceJobIdTest {
    /// A4: an acceptance job id is never below the offset, whatever the clock, and the offset sits far above any live id.
    @Test
    void firstJobId_alwaysAppliesTheAcceptanceOffset() {
        for (var now : List.of(0L, 1L, 99_999_999_999L, System.currentTimeMillis(), Long.MAX_VALUE, -1L)) {
            assertThat(AcceptanceStack.firstJobId(now)).isBetween(AcceptanceStack.ACCEPTANCE_JOB_ID_OFFSET,
                                                                  AcceptanceStack.ACCEPTANCE_JOB_ID_OFFSET + AcceptanceStack.ACCEPTANCE_JOB_ID_SPAN - 1);
        }
        assertThat(AcceptanceStack.ACCEPTANCE_JOB_ID_OFFSET).isGreaterThanOrEqualTo(700_000_000_000L);
    }
}
