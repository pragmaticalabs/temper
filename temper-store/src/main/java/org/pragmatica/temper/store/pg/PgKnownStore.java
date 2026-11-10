package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.known.KnownEntry;
import org.pragmatica.temper.domain.known.KnownMatch;
import org.pragmatica.temper.store.api.KnownListing;
import org.pragmatica.temper.store.api.KnownRow;
import org.pragmatica.temper.store.api.KnownStore;
import org.pragmatica.temper.store.api.StoreError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// [KnownStore] over Postgres (m2-design §4.2), and the active-entry read [Finishing] runs inside the completing
/// transaction ([#active]).
record PgKnownStore(Db db) implements KnownStore {
    private static final Logger LOG = LoggerFactory.getLogger(PgKnownStore.class);
    private static final String COLUMNS = "id, test_id, issue, reason, until_text, created_by, created_at, expired_at";

    /// The partial unique index `known_active` is the arbiter: an active entry for the test inserts nothing and returns
    /// no row; an expired one does not conflict.
    private static final String ADD = "INSERT INTO known (test_id, issue, reason, until_text, created_by) "
                                    + "VALUES ($1, $2, $3, $4, $5) ON CONFLICT (test_id) WHERE expired_at IS NULL DO NOTHING RETURNING " + COLUMNS;

    private static final String EXPIRE = "UPDATE known SET expired_at = coalesce(expired_at, now()) WHERE id = $1 RETURNING " + COLUMNS;

    private static final String ACTIVE = "SELECT " + COLUMNS + " FROM known WHERE expired_at IS NULL";

    /// Ruling R-M2-15: a row `KnownEntry` cannot read is listed as [KnownListing.Unreadable] with its id, its stored
    /// values and the reason, never hidden and never a failure of the whole list. The table refusing everything
    /// `KnownEntry` refuses (V8's CHECKs are its rules) keeps such rows to hand edits and older schemas.
    @Override
    public Promise<List<KnownListing>> list() {
        return Sql.query(db.executor(),
                         "SELECT " + COLUMNS + " FROM known ORDER BY id")
                  .map(resultSet -> listings(Sql.rows(resultSet)));
    }

    @Override
    public Promise<KnownRow> add(KnownEntry entry) {
        return Sql.query(db.executor(),
                         ADD,
                         entry.testId(),
                         entry.issue(),
                         entry.reason(),
                         entry.until(),
                         entry.createdBy().value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgKnownStore::knownRow))
                  .flatMap(row -> row.async(StoreError.KnownExists.FACTORY.apply(entry.testId())));
    }

    /// An unreadable row is expired like any other and answered as [KnownListing.Unreadable] (ruling R-M2-15).
    @Override
    public Promise<KnownListing> expire(long id) {
        return Sql.query(db.executor(),
                         EXPIRE,
                         id)
                  .map(resultSet -> Sql.first(resultSet).map(PgKnownStore::listing))
                  .flatMap(row -> row.async(StoreError.KnownNotFound.FACTORY.apply(id)));
    }

    /// The entries active at the known read, one statement inside the completing transaction `tx`, after the job lock
    /// (READ COMMITTED, ruling R-M2-14), as the matcher of the record it completes (m2-design §4.3). A row `KnownEntry`
    /// cannot read is skipped with a WARN naming its id and the domain's reason (ruling R-M2-15, fail-safe: it never
    /// marks a record, so it never masks a FAILED, and it never stops a job from finishing); `known list` shows it as
    /// UNREADABLE. `known_active` allows one active row per test; `min` keeps the map total regardless.
    static Promise<KnownMatch> active(QueryExecutor tx) {
        return Sql.query(tx, ACTIVE).map(resultSet -> knownMatch(readable(Sql.rows(resultSet))));
    }

    private static KnownMatch knownMatch(List<KnownRow> rows) {
        return KnownMatch.knownMatch(rows.stream()
                                         .collect(Collectors.toMap(PgKnownStore::testId, PgKnownStore::issue, Math::min)));
    }

    private static String testId(KnownRow row) {
        return row.entry()
                  .testId();
    }

    private static Integer issue(KnownRow row) {
        return row.entry()
                  .issue();
    }

    private static List<KnownRow> readable(List<PgRow> rows) {
        return rows.stream()
                   .flatMap(row -> readableRow(row).stream())
                   .toList();
    }

    /// Fail-safe (ruling R-M2-15): an entry `KnownEntry` cannot read never marks a record, so it never masks a FAILED;
    /// the WARN names it, and `known list` shows it as UNREADABLE.
    private static Option<KnownRow> readableRow(PgRow row) {
        return entry(row).onFailure(cause -> LOG.warn("known row {} unreadable, not applied: {}",
                                                      row.getLong("id"),
                                                      problem(cause)))
                    .map(entry -> stored(row, entry))
                    .option();
    }

    private static List<KnownListing> listings(List<PgRow> rows) {
        return rows.stream()
                   .map(PgKnownStore::listing)
                   .toList();
    }

    private static KnownListing listing(PgRow row) {
        return entry(row).fold(cause -> unreadable(row, cause), entry -> KnownListing.readable(stored(row, entry)));
    }

    private static KnownListing unreadable(PgRow row, Cause cause) {
        return KnownListing.Unreadable.unreadable(row.getLong("id"),
                                                  row.getString("test_id"),
                                                  storedIssue(row),
                                                  row.getString("reason"),
                                                  row.getString("until_text"),
                                                  row.getString("created_by"),
                                                  row.getInstant("created_at"),
                                                  option(row.getInstant("expired_at")),
                                                  problem(cause));
    }

    /// The stored issue; a NULL (possible only where an older schema or a hand edit dropped NOT NULL) reads as 0, which
    /// `KnownEntry` refuses, so the row is shown UNREADABLE and skipped at finish instead of failing either read.
    private static int storedIssue(PgRow row) {
        return option(row.getInt("issue")).or(0);
    }

    /// The domain's reasons, one per refused field, joined by `; `.
    private static String problem(Cause cause) {
        return cause.stream()
                    .map(Cause::message)
                    .collect(Collectors.joining("; "));
    }

    private static Result<KnownEntry> entry(PgRow row) {
        return KnownEntry.knownEntry(row.getString("test_id"),
                                     storedIssue(row),
                                     row.getString("reason"),
                                     row.getString("until_text"),
                                     row.getString("created_by"));
    }

    private static Result<KnownRow> knownRow(PgRow row) {
        return entry(row).map(entry -> stored(row, entry))
                    .mapError(Rows.corrupt("known"));
    }

    private static KnownRow stored(PgRow row, KnownEntry entry) {
        return KnownRow.knownRow(row.getLong("id"),
                                 entry,
                                 row.getInstant("created_at"),
                                 option(row.getInstant("expired_at")));
    }
}
