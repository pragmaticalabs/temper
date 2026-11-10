package org.pragmatica.temper.cli.transport;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// The decode of fleet lines: fields by name, extra fields ignored, a missing or mistyped
/// field the CLI reads fails the line.
class FleetLineTest {
    private static final JsonMapper JSON = TemperJson.mapper();
    private static final String EPOCH = "0f1e2d3c-4b5a-4968-8776-655443322110";
    private static final String EVENT = "{\"v\":1,\"epoch\":\"" + EPOCH + "\",\"seq\":43,\"at\":\"2026-11-03T10:00:00Z\","
                                        + "\"observedAt\":\"2026-11-03T09:59:58Z\",\"kind\":\"check-concluded\","
                                        + "\"subject\":\"acme/app#7\",\"catchUp\":true,\"source\":\"kiln\","
                                        + "\"payload\":{\"conclusion\":\"failure\",\"attempt\":2},\"added\":\"later\"}";
    private static final String HEARTBEAT = "{\"v\":1,\"kind\":\"heartbeat\",\"epoch\":\"" + EPOCH + "\",\"asOf\":\"2026-11-03T10:00:01Z\","
                                            + "\"seqHead\":44,\"sources\":{\"kiln\":{\"lastOkAt\":null,\"periodS\":5,\"stale\":true},"
                                            + "\"kiln:github\":{\"lastOkAt\":\"2026-11-03T09:59:00Z\",\"periodS\":60,\"stale\":false}},"
                                            + "\"selfCheck\":{\"ok\":false,\"failed\":[\"ssh-banner\",\"loopback\"],"
                                            + "\"advisory\":{\"load1\":30.5,\"cores\":8,\"memSomeAvg60\":61.0}},\"kilnLag\":2}";

    @Test
    void ndjson_decodesAnEvent_keepingTheLineAsReceived() {
        FleetLine.ndjson(JSON, EVENT)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(line -> {
                     var event = (FleetLine.Event) line;

                     assertThat(event.line()).isEqualTo(EVENT);
                     assertThat(event.epoch()).isEqualTo(EPOCH);
                     assertThat(event.seq()).isEqualTo(43);
                     assertThat(event.kind()).isEqualTo("check-concluded");
                     assertThat(event.subject()).isEqualTo("acme/app#7");
                     assertThat(event.catchUp()).isTrue();
                     assertThat(event.payload()
                                     .path("attempt")
                                     .asString()).isEqualTo("2");
                 });
    }

    @Test
    void ndjson_decodesTheHeartbeat() {
        FleetLine.ndjson(JSON, HEARTBEAT)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(line -> assertThat(line).isEqualTo(new FleetLine.Heartbeat(EPOCH,
                                                                                       44,
                                                                                       Map.of("kiln", true, "kiln:github", false),
                                                                                       false,
                                                                                       List.of("ssh-banner", "loopback"))));
    }

    @Test
    void ndjson_readsAnAbsentFailedList_asNoneFailed() {
        var beat = "{\"kind\":\"heartbeat\",\"epoch\":\"" + EPOCH + "\",\"seqHead\":0,\"sources\":{},\"selfCheck\":{\"ok\":true}}";

        FleetLine.ndjson(JSON, beat)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(line -> assertThat(((FleetLine.Heartbeat) line).failed()).isEmpty());
    }

    /// Each field the CLI reads, missing or mistyped, fails the line.
    @ParameterizedTest
    @ValueSource(strings = {"not json",
                            "{\"epoch\":\"e\",\"seq\":1,\"subject\":\"s\",\"catchUp\":false}",
                            "{\"kind\":\"pr-state\",\"seq\":1,\"subject\":\"s\",\"catchUp\":false}",
                            "{\"kind\":\"pr-state\",\"epoch\":\"e\",\"seq\":\"1\",\"subject\":\"s\",\"catchUp\":false}",
                            "{\"kind\":\"pr-state\",\"epoch\":\"e\",\"seq\":1,\"catchUp\":false}",
                            "{\"kind\":\"pr-state\",\"epoch\":\"e\",\"seq\":1,\"subject\":\"s\",\"catchUp\":\"no\"}",
                            "{\"kind\":\"heartbeat\",\"seqHead\":0,\"sources\":{},\"selfCheck\":{\"ok\":true}}",
                            "{\"kind\":\"heartbeat\",\"epoch\":\"e\",\"sources\":{},\"selfCheck\":{\"ok\":true}}",
                            "{\"kind\":\"heartbeat\",\"epoch\":\"e\",\"seqHead\":0,\"sources\":[],\"selfCheck\":{\"ok\":true}}",
                            "{\"kind\":\"heartbeat\",\"epoch\":\"e\",\"seqHead\":0,\"sources\":{\"kiln\":{}},\"selfCheck\":{\"ok\":true}}",
                            "{\"kind\":\"heartbeat\",\"epoch\":\"e\",\"seqHead\":0,\"sources\":{},\"selfCheck\":{}}",
                            "{\"kind\":\"heartbeat\",\"epoch\":\"e\",\"seqHead\":0,\"sources\":{},\"selfCheck\":{\"ok\":true,\"failed\":\"x\"}}",
                            "{\"kind\":\"heartbeat\",\"epoch\":\"e\",\"seqHead\":0,\"sources\":{},\"selfCheck\":{\"ok\":true,\"failed\":[1]}}"})
    void ndjson_failsALine_missingOrMistypingAFieldItReads(String line) {
        FleetLine.ndjson(JSON, line)
                 .onSuccess(value -> Assertions.fail("expected a failure, got " + value));
    }

