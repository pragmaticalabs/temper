package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.temper.domain.refusal.NewRefusal;
import org.pragmatica.temper.domain.refusal.RefusalRule;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.store.api.RefusalRow;
import org.pragmatica.temper.store.api.RefusalStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.option;


/// [RefusalStore] over Postgres: matchers parse through `RefusalMatcherDto.toDomain(code)` and are written through
/// its inverse `RefusalMatcherDto.from(rule)`.
record PgRefusalStore(Db db) implements RefusalStore {
    private static final String COLUMNS = "code, matcher::text AS matcher, issue, reason, until_text, expired_at";

    /// The code is the primary key: an existing code, active or expired, inserts nothing and returns no row.
    private static final String ADD = "INSERT INTO refusals (code, matcher, issue, reason, until_text) "
                                    + "VALUES ($1, $2::jsonb, $3, $4, $5) ON CONFLICT (code) DO NOTHING RETURNING " + COLUMNS;

    private static final String EXPIRE = "UPDATE refusals SET expired_at = coalesce(expired_at, now()) WHERE code = $1 RETURNING " + COLUMNS;

    @Override
    public Promise<List<RefusalRule>> active() {
        return Sql.query(db.executor(),
                         "SELECT code, matcher::text AS matcher FROM refusals WHERE expired_at IS NULL ORDER BY code")
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgRefusalStore::rule));
    }

    @Override
    public Promise<List<RefusalRow>> list() {
        return Sql.query(db.executor(),
                         "SELECT " + COLUMNS + " FROM refusals ORDER BY code")
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgRefusalStore::refusalRow));
    }

    @Override
    public Promise<RefusalRow> add(NewRefusal refusal) {
        return Json.write(RefusalMatcherDto.from(refusal.rule()))
                   .async()
                   .flatMap(matcher -> Sql.query(db.executor(),
                                                 ADD,
                                                 refusal.code(),
                                                 matcher,
                                                 Sql.nullable(refusal.issue()),
                                                 refusal.reason(),
                                                 Sql.nullable(refusal.until())))
                   .flatMap(resultSet -> Sql.parseFirst(resultSet, PgRefusalStore::refusalRow))
                   .flatMap(row -> row.async(StoreError.RefusalExists.FACTORY.apply(refusal.code())));
    }

    @Override
    public Promise<RefusalRow> expire(String code) {
        return Sql.query(db.executor(),
                         EXPIRE,
                         code)
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgRefusalStore::refusalRow))
                  .flatMap(row -> row.async(StoreError.RefusalNotFound.FACTORY.apply(code)));
    }

    private static Result<RefusalRule> rule(PgRow row) {
        return Json.read(row.getString("matcher"),
                         RefusalMatcherDto.class)
                   .flatMap(matcher -> matcher.toDomain(row.getString("code")))
                   .mapError(Rows.corrupt("refusals"));
    }

    private static Result<RefusalRow> refusalRow(PgRow row) {
        return rule(row).map(rule -> RefusalRow.refusalRow(rule,
                                                           option(row.getInt("issue")),
                                                           row.getString("reason"),
                                                           Rows.text(row, "until_text"),
                                                           option(row.getInstant("expired_at"))));
    }
}
