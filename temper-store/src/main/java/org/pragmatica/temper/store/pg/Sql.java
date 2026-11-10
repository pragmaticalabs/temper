package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Statement helpers. Binding rules pinned by `BindingIT` (m0-design §2.5): timestamps bind as UTC
/// [OffsetDateTime] (an `Instant` would bind as `timestamp`), jsonb as `$n::jsonb`, `String[]` as `$n::text[]`.
sealed interface Sql {
    static Promise<PgResultSet> query(QueryExecutor executor, String sql, Object... params) {
        return executor.completeQuery(sql, params)
                       .mapError(StoreError.Database.FACTORY);
    }

    static Promise<Unit> execute(QueryExecutor executor, String sql, Object... params) {
        return query(executor, sql, params).mapToUnit();
    }

    static Promise<Integer> update(QueryExecutor executor, String sql, Object... params) {
        return query(executor, sql, params).map(PgResultSet::affectedRows);
    }

    static List<PgRow> rows(PgResultSet resultSet) {
        return StreamSupport.stream(resultSet.spliterator(),
                                    false)
                            .toList();
    }

    static Option<PgRow> first(PgResultSet resultSet) {
        return resultSet.size() == 0
               ? none()
               : some(resultSet.index(0));
    }

    /// Every row through `parser`; the first failure fails the promise.
    static <T> Promise<List<T>> parseAll(PgResultSet resultSet, Fn1<Result<T>, PgRow> parser) {
        return Result.allOf(rows(resultSet).stream().map(parser::apply).toList()).async();
    }

    /// The first row through `parser`, if there is one.
    static <T> Promise<Option<T>> parseFirst(PgResultSet resultSet, Fn1<Result<T>, PgRow> parser) {
        return Values.optional(first(resultSet), parser).async();
    }

    /// Makes the current transaction a read-only snapshot: every statement after it sees the same committed state.
    /// Must be the transaction's first statement.
    static Promise<Unit> snapshot(QueryExecutor tx) {
        return execute(tx, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /// Driver binding: an absent value binds SQL NULL.
    static Object nullable(Option<?> value) {
        return value.toOptional()
                    .orElse(null);
    }

    static String[] texts(Stream<String> values) {
        return values.toArray(String[]::new);
    }

    /// Runs `step` for each item strictly one after another (a connection takes one statement at a time).
    static <T, R> Promise<List<R>> sequence(List<T> items, Fn1<Promise<R>, T> step) {
        return items.stream()
                    .reduce(Promise.success(List.<R> of()),
                            (done, item) -> then(done, item, step),
                            (left, _) -> left);
    }

    private static <T, R> Promise<List<R>> then(Promise<List<R>> done, T item, Fn1<Promise<R>, T> step) {
        return done.flatMap(results -> stepAndAppend(results, item, step));
    }

    private static <T, R> Promise<List<R>> stepAndAppend(List<R> results, T item, Fn1<Promise<R>, T> step) {
        return step.apply(item)
                   .map(next -> Stream.concat(results.stream(),
                                              Stream.of(next))
                                      .toList());
    }

    record unused() implements Sql {}
}
