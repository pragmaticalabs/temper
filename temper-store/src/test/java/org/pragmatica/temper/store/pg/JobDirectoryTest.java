package org.pragmatica.temper.store.pg;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The result record's evidence directory is the job's, from either tarball layout (m1-design §3.1, m4-design §4.6).
/// Probe: judge the layout by an `a<n>` directory name → the pre-M4 tarball of a unit named `a1` resolves to
/// `/ev/jobs` (red).
class JobDirectoryTest {
    @Test
    void attemptLayout_resolvesToTheJob() {
        assertThat(ResultRecords.jobDirectory(Path.of("/ev/jobs/7/units/main/a2/evidence.tar.gz"))).isEqualTo("/ev/jobs/7");
    }

    @Test
    void preM4Layout_resolvesToTheJob() {
        assertThat(ResultRecords.jobDirectory(Path.of("/ev/jobs/7/units/main/evidence.tar.gz"))).isEqualTo("/ev/jobs/7");
    }

    @Test
    void preM4Layout_ofAUnitNamedLikeAnAttempt_resolvesToTheJob() {
        assertThat(ResultRecords.jobDirectory(Path.of("/ev/jobs/7/units/a1/evidence.tar.gz"))).isEqualTo("/ev/jobs/7");
    }

    @Test
    void attemptLayout_ofAUnitNamedUnits_resolvesToTheJob() {
        assertThat(ResultRecords.jobDirectory(Path.of("/ev/jobs/7/units/units/a1/full.tar.gz"))).isEqualTo("/ev/jobs/7");
    }

    @Test
    void shallowPath_resolvesToItsOwnDirectory() {
        assertThat(ResultRecords.jobDirectory(Path.of("/x/evidence.tar.gz"))).isEqualTo("/x");
    }
}
