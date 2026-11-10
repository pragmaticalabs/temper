package org.pragmatica.temper.controller.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.RerunScript.GATE_BY_SHA;
import static org.pragmatica.temper.controller.it.RerunScript.RERUN;
import static org.pragmatica.temper.controller.it.RerunScript.jobOf;

/// m2-design §5.2 and §5.6 `RerunCancelIT` (it-pg): cancelling a rerun job mid-run ends it CANCELLED, its record still
/// carries the parent's `original`, and every class is `NOT-RUN(cancelled)`; the parent's record is byte-identical and
/// `gate` still answers FAILED with the parent. A non-terminal parent is refused (only a terminal one is rerun).
@Tag("pg")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerunCancelIT {
    private ControllerHarness harness;
    private RerunScript script;
    private long parent;
    private String parentRecordBefore;
    private long rerun;

    @BeforeAll
    void cancelARunningRerun() throws Exception {
        harness = new ControllerHarness(true, TimeSpan.timeSpan(500).millis());
        script = new RerunScript(harness);
        parent = jobOf(harness.post("/jobs", GATE_BY_SHA));
        harness.waitForState(parent, "FAILED");
        parentRecordBefore = recordJson(parent);
        script.holding(true);
        rerun = jobOf(harness.post("/jobs/" + parent + "/rerun", RERUN));
        harness.waitForState(rerun, "RUNNING");

        var cancel = harness.post("/jobs/" + rerun + "/cancel", "");

        assertThat(cancel.status()).as(cancel.body()).isEqualTo(200);
        harness.waitForState(rerun, "CANCELLED");
    }

    @AfterAll
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void cancelledRerun_keepsTheOriginal_andEveryClassIsNotRunCancelled() throws Exception {
        var result = harness.get("/jobs/" + rerun + "/result").body();

        assertThat(result).contains("\"verdict\":\"CANCELLED\"",
                                    "\"rerun-of=" + parent + "\"",
                                    "\"rerun(org.x.FooTest)=NOT-RUN(cancelled)\"",
                                    "\"original\":{\"job\":" + parent + ",\"verdict\":\"FAILED\",\"reason\":\"tests\"");
    }

    @Test
    void parent_isUntouched_andGateStillAnswersFailed() throws Exception {
        assertThat(recordJson(parent)).isEqualTo(parentRecordBefore);
        assertThat(harness.get("/gate/" + ControllerHarness.SHA_A).body()).contains("\"answer\":\"FAILED\"",
                                                                                   "\"job\":" + parent + ",",
                                                                                   "{\"job\":" + rerun + ",\"state\":\"CANCELLED\"");
    }

    /// A running parent is refused: its record does not exist yet, and a rerun never delays it.
    @Test
    void rerunOfARunningJob_isRefused() throws Exception {
        var running = jobOf(harness.post("/jobs",
                                         "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"ref\":\"main\"}},"
                                         + "\"recipe\":{\"moduleTest\":{\"pl\":[\"held\"]}}}"));

        harness.waitForState(running, "RUNNING");
        var refused = harness.post("/jobs/" + running + "/rerun", RERUN);

        assertThat(refused.status()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"rerun\"", "is RUNNING, not terminal");
    }

    private String recordJson(long job) {
        return harness.store.results().recordJson(JobId.jobId(job).unwrap()).await(Pg.WAIT).unwrap().fold(() -> "", text -> text);
    }
}
