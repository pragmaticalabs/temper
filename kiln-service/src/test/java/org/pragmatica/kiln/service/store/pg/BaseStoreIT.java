package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.KilnStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [BaseStore][org.pragmatica.kiln.service.store.BaseStore] against real Postgres: a write replaces its scope's runs and
/// leaves the other scopes, a run may be listed under two scopes, and the issues are replaced as a set.
@Tag("pg")
class BaseStoreIT {
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
    void runs_replaceTheirScope_only() {
        await(store.bases().runs("main", List.of(run(1, "main", T0), run(2, "main", T0))));
        await(store.bases().runs("schedule", List.of(run(2, "schedule", T0))));

        await(store.bases().runs("main", List.of(run(3, "main", T1))));

        assertThat(await(store.views().bases()).runs()).containsExactly(run(3, "main", T1), run(2, "schedule", T0));
    }

    @Test
    void issues_areReplacedAsASet() {
        var old = new BaseView.Issue(10, "nightly failed", "https://github.com/o/r/issues/10", T0, T0);
        var current = new BaseView.Issue(11, "nightly failed again", "https://github.com/o/r/issues/11", T1, T1);
        await(store.bases().issues(List.of(old)));

        await(store.bases().issues(List.of(current)));

        assertThat(await(store.views().bases()).issues()).containsExactly(current);
    }

    private static BaseView.Run run(long id, String scope, Instant at) {
        return new BaseView.Run(id, scope, "CI", "completed", id == 1 ? none() : some("success"), "c".repeat(40),
                                "https://github.com/o/r/actions/runs/" + id, at, at);
    }
}
