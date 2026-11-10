package org.pragmatica.temper.acceptance.sizing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The sizing data of m1-design §8.3: test time per module through the manifest (drift unattributed, a `@Nested`
/// row through its outermost class), the facts, and the derived values with their stated formula.
class SizingReportTest {
    static final JobSizing GATE = JobSizing.jobSizing(7,
                                                              TemperSide.temperSide(result(Duration.ofMinutes(94)),
                                                                                    List.of(row("a.CoreTest", 10), row("a.CoreTest", 5),
                                                                                            row("e.EmberTest$Inner", 600), row("e.EmberTest", 1200),
                                                                                            row("x.DriftTest", 3)),
                                                                                    new ManifestView(true, false,
                                                                                                     List.of(entry("core", "a.CoreTest"),
                                                                                                             entry("aether/ember", "e.EmberTest")),
                                                                                                     List.of(), List.of(), List.of("x.DriftTest"), List.of()),
                                                                                    "[INFO] Reactor Summary for p:\n[INFO] core .......... SUCCESS [01:00 min]\n"),
                                                              some(facts()),
                                                              some(new UnitEndFactsDto(facts(), some(6L << 30), Instant.now(), Map.of(),
                                                                                       new TcLeftoversDto(List.of(), none()), some(2L << 20))));

    @Test
    void testSecondsByModule_attributesThroughTheManifest() {
        assertThat(GATE.testSecondsByModule()).containsOnlyKeys("core", "aether/ember", JobSizing.UNATTRIBUTED);
        assertThat(GATE.testSecondsByModule().get("core")).isCloseTo(15, within(1e-9));
        assertThat(GATE.testSecondsByModule().get("aether/ember")).as("the @Nested row by its outermost class").isCloseTo(1800, within(1e-9));
        assertThat(GATE.testSecondsByModule().get(JobSizing.UNATTRIBUTED)).isCloseTo(3, within(1e-9));
        assertThat(GATE.testSecondsUnder("aether/ember")).isCloseTo(1800, within(1e-9));
    }

    @Test
    void factsAndWall_areRead() {
        assertThat(GATE.peakMib()).isEqualTo(some(6144L));
        assertThat(GATE.memAvailableMinMib()).isEqualTo(some(2048L));
        assertThat(GATE.wallSecondsByModule()).containsEntry("core", 60.0);
        assertThat(GATE.wall()).isEqualTo(some(Duration.ofMinutes(94)));
    }

    @Test
    void derived_unitMemoryShardsAndRest() {
        var budget = new SizingReport.Budget(20480, 8192, "aether/ember");

        assertThat(SizingReport.unitMemoryMib(6144)).as("6144 × 1.25 = 7680, a multiple of 512").isEqualTo(7680);
        assertThat(SizingReport.unitMemoryMib(6000)).isEqualTo(7680);
        // floor((budget − rest reservation) / unit.memory), GATE's peak 6144 reserving 7680: (20480 − 7680) / 7680 = 1.67
        // → 1, and (20480 − 7680) / 3072 = 4.17 → 4.
        assertThat(SizingReport.restReservationMib(List.of(GATE))).isEqualTo(some(7680L));
        assertThat(SizingReport.shards(7680, 7680, budget)).isEqualTo(some(1L));
        assertThat(SizingReport.shards(3072, 7680, budget)).isEqualTo(some(4L));
        assertThat(SizingReport.derived(List.of(GATE), Option.none(), budget).toList())
            .anyMatch(line -> line.contains("not derivable"))
            .anyMatch(line -> line.contains("rest reservation = 7680 MiB (max Gate peak × 1.25)"))
            .anyMatch(line -> line.contains("rest = 64.0 min") && line.contains("exceeds 40 min"));
    }

    /// The §12.1 sizing report's numbers (§5, §6): ember peak 3276 MiB (3.2 GiB) → U = 4096, Gate peak 8356 MiB →
    /// R = 10752. (20480 − 10752) / 4096 = 2.375 → 2; the old (20480 − 8192) / 4096 = 3 left `rest` unreserved.
    @Test
    void shards_reserveTheRestUnit_notTheBbReactor_nPlusOneRegression() {
        var budget = new SizingReport.Budget(20480, 8192, "aether/ember");
        var lines = SizingReport.derived(List.of(sized(55, 8356)), some(sized(56, 3276)), budget).toList();

        assertThat(SizingReport.unitMemoryMib(3276)).isEqualTo(4096);
        assertThat(SizingReport.unitMemoryMib(8356)).isEqualTo(10752);
        assertThat(SizingReport.shards(4096, 10752, budget)).isEqualTo(some(2L));
        assertThat(lines).contains("- rest reservation = 10752 MiB (max Gate peak × 1.25)",
                                   "- gate.emberShards = 2 = floor((budget 20480 MiB − rest reservation 10752 MiB) / unit.memory 4096 MiB)",
                                   "- beside one bb-* reactor: 10752 + 2×4096 + 8192 = 27136 MiB of limits [compare with the host's memory; see the sizing report §5]");
    }

    /// R + U > budget: 10752 + 10240 > 20480, so not one shard fits; never reported as 1.
    @Test
    void shards_doesNotFit_whenRestAndOneShardExceedTheBudget() {
        var budget = new SizingReport.Budget(20480, 8192, "aether/ember");
        var lines = SizingReport.derived(List.of(sized(55, 8356)), some(sized(56, 8192)), budget).toList();

        assertThat(SizingReport.shards(10240, 10752, budget)).isEqualTo(none());
        assertThat(lines).contains("- gate.emberShards: does not fit (budget 20480 MiB − rest 10752 MiB < one shard 10240 MiB)")
                         .noneMatch(line -> line.startsWith("- gate.emberShards ="))
                         .noneMatch(line -> line.startsWith("- beside one bb-* reactor"));
    }

    @Test
    void shards_notDerivable_withoutAGatePeak() {
        var budget = new SizingReport.Budget(20480, 8192, "aether/ember");
        var lines = SizingReport.derived(List.of(JobSizing.jobSizing(55, GATE.side(), none(), none())), some(sized(56, 3276)), budget).toList();

        assertThat(SizingReport.restReservationMib(List.of(JobSizing.jobSizing(55, GATE.side(), none(), none())))).isEqualTo(none());
        assertThat(lines).contains("- unit.memory = 4096 MiB (Ember ModuleTest peak × 1.25, rounded up to 512 MiB)",
                                   "- rest reservation: not derivable (no Gate job has a memory.peak)",
                                   "- gate.emberShards: not derivable (no rest reservation)")
                         .noneMatch(line -> line.startsWith("- gate.emberShards ="));
    }

    @Test
    void markdown_namesEachJobAndTheHonestyNotes() {
        var report = SizingReport.markdown("2026-10-14", List.of(GATE), Option.some(GATE), new SizingReport.Budget(20480, 8192, "aether/ember"));

        assertThat(report).contains("# T1 sizing, 2026-10-14", "## job 7", "| aether/ember | 30.0 min |", "memory.peak: 6144 MiB",
                                    "unit.memory = 7680 MiB", "gate.emberShards = 1", "excludes Testcontainers siblings");
    }

    private static JobSizing sized(long job, long peakMib) {
        return JobSizing.jobSizing(job, GATE.side(), some(facts()),
                                   some(new UnitEndFactsDto(facts(), some(peakMib << 20), Instant.now(), Map.of(),
                                                            new TcLeftoversDto(List.of(), none()), some(2L << 20))));
    }

    private static TestcaseDto row(String className, double seconds) {
        return new TestcaseDto(className, "t", "passed", seconds, "gate", false);
    }

    private static ManifestEntryDto entry(String module, String className) {
        return new ManifestEntryDto(module, className, ManifestEntryDto.SUREFIRE);
    }

    private static HostFactsDto facts() {
        return new HostFactsDto(1.5, 2.5, 32, 1, 1, some(1), some(0), 8L << 20, 100L << 30, 100L << 30, Instant.now(), List.of());
    }

    private static ResultRecordDto result(Duration run) {
        return new ResultRecordDto(7, "PASSED", none(), none(), new SourceView(none(), some("a".repeat(40))), new RecipeDto.Gate(), List.of(),
                                   new ResultRecordDto.CountsDto(5, 5, 0, 0, 0, 5, 3, some(2), some(0), some(1)), List.of(), 0, List.of(),
                                   none(), ResultRecordDto.EvidenceDto.NOT_STORED, new ResultRecordDto.TimingsDto(none(), some(run)), none(), List.of());
    }
}
