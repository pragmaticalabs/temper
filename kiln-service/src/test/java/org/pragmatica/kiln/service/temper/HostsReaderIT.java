package org.pragmatica.kiln.service.temper;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.core.temper.HostsRead;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.temper.TemperFake.T0;
import static org.pragmatica.kiln.service.temper.TemperFake.TOKEN;
import static org.pragmatica.kiln.service.temper.TemperFake.await;
import static org.pragmatica.lang.Option.some;

/// Rows S1.18-S1.20: the `hosts` source reads `GET /api/v1/fleet/alarms` tolerantly
/// and reports health in kiln's existing vocabulary, holding the last good reply in memory.
@Tag("pg")
class HostsReaderIT {
    private static final String ROUTE = "/api/v1/fleet/alarms";
    private static final String BODY = "{\"v\":1,\"asOf\":\"2026-10-30T10:00:00Z\",\"hosts\":[{\"host\":\"bigboy\",\"status\":\"ok\","
                                       + "\"takenAt\":\"2026-10-30T09:59:00Z\",\"alarms\":[{\"kind\":\"queue-wait\",\"ticket\":\"t-7\","
                                       + "\"ageMin\":75,\"limitMin\":60,\"firedAt\":\"2026-10-30T09:58:00Z\"}]}]}";

    private KilnStore store;
    private TemperFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = TemperFake.start();
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    /// S1.18: a 200 with fields kiln does not know is `Ok` and its rows are held.
    @Test
    void cycle_isOk_onAnUnknownField_andHoldsTheRows() {
        fake.answer(ROUTE, 200, BODY.replace("\"v\":1,", "\"v\":1,\"futureField\":{\"x\":[1,2]},")
                                    .replace("\"status\":\"ok\",", "\"status\":\"ok\",\"newPerHost\":true,"));
        var reader = reader(fake.http());

        assertThat(await(reader.cycle())).isEqualTo(new SourceState.Ok(T0));

        assertThat(state()).isEqualTo(new SourceState.Ok(T0));
        assertThat(reader.lastReply().stream()).singleElement().satisfies(held -> {
            assertThat(held.observedAt()).isEqualTo(T0);
            assertThat(held.value().hosts()).singleElement().satisfies(host -> {
                assertThat(host.host()).isEqualTo("bigboy");
                assertThat(host.alarms()).singleElement().satisfies(alarm -> {
                    assertThat(alarm.kind()).isEqualTo("queue-wait");
                    assertThat(alarm.ticket()).isEqualTo(some("t-7"));
                    assertThat(alarm.ageMin()).isEqualTo(some(75));
                });
            });
        });
        assertThat(fake.authorizations()).singleElement().isEqualTo("Bearer " + TOKEN);
    }

    /// S1.19: a refusal is `TEMPER-DOWN(<reason>)` since the first failure; the last rows stay; kiln writes no event.
    @ParameterizedTest
    @ValueSource(ints = {500, 401, 404})
    void cycle_isTemperDown_sinceTheFirstFailure_andKeepsTheLastRows(int status) {
        fake.answer(ROUTE, 200, BODY);
        var reader = reader(fake.http());

        await(reader.cycle());
        fake.answer(ROUTE, status, "no");
        now.set(T0.plusSeconds(60));
        var first = await(reader.cycle());
        now.set(T0.plusSeconds(120));
        var second = await(reader.cycle());

        assertThat(first).isEqualTo(new SourceState.TemperDown("HTTP " + status, T0.plusSeconds(60)));
        assertThat(second).isEqualTo(new SourceState.TemperDown("HTTP " + status, T0.plusSeconds(60)));
        assertThat(state()).isEqualTo(second);
        assertThat(reader.lastReply().stream()).singleElement().satisfies(held -> {
            assertThat(held.observedAt()).as("the rows keep the age of the last success").isEqualTo(T0);
            assertThat(held.value().hosts()).hasSize(1);
        });
        assertThat(await(store.changes().since(T0.minusSeconds(3_600), 100))).as("kiln emits no event").isEmpty();
    }

    @Test
    void cycle_isTemperDown_onARefusedConnection() throws Exception {
        var reader = reader(JdkTemperHttp.jdkTemperHttp(HttpClient.newHttpClient(), closedPort(), TOKEN, Duration.ofSeconds(5)));

        assertThat(await(reader.cycle())).isInstanceOfSatisfying(SourceState.TemperDown.class,
                                                                 down -> assertThat(down.reason()).startsWith("unreachable: ")
                                                                                                  .doesNotContain(TOKEN));
        assertThat(reader.lastReply().isPresent()).isFalse();
    }

    /// S1.20: a reply without `hosts` is `TEMPER-SCHEMA(hosts)`, never `Ok` with no hosts.
    @Test
    void cycle_isTemperSchema_onAReplyWithoutHosts() {
        fake.answer(ROUTE, 200, "{\"v\":1,\"asOf\":\"2026-10-30T10:00:00Z\"}");
        var reader = reader(fake.http());

        assertThat(await(reader.cycle())).isEqualTo(new SourceState.TemperSchema("hosts", T0));
        assertThat(state()).isEqualTo(new SourceState.TemperSchema("hosts", T0));
        assertThat(reader.lastReply().isPresent()).isFalse();
    }

    @Test
    void cycle_keepsTheLastRows_whenALaterReplyLacksAField() {
        fake.answer(ROUTE, 200, BODY);
        var reader = reader(fake.http());

        await(reader.cycle());
        fake.answer(ROUTE, 200, "{\"v\":1}");
        now.set(T0.plusSeconds(60));

        assertThat(await(reader.cycle())).isEqualTo(new SourceState.TemperSchema("hosts", T0.plusSeconds(60)));
        assertThat(reader.lastReply().stream()).singleElement().satisfies(held -> assertThat(held.observedAt()).isEqualTo(T0));
    }

    private HostsReader reader(TemperHttp http) {
        return HostsReader.hostsReader(http, store.sources(), now::get);
    }

    private SourceState state() {
        return await(store.sources().all()).stream()
                                            .filter(status -> status.source().equals(Source.HOSTS))
                                            .map(SourceStatus::state)
                                            .findFirst()
                                            .orElseThrow();
    }

    private static URI closedPort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }
}
