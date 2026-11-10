package org.pragmatica.temper.controller.evidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.domain.model.JobId;

import static org.assertj.core.api.Assertions.assertThat;

/// `result.json` writes (m1-design §3.3): atomic (no `.partial` left), byte-identical, and the sweep writes only
/// the files that are missing.
class ResultFilesTest {
    @TempDir
    Path root;

    private final FakeStores.Results results = new FakeStores.Results();

    private ResultFiles files() {
        return ResultFiles.resultFiles(EvidenceLayout.evidenceLayout(root), results, Instant::now);
    }

    private static JobId job(long id) {
        return JobId.jobId(id).unwrap();
    }

    @Test
    void write_publishesTheExactBytes_andNoPartial() throws Exception {
        var json = "{\"job\":1,\"verdict\":\"PASSED\",\"note\":\"ü\"}";

        files().write(job(1), json).unwrap();

        var file = root.resolve("jobs/1/result.json");
        assertThat(Files.readString(file)).isEqualTo(json);
        assertThat(Files.list(file.getParent())).containsExactly(file);
    }

    @Test
    void sweep_writesOnlyMissingFiles_andCountsThem() throws Exception {
        results.json.put(job(1), "{\"job\":1}");
        results.json.put(job(2), "{\"job\":2}");
        files().write(job(1), "{\"job\":1,\"already\":true}").unwrap();

        assertThat(files().sweep().await().unwrap()).isEqualTo(1);
        assertThat(Files.readString(root.resolve("jobs/2/result.json"))).isEqualTo("{\"job\":2}");
        assertThat(Files.readString(root.resolve("jobs/1/result.json"))).as("an existing file is left alone")
                                                                        .isEqualTo("{\"job\":1,\"already\":true}");
    }

    @Test
    void written_writesNothing_forAJobWithoutAResult() {
        files().written(job(3)).await().unwrap();

        assertThat(root.resolve("jobs/3/result.json")).doesNotExist();
    }
}
