package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;


/// Transaction helper over the postgres-async pool (m0-design §2.5).
interface Db {
    /// Begin; run `work`; commit on success, roll back on failure; ALWAYS close the connection, because commit and
    /// rollback do not release it. Failures of the work are reported unchanged; driver failures as
    /// `StoreError.Database`.
    <T> Promise<T> inTransaction(Fn1<Promise<T>, Transaction> work);
    /// Runs `work` on one connection taken from the pool, outside any transaction, and ALWAYS closes it afterwards.
    /// Close returns the connection to the pool; the session does NOT end, so session-scoped state (a session-level
    /// advisory lock) outlives the call. Use [#inTransaction] for anything that must not.
    <T> Promise<T> withConnection(Fn1<Promise<T>, Connection> work);
    /// The pool, for single statements outside a transaction.
    QueryExecutor executor();
    /// Dedicated sessions for session-level advisory locks, outside the pool.
    LockSessions locks();
    Promise<Unit> close();

    /// The pool and the lock sessions of one builder: one configuration, one event loop group.
    static Db db(NettyConnectibleBuilder builder) {
        return new PgDb(builder.pool(),
                        LockSessions.lockSessions(builder.plain()));
    }
}
