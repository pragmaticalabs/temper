package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.protocol.api.ResultRecordDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// Ruling R-10 changes the read path of `results.record` from `jsonb → ResultRecordDto` to
/// `jsonb → ResultRecordDto → ResultRecord`, which the HTTP adapter maps back to the DTO. This pins that the JSON the
/// new path writes out is byte for byte the JSON the old path wrote, for a record the store finishes now and for a
/// row already stored in the M1 (V2/V3) shape, with every optional member present.
@Tag("pg")
class ResultRecordRoundTripIT {
    /// A `results.record` as the M1 store wrote it under schema V2/V3: manifest, port group, Testcontainers leftovers,
    /// stored evidence and a module-test recipe.
    private static final String M1_RECORD = """
        {"job": %d, "verdict": "FAILED", "reason": "missing-classes",
         "source": {"ref": "main", "sha": "0cf563192a0cf563192a0cf563192a0cf563192a"},
         "recipe": {"moduleTest": {"pl": ["temper-store"], "test": "LifecycleIT", "itTest": null}},
         "annotations": ["flaky"],
         "counts": {"testcases": 12, "passed": 11, "failed": 1, "error": 0, "skipped": 0, "testsAttr": 12, "classes": 3,
                    "manifest": 4, "missing": 1, "drift": 0},
         "failed": [{"class": "a.ATest", "name": "one", "known": null}],
         "observedFailures": 1,
         "units": [{"id": "main", "rc": 1, "host": "bigboy", "image": "sha256:abc",
                    "start": "2026-10-13T10:00:00Z", "end": "2026-10-13T10:03:00Z",
                    "tcLeftovers": {"containers": [{"id": "tc-left", "session": "s-9"}], "unverified": null},
                    "portGroup": {"ticket": "bb-3", "heldFrom": "2026-10-13T10:00:01Z"}}],
         "manifest": {"computed": true, "missing": ["c.CTest"], "drift": [], "notReached": ["late-module"],
                      "unresolvedAnnotations": 1},
         "evidence": {"stored": true, "path": "/srv/temper/jobs/%d", "bytes": 1234},
         "timings": {"queued": "PT12S", "run": "PT3M"}}
        """;

    private StoreHarness harness;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void result_writesTheSameJson_asTheDtoReadOfAStoreFinishedRecord() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(1),
                              List.of(TestJobs.row("a.ATest", "one", Outcome.FAILED), TestJobs.row("a.ATest", "two", Outcome.PASSED)));

        assertSameJsonAsBefore(job);
    }

    @Test
    void result_writesTheSameJson_asTheDtoReadOfAnExistingM1Row() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.execute("INSERT INTO results (job_id, verdict, counts, record, judge) VALUES ($1, 'FAILED', '{}', $2::jsonb, 'pre-judge')",
                        job.value(),
                        M1_RECORD.formatted(job.value(), job.value()));

        assertSameJsonAsBefore(job);
        assertThat(harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst().portGroup()
                          .map(group -> group.ticket()))
            .as("the M1 members read into the model")
            .isEqualTo(some("bb-3"));
    }

    /// Old path: the stored jsonb read as the DTO and written. New path: the store's model mapped back to the DTO.
    private void assertSameJsonAsBefore(JobId job) {
        var stored = harness.store.results().recordJson(job).await(PgFixture.WAIT).unwrap().unwrap();
        var before = Json.read(stored, ResultRecordDto.class).unwrap();
        var after = WireRecords.resultRecordDto(harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap());

        assertThat(after).isEqualTo(before);
        assertThat(Json.write(after).unwrap()).isEqualTo(Json.write(before).unwrap());
    }
}
