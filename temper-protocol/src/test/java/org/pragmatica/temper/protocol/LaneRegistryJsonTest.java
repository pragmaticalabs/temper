package org.pragmatica.temper.protocol;

import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.protocol.api.CheckpointDto;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.LeaseCall;
import org.pragmatica.temper.protocol.json.TemperJson;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The wire of the lane registry. A claim with no checkpoint and no warning reads exactly as before the registry (the
/// two keys are left out, so an old strict client still parses it); an old heartbeat body still parses.
class LaneRegistryJsonTest {
    private static final org.pragmatica.json.JsonMapper MAPPER = TemperJson.mapper();
    private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");

    private static ClaimResponse claim(CheckpointDto checkpoint, List<String> warnings) {
        return new ClaimResponse(42,
                                 "s",
                                 "CODE",
                                 1,
                                 7,
                                 "t",
                                 "a".repeat(40),
                                 0,
                                 AT,
                                 AT,
                                 "a/b",
                                 "file:///x",
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 checkpoint,
                                 warnings);
    }

    /// Mutation: drop `@JsonInclude` from `checkpoint` or `warnings` → the key appears and this is red.
    @Test
    void claimResponse_withoutCheckpointOrWarnings_hasNeitherKey() {
        var json = MAPPER.writeAsString(claim(null, List.of())).unwrap();

        assertThat(json).doesNotContain("checkpoint").doesNotContain("warnings");
        assertThat(MAPPER.readString(json, ClaimResponse.class).unwrap().warnings()).isEmpty();
        assertThat(MAPPER.readString(json, ClaimResponse.class).unwrap().checkpoint()).isNull();
    }

    @Test
    void claimResponse_withCheckpointAndWarnings_roundTrips() {
        var checkpoint = new CheckpointDto(2,
                                           1,
                                           "c".repeat(40),
                                           List.of(new CheckpointDto.Item("V20", true)),
                                           List.of(new CheckpointDto.Entry("docs/m3-design.md", "315-414")),
                                           "n",
                                           AT);
        var original = claim(checkpoint, List.of("dirty-predecessor"));
        var json = MAPPER.writeAsString(original).unwrap();

        assertThat(json).contains("\"checkpoint\"").contains("\"warnings\":[\"dirty-predecessor\"]");
        assertThat(MAPPER.readString(json, ClaimResponse.class).unwrap()).isEqualTo(original);
    }

    @Test
    void heartbeat_oldBodyParses_andAReportIsRead() {
        var old = MAPPER.readString("{\"epoch\":3}", LeaseCall.Heartbeat.class).unwrap();
        var withReport = MAPPER.readString("{\"epoch\":3,\"report\":{\"head\":\""
                                          + "a".repeat(40)
                                          + "\",\"dirty\":2,\"unpushed\":1}}",
                                           LeaseCall.Heartbeat.class)
                               .unwrap();

        assertThat(old.epoch()).isEqualTo(3);
        assertThat(old.report().isEmpty()).isTrue();
        assertThat(withReport.report().unwrap()).isEqualTo(new LeaseCall.Tree("a".repeat(40), 2, 1));
    }
}