    @Test
    void text_decodesATextEvent_andTheJsonHeartbeat() {
        var line = "0f1e2d3c:7 2026-11-03T10:00:00Z host-unreachable bigboy catchUp=1 reason=b64:dGltZW91dA";

        FleetLine.text(JSON, line)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(decoded -> {
                     var event = (FleetLine.Event) decoded;

                     assertThat(event.line()).isEqualTo(line);
                     assertThat(event.epoch()).isEqualTo("0f1e2d3c");
                     assertThat(event.seq()).isEqualTo(7);
                     assertThat(event.kind()).isEqualTo("host-unreachable");
                     assertThat(event.subject()).isEqualTo("bigboy");
                     assertThat(event.catchUp()).isTrue();
                 });
        FleetLine.text(JSON, HEARTBEAT)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(decoded -> assertThat(decoded).isInstanceOf(FleetLine.Heartbeat.class));
    }

    /// Lead ruling F3, the reader's half: the controller's text line for the raw subject and value `b64:Zm9v` (pinned
    /// by the controller's `FleetUnitsTest.text_encodesARawValueThatStartsWithB64`) decodes, and one base64url decode
    /// of each `b64:` field gives the raw text back.
    @Test
    void text_aRawValueStartingWithB64_roundTrips() {
        var line = "01234567:42 2026-11-03T12:00:00Z pr-state b64:YjY0OlptOXY catchUp=0 title=b64:YjY0OlptOXY";

        FleetLine.text(JSON, line)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(decoded -> assertThat(readerValue(((FleetLine.Event) decoded).subject())).isEqualTo("b64:Zm9v"));
        assertThat(readerValue(line.substring(line.lastIndexOf("title=") + "title=".length()))).isEqualTo("b64:Zm9v");
    }

    /// The reader rule: a `b64:` value is base64url, anything else is the value itself.
    private static String readerValue(String value) {
        return value.startsWith("b64:")
               ? new String(Base64.getUrlDecoder().decode(value.substring(4)), StandardCharsets.UTF_8)
               : value;
    }

    @ParameterizedTest
    @ValueSource(strings = {"0f1e2d3c 2026-11-03T10:00:00Z gap - catchUp=0",
                            "0f1e2d3c:7 2026-11-03T10:00:00Z gap - catchUp=2",
                            "0F1E2D3C:7 2026-11-03T10:00:00Z gap - catchUp=0",
                            "0f1e2d3c:99999999999999999999 2026-11-03T10:00:00Z gap - catchUp=0"})
    void text_failsALine_outsideTheTextForm(String line) {
        FleetLine.text(JSON, line)
                 .onSuccess(value -> Assertions.fail("expected a failure, got " + value));
    }

    @Test
    void fleetReply_keepsEventsInOrder_andTheLastHeartbeat() {
        var first = new FleetLine.Heartbeat(EPOCH, 1, Map.of(), true, List.of());
        var last = new FleetLine.Heartbeat(EPOCH, 2, Map.of(), true, List.of());
        var event = new FleetLine.Event("l", EPOCH, 2, "gap", "s", false, null);
        var reply = FleetReply.fleetReply(List.of(first, event, last));

        assertThat(reply.events()).containsExactly(event);
        assertThat(reply.heartbeat()
                        .stream()).containsExactly(last);
        assertThat(FleetReply.fleetReply(List.of(event))
                             .heartbeat()
                             .isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0f1e2d3c-4b5a-4968-8776-655443322110:0",
                            "0F1E2D3C-4B5A-4968-8776-655443322110:9223372036854775807"})
    void fleetPosition_parsesEpochAndSeq(String raw) {
        FleetPosition.fleetPosition(raw)
                     .onFailure(cause -> Assertions.fail(cause.message()))
                     .onSuccess(position -> assertThat(position.text()).isEqualTo(raw.toLowerCase()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "42", "0f1e2d3c:42", "0f1e2d3c-4b5a-4968-8776-655443322110", "0f1e2d3c-4b5a-4968-8776-655443322110:",
                            "0f1e2d3c-4b5a-4968-8776-655443322110:-1", "0f1e2d3c-4b5a-4968-8776-655443322110:9223372036854775808"})
    void fleetPosition_refusesAnythingElse(String raw) {
        FleetPosition.fleetPosition(raw)
                     .onSuccess(value -> Assertions.fail("expected a failure, got " + value));
    }

    @Test
    void fleetPosition_past_neverMovesBack() {
        var position = FleetPosition.fleetPosition(EPOCH, 10);

        assertThat(position.past(12)
                           .seq()).isEqualTo(12);
        assertThat(position.past(3)
                           .seq()).isEqualTo(10);
    }
}
