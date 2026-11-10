package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.json.TemperJson;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The `fleet/alarms` reply shape. Absent values are `null` on the wire, every key is
/// present, and a reply round-trips through the strict mapper the CLI (S1-C) decodes with.
class FleetAlarmsViewJsonTest {
    private static final Instant AS_OF = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant TAKEN = Instant.parse("2026-11-06T09:59:40Z");

    private static FleetAlarmsView sample() {
        var queueWait = FleetAlarmsView.Alarm.alarm("queue-wait", some("t-1"), some(61), some(60), none(), none(), TAKEN);
        var retiring = FleetAlarmsView.Alarm.alarm("host-retiring", none(), none(), none(), some(1), none(), TAKEN);

        return FleetAlarmsView.fleetAlarmsView(AS_OF,
                                               List.of(FleetAlarmsView.HostAlarms.hostAlarms("bigboy",
                                                                                             "ok",
                                                                                             some(TAKEN),
                                                                                             some(TAKEN),
                                                                                             some(0),
                                                                                             some(Instant.parse("2026-11-07T00:00:00Z")),
                                                                                             List.of(queueWait, retiring)),
                                                       FleetAlarmsView.HostAlarms.hostAlarms("d1",
                                                                                             "NOT-COLLECTED",
                                                                                             none(),
                                                                                             none(),
                                                                                             none(),
                                                                                             none(),
                                                                                             List.of())));
    }

    @Test
    void reply_roundTripsThroughTheStrictMapper() {
        var json = TemperJson.mapper().writeAsString(sample()).unwrap();
        var back = TemperJson.mapper().readString(json, FleetAlarmsView.class).unwrap();

        assertThat(back).isEqualTo(sample());
        assertThat(back.v()).isEqualTo(1);
    }

    @Test
    void reply_carriesEveryKeyWithNullForAbsentValues() {
        var json = TemperJson.mapper().writeAsString(sample()).unwrap();

        assertThat(json).contains("\"v\":1",
                                  "\"asOf\":\"2026-11-06T10:00:00Z\"",
                                  "\"status\":\"NOT-COLLECTED\"",
                                  "\"takenAt\":null",
                                  "\"retireAt\":\"2026-11-07T00:00:00Z\"",
                                  "\"kind\":\"queue-wait\"",
                                  "\"ticket\":\"t-1\"",
                                  "\"ageMin\":61",
                                  "\"limitMin\":60",
                                  "\"days\":null",
                                  "\"firedAt\":\"2026-11-06T09:59:40Z\"",
                                  "\"days\":1");
    }

    @Test
    void reply_absentOptionalKeysReadAsNone() {
        var json = "{\"v\":1,\"asOf\":\"2026-11-06T10:00:00Z\",\"hosts\":[{\"host\":\"h\",\"status\":\"UNREACHABLE\","
                 + "\"alarms\":[{\"kind\":\"clock-skew\",\"skewS\":-31,\"firedAt\":\"2026-11-06T09:00:00Z\"}]}]}";
        var back = TemperJson.mapper().readString(json, FleetAlarmsView.class).unwrap();
        var host = back.hosts().getFirst();

        assertThat(host.takenAt()).isEqualTo(Option.<Instant> none());
        assertThat(host.alarms().getFirst().skewS()).isEqualTo(some(-31));
        assertThat(host.alarms().getFirst().ticket().isPresent()).isFalse();
    }
}
