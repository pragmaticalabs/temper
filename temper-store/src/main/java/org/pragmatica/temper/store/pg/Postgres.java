package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.Store;


/// The one public entry of the Postgres adapter (ruling R-10 moved it here from `Store.connect`).
public sealed interface Postgres {
    /// Builds the pool and applies the pending migrations; a migration failure closes the pool and fails. `judge` is the
    /// build this store writes as (m2-design §1.7): every job it inserts, every unit it grants and every result it writes
    /// names it.
    static Promise<Store> connect(DbConfig cfg, Judge judge) {
        return PgStore.connect(cfg, judge);
    }

    record unused() implements Postgres {}
}
