package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.LostAttemptDto;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling R-10: the store port's models are member-for-member copies of the wire forms, so a result record read
/// through the model and written out again is the same JSON, and the event kinds keep their wire names.
class WireRecordsTest {
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private static final CountsDto COUNTS = new CountsDto(20, 17, 1, 1, 1, 21, 4, some(5), some(1), some(0));

    private static ResultRecordDto record(RecipeDto recipe, boolean withManifest) {
        var unit = ResultRecordDto.UnitResultDto.unitResultDto("main",
                                                               some(1),
                                                               some("bigboy"),
                                                               some("sha256:abc"),
                                                               some(T0),
                                                               some(T0.plusSeconds(90)),
                                                               some(new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto("tc-left",
                                                                                                                            "s-9")),
                                                                                       some("reap timed out"))),
                                                               some(ResultRecordDto.PortGroupDto.portGroupDto("bb-3", T0)));
        var bare = ResultRecordDto.UnitResultDto.unitResultDto("it", none(), none(), none(), none(), none(), none(), none());

        return ResultRecordDto.resultRecordDto(4711,
                                               "FAILED",
                                               some("missing-classes"),
                                               some("2 classes never reported"),
                                               new SourceView(some("main"), some("0cf563192a" + "0".repeat(30))),
                                               recipe,
                                               List.of("evidence=missing", "flaky"),
                                               COUNTS,
                                               List.of(new ResultRecordDto.FailedDto("a.ATest", "one", none()),
                                                       new ResultRecordDto.FailedDto("b.BTest", "two", some("KNOWN-7"))),
                                               2,
                                               List.of(unit, bare),
                                               withManifest
                                               ? some(new ResultRecordDto.ManifestDto(true,
                                                                                      List.of("c.CTest"),
                                                                                      List.of(),
                                                                                      List.of("late-module"),
                                                                                      1))
                                               : none(),
                                               ResultRecordDto.EvidenceDto.evidenceDto(true, some("/srv/jobs/4711"), some(1234L)),
                                               ResultRecordDto.TimingsDto.timingsDto(some(Duration.ofSeconds(12)),
                                                                                     some(Duration.ofMinutes(3))),
                                               some("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d"));
    }

    private static ResultRecordDto roundTrip(ResultRecordDto dto) {
        return WireRecords.resultRecordDto(WireRecords.resultRecord(dto).unwrap());
    }

    @Test
    void resultRecord_roundTripsToTheSameDtoAndJson_forEveryRecipeAndManifestShape() {
        var recipes = List.<RecipeDto>of(new RecipeDto.Mvn(List.of("verify")),
                                         new RecipeDto.ModuleTest(List.of("a", "b"), some("ATest"), none()),
                                         new RecipeDto.Gate(),
                                         new RecipeDto.Mutate(some(Map.of("ops", List.of("negate")))),
                                         new RecipeDto.Mutate(none()),
                                         new RecipeDto.Rerun(4711,
                                                             List.of(new RecipeDto.Rerun.TargetDto("org.x.FooTest", "core", "surefire")),
                                                             none(),
                                                             some(RecipeDto.Rerun.BoundDto.boundDto(2_880_000, "Σ 25.1m ×1.5 + install 5m + slack 5m"))),
                                         new RecipeDto.Rerun(4711, List.of(), none(), none()));

        for (var recipe : recipes) {
            for (var withManifest : List.of(true, false)) {
                var dto = record(recipe, withManifest);

                assertThat(roundTrip(dto)).isEqualTo(dto);
                assertThat(Json.write(roundTrip(dto)).unwrap()).isEqualTo(Json.write(dto).unwrap());
            }
        }
    }

    @Test
    void resultRecord_readsTheModelMemberForMember() {
        var model = WireRecords.resultRecord(record(new RecipeDto.Gate(), true)).unwrap();

        assertThat(model.counts()).isEqualTo(Counts.counts(20, 17, 1, 1, 1, 21, 4, some(5), some(1), some(0)));
        assertThat(model.units().getFirst().portGroup().map(group -> group.ticket())).isEqualTo(some("bb-3"));
        assertThat(model.failed().get(1).known()).isEqualTo(some("KNOWN-7"));
        assertThat(model.manifest().map(manifest -> manifest.notReached())).isEqualTo(some(List.of("late-module")));
    }

    @Test
    void resultRecord_fails_whenAStoredRecordLacksAMember() {
        var full = record(new RecipeDto.Gate(), false);
        var noSource = new ResultRecordDto(full.job(), full.verdict(), full.reason(), full.reasonDetail(), null, full.recipe(),
                                           full.annotations(), full.counts(), full.failed(), full.observedFailures(), full.units(), full.manifest(),
                                           full.evidence(), full.timings(), full.judge(), List.of());

        assertThat(WireRecords.resultRecord(noSource).isFailure()).isTrue();
    }

    /// m2-design §1.7: a record written before the judge existed has none, and keeps none through the model, so it is
    /// served as stored; a record that names one keeps it.
    @Test
    void resultRecord_keepsTheJudge_andAPreJudgeRecordStaysWithoutOne() {
        var judged = record(new RecipeDto.Gate(), false);
        var preJudge = new ResultRecordDto(judged.job(), judged.verdict(), judged.reason(), judged.reasonDetail(),
                                           judged.source(), judged.recipe(), judged.annotations(), judged.counts(),
                                           judged.failed(), judged.observedFailures(), judged.units(), judged.manifest(),
                                           judged.evidence(), judged.timings(), none(), List.of());

        assertThat(WireRecords.resultRecord(judged).unwrap().judge()).isEqualTo(judged.judge()).isNotEqualTo(none());
        assertThat(roundTrip(preJudge)).isEqualTo(preJudge);
        assertThat(WireRecords.resultRecord(preJudge).unwrap().judge()).isEqualTo(none());
    }

    /// M4 §11: a unit's attempt, CPU class and lost attempts survive the model, member for member.
    @Test
    void resultRecord_roundTripsTheM4UnitFields() {
        var lost = LostAttemptDto.lostAttemptDto(1, "bb", T0, some(T0.plusSeconds(5)), T0.plusSeconds(60));
        var unstarted = LostAttemptDto.lostAttemptDto(2, "cloud", T0.plusSeconds(70), none(), T0.plusSeconds(80));
        var unit = ResultRecordDto.UnitResultDto.unitResultDto("main",
                                                               some(0),
                                                               some("bigboy"),
                                                               none(),
                                                               some(T0.plusSeconds(90)),
                                                               some(T0.plusSeconds(120)),
                                                               none(),
                                                               none(),
                                                               some(3),
                                                               some(true),
                                                               List.of(lost, unstarted));
        var base = record(new RecipeDto.Gate(), false);
        var dto = ResultRecordDto.resultRecordDto(base.job(),
                                                  base.verdict(),
                                                  base.reason(),
                                                  base.reasonDetail(),
                                                  base.source(),
                                                  base.recipe(),
                                                  base.annotations(),
                                                  base.counts(),
                                                  base.failed(),
                                                  base.observedFailures(),
                                                  List.of(unit),
                                                  base.manifest(),
                                                  base.evidence(),
                                                  base.timings(),
                                                  base.judge());

        assertThat(roundTrip(dto)).isEqualTo(dto);
    }

    /// `units.side_effects` reads each wire name to its value and refuses any other.
    @Test
    void sideEffects_readsEveryWireName_andRefusesAnUnknownOne() {
        for (var value : SideEffects.values()) {
            assertThat(Rows.sideEffects(value.wire()).unwrap()).isEqualTo(value);
        }
        assertThat(Rows.sideEffects("internal").isFailure()).isTrue();
    }

    @Test
    void counts_roundTripThroughTheDomainCounts() {
        assertThat(CountsDto.countsDto(WireRecords.counts(COUNTS))).isEqualTo(COUNTS);
    }

    @Test
    void eventKind_roundTripsEveryKind_andKeepsTheWireNames() {
        for (var wire : EventKind.values()) {
            assertThat(WireRecords.eventKind(WireRecords.kind(wire))).isEqualTo(wire);
        }
        for (var kind : EventRow.Kind.values()) {
            assertThat(WireRecords.kind(WireRecords.eventKind(kind))).isEqualTo(kind);
        }
        assertThat(WireRecords.eventKind(EventRow.Kind.UNIT_FINISHED).wire()).isEqualTo("unit-finished");
    }

    /// The `events.payload` jsonb with the domain `Counts` is the JSON it was with the wire `CountsDto`.
    @Test
    void eventPayload_countsSerializeAsTheWireCounts() {
        var payload = EventPayload.eventPayload(some("0cf5631"), none(), some("gate"), none(), some("FAILED"), none(),
                                                some(WireRecords.counts(COUNTS)), none());
        var countsJson = Json.write(COUNTS).unwrap();

        assertThat(Json.write(payload).unwrap()).contains("\"counts\":" + countsJson);
        assertThat(Json.read(Json.write(payload).unwrap(), EventPayload.class).unwrap()).isEqualTo(payload);
    }
}
