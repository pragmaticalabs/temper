package org.pragmatica.temper.controller.it;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.temper.controller.it.RerunScript.GATE_BY_SHA;
import static org.pragmatica.temper.controller.it.RerunScript.RERUN;
import static org.pragmatica.temper.controller.it.RerunScript.RERUN_FRESH;
import static org.pragmatica.temper.controller.it.RerunScript.deduplicated;
import static org.pragmatica.temper.controller.it.RerunScript.jobOf;

/// m2-design §5.6 `RerunSubmitIT` (it-pg, the real controller over HTTP and Postgres, a scripted fake worker): a Gate
/// that failed `org.x.FooTest` is rerun; the rerun passes. The parent's stored `results.record` is byte-identical
/// before and after, its `finished_at` too, and `gate` still answers FAILED with the parent: a rerun job is never a
/// Gate candidate. A repeat request deduplicates onto the first rerun (exit 10's path); `fresh` makes another.
///
/// **Bold probe** (shared with §1.6): drop `j.recipe_kind = 'gate'` from `GateRows.CANDIDATES` → the rerun's PASSED is
/// a candidate at the SHA, the answer turns CONFLICT, and [#gate_stillAnswersFailed_withTheParent_andListsTheRerun] goes
/// red.
@Tag("pg")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerunSubmitIT {
    private ControllerHarness harness;
    private long parent;
    private String parentRecordBefore;
    private JobRow parentRowBefore;
    private long rerun;

    @BeforeAll
    void rerunAFailedGate() throws Exception {
        harness = new ControllerHarness(true, TimeSpan.timeSpan(500).millis());
        new RerunScript(harness);
        parent = jobOf(harness.post("/jobs", GATE_BY_SHA));
        harness.waitForState(parent, "FAILED");
        parentRecordBefore = recordJson(parent);
        parentRowBefore = row(parent);

        var submitted = harness.post("/jobs/" + parent + "/rerun", RERUN);

        assertThat(submitted.status()).as(submitted.body()).isEqualTo(200);
        assertThat(deduplicated(submitted)).isFalse();
        rerun = jobOf(submitted);
        harness.waitForState(rerun, "PASSED");
    }

    @AfterAll
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void parentRecord_isByteIdentical_andItsFinishedTimeUnchanged() throws Exception {
        assertThat(recordJson(parent)).isEqualTo(parentRecordBefore);
        assertThat(row(parent).finishedAt()).isEqualTo(parentRowBefore.finishedAt());
        assertThat(row(parent).state()).isEqualTo(parentRowBefore.state());
    }

    @Test
    void gate_stillAnswersFailed_withTheParent_andListsTheRerun() throws Exception {
        var gate = harness.get("/gate/" + ControllerHarness.SHA_A);

        assertThat(gate.status()).as(gate.body()).isEqualTo(200);
        assertThat(gate.body()).contains("\"answer\":\"FAILED\"", "\"job\":" + parent + ",")
                               .contains("\"reruns\":[{\"job\":" + rerun + ",\"state\":\"PASSED\",\"outcomes\":[\"rerun(org.x.FooTest)=PASSED\"]}");
    }

    @Test
    void rerunRecord_carriesTheOriginal_andThePerClassOutcome_only() throws Exception {
        var result = harness.get("/jobs/" + rerun + "/result").body();

        assertThat(result).contains("\"verdict\":\"PASSED\"",
                                    "\"rerun-of=" + parent + "\"",
                                    "\"rerun-bound=30m (floor; derived 10.0m)\"",
                                    "\"rerun(org.x.FooTest)=PASSED\"",
                                    "\"original\":{\"job\":" + parent + ",\"verdict\":\"FAILED\",\"reason\":\"tests\"",
                                    "{\"class\":\"org.x.FooTest\",\"name\":\"works\",\"known\":null}")
                          .doesNotContainIgnoringCase("flaky");
    }

    /// maintainer ruling on the rerun bound, over the real store: the parent's stored FooTest row (0.2 s) is the history, so the
    /// derived bound is the 30 min floor, and it is the stored unit's `timeout_s`.
    @Test
    void rerunUnit_isBoundedByTheDerivedBound_fromTheStoredHistory() throws Exception {
        assertThat(row(rerun).units()).extracting(UnitRow::timeout).containsExactly(Duration.ofMinutes(30));
        assertThat(harness.get("/jobs/" + rerun).body()).contains("\"bound\":{\"millis\":1800000,\"derivation\":\"floor; derived 10.0m\"}");
    }

    @Test
    void rerunJob_hasNoRef_isNotSuperseded_andTheParentViewListsIt() throws Exception {
        var view = harness.get("/jobs/" + rerun).body();

        assertThat(view).contains("\"superseded\":false", "\"source\":{\"ref\":null,\"sha\":\"" + ControllerHarness.SHA_A + "\"}", "\"rerun\":{\"of\":" + parent);
        assertThat(row(rerun).ref().isEmpty()).isTrue();
        assertThat(harness.get("/jobs/" + parent).body()).contains("\"reruns\":[{\"job\":" + rerun + ",\"state\":\"PASSED\"");
    }

    @Test
    void repeat_deduplicatesOntoTheFirstRerun_freshMakesAnother() throws Exception {
        var repeat = harness.post("/jobs/" + parent + "/rerun", RERUN);

        assertThat(repeat.status()).as(repeat.body()).isEqualTo(200);
        assertThat(deduplicated(repeat)).isTrue();
        assertThat(jobOf(repeat)).isEqualTo(rerun);

        var fresh = harness.post("/jobs/" + parent + "/rerun", RERUN_FRESH);

        assertThat(fresh.status()).as(fresh.body()).isEqualTo(200);
        assertThat(deduplicated(fresh)).isFalse();
        assertThat(jobOf(fresh)).isNotEqualTo(rerun);
        harness.waitForState(jobOf(fresh), "PASSED");
        assertThat(recordJson(parent)).as("still byte-identical").isEqualTo(parentRecordBefore);
    }

    @Test
    void rerunOfARerun_isRefused_andPersisted() throws Exception {
        var refused = harness.post("/jobs/" + rerun + "/rerun", RERUN);

        assertThat(refused.status()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"rerun\"", "is a rerun");
        assertThat(harness.get("/jobs/" + jobOf(refused)).body()).contains("\"state\":\"REFUSED\"", "\"reason\":\"rerun\"");
        assertThat(harness.get("/jobs/" + rerun).body()).as("a refused request is not listed as a rerun").contains("\"reruns\":[]");
    }

    @Test
    void rerunOfAPassedJob_isRefused() throws Exception {
        var passed = jobOf(harness.post("/jobs", ControllerHarness.mvnJob("{\"pushed\":{\"ref\":\"main\"}}", "verify")));

        harness.waitForState(passed, "PASSED");
        var refused = harness.post("/jobs/" + passed + "/rerun", RERUN);

        assertThat(refused.status()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"rerun\"", "only gate and module-test jobs are rerun");
    }

    @Test
    void rerunOfAnUnknownJob_is404_andABadBodyIs400() throws Exception {
        assertThat(harness.post("/jobs/999999999/rerun", RERUN).status()).isEqualTo(404);
        assertThat(harness.post("/jobs/" + parent + "/rerun", "{\"submittedBy\":\"no spaces allowed\"}").status()).isEqualTo(400);
        assertThat(harness.post("/jobs/abc/rerun", RERUN).status()).isEqualTo(400);
    }

    /// A client cannot submit a rerun recipe through `POST /jobs`: the preconditions would be skipped.
    @Test
    void submit_withARerunRecipe_is400() throws Exception {
        var direct = harness.post("/jobs",
                                  "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"sha\":\"" + ControllerHarness.SHA_A
                                  + "\"}},\"recipe\":{\"rerun\":{\"of\":" + parent
                                  + ",\"classes\":[{\"class\":\"org.x.FooTest\",\"module\":\"core\",\"runner\":\"surefire\"}]}}}");

        assertThat(direct.status()).as(direct.body()).isEqualTo(400);
    }

    private String recordJson(long job) {
        return harness.store.results().recordJson(JobId.jobId(job).unwrap()).await(Pg.WAIT).unwrap().fold(() -> "", text -> text);
    }

    private JobRow row(long job) {
        return harness.store.jobs().job(JobId.jobId(job).unwrap()).await(Pg.WAIT).unwrap().fold(() -> fail("expected a value"), found -> found);
    }
}
