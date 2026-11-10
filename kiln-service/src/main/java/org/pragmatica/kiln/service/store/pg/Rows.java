package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.postgres.PgRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Column readers shared by the kiln-pg stores. A reader that cannot parse a stored value fails with the value's own
/// cause; each store wraps it into `KilnStoreError.CorruptRow` of its table ([#corrupt]).
sealed interface Rows {
    Fn1<KilnStoreError.NullColumn, String> NULL_COLUMN = KilnStoreError.NullColumn.FACTORY;
    Fn1<KilnStoreError.UnknownValue, String> UNKNOWN_VALUE = KilnStoreError.UnknownValue.FACTORY;

    static Fn1<Cause, Cause> corrupt(String table) {
        return cause -> KilnStoreError.CorruptRow.FACTORY.apply(table, cause);
    }

    static Option<String> text(PgRow row, String column) {
        return option(row.getString(column));
    }

    static Result<String> required(PgRow row, String column) {
        return text(row, column).toResult(NULL_COLUMN.apply(column));
    }

    static Option<Instant> instant(PgRow row, String column) {
        return option(row.getInstant(column));
    }

    static Result<Instant> parseInstant(String raw) {
        return DateTime.parseInstant(raw);
    }

    /// `raw` parsed when present; absent stays absent, and a present value that does not parse fails.
    static <T> Result<Option<T>> optional(Option<String> raw, Fn1<Result<T>, String> parser) {
        return flip(raw.map(parser::apply));
    }

    /// An absent value is a successful absence; a present one keeps its own outcome.
    static <T> Result<Option<T>> flip(Option<Result<T>> value) {
        return value.map(Rows::present)
                    .or(() -> success(none()));
    }

    private static <T> Result<Option<T>> present(Result<T> parsed) {
        return parsed.map(Option::some);
    }

    /// The constant of `type` whose name is `raw` in any case; enum columns store the name (`pr.state` and the pass kinds
    /// in lower case).
    static <E extends Enum<E>> Result<E> constant(Class<E> type, String raw) {
        return Option.from(Arrays.stream(type.getEnumConstants())
                                 .filter(value -> value.name()
                                                       .equalsIgnoreCase(raw))
                                 .findFirst()).toResult(UNKNOWN_VALUE.apply(raw));
    }

    static String lower(Enum<?> value) {
        return value.name()
                    .toLowerCase(Locale.ROOT);
    }

    /// The `host` column: the Mac's rows are `mac`, which [HostName#hostName] refuses for configured hosts.
    static Result<HostName> host(String raw) {
        return HostName.MAC.value().equals(raw)
               ? success(HostName.MAC)
               : HostName.hostName(raw);
    }

    /// A `bigint[]` bound as `$n::text[]::bigint[]` (as temper binds them).
    static String[] longs(List<Long> values) {
        return Sql.texts(values.stream().map(String::valueOf));
    }

    record unused() implements Rows {}
}
