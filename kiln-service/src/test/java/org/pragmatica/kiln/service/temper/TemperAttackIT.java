package org.pragmatica.kiln.service.temper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.temper.TemperFake.DIGEST;
import static org.pragmatica.kiln.service.temper.TemperFake.T0;
import static org.pragmatica.kiln.service.temper.TemperFake.TOKEN;
import static org.pragmatica.kiln.service.temper.TemperFake.await;
import static org.pragmatica.kiln.service.temper.TemperFake.openPr;
import static org.pragmatica.kiln.service.temper.TemperFake.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The WP6 verifier's attacks on the temper reader (kilnv6, t1–t6), kept as regression tests, with the fix round's
/// controls: a gate answer for another SHA is `TEMPER-SCHEMA(sha)` and never shown as the head's (reader, store and
/// view), a prefix answer stands, and the loop is single-flight with a deadline.
@Tag("pg")
class TemperAttackIT {
    private KilnStore store;
    private TemperFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = TemperFake.start();
        fake.answer(DIGEST, 200, "queue: 0\n");
        await(store.prs().upsert(List.of(openPr(1)), T0));
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    /// T1: a gate answer naming another SHA is `TEMPER-SCHEMA(sha)` and is never shown as the head's.
    @Test
    void t1_gateForAnotherSha_isSchemaSha_andNotShownAsTheHeads() {
        fake.gate(sha(1), 200, "{\"sha\":\"" + sha(99).value() + "\",\"answer\":\"PASS\"}");

        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.TemperSchema("sha", T0));
        assertThat(shownGate()).isEqualTo(some(new GateRead.SchemaBroken("sha")));
    }

    /// Control: temper answers a prefix query with the prefix; an answer naming a prefix of the head stands.
    @Test
    void t1_gateForAPrefixOfTheHead_isShown() {
        var prefix = sha(1).value().substring(0, 7);

        fake.gate(sha(1), 200, "{\"sha\":\"" + prefix + "\",\"answer\":\"NONE\"}");

        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.Ok(T0));
        assertThat(shownGate()).isEqualTo(some(new GateRead.Answered(prefix, "NONE", none(), none())));
    }

    @Test
    void matched_keepsTheHeadOrAPrefix_andRefusesAnyOtherSha() {
        var head = sha(1);

        assertThat(TemperReader.matched(head, answered(head.value()))).isEqualTo(answered(head.value()));
        assertThat(TemperReader.matched(head, answered(head.value().substring(0, 12)))).isEqualTo(answered(head.value().substring(0, 12)));
        assertThat(TemperReader.matched(head, answered(sha(99).value()))).isEqualTo(new GateRead.SchemaBroken("sha"));
        assertThat(TemperReader.matched(head, answered(head.value() + "0"))).isEqualTo(new GateRead.SchemaBroken("sha"));
        assertThat(TemperReader.matched(head, new GateRead.SchemaBroken("answer"))).isEqualTo(new GateRead.SchemaBroken("answer"));
        assertThat(TemperReader.matched(head, answered(head.value().substring(0, 7)))).isEqualTo(answered(head.value().substring(0, 7)));
        assertThat(TemperReader.matched(head, answered(head.value().substring(0, 6)))).as("6 chars").isEqualTo(new GateRead.SchemaBroken("sha"));
        assertThat(TemperReader.matched(head, answered("0"))).isEqualTo(new GateRead.SchemaBroken("sha"));
    }

    /// R6 (round 3): a one-character sha is a prefix of the head, yet names no commit: `TEMPER-SCHEMA(sha)`, never the
    /// head's PASS.
    @Test
    void r6_oneCharSha_isSchemaSha_andNotShownAsTheHeads() {
        fake.gate(sha(1), 200, "{\"sha\":\"0\",\"answer\":\"PASS\"}");

        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.TemperSchema("sha", T0));
        assertThat(shownGate()).isEqualTo(some(new GateRead.SchemaBroken("sha")));
    }

    /// Only lowercase hex stands: an upper-case answer for the head is `TEMPER-SCHEMA(sha)`.
    @Test
    void matched_refusesUpperCase() {
        var head = Sha.sha("ab".repeat(20)).unwrap();

        assertThat(TemperReader.matched(head, answered("AB".repeat(20)))).isEqualTo(new GateRead.SchemaBroken("sha"));
        assertThat(TemperReader.matched(head, answered("ab".repeat(20)))).isEqualTo(answered("ab".repeat(20)));
    }

    /// The store keeps the answer's own sha, and the view shows an answer only for the head it names (or a prefix).
    @Test
    void t1_view_showsAStoredAnswerOnlyForTheHeadItNames() {
        var pr = openPr(1).number();

        await(store.temper().gate(pr, sha(1), answered(sha(99).value()), T0));
        assertThat(shownGate()).as("an answer for another SHA").isEqualTo(none());

        await(store.temper().gate(pr, sha(1), answered(sha(1).value()), T0));
        assertThat(shownGate()).isEqualTo(some(answered(sha(1).value())));

        await(store.temper().gate(pr, sha(1), answered(sha(1).value().substring(0, 7)), T0));
        assertThat(shownGate()).isEqualTo(some(answered(sha(1).value().substring(0, 7))));
    }

    // 10: single-flight, with a deadline

    /// An overlapping tick is skipped and counted, and reads nothing.
    @Test
    void tick_isSkippedAndCounted_whileACycleRuns() {
        var digest = Promise.<String> promise();
        var http = stub(() -> digest);
        var reader = TemperReader.temperReader(http, store, now::get);

        var first = reader.tick();

        assertThat(await(reader.tick())).isEqualTo(none());
        assertThat(reader.skippedTicks()).isEqualTo(1);
        digest.succeed("queue: 0\n");
        assertThat(await(first)).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(await(reader.tick())).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(reader.skippedTicks()).isEqualTo(1);
    }

    /// A synchronous throw settles the tick and frees the next.
    @Test
    void tick_thatThrows_releasesTheFlag() {
        var left = new AtomicInteger(1);
        var reader = TemperReader.temperReader(stub(() -> thrownOnce(left)), store, now::get);

        assertThat(reader.tick().await(TemperFake.WAIT).isFailure()).isTrue();
        assertThat(await(reader.tick())).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(reader.skippedTicks()).isZero();
    }

    /// A temper that never answers: past the deadline the tick reads `TEMPER-DOWN(cycle timed out)` and frees the next;
    /// the late cycle, answered afterwards, reads no gate.
    @Test
    void tick_pastTheDeadline_readsTimedOut_andTheLateCycleReadsNoGate() throws InterruptedException {
        var digest = Promise.<String> promise();
        var gates = new AtomicInteger();
        var answered = new AtomicInteger(1);
        var http = new TemperHttp() {
            @Override
            public Promise<String> digestText() {
                return answered.getAndDecrement() > 0 ? digest : Promise.success("queue: 0\n");
            }

            @Override

            public Promise<org.pragmatica.kiln.core.temper.HostsRead> fleetAlarms() {

                return Promise.success(new org.pragmatica.kiln.core.temper.HostsRead.Answered(java.util.List.of()));

            }


            @Override
            public Promise<GateRead> gate(Sha head) {
                gates.incrementAndGet();
                return Promise.success(answered(head.value()));
            }
        };
        var reader = TemperReader.temperReader(http, store, now::get, Duration.ofMillis(300));

        assertThat(await(reader.tick())).isEqualTo(some(new SourceState.TemperDown("cycle timed out", T0)));
        digest.succeed("late\n");
        Thread.sleep(300);
        assertThat(gates.get()).as("the late cycle reads no gate").isZero();
        assertThat(await(store.views().digest()).isEmpty()).as("the late cycle writes no digest").isTrue();

        assertThat(await(reader.tick())).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(gates.get()).isEqualTo(1);
    }

    /// T2: a 200 whose body is not JSON is TEMPER-SCHEMA(body), not TEMPER-DOWN.
    @Test
    void t2_nonJson200_isSchemaBody() {
        fake.gate(sha(1), 200, "<html>oops</html>");

        assertThat(await(reader(fake.http()).cycle())).isEqualTo(new SourceState.TemperSchema("body", T0));
    }

    /// T3: a digest holding NUL must still yield a source state (verbatim, or a stated refusal), not a failed cycle.
    @Test
    void t3_digestWithNul_yieldsASourceState() {
        fake.answer(DIGEST, 200, "queue\u0000 3\n");
        fake.gate(sha(1), 200, "{\"sha\":\"" + sha(1).value() + "\",\"answer\":\"PASS\"}");

        var cycle = reader(fake.http()).cycle().await(TemperFake.WAIT);

        assertThat(cycle.isSuccess()).as("cycle " + cycle).isTrue();
    }

    /// T4: a huge digest (over the 4 MiB cap) is TEMPER-DOWN, not a hang or an OOM.
    @Test
    void t4_hugeDigest_isTemperDown() {
        fake.answer(DIGEST, 200, "x".repeat(5 * 1024 * 1024));

        assertThat(await(reader(fake.http()).cycle())).isInstanceOf(SourceState.TemperDown.class);
    }

    /// T5: a slow temper is bounded by the whole-exchange timeout.
    @Test
    void t5_slowTemper_isBoundedByTheTimeout() throws IOException {
        var slow = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        slow.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write("q".getBytes());
                exchange.getResponseBody().flush();
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slow.start();
        try {
            var http = JdkTemperHttp.jdkTemperHttp(HttpClient.newHttpClient(),
                                                   URI.create("http://127.0.0.1:" + slow.getAddress().getPort()),
                                                   TOKEN,
                                                   Duration.ofSeconds(1));
            var started = System.nanoTime();
            var state = reader(http).cycle().await(TimeSpan.timeSpan(6).seconds());
            var took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(state.isSuccess()).as("state " + state).isTrue();
            assertThat(state.unwrap()).isInstanceOf(SourceState.TemperDown.class);
            assertThat(took).isLessThan(Duration.ofSeconds(4));
        } finally {
            slow.stop(0);
        }
    }

    /// T6: with a redirect-following client (JdkTemperHttp takes any client), the bearer must not reach another host.
    @Test
    void t6_redirectToAnotherHost_neverCarriesTheReadToken() throws IOException {
        var other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var seen = new CopyOnWriteArrayList<String>();

        other.createContext("/", exchange -> {
            seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        other.start();
        var redirector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        redirector.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + "/x");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirector.start();
        try {
            var following = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            var http = JdkTemperHttp.jdkTemperHttp(following,
                                                   URI.create("http://127.0.0.1:" + redirector.getAddress().getPort()),
                                                   TOKEN,
                                                   Duration.ofSeconds(5));

            http.digestText().await(TimeSpan.timeSpan(10).seconds());
            assertThat(seen).as("Authorization seen by the other host").noneMatch(value -> value.contains(TOKEN));
        } finally {
            redirector.stop(0);
            other.stop(0);
        }
    }

    private TemperReader reader(TemperHttp http) {
        return TemperReader.temperReader(http, store, now::get);
    }

    private Option<GateRead> shownGate() {
        return await(store.views().openPrs()).getFirst().gate().map(Observed::value);
    }

    private static GateRead answered(String sha) {
        return new GateRead.Answered(sha, "PASS", none(), none());
    }

    private static Promise<String> thrownOnce(AtomicInteger left) {
        if (left.getAndDecrement() > 0) {
            throw new IllegalStateException("temper client broke");
        }
        return Promise.success("queue: 0\n");
    }

    /// The digest from `digest`; every gate answers PASS for the head asked.
    private static TemperHttp stub(Supplier<Promise<String>> digest) {
        return new TemperHttp() {
            @Override
            public Promise<String> digestText() {
                return digest.get();
            }

            @Override

            public Promise<org.pragmatica.kiln.core.temper.HostsRead> fleetAlarms() {

                return Promise.success(new org.pragmatica.kiln.core.temper.HostsRead.Answered(java.util.List.of()));

            }


            @Override
            public Promise<GateRead> gate(Sha head) {
                return Promise.success(answered(head.value()));
            }
        };
    }
}
