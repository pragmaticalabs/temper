package org.pragmatica.kiln.service.store.pg;

import java.nio.file.Path;

import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Promise;


/// The one public entry of kiln-pg (as temper's `Postgres`).
public sealed interface KilnPostgres {
    /// Builds the pool and applies the pending `kiln/db/migration` files under lock 7171; a migration failure closes the
    /// pool and fails. The PR and check writes record their changes with WP2's `Changes.between`, about `repo` (the
    /// configured `[github] repo`, the one repository kiln reads until multi-repo, CI interface T1 WP2).
    /// `highWater` is the change feed's high-water file (`[db] highWaterFile`), checked at start for a restored database.
    static Promise<KilnStore> connect(DbConfig cfg, String repo, Path highWater) {
        return PgKilnStore.connect(cfg, repo, highWater, ChangeDiff.CHANGES);
    }

    record unused() implements KilnPostgres {}
}
