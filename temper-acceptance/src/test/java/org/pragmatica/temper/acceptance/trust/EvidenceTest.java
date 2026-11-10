package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.api.ResultRecordDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Where the judge finds a unit's small tarball: under the attempt's directory `units/<unit>/a<n>/` (m4-design §4.6),
/// directly under `units/<unit>/` for evidence stored before M4. A miss is silent downstream (the judge shows
/// "unavailable" and the sizing report "unknown"), so each layout is pinned here.
class EvidenceTest {
    @TempDir
    Path root;

    @Test
    void tarball_ofAnAttempt_isFoundUnderItsDirectory() throws Exception {
        var tarball = file("units/main/a1/evidence.tar.gz");

        assertThat(Evidence.tarball(stored(), "main")).isEqualTo(some(tarball));
        assertThat(Evidence.firstTarball(stored())).isEqualTo(some(tarball));
    }

    @Test
    void tarball_ofSeveralAttempts_isTheHighestAttempts_comparedAsANumber() throws Exception {
        file("units/main/a2/evidence.tar.gz");
        file("units/main/a9/evidence.tar.gz");
        var tenth = file("units/main/a10/evidence.tar.gz");

        assertThat(Evidence.tarball(stored(), "main")).isEqualTo(some(tenth));
    }

    @Test
    void tarball_skipsAnAttemptWithoutOne_andDirectoriesThatAreNoAttempt() throws Exception {
        var first = file("units/main/a1/evidence.tar.gz");
        Files.createDirectories(root.resolve("units/main/a2"));
        file("units/main/a0/evidence.tar.gz");
        file("units/main/b3/evidence.tar.gz");
        file("units/main/a03/evidence.tar.gz");

        assertThat(Evidence.tarball(stored(), "main")).isEqualTo(some(first));
    }

    @Test
    void tarball_storedBeforeM4_isFoundDirectlyUnderTheUnit() throws Exception {
        var old = file("units/main/evidence.tar.gz");

        assertThat(Evidence.tarball(stored(), "main")).isEqualTo(some(old));
    }

    @Test
    void tarball_whenNotStoredOrAbsent_isNone() throws Exception {
        file("units/main/a1/evidence.tar.gz");

        assertThat(Evidence.tarball(record(false), "main")).isEqualTo(none());
        assertThat(Evidence.tarball(stored(), "other")).isEqualTo(none());
    }

    private Path file(String relative) throws Exception {
        var path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "tarball");
    }

    private ResultRecordDto stored() {
        return record(true);
    }

    private ResultRecordDto record(boolean stored) {
        var result = Captures.result("PASSED", 1, 1);
        return new ResultRecordDto(result.job(),
                                   result.verdict(),
                                   result.reason(),
                                   result.reasonDetail(),
                                   result.source(),
                                   result.recipe(),
                                   result.annotations(),
                                   result.counts(),
                                   result.failed(),
                                   result.observedFailures(),
                                   List.of(ResultRecordDto.UnitResultDto.unitResultDto("main", some(0), none(), none(), none(), none(), none(), none())),
                                   result.manifest(),
                                   ResultRecordDto.EvidenceDto.evidenceDto(stored, some(root.toString()), Option.some(7L)),
                                   result.timings(),
                                   result.judge(),
                                   result.userProperties());
    }
}
