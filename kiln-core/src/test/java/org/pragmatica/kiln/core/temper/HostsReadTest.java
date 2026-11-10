package org.pragmatica.kiln.core.temper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The tolerant read of `fleet/alarms`.
class HostsReadTest {
    private static final String HOST = "{\"host\":\"h\",\"status\":\"ok\",\"alarms\":[]}";

    @Test
    void decode_ignoresUnknownFields_atEveryLevel() {
        var read = HostsRead.decode("{\"v\":1,\"later\":{\"a\":[1]},\"hosts\":[{\"host\":\"h\",\"status\":\"ok\",\"extra\":1,"
                                    + "\"retireAt\":\"2026-12-01T00:00:00Z\",\"skewS\":3,\"alarms\":[{\"kind\":\"host-retiring\","
                                    + "\"days\":3,\"unknown\":\"x\"}]}]}");

        assertThat(read).isInstanceOfSatisfying(HostsRead.Answered.class, answered ->
            assertThat(answered.hosts()).singleElement().satisfies(host -> {
                assertThat(host.skewS()).isEqualTo(some(3));
                assertThat(host.retireAt().isPresent()).isTrue();
                assertThat(host.alarms()).singleElement().satisfies(alarm -> assertThat(alarm.days()).isEqualTo(some(3)));
            }));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"hosts\":[]}|v",
        "{\"v\":1}|hosts",
        "{\"v\":1,\"hosts\":{}}|hosts",
        "{\"v\":1,\"hosts\":[{\"status\":\"ok\",\"alarms\":[]}]}|host",
        "{\"v\":1,\"hosts\":[{\"host\":\"h\",\"alarms\":[]}]}|status",
        "{\"v\":1,\"hosts\":[{\"host\":\"h\",\"status\":\"ok\"}]}|alarms",
        "not json|body",
        "''|body"
    })
    void decode_namesTheMissingRequiredField(String body, String field) {
        assertThat(HostsRead.decode(body)).isEqualTo(new HostsRead.SchemaBroken(field));
    }

    @Test
    void decode_readsAnAlarmWithoutKind_asUnknown() {
        var read = HostsRead.decode("{\"v\":1,\"hosts\":[{\"host\":\"h\",\"status\":\"ok\",\"alarms\":[{\"ticket\":\"t\"}]}]}");

        assertThat(read).isInstanceOfSatisfying(HostsRead.Answered.class, answered ->
            assertThat(answered.hosts().getFirst().alarms()).singleElement().satisfies(alarm -> {
                assertThat(alarm.kind()).isEqualTo("unknown");
                assertThat(alarm.ticket()).isEqualTo(some("t"));
            }));
    }

    @Test
    void decode_readsAnUnparseableTimeAsAbsent_notAsBroken() {
        var read = HostsRead.decode("{\"v\":1,\"hosts\":[{\"host\":\"h\",\"status\":\"ok\",\"takenAt\":\"soon\",\"alarms\":[]}]}");

        assertThat(read).isInstanceOfSatisfying(HostsRead.Answered.class, answered ->
            assertThat(answered.hosts().getFirst().takenAt()).isEqualTo(none()));
    }

    @Test
    void decode_acceptsNoHosts() {
        assertThat(HostsRead.decode("{\"v\":1,\"hosts\":[" + HOST + "]}")).isInstanceOf(HostsRead.Answered.class);
        assertThat(HostsRead.decode("{\"v\":1,\"hosts\":[]}")).isEqualTo(new HostsRead.Answered(java.util.List.of()));
    }
}
