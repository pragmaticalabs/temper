package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.store.KilnStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pr;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [TemperStore][org.pragmatica.kiln.service.store.TemperStore] against real Postgres: the digest is stored verbatim;
/// a gate read is an answer (E-1: `job` and `judge` may be absent) or the field a broken answer lacked, shown for the
/// current head only.
@Tag("pg")
class TemperStoreIT {
    private KilnStore store;

    @BeforeEach
    void store() {
        store = KilnPgFixture.freshStore(StoreFixtures.DIFF);
    }

    @AfterEach
    void close() {
        if (store != null) {
            await(store.close());
        }
    }

    @Test
    void digest_isStoredVerbatim_lastWriteWins() {
        assertThat(await(store.views().digest())).isEqualTo(none());
        var text = "jobs: 3 running\n  #42 gate  ✓\n\t<raw & unescaped>";

        await(store.temper().digest("old", T0));
        await(store.temper().digest(text, T1));

        assertThat(await(store.views().digest())).isEqualTo(some(new Observed<>(text, T1)));
    }

    @Test
    void gate_roundTripsAnAnswer_aNoneAnswer_andABrokenOne() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        var reads = List.<GateRead> of(new GateRead.Answered(sha('a').value(), "PASS", some(42L), some("1a2b3c4d")),
                                       new GateRead.Answered(sha('a').value(), "NONE", none(), none()),
                                       new GateRead.SchemaBroken("answer"));

        for (var read : reads) {
            await(store.temper().gate(pr(1), sha('a'), read, T1));

            assertThat(await(store.views().openPrs()).getFirst().gate()).isEqualTo(some(new Observed<>(read, T1)));
        }
    }

    @Test
    void gate_ofAnOlderHead_isNotShown() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        await(store.temper().gate(pr(1), sha('a'), new GateRead.Answered(sha('a').value(), "PASS", none(), none()), T1));

        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'b')), T2));

        assertThat(await(store.views().openPrs()).getFirst().gate()).isEqualTo(none());
    }
}
