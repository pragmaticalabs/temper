package org.pragmatica.kiln.service.temper;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.temper.TemperFake.DIGEST;
import static org.pragmatica.kiln.service.temper.TemperFake.T0;
import static org.pragmatica.kiln.service.temper.TemperFake.TOKEN;
import static org.pragmatica.kiln.service.temper.TemperFake.await;
import static org.pragmatica.kiln.service.temper.TemperFake.openPr;
import static org.pragmatica.kiln.service.temper.TemperFake.sha;
import static org.pragmatica.lang.Option.some;

/// spec §4.5, §5.6: the digest is stored verbatim; temper refusing or failing (5xx, 401, 403, connect failure) is
/// `TEMPER-DOWN(<reason>)` since its first failure, and keeps the digest already stored. Every read carries the bearer
/// read token, which no failure and no `toString` shows.
@Tag("pg")
class TemperReaderIT {
    private static final String TEXT = "queue: 3 waiting\n  job 41  RUNNING  bigboy\n";
    private static final String GATE = "{\"sha\":\"" + sha(1).value() + "\",\"answer\":\"PASS\"}";

    private KilnStore store;
    private TemperFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = TemperFake.start();
        fake.answer(DIGEST, 200, TEXT);
        fake.gate(sha(1), 200, GATE);
        await(store.prs().upsert(List.of(openPr(1)), T0));
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    @Test
    void cycle_storesTheDigestVerbatim_andSendsTheBearer() {
        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.Ok(T0));

        assertThat(await(store.views().digest())).isEqualTo(some(Observed.observed(TEXT, T0)));
        assertThat(fake.authorizations()).hasSize(2).allMatch(("Bearer " + TOKEN)::equals);
        assertThat(state()).isEqualTo(new SourceState.Ok(T0));
    }

    /// Only open heads are asked: a closed PR's head gets no gate read (it has no gate answer, and asking it would
    /// read TEMPER-DOWN from the 404).
    @Test
    void cycle_readsTheGatesOfOpenPrsOnly() {
        var closed = new org.pragmatica.kiln.core.github.PullRequest(org.pragmatica.kiln.core.model.PrNumber.prNumber(5).unwrap(), "closed",
                                                                   org.pragmatica.kiln.core.github.PrState.CLOSED, false,
                                                                   openPr(5).base(), openPr(5).headRef(), some("o/r"), sha(5),
                                                                   org.pragmatica.lang.Option.none(), some(T0), T0, List.of(),
                                                                   List.of(), List.of());

        await(store.prs().upsert(List.of(closed), T0));

        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.Ok(T0));
        assertThat(fake.authorizations()).as("the digest and one open head").hasSize(2);
    }

    /// temper's root may carry a path and a trailing slash: the API paths go below it.
    @Test
    void http_appendsThePathsBelowARootWithAPath() {
        fake.answer("/t" + DIGEST, 200, "below");
        var http = JdkTemperHttp.jdkTemperHttp(HttpClient.newHttpClient(), URI.create(fake.api() + "/t/"), TOKEN, Duration.ofSeconds(5));

        assertThat(await(http.digestText())).isEqualTo("below");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 401, 403})
    void cycle_isTemperDown_onARefusedDigest_andKeepsTheOldDigest(int status) {
        var reader = reader(fake.http());

        await(reader.cycle());
        fake.answer(DIGEST, status, "nope");
        now.set(T0.plusSeconds(60));

        assertThat(await(reader.cycle())).isEqualTo(new SourceState.TemperDown("HTTP " + status, T0.plusSeconds(60)));
        assertThat(await(store.views().digest())).as("the old digest, with its age").isEqualTo(some(Observed.observed(TEXT, T0)));
    }

    @Test
    void cycle_isTemperDown_onARefusedGate() {
        fake.gate(sha(1), 401, "");

        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.TemperDown("HTTP 401", T0));
    }

    @Test
    void cycle_isTemperDown_sinceTheFirstFailure_whenTemperIsUnreachable() throws Exception {
        var closed = closedPort();
        var reader = reader(JdkTemperHttp.jdkTemperHttp(HttpClient.newHttpClient(), closed, TOKEN, Duration.ofSeconds(5)));

        var first = await(reader.cycle());

        now.set(T0.plusSeconds(60));
        var second = await(reader.cycle());

        assertThat(first).isInstanceOfSatisfying(SourceState.TemperDown.class,
                                                 down -> assertThat(down.reason()).startsWith("unreachable: ")
                                                                                  .doesNotContain(TOKEN));
        assertThat(second).isInstanceOfSatisfying(SourceState.TemperDown.class,
                                                  down -> assertThat(down.since()).isEqualTo(T0));
    }

    @Test
    void toString_andFailures_neverShowTheToken() {
        var http = fake.http();

        assertThat(http.toString()).doesNotContain(TOKEN).contains("<redacted>");
        fake.answer(DIGEST, 403, "");
        var failure = http.digestText().await(TemperFake.WAIT);

        assertThat(failure.isFailure()).isTrue();
        failure.onFailure(cause -> assertThat(cause.message()).doesNotContain(TOKEN));
    }

    private TemperReader reader(TemperHttp http) {
        return TemperReader.temperReader(http, store, now::get);
    }

    private SourceState state() {
        return await(store.sources().all()).stream()
                                            .filter(status -> status.source().equals(Source.TEMPER))
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
