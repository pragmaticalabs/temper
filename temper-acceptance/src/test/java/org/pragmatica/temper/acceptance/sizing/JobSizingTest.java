package org.pragmatica.temper.acceptance.sizing;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.UnitResultDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m4-design §9.4, ruling R-M4-9 (probe P14): sizing runs on dedicated-CPU hosts only. A job whose record says a unit ran
/// on a shared-CPU host is refused, named and excluded from the report; a dedicated one, or a record written before M4
/// (no `sharedCpu`), sizes as before.
class JobSizingTest {
    private static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final SizingReport.Budget BUDGET = new SizingReport.Budget(20480, 8192, "aether/ember");

    @Test
    void comparable_sharedCpuUnit_refusedNamingTheUnit() {
        var shared = with(SizingReportTest.GATE, unit("gate", some(true)), unit("rest", some(false)));

        var refused = shared.comparable();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(JobSizing.SharedCpu.sharedCpu(7, List.of("gate"))));
        refused.onFailure(cause -> assertThat(cause.message()).isEqualTo("job 7: unit gate ran on a shared-CPU host; its wall time and "
                                                                         + "memory.peak are not comparable (R-M4-9)"));
    }

    @Test
    void comparable_dedicatedOrPreM4_accepted() {
        var dedicated = with(SizingReportTest.GATE, unit("gate", some(false)));
        var preM4 = with(SizingReportTest.GATE, unit("gate", none()));

        assertThat(dedicated.comparable().option()).isEqualTo(some(dedicated));
        assertThat(preM4.comparable().option()).isEqualTo(some(preM4));
    }

    /// The refused job is named under "Excluded" and is neither a section nor a term of the derivation; a refused
    /// `--ember-job` leaves the shard figures not derivable.
    @Test
    void markdown_sharedCpuJob_namedAndExcluded() {
        var shared = with(SizingReportTest.GATE, unit("gate", some(true)));
        var report = SizingReport.markdown("2026-10-27", List.of(shared), some(shared), BUDGET);

        assertThat(report).contains("## Excluded (shared CPU, m4-design §9.4)",
                                    "- job 7: unit gate ran on a shared-CPU host",
                                    "- unit.memory, gate.emberShards: not derivable",
                                    "- rest reservation: not derivable")
                          .doesNotContain("## job 7")
                          .doesNotContain("memory.peak: 6144 MiB");
        assertThat(report.lines().filter(line -> line.startsWith("- job 7: unit gate")).count()).as("named once").isEqualTo(1);
    }

    @Test
    void markdown_dedicatedJob_sizedAsBefore() {
        var dedicated = with(SizingReportTest.GATE, unit("gate", some(false)));
        var report = SizingReport.markdown("2026-10-27", List.of(dedicated), some(dedicated), BUDGET);

        assertThat(report).contains("## job 7", "memory.peak: 6144 MiB", "gate.emberShards = 1")
                          .doesNotContain("Excluded");
    }

    private static UnitResultDto unit(String id, Option<Boolean> sharedCpu) {
        return UnitResultDto.unitResultDto(id, some(0), some("bigboy"), none(), some(AT), some(AT), none(), none(), some(1), sharedCpu, List.of());
    }

    private static JobSizing with(JobSizing job, UnitResultDto... units) {
        var result = job.side().result();
        var record = new ResultRecordDto(result.job(), result.verdict(), result.reason(), result.reasonDetail(), result.source(),
                                         result.recipe(), result.annotations(), result.counts(), result.failed(),
                                         result.observedFailures(), List.of(units), result.manifest(), result.evidence(),
                                         result.timings(), result.judge(), result.userProperties());

        return JobSizing.jobSizing(job.job(),
                                   TemperSide.temperSide(record, job.side().rows(), job.side().manifest(), job.side().logTail()),
                                   job.start(),
                                   job.end());
    }
}
