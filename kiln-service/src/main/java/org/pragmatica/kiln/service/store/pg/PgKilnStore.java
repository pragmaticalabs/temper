package org.pragmatica.kiln.service.store.pg;

import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Supplier;

import org.pragmatica.kiln.service.store.BaseStore;
import org.pragmatica.kiln.service.store.ChangeStore;
import org.pragmatica.kiln.service.store.CheckStore;
import org.pragmatica.kiln.service.store.CheckoutStore;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.kiln.service.store.FeedStore;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.kiln.service.store.TemperStore;
import org.pragmatica.kiln.service.store.ViewStore;
import org.pragmatica.kiln.service.store.PipelineStore;
import org.pragmatica.kiln.service.store.WorkStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;


/// [KilnStore] over one postgres-async pool, as temper's `PgStore`. Package-private: the pool ([Db]) never leaves the
/// adapter. The public entry is [KilnPostgres#connect].
record PgKilnStore(Db db,
                   PrStore prs,
                   CheckStore checks,
                   SourceStore sources,
                   CheckoutStore checkouts,
                   ChangeStore changes,
                   EtagStore etags,
                   TemperStore temper,
                   ViewStore views,
                   BaseStore bases,
                   FeedStore feed,
                   WorkStore work,
                   PipelineStore pipeline) implements KilnStore {
    /// Pool, then migrations; a failed migration closes the pool and reports the migration's failure. `diff` is the
    /// change rule the PR and check writes apply ([ChangeDiff#CHANGES] in production); `repo` (`[github] repo`) is the
    /// repository their changes are recorded about.
    static Promise<KilnStore> connect(DbConfig cfg, String repo, Path highWater, ChangeDiff diff) {
        return connect(cfg, repo, highWater, diff, Instant::now);
    }

    /// `clock` feeds the change watermark ([Watermark]) when no change write is in flight. The watermark starts at the
    /// newest stored `recorded_at`, so a clock that stepped back across a restart never stamps a change at or below a
    /// cursor handed out before it. `highWater` is the change feed's mark outside the database ([HighWater]).
    static Promise<KilnStore> connect(DbConfig cfg,
                                      String repo,
                                      Path highWater,
                                      ChangeDiff diff,
                                      Supplier<Instant> clock) {
        return pool(cfg).async()
                   .map(Db::db)
                   .flatMap(db -> opened(db,
                                         repo,
                                         HighWater.highWater(highWater),
                                         diff,
                                         clock));
    }

    static KilnStore pgKilnStore(Db db, String repo, HighWater feedMark, ChangeDiff diff, Watermark committed) {
        return new PgKilnStore(db,
                               new PgPrStore(db, repo, diff, committed, feedMark),
                               new PgCheckStore(db, repo, diff, committed, feedMark),
                               new PgSourceStore(db),
                               new PgCheckoutStore(db),
                               new PgChangeStore(db, committed, feedMark),
                               new PgEtagStore(db),
                               new PgTemperStore(db),
                               new PgViewStore(db),
                               new PgBaseStore(db),
                               new PgFeedStore(db, committed, feedMark),
                               new PgWorkStore(db),
                               new PgPipelineStore(db));
    }

    @Override
    public Promise<Unit> close() {
        return db.close();
    }

    private static Result<Connectible> pool(DbConfig cfg) {
        return Result.lift(cause -> KilnStoreError.Database.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> builder(cfg).pool());
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

    /// Migrations, the restore check ([HighWater#checked]), then the watermark's start; any failing closes the pool.
    private static Promise<KilnStore> opened(Db db,
                                             String repo,
                                             HighWater feedMark,
                                             ChangeDiff diff,
                                             Supplier<Instant> clock) {
        return MigrationRunner.migrate(db)
                              .flatMap(_ -> feedMark.checked(db))
                              .flatMap(_ -> PgChangeStore.lastRecorded(db.executor()))
                              .map(last -> pgKilnStore(db,
                                                       repo,
                                                       feedMark,
                                                       diff,
                                                       Watermark.watermark(clock, last)))
                              .fold(outcome -> closedOnFailure(db, outcome));
    }

    private static Promise<KilnStore> closedOnFailure(Db db, Result<KilnStore> outcome) {
        return outcome.fold(cause -> closeAfter(db, cause), Promise::success);
    }

    /// FER: the pool is closed best-effort; the startup failure is what startup reports.
    private static Promise<KilnStore> closeAfter(Db db, Cause cause) {
        return db.close()
                 .fold(_ -> cause.promise());
    }
}
