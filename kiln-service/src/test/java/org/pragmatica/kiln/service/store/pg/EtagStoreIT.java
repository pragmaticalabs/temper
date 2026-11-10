package org.pragmatica.kiln.service.store.pg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.store.Cached;
import org.pragmatica.kiln.service.store.KilnStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [EtagStore][org.pragmatica.kiln.service.store.EtagStore] against real Postgres: the last 200 answer per URL.
@Tag("pg")
class EtagStoreIT {
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
    void cached_isAbsentUntilStored_thenTheLastAnswer_perUrl() {
        var pulls = "/repos/o/r/pulls?state=all";
        assertThat(await(store.etags().cached(pulls))).isEqualTo(none());

        await(store.etags().store(pulls, "W/\"1\"", "[]", T0));
        await(store.etags().store(pulls, "W/\"2\"", "[{\"number\":1}]", T1));
        await(store.etags().store("/repos/o/r/branches/main", "W/\"b\"", "{}", T0));

        assertThat(await(store.etags().cached(pulls))).isEqualTo(some(new Cached("W/\"2\"", "[{\"number\":1}]", T1)));
        assertThat(await(store.etags().cached("/repos/o/r/branches/main"))).isEqualTo(some(new Cached("W/\"b\"", "{}", T0)));
    }
}
