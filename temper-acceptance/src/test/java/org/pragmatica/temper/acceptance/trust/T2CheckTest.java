package org.pragmatica.temper.acceptance.trust;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.TestcaseDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The T2 assertions (m1-design §8.2) one at a time: each must fail on its own.
class T2CheckTest {
    private static final String PROBE = "fx.includes.CrashedForkProbeTest";
    private static final String CRASH = "[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?";

    @Test
    void check_passes_onACrashedFork() {
        assertThat(T2Check.t2Check(side("FAILED", "missing-classes", 1, List.of(PROBE), List.of(), CRASH), PROBE).passed()).isTrue();
    }

    @Test
    void check_fails_onEachMissingFact() {
        assertThat(T2Check.t2Check(side("FAILED", "rc", 1, List.of(PROBE), List.of(), CRASH), PROBE).failedMissingClasses()).isFalse();
        assertThat(T2Check.t2Check(side("FAILED", "missing-classes", 1, List.of(), List.of(), CRASH), PROBE).probeMissing()).isFalse();
        assertThat(T2Check.t2Check(side("FAILED", "missing-classes", 0, List.of(PROBE), List.of(), CRASH), PROBE).rcNonZero()).isFalse();
        assertThat(T2Check.t2Check(side("FAILED", "missing-classes", 1, List.of(PROBE), List.of(), "[INFO] BUILD FAILURE"), PROBE).forkCrashLogged()).isFalse();
        assertThat(T2Check.t2Check(side("FAILED", "missing-classes", 1, List.of(PROBE), List.of(PROBE), CRASH), PROBE).noProbeRows()).isFalse();
    }

    @Test
    void control_holds_unlessTheParentIsMissingClasses() {
        assertThat(T2Check.controlHolds(side("PASSED", null, 0, List.of(), List.of(), "").result())).isTrue();
        assertThat(T2Check.controlHolds(side("FAILED", "missing-classes", 1, List.of(), List.of(), "").result())).isFalse();
    }

    private static TemperSide side(String verdict, String reason, int rc, List<String> missing, List<String> rowClasses, String log) {
        var result = new ResultRecordDto(1, verdict, reason == null ? none() : some(reason), none(), new SourceView(none(), some("b".repeat(40))),
                                         new RecipeDto.Gate(), List.of(),
                                         new ResultRecordDto.CountsDto(1, 1, 0, 0, 0, 1, 1, some(1), some(missing.size()), some(0)),
                                         List.of(), 0,
                                         List.of(ResultRecordDto.UnitResultDto.unitResultDto("gate", some(rc), some("bb"), none(), some(Instant.now()),
                                                                                   some(Instant.now()), none(), none())),
                                         none(), ResultRecordDto.EvidenceDto.NOT_STORED, new ResultRecordDto.TimingsDto(none(), none()), none(), List.of());
        var rows = rowClasses.stream().map(name -> new TestcaseDto(name, "t", "passed", 0.1, "gate", false)).toList();
        return TemperSide.temperSide(result, rows, new ManifestView(true, false, List.of(), List.of(), missing, List.of(), List.of()), log);
    }
}
