package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.DigestStore;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.EvidenceStore;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.HostStore;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.KnownStore;
import org.pragmatica.temper.store.api.MacStore;
import org.pragmatica.temper.store.api.MaintenanceStore;
import org.pragmatica.temper.store.api.RefusalStore;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.RetentionStore;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.ReviewStore;
import org.pragmatica.temper.store.api.T2Store;
import org.pragmatica.temper.store.api.TaskStore;
import org.pragmatica.temper.store.api.UnitStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// [Store] over one postgres-async pool. Package-private: the pool ([Db]) never leaves the adapter, so nothing
/// outside it can insert an event without the order lock. The public entry is [Postgres#connect].
record PgStore(Db db,
               JobStore jobs,
               UnitStore units,
               ResultStore results,
               EventStore events,
               RefusalStore refusals,
               KnownStore known,
               HostStore hosts,
               EvidenceStore evidence,
               RetentionStore retention,
               MaintenanceStore maintenance,
               DigestStore digest,
               TaskStore tasks,
               FleetStore fleet,
               T2Store t2,
               ReviewStore reviews,
               MacStore mac,
               BbStore bb) implements Store {
    private static final Logger LOG = LoggerFactory.getLogger(PgStore.class);
    private static final TimeSpan CLOSE_TIMEOUT = TimeSpan.timeSpan(30).seconds();

    /// Pool, then migrations; a failed migration closes the pool and reports the migration's failure.
    static Promise<Store> connect(DbConfig cfg, Judge judge) {
        return pool(cfg).async()
                   .flatMap(db -> migrated(db, judge));
    }

    /// The repositories that write a judge (jobs, grants, results) write `judge`.
    static Store pgStore(Db db, Judge judge) {
        return new PgStore(db,
                           new PgJobStore(db, judge),
                           new PgUnitStore(db, judge),
                           new PgResultStore(db),
                           new PgEventStore(db),
                           new PgRefusalStore(db),
                           new PgKnownStore(db),
                           new PgHostStore(db),
                           new PgEvidenceStore(db),
                           new PgRetentionStore(db),
                           new PgMaintenanceStore(db),
                           new PgDigestStore(db),
                           new PgTaskStore(db),
                           new PgFleetStore(db),
                           new PgT2Store(db),
                           new PgReviewStore(db),
                           new PgMacStore(db),
                           new PgBbStore(db));
    }

    /// `AutoCloseable` contract: blocks until the pool is closed, at most [#CLOSE_TIMEOUT]. FER: a failed or timed-out
    /// close is logged and dropped — the store is being discarded, and the caller (shutdown) has nothing to retry.
    @Contract
    @TerminalOperation
    @Override
    public void close() {
        db.close()
          .await(CLOSE_TIMEOUT)
          .onFailure(cause -> LOG.warn("temper-store: pool close failed: {}",
                                       cause.message()));
    }

    private static Result<Db> pool(DbConfig cfg) {
        return Result.lift(cause -> StoreError.Database.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> Db.db(builder(cfg)));
    }

    private static NettyConnectibleBuilder builder(DbConfig cfg) {
        var builder = new NettyConnectibleBuilder();

        builder.hostname(cfg.host())
               .port(cfg.port())
               .database(cfg.database())
               .username(cfg.user())
               .password(cfg.password())
               .maxConnections(cfg.maxConnections());

        return builder;
    }

    private static Promise<Store> migrated(Db db, Judge judge) {
        return MigrationRunner.migrate(db).fold(outcome -> afterMigration(db, judge, outcome));
    }

    private static Promise<Store> afterMigration(Db db, Judge judge, Result<Unit> outcome) {
        return outcome.fold(cause -> closeAfter(db, cause), _ -> guarded(db, judge));
    }

    /// The idle_session_timeout guard, read on a lock session; a refusal closes the pool like a failed migration.
    private static Promise<Store> guarded(Db db, Judge judge) {
        return db.locks()
                 .idleSessionTimeout()
                 .flatMap(reported -> IdleSessionTimeout.check(reported).async())
                 .fold(outcome -> outcome.fold(cause -> closeAfter(db, cause),
                                               _ -> Promise.success(pgStore(db, judge))));
    }

    /// FER: the pool is closed best-effort; the migration's failure is what startup reports.
    private static Promise<Store> closeAfter(Db db, Cause cause) {
        return db.close()
                 .fold(_ -> cause.promise());
    }
}
