package org.pragmatica.temper.store.pg;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.temper.store.api.StoreError;


/// Session-level advisory locks on dedicated sessions. Each use opens its own physical connection
/// through `sessions` (a non-pooling [Connectible]: its close sends Terminate and ends the server session), never one
/// of the main pool: the locked work's own statements use the main pool, so holding a lock never takes a connection
/// the work or any other caller needs. The lock is taken with `pg_try_advisory_lock`, released with
/// `pg_advisory_unlock` only after the work's promise has completed, and the session is closed afterwards whatever
/// happened, so a failed or uncertain UNLOCK cannot leave the lock behind. The lock statements are bounded; the work is
/// not cut off here (its time bounds sit on its own calls), because releasing while the work runs would let a second
/// caller in beside it.
///
/// WP-4b (§5.8 dismissal) adds the blocking variant here: the same key taken with `pg_advisory_lock` under
/// `lock_timeout` (≤ 40 s) on a session of this kind.
record LockSessions(Connectible sessions, Semaphore slots, TimeSpan bound) {
    /// At most four locked calls at once. A review holds its session for up to the POST (30 s) plus the carry proof
    /// (≤ 120 s), and reviews are rare (one per verdict, by a handful of verifier lanes), so four serve the fleet; the
    /// bound keeps the lock sessions a small fixed share of Postgres' connections beside the main pool. A fifth caller
    /// is refused at once ([StoreError.PrLock#BUSY], 503) instead of waiting in an unbounded queue.
    static final int SLOTS = 4;
    /// Bound on opening the session (connect, startup, auth) and on each lock statement (try, unlock): none waits on
    /// another session, so only a broken link or a stalled server is slow.
    static final TimeSpan STATEMENT_BOUND = TimeSpan.timeSpan(10).seconds();
    private static final String TRY_LOCK = "SELECT pg_try_advisory_lock($1::int, hashtext($2::text)) AS got";
    private static final String IDLE_TIMEOUT = "SELECT current_setting('idle_session_timeout') AS v";
    private static final String UNLOCK = "SELECT pg_advisory_unlock($1::int, hashtext($2::text)) AS released";

    static LockSessions lockSessions(Connectible sessions) {
        return lockSessions(sessions, STATEMENT_BOUND);
    }

    static LockSessions lockSessions(Connectible sessions, TimeSpan bound) {
        return new LockSessions(sessions, new Semaphore(SLOTS), bound);
    }

    /// Runs `work` while a dedicated session holds the lock `(classId, hashtext(key))`; `none` when another session
    /// holds it (`work` is not run). Fails with [StoreError.PrLock#BUSY] when all [#SLOTS] sessions are in use, and with
    /// [StoreError.PrLock#LOST] when `work` succeeded but the lock was not released by its own session.
    <T> Promise<Option<T>> tryWithLock(int classId, String key, Supplier<Promise<T>> work) {
        return slots.tryAcquire()
               ? onSession(classId, key, work).withResult(_ -> slots.release())
               : StoreError.PrLock.BUSY.promise();
    }

    private <T> Promise<Option<T>> onSession(int classId, String key, Supplier<Promise<T>> work) {
        return connected().mapError(StoreError.Database.FACTORY)
                        .flatMap(session -> locked(session, classId, key, work, bound).fold(outcome -> closed(session,
                                                                                                              outcome)));
    }

    /// The server's `idle_session_timeout` as seen by a lock session, which is the kind of session it must be safe for.
    Promise<String> idleSessionTimeout() {
        return connected().mapError(StoreError.Database.FACTORY)
                        .flatMap(session -> Sql.query(session, IDLE_TIMEOUT)
                                               .timeout(bound)
                                               .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                                               .map(row -> row.getString("v"))
                                               .fold(outcome -> closed(session, outcome)));
    }

    /// The whole acquire (connect, startup, auth) is bounded; Netty's own bound covers only the TCP connect. A session
    /// that completes after the bound is closed at once, so a stalled server leaves neither a permit nor a connection.
    private Promise<Connection> connected() {
        var guarded = Promise.<Connection> promise();

        guarded.timeout(bound);
        sessions.getConnection()
                .withResult(attempt -> attempt.onSuccess(session -> adopted(guarded, session))
                                              .onFailure(guarded::fail));

        return guarded;
    }

    private static void adopted(Promise<Connection> guarded, Connection session) {
        guarded.succeed(session).withFailure(_ -> session.close());
    }

    private static <T> Promise<Option<T>> locked(Connection session,
                                                 int classId,
                                                 String key,
                                                 Supplier<Promise<T>> work,
                                                 TimeSpan bound) {
        return Sql.query(session, TRY_LOCK, classId, key)
                  .timeout(bound)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .flatMap(row -> row.getBoolean("got")
                                  ? work.get()
                                        .fold(outcome -> unlocked(session, classId, key, outcome, bound))
                                        .map(Option::some)
                                  : Promise.success(Option.<T> none()));
    }

    /// The finally: runs after the work's promise completed, whatever it answered. A failed work keeps its own
    /// failure; a succeeded work whose UNLOCK did not report the release becomes [StoreError.PrLock#LOST].
    private static <T> Promise<T> unlocked(Connection session,
                                           int classId,
                                           String key,
                                           Result<T> outcome,
                                           TimeSpan bound) {
        return Sql.query(session, UNLOCK, classId, key)
                  .timeout(bound)
                  .map(resultSet -> Sql.first(resultSet)
                                       .map(row -> row.getBoolean("released"))
                                       .or(false))
                  .fold(released -> outcome.isSuccess() && !released.or(false)
                                    ? StoreError.PrLock.LOST.<T> promise()
                                    : Promise.resolved(outcome));
    }

    /// FER: a failed close is dropped. The session is a dedicated physical connection: closing it ends the server
    /// session, and with it any lock an UNLOCK did not release; a connection already dead holds nothing.
    private static <T> Promise<T> closed(Connection session, Result<T> outcome) {
        return session.close()
                      .fold(_ -> Promise.resolved(outcome));
    }
}
