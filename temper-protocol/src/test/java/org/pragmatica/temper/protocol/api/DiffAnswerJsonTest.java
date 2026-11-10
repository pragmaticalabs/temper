package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `GET /api/v1/diff`'s body (m2-design §3.3): `{"diff":…}` or `{"refused":…}`, both round-tripping as `DiffAnswer`
/// and decoding as what the CLI expects. A test entry is `{class, name, outcome}` (`TestcaseDto`'s `class` key, which
/// the hand cross-check's `jq` reads, §10).
class DiffAnswerJsonTest {
    private static final DiffView VIEW = DiffView.diffView(DiffView.Side.side(4690, JobState.PASSED, "gate", 12),
                                                           DiffView.Side.side(4711, JobState.FAILED, "gate", 13),
                                                           some(4690L),
                                                           List.of(DiffView.Entry.entry("org.x.FooTest", "newCase", KeyOutcome.FAILED)),
                                                           List.of(),
                                                           List.of(DiffView.Entry.entry("org.x.BarTest", "flips", KeyOutcome.FAILED)),
                                                           List.of(),
                                                           List.of(DiffView.Skip.skip("org.x.BazTest", "off", KeyOutcome.FAILED, KeyOutcome.SKIPPED)),
                                                           DiffView.Counts.counts(1, 1, 0, 1, 0, 1, 0));

    @Test
    void view_roundTrips_inTheDiffWrapper_withClassKeys() {
        var json = TemperJson.mapper().writeAsString(VIEW).unwrap();

        assertThat(json).startsWith("{\"diff\":{")
                        .contains("{\"class\":\"org.x.FooTest\",\"name\":\"newCase\",\"outcome\":\"FAILED\"}")
                        .contains("\"skipChanged\":[{\"class\":\"org.x.BazTest\",\"name\":\"off\",\"a\":\"FAILED\",\"b\":\"SKIPPED\"}]")
                        .contains("\"base\":4690")
                        .contains("\"b\":{\"job\":4711,\"verdict\":\"FAILED\",\"recipe\":\"gate\",\"testcases\":13}");
        assertThat(TemperJson.mapper().readString(json, DiffAnswer.class).unwrap()).isEqualTo(VIEW);
    }

    @Test
    void refusal_roundTrips_inTheRefusedWrapper() {
        var pruned = DiffRefusal.diffRefusal(DiffObstacle.PRUNED,
                                             some(4690L),
                                             4711,
                                             List.of(DiffRefusal.SideState.sideState(DiffOutcome.Side.A, 4690, JobState.PASSED)),
                                             none(),
                                             List.of());
        var conflict = DiffRefusal.diffRefusal(DiffObstacle.BASE_CONFLICT,
                                               none(),
                                               4711,
                                               List.of(),
                                               some("0cf5631" + "0".repeat(33)),
                                               List.of(GateView.Vote.vote(4711, JobState.FAILED, "unjudged")));

        for (var refusal : List.of(pruned, conflict)) {
            var json = TemperJson.mapper().writeAsString(refusal).unwrap();

            assertThat(json).startsWith("{\"refused\":{");
            assertThat(TemperJson.mapper().readString(json, DiffAnswer.class).unwrap()).isEqualTo(refusal);
        }
        assertThat(TemperJson.mapper().writeAsString(pruned).unwrap()).contains("\"side\":\"A\"");
    }
}
