package org.pragmatica.kiln.service.store.pg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.vo.Uuid;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// kiln's restore detection (verifier NOTE-1, R2-F1..F3): `file`, outside
/// the database, holds `<epoch> <last id>`; `known` holds the last mark kiln saw, in memory. A database restored or
/// replaced gets a new epoch, so a reader resets instead of skipping the ids a restored sequence hands out again:
/// - at start, when the file's epoch is not the database's, its id is above the database's sequence, it does not parse,
///   it cannot be read or written, or it is missing while the sequence has handed out ids (unknown history);
/// - while running ([#guarded], [#verified]), when the database's sequence is below the last mark kiln saw in the same
///   epoch: a restore under a running kiln. Checked before every feed read, inside every change-writing transaction
///   (before an id is drawn, so a batch can never carry a restored sequence past the mark unseen), and after every
///   committed batch, before the file is rewritten: a lower mark never overwrites a higher one without a rotation.
///
/// A file behind the database (a crash between commit and rename) is normal. No operator step. A file that cannot be
/// used never stops kiln (lead ruling R2-F3): kiln warns, and the feed's `kiln:highwater` source stops advancing
/// ([#lastOkAt]), so the reader sees it go stale.
///
/// Residual: a check that read the database before a rotation can merge its old-epoch mark into `known` and the file
/// after it; the next check adopts the database's epoch again, and the next start rotates once more (one extra reset,
/// never a loss).
record HighWater(Path file,
                 Supplier<Instant> clock,
                 AtomicReference<Option<Mark>> known,
                 AtomicReference<Option<Attempt>> attempted,
                 AtomicReference<Option<Instant>> okAt) {
    private static final Logger LOG = LoggerFactory.getLogger(HighWater.class);
    /// A mark already written is rewritten after this long, so a healthy file's `lastOkAt` stays well inside the
    /// source's period ([ChangeFeed#HIGH_WATER_PERIOD_S]).
    static final Duration REWRITE = Duration.ofSeconds(ChangeFeed.HIGH_WATER_PERIOD_S / 2);

    private static final String MARK = "SELECT (SELECT id::text FROM kiln_epoch) AS epoch, "
                                     + "(SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM change_id_seq) AS last";

    /// Removes the epoch seen, not whatever is there: two checks rotating at once rotate once.
    private static final String RETIRE = "DELETE FROM kiln_epoch WHERE id = $1::uuid";

    private static final String RESTORED = "the high-water file is ahead of it, foreign, unreadable, or missing after ids were handed out (a restored or replaced database)";

    private static final String WENT_BACK = "its sequence went back below the last id seen (a restore under a running kiln)";

    private static final String UNWRITABLE = "the high-water file cannot be written, so the next start could not tell a restore";

    /// The database's epoch and the last id its sequence handed out.
    record Mark(Uuid epoch, long last) {
        String line() {
            return epoch.value() + " " + last + "\n";
        }

        static Option<Mark> parsed(String line) {
            return Option.option(line.trim().split(" "))
                         .filter(parts -> parts.length == 2)
                         .flatMap(parts -> Result.all(Uuid.uuid(parts[0]),
                                                      Number.parseLong(parts[1]))
                                                 .map(Mark::new)
                                                 .option());
        }
    }

    /// The last write tried: its mark and when.
    record Attempt(Mark mark, Instant at) {}

    static HighWater highWater(Path file) {
        return new HighWater(file,
                             Instant::now,
                             new AtomicReference<>(none()),
                             new AtomicReference<>(none()),
                             new AtomicReference<>(none()));
    }

    /// When the file was last written: the feed's `kiln:highwater` source (lead ruling R2-F3). None before the first.
    Option<Instant> lastOkAt() {
        return okAt.get();
    }

    /// Condition, at start: the stored mark is not this database's past, so the database was restored or replaced. A
    /// file that does not parse is unknown; an absent file is a first start only while the sequence has handed out no
    /// id, else unknown (R2-F2). Unknown rotates.
    static boolean regressed(Option<String> stored, Mark database) {
        return stored.map(text -> Mark.parsed(text)
                                      .map(mark -> !mark.epoch()
                                                        .equals(database.epoch()) || mark.last() > database.last())
                                      .or(true))
                     .or(database.last() > 0);
    }

    /// Condition, while running (R2-F1): the database's sequence is below the last mark seen in its epoch. `known` is
    /// read before the database, and a sequence never goes back by itself, so a mark seen concurrently never counts.
    static boolean behind(Option<Mark> known, Mark database) {
        return known.filter(mark -> mark.epoch()
                                        .equals(database.epoch()) && mark.last() > database.last())
                    .isPresent();
    }

    /// The mark to keep: the known one when it is ahead in the same epoch, else the one seen.
    static Mark later(Option<Mark> known, Mark seen) {
        return known.filter(mark -> mark.epoch()
                                        .equals(seen.epoch()) && mark.last() > seen.last())
                    .or(seen);
    }

    /// Condition: the file is written when the mark differs from the last write tried, or that try is [#REWRITE] old.
    static boolean due(Option<Attempt> last, Mark mark, Instant now) {
        return last.filter(attempt -> attempt.mark()
                                             .equals(mark) && attempt.at()
                                                                     .plus(REWRITE)
                                                                     .isAfter(now))
                   .isEmpty();
    }

    /// At start: the restore check, then the file rewritten from the database.
    Promise<Unit> checked(Db db) {
        return mark(db.executor()).flatMap(database -> started(db.executor(), database, rotatesAtStart(read(), database)));
    }

    /// Before a feed read, and after a committed batch: the check, then the mark kept and the file rewritten when due.
    Promise<Unit> guarded(QueryExecutor executor) {
        return verified(executor).map(this::kept);
    }

    /// The check alone, on `executor`: a database behind the known mark rotates its epoch. Inside a change-writing
    /// transaction the mark is not kept (the transaction may roll back); reading the sequence and `kiln_epoch` there
    /// locks them until it commits, so a restore cannot land between the check and the ids it draws.
    Promise<Mark> verified(QueryExecutor executor) {
        var before = known.get();

        return mark(executor).flatMap(database -> rotatedWhen(executor, database, behind(before, database), WENT_BACK));
    }

    /// After a committed batch with changes. FER: a failed check is logged and the batch stands; the next feed read or
    /// batch checks again.
    Promise<List<Change>> after(Db db, List<Change> changes) {
        return changes.isEmpty()
               ? Promise.success(changes)
               : guarded(db.executor()).onFailure(cause -> LOG.warn("kiln could not check {}: {}",
                                                                    file,
                                                                    cause.message()))
                        .fold(_ -> Promise.success(changes));
    }

    /// Unreadable is unknown (R2-F3): kiln warns and rotates.
    private boolean rotatesAtStart(Result<Option<String>> stored, Mark database) {
        return stored.onFailure(cause -> LOG.warn("kiln cannot read {}: {}",
                                                  file,
                                                  cause.message()))
                     .map(text -> regressed(text, database))
                     .or(true);
    }

    private Promise<Unit> started(QueryExecutor executor, Mark database, boolean rotates) {
        return rotatedWhen(executor, database, rotates, RESTORED).flatMap(mark -> writtenAtStart(executor, mark, rotates));
    }

    /// FER (lead ruling R2-F3): an unwritable file never refuses the start; kiln warns, rotates (not twice: not again
    /// when it just did), and keeps the mark in memory.
    private Promise<Unit> writtenAtStart(QueryExecutor executor, Mark mark, boolean rotated) {
        return attempt(remembered(mark)).fold(cause -> unwritableAtStart(executor, mark, rotated, cause),
                                              _ -> Promise.unitPromise());
    }

    private Promise<Unit> unwritableAtStart(QueryExecutor executor, Mark mark, boolean rotated, Cause cause) {
        LOG.warn("kiln cannot write {}: {}", file, cause.message());

        return rotatedWhen(executor, mark, !rotated, UNWRITABLE).map(this::remembered)
                          .mapToUnit();
    }

    private Promise<Mark> rotatedWhen(QueryExecutor executor, Mark database, boolean rotates, String why) {
        return rotates
               ? rotated(executor, database, why)
               : Promise.success(database);
    }

    /// The seen epoch retired, then the new mark (its epoch created by [#mark]).
    private Promise<Mark> rotated(QueryExecutor executor, Mark database, String why) {
        LOG.warn("kiln rotates the change feed's epoch {}: {}",
                 database.epoch().value(),
                 why);

        return Sql.execute(executor,
                           RETIRE,
                           database.epoch().value().toString())
                  .flatMap(_ -> mark(executor));
    }

    private Unit kept(Mark seen) {
        return writtenIfDue(remembered(seen));
    }

    private Mark remembered(Mark seen) {
        return known.updateAndGet(current -> some(later(current, seen)))
                    .or(seen);
    }

    /// FER: a failed write is logged, and `lastOkAt` stops advancing, so the feed's reader sees `kiln:highwater` go
    /// stale. Synchronized: one write at a time, so two never share the sibling file.
    private synchronized Unit writtenIfDue(Mark mark) {
        return due(attempted.get(), mark, clock.get())
               ? attempt(mark).onFailure(cause -> LOG.warn("kiln could not write {}: {}",
                                                           file,
                                                           cause.message()))
                        .or(unit())
               : unit();
    }

    private Result<Unit> attempt(Mark mark) {
        attempted.set(some(new Attempt(mark, clock.get())));

        return write(mark).onSuccess(_ -> okAt.set(some(clock.get())));
    }

    /// The epoch row (created when missing, [PgChangeStore#ENSURE_EPOCH]) and the sequence's last id.
    private static Promise<Mark> mark(QueryExecutor executor) {
        return Sql.execute(executor, PgChangeStore.ENSURE_EPOCH)
                  .flatMap(_ -> Sql.query(executor, MARK))
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, HighWater::mark))
                  .flatMap(Option::async);
    }

    private static Result<Mark> mark(PgRow row) {
        return Rows.required(row, "epoch")
                   .flatMap(Uuid::uuid)
                   .map(epoch -> new Mark(epoch,
                                          row.getLong("last")))
                   .mapError(Rows.corrupt("kiln_epoch"));
    }

    private Result<Option<String>> read() {
        return Files.exists(file)
               ? Result.lift(this::failed,
                             () -> Files.readString(file))
                       .map(Option::some)
               : Result.success(none());
    }

    /// Written beside, then renamed over: a reader never sees half a line.
    private Result<Unit> write(Mark mark) {
        var next = file.resolveSibling(file.getFileName() + ".next");

        return Result.lift(this::failed,
                           () -> Files.createDirectories(file.toAbsolutePath().getParent()))
                     .flatMap(_ -> Result.lift(this::failed,
                                               () -> Files.writeString(next,
                                                                       mark.line())))
                     .flatMap(_ -> Result.lift(this::failed,
                                               () -> Files.move(next, file, REPLACE_EXISTING, ATOMIC_MOVE)))
                     .mapToUnit();
    }

    private KilnStoreError.HighWaterFile failed(Throwable thrown) {
        return KilnStoreError.HighWaterFile.FACTORY.apply(file.toString(), Causes.fromThrowable(thrown));
    }
}
