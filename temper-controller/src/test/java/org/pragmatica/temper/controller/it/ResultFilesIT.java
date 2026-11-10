package org.pragmatica.temper.controller.it;

import java.nio.file.Files;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.ResultFiles;
import org.pragmatica.temper.domain.model.JobId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// `ResultFilesIT` (m1-design §3.3, §9.5, it-pg): a crash between the result's commit and its `result.json` write
/// (the file removed after the job finished stands in for it) is closed by the sweep, and the file is the stored
/// `results.record`, byte for byte.
@Tag("pg")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultFilesIT {
    private ControllerHarness harness;
    private ResultFiles files;

    @BeforeAll
    void start() throws Exception {
        harness = new ControllerHarness(true, TimeSpan.timeSpan(500).millis());
        files = ResultFiles.resultFiles(EvidenceLayout.evidenceLayout(harness.evidenceRoot), harness.store.results(), Instant::now);
    }

    @AfterAll
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    private String stored(long job) {
        return harness.store.results()
                            .recordJson(JobId.jobId(job).unwrap())
                            .await()
                            .unwrap()
                            .unwrap();
    }

    @Test
    void sweep_writesTheMissingFile_byteIdenticalToTheStoredRecord() throws Exception {
        var job = jobOf(harness.post("/jobs", mvnJob("{\"pushed\":{\"ref\":\"main\"}}", "-pl", "sweep", "verify")));
        harness.waitForState(job, "PASSED");
        var file = files.layout().resultJson(JobId.jobId(job).unwrap());
        waitFor(() -> Files.exists(file));
        Files.delete(file);

        var written = files.sweep().await().unwrap();

        assertThat(written).isGreaterThanOrEqualTo(1);
        assertThat(file).exists();
        assertThat(Files.readString(file)).isEqualTo(stored(job));
        assertThat(files.sweep().await().unwrap()).as("a second sweep finds nothing missing").isZero();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
