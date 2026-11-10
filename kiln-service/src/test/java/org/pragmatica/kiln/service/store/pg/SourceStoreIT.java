package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.store.KilnStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.BIGBOY;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [SourceStore][org.pragmatica.kiln.service.store.SourceStore] against real Postgres: every state of every source key
/// round-trips; only `Ok` sets the last success, and a failure keeps it (spec §6 panel 1).
@Tag("pg")
class SourceStoreIT {
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
    void everyState_roundTrips_forEverySourceKey() {
        var states = List.<SourceState> of(new SourceState.Ok(T0),
                                            new SourceState.Unreachable("ssh exit 255", T0),
                                            new SourceState.Incomplete("no trailer", T0),
                                            new SourceState.RateLimited(T2, T0),
                                            new SourceState.GithubDown("502", T0),
                                            new SourceState.TemperDown("connect refused", T0),
                                            new SourceState.TemperSchema("answer", T0));
        var sources = List.of(Source.GITHUB, Source.MIRROR, Source.TEMPER, Source.MAC, Source.host(BIGBOY));

        for (var state : states) {
            for (var source : sources) {
                await(store.sources().record(source, state));
            }
            assertThat(await(store.sources().all())).extracting(SourceStatus::state).containsOnly(state);
        }
        assertThat(await(store.sources().all())).extracting(SourceStatus::source)
                                                .containsExactly(Source.GITHUB, Source.host(BIGBOY), Source.MAC, Source.MIRROR, Source.TEMPER);
    }

    /// A stored row that no longer parses fails the read as a corrupt row of its table (`KilnStoreError.CorruptRow`, inside
    /// the read's composite), never as a missing source.
    @Test
    void all_failsWithCorruptRow_onAnUnparseableRow() {
        StoreFixtures.execute(store, "INSERT INTO source_state (source, state, since, updated_at) VALUES ('host:Bad Name', 'OK', now(), now())");

        store.sources()
             .all()
             .await(KilnPgFixture.WAIT)
             .onSuccess(all -> fail("expected a corrupt row, got " + all))
             .onFailure(cause -> assertThat(cause.message()).contains("Unreadable source_state row"));
    }

    @Test
    void lastSuccess_isSetByOk_andKeptThroughAFailure() {
        await(store.sources().record(Source.GITHUB, new SourceState.GithubDown("502", T0)));
        assertThat(await(store.sources().all()).getFirst().lastSuccess()).isEqualTo(none());

        await(store.sources().record(Source.GITHUB, new SourceState.Ok(T1)));
        await(store.sources().record(Source.GITHUB, new SourceState.RateLimited(T2.plusSeconds(600), T2)));

        assertThat(await(store.sources().all())).containsExactly(SourceStatus.sourceStatus(Source.GITHUB,
                                                                                           new SourceState.RateLimited(T2.plusSeconds(600), T2),
                                                                                           some(T1)));
    }
}
