package org.pragmatica.temper.merge.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The fixtures are excerpts of real job logs, recorded 2026-11-08 from pragmaticalabs/pragmatica merge-group runs
/// 42420645926 (jobs 128548485648 test-group-g3, 128556313308 build-and-test) and 42415039453 (job 128531192497
/// ember-shard-4), timestamps and all; only the ANSI colour codes of the gate job were stripped.
class FailedTestsTest {
    @Test
    void anErrorInAMethod_isNamedClassDotMethod_once() {
        assertThat(FailedTests.in(EngineTest.fixture("job-log-test-group-g3.txt")))
            .containsExactly("org.pragmatica.storage.AppendLogTest.append_writesFrameBeforeReturning");
    }

    @Test
    void aFailureInAMethod_isNamedClassDotMethod_once() {
        assertThat(FailedTests.in(EngineTest.fixture("job-log-ember-shard-4.txt")))
            .containsExactly("org.pragmatica.aether.ember.EmberFirstPublishRefusedCatchupTest.firstPublish_toANewCf2Stream_isAcknowledged_whenEveryReplicasFirstCatchupReadIsRefused");
    }

    @Test
    void aGateJobThatOnlyReportsAnotherJob_namesNoTest() {
        assertThat(FailedTests.in(EngineTest.fixture("job-log-build-and-test-gate.txt"))).isEmpty();
    }

    @Test
    void withoutPerMethodLines_theResultsSummaryIsRead() {
        var log = """
            2026-11-07T22:39:38.1257835Z [INFO]\s
            2026-11-07T22:39:38.1258009Z [ERROR] Failures:\s
            2026-11-07T22:39:38.1258740Z [ERROR]   FooTest.bar:42 expected: <1> but was: <2>
            2026-11-07T22:39:38.1258741Z [ERROR]   FooTest.baz » IllegalState boom
            2026-11-07T22:39:38.1259162Z [INFO]\s
            2026-11-07T22:39:38.1259398Z [ERROR] Tests run: 10, Failures: 2, Errors: 0, Skipped: 0
            2026-11-07T22:39:38.1259399Z [ERROR]   NotATest.line after the summary ended
            """;
        assertThat(FailedTests.in(log)).containsExactly("FooTest.bar", "FooTest.baz");
    }
}
