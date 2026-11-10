package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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
import org.pragmatica.kiln.service.store.KilnStoreError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Statement helpers, copied from temper-store (kiln-f1-design §1). Binding rules as temper's `BindingIT` pins them:
/// timestamps bind as UTC [OffsetDateTime] (an `Instant` would bind as `timestamp`), jsonb as `$n::jsonb`, `String[]` as `$n::text[]`.
///
/// Every `String` and `String[]` parameter is bound with U+0000 replaced by U+FFFD ([#text]): Postgres `text` refuses
/// U+0000, and one such character in a free-text field (a PR title, a label, a reason, the digest) would otherwise fail
/// the whole batch it is written in, on every cycle (kiln WP5 verification, lead ruling 3). A lookup binds the same
/// replacement, so it finds what was stored. jsonb values get the same replacement in their JSON text ([Json#write]).
sealed interface Sql {
    String NUL = "\u0000";
    String REPLACEMENT = "\uFFFD";

    static Promise<PgResultSet> query(QueryExecutor executor, String sql, Object... params) {
        return executor.completeQuery(sql,
                                      bound(params))
                       .mapError(KilnStoreError.Database.FACTORY);
    }

    /// `raw` as the store writes it: U+0000 replaced by U+FFFD.
    static String text(String raw) {
        return raw.replace(NUL, REPLACEMENT);
    }

    private static Object[] bound(Object[] params) {
        return Arrays.stream(params)
                     .map(Sql::boundParam)
                     .toArray();
    }

    private static Object boundParam(Object param) {
        return switch (param) {
            case String raw -> text(raw);
            case String[] raw -> Arrays.stream(raw).map(Sql::text).toArray(String[]::new);
            case null, default -> param;
        };
    }

    static Promise<Unit> execute(QueryExecutor executor, String sql, Object... params) {
        return query(executor, sql, params).mapToUnit();
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
        return first(resultSet).map(parser::apply)
                    .map(Sql::present)
                    .or(() -> Result.success(none()))
                    .async();
    }

    private static <T> Result<Option<T>> present(Result<T> parsed) {
        return parsed.map(Option::some);
    }

    /// Every row through `reader`, for rows that cannot fail to read.
    static <T> List<T> readAll(PgResultSet resultSet, Fn1<T, PgRow> reader) {
        return rows(resultSet).stream()
                   .map(reader::apply)
                   .toList();
    }

    /// The first row through `reader`, if there is one, for rows that cannot fail to read.
    static <T> Option<T> readFirst(PgResultSet resultSet, Fn1<T, PgRow> reader) {
        return first(resultSet).map(reader::apply);
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
