package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.pragmatica.kiln.service.store.KilnStore;

/// [KilnPgFixture] for tests outside this package (WP6's pollers): a freshly created, migrated database opened by the
/// production [KilnPostgres#connect], so its change rule is the production one. Without docker the test is skipped.
public final class PgStores {
    private PgStores() {}

    public static KilnStore freshStore() {
        return KilnPostgres.connect(KilnPgFixture.freshDatabase(), StoreFixtures.REPO, StoreFixtures.highWater())
                           .await(KilnPgFixture.WAIT)
                           .unwrap();
    }

    /// Every row of `table` as JSON text, in a stable order.
    public static List<String> dump(KilnStore store, String table) {
        return StoreFixtures.dump(store, table);
    }
}
