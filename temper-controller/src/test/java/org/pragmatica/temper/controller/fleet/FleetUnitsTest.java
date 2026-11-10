package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.temper.protocol.api.FleetEventLine;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetHead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The temper epoch decision, the self-check verdict (R2-12), staleness by age (F13) and the line renderings.
class FleetUnitsTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    /// No file on a stream never written (no row, no floor, the sequence never used) is the first start.
    @Test
    void decide_noFileOnAFreshStream_isFirst() {
        assertThat(EpochCheck.decide(none(), FleetHead.fleetHead("E", 0, 0))).isEqualTo(EpochCheck.Verdict.FIRST);
    }

    /// Correction 2 (b): no file but a used stream is an unknown history; a sequence used by rolled-back inserts only
    /// (no row) counts as used.
    @Test
    void decide_noFileOnAUsedStream_isUnknown() {
        assertThat(EpochCheck.decide(none(), FleetHead.fleetHead("E", 10, 10))).isEqualTo(EpochCheck.Verdict.UNKNOWN);
        assertThat(EpochCheck.decide(none(), FleetHead.fleetHead("E", 0, 1))).isEqualTo(EpochCheck.Verdict.UNKNOWN);
        assertThat(EpochCheck.decide(none(), FleetHead.fleetHead("E", 1, 0))).isEqualTo(EpochCheck.Verdict.UNKNOWN);
    }

    /// NOTE-7: the mark is compared with the head alone. A mark is a committed seq, so a sequence above the head (an
    /// insert in flight when the dump was taken) never excuses a mark above the head.
    @Test
    void ahead_comparesWithTheHeadOnly_neverTheSequence() {
        assertThat(EpochCheck.ahead(new HighWater.Mark("E", 12), FleetHead.fleetHead("E", 10, 12))).isTrue();
        assertThat(EpochCheck.ahead(new HighWater.Mark("E", 10), FleetHead.fleetHead("E", 10, 12))).isFalse();
        assertThat(EpochCheck.ahead(new HighWater.Mark("E", 11), FleetHead.fleetHead("E", 11, 0))).isFalse();
        assertThat(EpochCheck.ahead(new HighWater.Mark("E", 12), FleetHead.fleetHead("E", 11, 0))).isTrue();
        assertThat(EpochCheck.decide(some(new HighWater.Mark("E", 12)), FleetHead.fleetHead("E", 10, 12))).isEqualTo(EpochCheck.Verdict.RESTORED);
    }

    /// A dump taken earlier in the SAME epoch restores the epoch with a lower head: only the seq comparison sees it.
    @Test
    void decide_sameEpochFileAheadOfTheDatabase_isRestored() {
        assertThat(EpochCheck.decide(some(new HighWater.Mark("E", 11)), FleetHead.fleetHead("E", 10, 10))).isEqualTo(EpochCheck.Verdict.RESTORED);
    }

    @Test
    void decide_otherEpoch_isRestored_andAFileBehind_isCurrent() {
        assertThat(EpochCheck.decide(some(new HighWater.Mark("D", 1)), FleetHead.fleetHead("E", 10, 10))).isEqualTo(EpochCheck.Verdict.RESTORED);
        assertThat(EpochCheck.decide(some(new HighWater.Mark("E", 9)), FleetHead.fleetHead("E", 10, 10))).isEqualTo(EpochCheck.Verdict.CURRENT);
        assertThat(EpochCheck.decide(some(new HighWater.Mark("E", 10)), FleetHead.fleetHead("E", 10, 10))).isEqualTo(EpochCheck.Verdict.CURRENT);
    }

    /// R2-12: load far above any fixed limit, banner and loopback fine: the check passes, the load is only advisory.
    @Test
    void verdict_load4xCores_withBannerAndLoopbackFine_isOk() {
        var cores = 8;
        var check = SelfCheck.verdict(true, true, HeartbeatLine.Advisory.advisory(some(4.0 * cores), cores, some(95.0)));

        assertThat(check.ok()).isTrue();
        assertThat(check.failed()).isEmpty();
        assertThat(check.advisory().load1()).isEqualTo(some(32.0));
    }

    @Test
    void verdict_namesEachFailedProbe() {
        var advisory = HeartbeatLine.Advisory.advisory(none(), 4, none());

        assertThat(SelfCheck.verdict(false, true, advisory).failed()).containsExactly(SelfCheck.BANNER);
        assertThat(SelfCheck.verdict(true, false, advisory).failed()).containsExactly(SelfCheck.LOOPBACK);
        assertThat(SelfCheck.verdict(false, false, advisory).ok()).isFalse();
    }

    @Test
    void hasKeyLine_needsAKeyLine_notJustComments() {
        assertThat(SelfCheck.hasKeyLine(completed("# 127.0.0.1:22 SSH-2.0-OpenSSH_9.6\n"))).isFalse();
        assertThat(SelfCheck.hasKeyLine(completed(""))).isFalse();
        assertThat(SelfCheck.hasKeyLine(completed("# banner\n127.0.0.1 ssh-ed25519 AAAAC3Nza\n"))).isTrue();
    }

    @Test
    void advisoryParsers_readProcFormats() {
        assertThat(SelfCheck.load1("3.52 2.10 1.00 2/900 1234\n")).isEqualTo(some(3.52));
        assertThat(SelfCheck.memSomeAvg60("some avg10=1.00 avg60=2.50 avg300=0.10 total=1\nfull avg10=0.00 avg60=0.70 avg300=0.00 total=0\n"))
            .isEqualTo(some(2.5));
        assertThat(SelfCheck.memSomeAvg60("")).isEqualTo(none());
        assertThat(SelfCheck.memSomeAvg60("full avg10=0.00 avg60=9.00 avg300=0.00 total=0\nsome avg10=1.00 avg60=1.50 avg300=0.10 total=1\n"))
            .as("the some line, whatever its position").isEqualTo(some(1.5));
    }

    /// PIT (KilnReply.malformed): a `changes` that is not an array is a malformed reply, a failure (never an NPE).
    @Test
    void decode_changesNotAnArray_isMalformed() {
        var reply = KilnReply.decode(TemperJson.mapper().readTree("{\"epoch\":\"E1\",\"floor\":0,\"head\":3,\"changes\":{}}").unwrap());

        assertThat(reply.isFailure()).isTrue();
        assertThat(reply.fold(Cause::message, _ -> "")).contains("malformed changes");
    }

    @Test
    void stale_byAgeOnly_beyondThreePeriods_orNeverOk() {
        assertThat(Staleness.stale(none(), 60, NOW)).isTrue();
        assertThat(Staleness.stale(some(NOW.minusSeconds(180)), 60, NOW)).isFalse();
        assertThat(Staleness.stale(some(NOW.minusSeconds(181)), 60, NOW)).isTrue();
        assertThat(Staleness.stale(some(NOW.plusSeconds(Staleness.FUTURE_SLACK_S)), 60, NOW)).as("at the future slack").isFalse();
        assertThat(Staleness.stale(some(NOW.plusSeconds(Staleness.FUTURE_SLACK_S + 1)), 60, NOW)).as("beyond it").isTrue();
    }

    @Test
    void text_rendersTheContractGrammar_andEncodesValuesWithSpaces() {
        var payload = TemperJson.mapper().readTree("{\"pr\":7,\"title\":\"a b\",\"x\":\"k=v\"}").unwrap();
        var line = FleetEventLine.fleetEventLine("0123456789abcdef", 42, NOW, NOW, "pr-state", "o/r#7", true, "kiln", payload);

        assertThat(FleetLines.text(line)).isEqualTo("01234567:42 2026-11-03T12:00:00Z pr-state o/r#7 catchUp=1 pr=7 title=b64:YSBi x=b64:az12");
    }

    /// Lead ruling F3: a raw value that already starts with `b64:` is encoded too (subject and payload value alike), so
    /// a text reader that decodes every `b64:` value gets the raw text back, never a second decode of it. Probe: drop
    /// the prefix test → the value is written as is → red.
    @Test
    void text_encodesARawValueThatStartsWithB64() {
        var payload = TemperJson.mapper().readTree("{\"title\":\"b64:Zm9v\"}").unwrap();
        var line = FleetEventLine.fleetEventLine("0123456789abcdef", 42, NOW, NOW, "pr-state", "b64:Zm9v", false, "kiln", payload);

        assertThat(FleetLines.text(line)).isEqualTo("01234567:42 2026-11-03T12:00:00Z pr-state b64:YjY0OlptOXY catchUp=0 title=b64:YjY0OlptOXY");
        assertThat(List.of(FleetLines.value("b64:"), FleetLines.value("b64"), FleetLines.value("xb64:a")))
            .containsExactly("b64:YjY0Og", "b64", "xb64:a");
    }

    @Test
    void ndjson_keepsTheContractFieldOrder() {
        var mapper = TemperJson.mapper();
        var line = FleetEventLine.fleetEventLine("E", 1, NOW, NOW, "gap", "kiln", false, "kiln", FleetLines.empty());
        var heartbeat = HeartbeatLine.heartbeatLine("E", NOW, 1, Map.of(), SelfCheck.NOT_RUN, 0);

        assertThat(mapper.writeAsString(line).unwrap())
            .startsWith("{\"v\":1,\"epoch\":\"E\",\"seq\":1,\"at\":\"2026-11-03T12:00:00Z\",\"observedAt\":\"2026-11-03T12:00:00Z\",\"kind\":\"gap\",\"subject\":\"kiln\",\"catchUp\":false,\"source\":\"kiln\",\"payload\":{}");
        assertThat(mapper.writeAsString(heartbeat).unwrap()).startsWith("{\"v\":1,\"kind\":\"heartbeat\",\"epoch\":\"E\",\"asOf\":");
        assertThat(List.of(FleetLines.value(""), FleetLines.value("plain"))).containsExactly("b64:", "plain");
    }

    private static Completed completed(String stdout) {
        return Completed.completed("ssh-keyscan", 0, stdout.getBytes(), new byte[0], false);
    }
}
