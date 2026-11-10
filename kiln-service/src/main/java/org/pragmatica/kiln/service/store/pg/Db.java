package org.pragmatica.kiln.service.store.pg;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;


/// Transaction helper over the postgres-async pool, copied from temper-store (kiln-f1-design §1).
interface Db {
    /// Begin; run `work`; commit on success, roll back on failure; ALWAYS close the connection, because commit and
    /// rollback do not release it. Failures of the work are reported unchanged; driver failures as
    /// `KilnStoreError.Database`.
    <T> Promise<T> inTransaction(Fn1<Promise<T>, Transaction> work);
    /// The pool, for single statements outside a transaction.
    QueryExecutor executor();
    Promise<Unit> close();

    static Db db(Connectible pool) {
        return new PgDb(pool);
    }
}
