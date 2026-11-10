package org.pragmatica.kiln.service.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Typed reads of `kiln.toml` values: a key either parses or names itself in a [ConfigError]. Based on temper's
/// controller `TomlValues`, with one change: every value is read raw and type-checked, for a section as for a
/// `[[hosts]]` table. The document getters convert silently (`getString` turns `port = 1` into `"1"`, `getInt` turns
/// `"7171"` or an out-of-range number into none, which a default would then hide), and kiln refuses at start instead.
sealed interface TomlValues {
    Pattern NOT_OPTION = Pattern.compile("^[^-].*$", Pattern.DOTALL);
    Pattern ABSOLUTE_PATH = Pattern.compile("^(?:/|~/)[^\\p{Cc}\\u2028\\u2029]*$");

    /// A section's raw values; an absent section has none.
    static Map<String, Object> section(TomlDocument doc, String section) {
        return option(doc.sections().get(section)).or(Map.of());
    }

    /// A required, non-blank string.
    static Result<String> string(Map<String, Object> table, String section, String key) {
        return option(table.get(key)).toResult(ConfigError.Missing.FACTORY.apply(section, key))
                     .flatMap(value -> text(section, key, value))
                     .flatMap(value -> nonBlank(section, key, value));
    }

    /// An optional string: absent or blank is none; a value that is not a string is refused.
    static Result<Option<String>> optionalString(Map<String, Object> table, String section, String key) {
        return optional(option(table.get(key)), value -> text(section, key, value)).map(TomlValues::nonBlankOnly);
    }

    /// An optional string with a default for absent or blank.
    static Result<String> string(Map<String, Object> table, String section, String key, String fallback) {
        return optionalString(table, section, key).map(value -> value.or(fallback));
    }

    /// An integer: absent takes `fallback` (or is missing without one); a value that is not a TOML integer, or does not
    /// fit an `int`, is refused.
    static Result<Integer> integer(Map<String, Object> table, String section, String key, Option<Integer> fallback) {
        return option(table.get(key)).map(value -> whole(section, key, value))
                     .or(() -> fallback.toResult(ConfigError.Missing.FACTORY.apply(section, key)));
    }

    /// A required whole number that may exceed `int` (a GitHub installation id).
    static Result<Long> longInteger(Map<String, Object> table, String section, String key) {
        return option(table.get(key)).toResult(ConfigError.Missing.FACTORY.apply(section, key))
                     .flatMap(value -> value instanceof Long number
                                       ? Result.success(number)
                                       : ConfigError.Invalid.FACTORY.apply(section,
                                                                           key,
                                                                           cause("must be an integer")).<Long> result());
    }

    /// A required list of strings; anything else is refused.
    static Result<List<String>> stringList(Map<String, Object> table, String section, String key) {
        return option(table.get(key)).toResult(ConfigError.Missing.FACTORY.apply(section, key))
                     .flatMap(value -> strings(section, key, value));
    }

    static Result<Path> path(Map<String, Object> table, String section, String key, Path home) {
        return string(table, section, key).flatMap(raw -> absolute(section, key, raw, home));
    }

    static Result<Option<Path>> optionalPath(Map<String, Object> table, String section, String key, Path home) {
        return optionalString(table, section, key).flatMap(raw -> optional(raw,
                                                                           text -> absolute(section, key, text, home)));
    }

    static Result<Path> path(Map<String, Object> table, String section, String key, String fallback, Path home) {
        return string(table, section, key, fallback).flatMap(raw -> absolute(section, key, raw, home));
    }

    /// A path is `/abs...` or `~/...`, with no control character and no empty, `.` or `..` component. A relative path is
    /// refused (kiln runs from a unit, whose working directory is nobody's choice), and so are `~` alone and `~user/...`:
    /// `~/` is the only expansion.
    static Result<Path> absolute(String section, String key, String raw, Path home) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             ABSOLUTE_PATH,
                             invalid(section, key, raw))
                     .flatMap(shaped -> Verify.ensure(shaped,
                                                      TomlValues::canonical,
                                                      invalid(section, key, raw)))
                     .map(text -> expand(text, home));
    }

    /// After the leading `/` or `~/`, no component is empty (`//`, a trailing `/`, `~/` alone), `.` or `..`, as for a
    /// checkout path: a path names its file directly, never through an alias.
    private static boolean canonical(String path) {
        return Arrays.stream(path.replaceFirst("^~?/", "").split("/", -1)).allMatch(TomlValues::component);
    }

    private static boolean component(String component) {
        return ! component.isEmpty()
               && !component.equals(".")
               && !component.equals("..");
    }

    /// A key kiln refuses whatever its value, with the reason.
    static Result<Map<String, Object>> absent(Map<String, Object> table, String section, String key, String reason) {
        return option(table.get(key)).map(_ -> ConfigError.Invalid.FACTORY.apply(section,
                                                                                 key,
                                                                                 cause(reason)).<Map<String, Object>> result())
                     .or(success(table));
    }

    /// A positive integer; the section and key name it in the failure.
    static Result<Integer> positive(String section, String key, int value) {
        return Verify.ensure(value, Verify.Is::positive, invalid(section, key, value));
    }

    static Result<Integer> between(String section, String key, int value, int min, int max) {
        return Verify.ensure(value, Verify.Is::between, min, max, invalid(section, key, value));
    }

    /// A value that becomes an ssh or git argument must not start with `-`, so it can never be read as an option
    /// (`user = "-oProxyCommand=..."`). The config is operator-written; this is a cheap guard, not a trust boundary.
    static Result<String> notOption(String section, String key, String value) {
        return Verify.ensure(value, Verify.Is::matches, NOT_OPTION, invalid(section, key, value));
    }

    /// `~/x` → under `home`; an absolute path as written.
    private static Path expand(String raw, Path home) {
        return raw.startsWith("~/")
               ? home.resolve(raw.substring(2))
               : Path.of(raw);
    }

    static ConfigError invalid(String section, String key, Object value) {
        return ConfigError.Invalid.FACTORY.apply(section, key, cause("value '" + value + "'"));
    }

    /// An optional raw value: absent stays absent, present must parse.
    static <T, R> Result<Option<T>> optional(Option<R> raw, Fn1<Result<T>, R> parser) {
        return raw.map(parser)
                  .map(parsed -> parsed.map(Option::some))
                  .or(success(none()));
    }

    private static Result<String> text(String section, String key, Object value) {
        return value instanceof String text
               ? success(text)
               : ConfigError.Invalid.FACTORY.apply(section, key, cause("must be a string")).result();
    }

    private static Result<Integer> whole(String section, String key, Object value) {
        return value instanceof Long number
               ? Verify.ensure(number,
                               Verify.Is::between,
                               (long) Integer.MIN_VALUE,
                               (long) Integer.MAX_VALUE,
                               invalid(section, key, value)).map(Math::toIntExact)
               : ConfigError.Invalid.FACTORY.apply(section, key, cause("must be an integer")).result();
    }

    private static Result<List<String>> strings(String section, String key, Object value) {
        return value instanceof List<?> list && list.stream()
                                                    .allMatch(String.class::isInstance)
               ? success(list.stream().map(String.class::cast).toList())
               : ConfigError.Invalid.FACTORY.apply(section, key, cause("must be a list of strings")).result();
    }

    private static Option<String> nonBlankOnly(Option<String> value) {
        return value.filter(text -> !text.isBlank());
    }

    private static Result<String> nonBlank(String section, String key, String value) {
        return Verify.ensure(value, Verify.Is::notBlank, invalid(section, key, value));
    }

    record unused() implements TomlValues {}
}
