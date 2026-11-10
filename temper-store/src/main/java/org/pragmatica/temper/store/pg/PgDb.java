package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.store.api.StoreError;


/// [Db] over a postgres-async pool.
record PgDb(Connectible pool, LockSessions locks) implements Db {
    @Override
    public <T> Promise<T> inTransaction(Fn1<Promise<T>, Transaction> work) {
        return pool.getConnection()
                   .mapError(StoreError.Database.FACTORY)
                   .flatMap(connection -> inConnection(connection, work));
    }

    @Override
    public <T> Promise<T> withConnection(Fn1<Promise<T>, Connection> work) {
        return pool.getConnection()
                   .mapError(StoreError.Database.FACTORY)
                   .flatMap(connection -> work.apply(connection)
                                              .fold(outcome -> release(connection, outcome)));
    }

    @Override
    public QueryExecutor executor() {
        return pool;
    }

    @Override
    public Promise<Unit> close() {
        return pool.close()
                   .mapError(StoreError.Database.FACTORY);
    }

    private static <T> Promise<T> inConnection(Connection connection, Fn1<Promise<T>, Transaction> work) {
        return connection.begin()
                         .mapError(StoreError.Database.FACTORY)
                         .flatMap(transaction -> runAndFinish(transaction, work))
                         .fold(outcome -> release(connection, outcome));
    }

    private static <T> Promise<T> runAndFinish(Transaction transaction, Fn1<Promise<T>, Transaction> work) {
        return work.apply(transaction)
                   .fold(outcome -> finish(transaction, outcome));
    }

    private static <T> Promise<T> finish(Transaction transaction, Result<T> outcome) {
        return outcome.fold(cause -> rollback(transaction, cause), value -> commit(transaction, value));
    }

    private static <T> Promise<T> commit(Transaction transaction, T value) {
        return transaction.commit()
                          .mapError(StoreError.Database.FACTORY)
                          .map(_ -> value);
    }

    /// FER: a failed ROLLBACK is dropped and the work's own failure is reported. Nothing is committed either way —
    /// Postgres commits only on COMMIT, and the connection is closed right after, which aborts any transaction a
    /// broken connection still held.
    private static <T> Promise<T> rollback(Transaction transaction, Cause cause) {
        return transaction.rollback()
                          .fold(_ -> cause.promise());
    }

    /// FER: a failed close is dropped. The transaction's outcome is already settled (committed or not), and the
    /// pool discards a connection it cannot return.
    private static <T> Promise<T> release(Connection connection, Result<T> outcome) {
        return connection.close()
                         .fold(_ -> Promise.resolved(outcome));
    }
}
