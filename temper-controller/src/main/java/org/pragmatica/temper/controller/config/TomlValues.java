package org.pragmatica.temper.controller.config;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.Values;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Typed reads of `controller.toml` values: a key either parses or names itself in a [ConfigError]. Values of a
/// `[[hosts]]` table come as a raw map (`TomlDocument.getTableArray`), the others through the document getters.
sealed interface TomlValues {
    Pattern NOT_OPTION = Pattern.compile("^[^-].*$", Pattern.DOTALL);
    Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    static Result<String> string(TomlDocument doc, String section, String key) {
        return doc.getString(section, key)
                  .toResult(ConfigError.Missing.FACTORY.apply(section, key))
                  .flatMap(value -> nonBlank(section, key, value));
    }

    static int integer(TomlDocument doc, String section, String key, int fallback) {
        return doc.getInt(section, key)
                  .or(fallback);
    }

    static Result<Integer> integer(TomlDocument doc, String section, String key) {
        return doc.getInt(section, key)
                  .toResult(ConfigError.Missing.FACTORY.apply(section, key));
    }

    /// An optional string; blank counts as absent (`mavenOpts = ""`).
    static Option<String> optionalString(TomlDocument doc, String section, String key) {
        return doc.getString(section, key)
                  .filter(value -> !value.isBlank());
    }

    /// An optional string that must be a string when present (`readTokenFile = 1` is refused, not read as `"1"`);
    /// blank counts as absent.
    static Result<Option<String>> optionalText(TomlDocument doc, String section, String key) {
        return Values.optional(rawValue(doc, section, key), value -> text(section, key, value)).map(TomlValues::nonBlankOnly);
    }

    /// The value as parsed, of whatever type (`getString` would turn a number into its text).
    private static Option<Object> rawValue(TomlDocument doc, String section, String key) {
        return option(doc.sections().get(section)).flatMap(values -> option(values.get(key)));
    }

    private static Result<String> text(String section, String key, Object value) {
        return value instanceof String text
               ? success(text)
               : ConfigError.Invalid.FACTORY.apply(section, key, cause("must be a string")).result();
    }

    private static Option<String> nonBlankOnly(Option<String> value) {
        return value.filter(text -> !text.isBlank());
    }

    static Result<List<String>> stringList(TomlDocument doc, String section, String key) {
        return doc.getStringList(section, key)
                  .toResult(ConfigError.Missing.FACTORY.apply(section, key));
    }

    static Result<Path> path(TomlDocument doc, String section, String key, Path home) {
        return string(doc, section, key).map(raw -> expand(raw, home));
    }

    static Result<String> string(Map<String, Object> table, String section, String key) {
        return option(table.get(key)).filter(String.class::isInstance)
                     .map(String.class::cast)
                     .toResult(ConfigError.Missing.FACTORY.apply(section, key))
                     .flatMap(value -> nonBlank(section, key, value));
    }

    static Result<Path> path(Map<String, Object> table, String section, String key, Path home) {
        return string(table, section, key).map(raw -> expand(raw, home));
    }

    static Result<Integer> integer(Map<String, Object> table, String section, String key, Option<Integer> fallback) {
        return option(table.get(key)).filter(Number.class::isInstance)
                     .map(value -> ((Number) value).intValue())
                     .orElse(() -> fallback)
                     .toResult(ConfigError.Missing.FACTORY.apply(section, key));
    }

    /// An optional ISO-8601 instant of a `[[hosts]]` table (`retireAt = "2026-11-13T00:00:00Z"`): absent is none; a value
    /// that is not a string, or does not parse, is refused.
    static Result<Option<Instant>> optionalInstant(Map<String, Object> table, String section, String key) {
        return Values.optional(option(table.get(key)), value -> instant(section, key, value));
    }

    private static Result<Instant> instant(String section, String key, Object value) {
        return text(section, key, value).flatMap(raw -> parsedInstant(section, key, raw));
    }

    private static Result<Instant> parsedInstant(String section, String key, String raw) {
        return DateTime.parseInstant(raw).mapError(cause -> ConfigError.Invalid.FACTORY.apply(section, key, cause));
    }

    /// An optional boolean of a `[[hosts]]` table, false when absent; a value that is not a boolean is refused rather than
    /// read as false (`sharedCpu = "true"` read as false would grant dedicated-CPU jobs to a shared host).
    static Result<Boolean> strictFlag(Map<String, Object> table, String section, String key) {
        return Values.optional(option(table.get(key)),
                               value -> bool(section, key, value))
                     .map(flag -> flag.or(false));
    }

    private static Result<Boolean> bool(String section, String key, Object value) {
        return value instanceof Boolean flag
               ? success(flag)
               : ConfigError.Invalid.FACTORY.apply(section, key, cause("must be true or false")).result();
    }

    static boolean flag(Map<String, Object> table, String key) {
        return option(table.get(key)).filter(Boolean.class::isInstance)
                     .map(Boolean.class::cast)
                     .or(false);
    }

    /// An optional whole number of a `[[hosts]]` table from 0 to `max`, `fallback` when absent. A value that is not a
    /// whole number (`2.5`, `"6"`) or is out of range is refused with the reason, never read as another number
    /// (`integer` reads `2.5` as 2 and `"6"` as absent).
    static Result<Integer> optionalWhole(Map<String, Object> table, String section, String key, int fallback, int max) {
        return Values.optional(option(table.get(key)),
                               value -> whole(section, key, value, max))
                     .map(found -> found.or(fallback));
    }

    /// An optional TOML integer from `min` to `max`; absent → `fallback`. A string, a float, a boolean or a number out of
    /// range is refused with one reason, `must be a whole number from <min> to <max>`, naming the section and the key.
    static Result<Integer> optionalWholeBetween(Map<String, Object> table,
                                                String section,
                                                String key,
                                                int fallback,
                                                int min,
                                                int max) {
        return Values.optional(option(table.get(key)),
                               value -> wholeBetween(section, key, value, min, max))
                     .map(found -> found.or(fallback));
    }

    private static Result<Integer> wholeBetween(String section, String key, Object value, int min, int max) {
        return (value instanceof Long || value instanceof Integer) && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max
               ? Result.success(((Number) value).intValue())
               : ConfigError.Invalid.FACTORY.apply(section,
                                                   key,
                                                   cause("must be a whole number from " + min
                                                        + " to " + max
                                                        + ", was " + shown(value))).result();
    }

    private static Result<Integer> whole(String section, String key, Object value, int max) {
        return value instanceof Long || value instanceof Integer
               ? inRange(section, key, ((Number) value).longValue(), max)
               : ConfigError.Invalid.FACTORY.apply(section, key, cause("must be a whole number, was " + shown(value))).result();
    }

    /// A string value quoted, so `"6"` reads as the string it is; anything else as written.
    private static String shown(Object value) {
        return value instanceof String text
               ? "\"" + text + "\""
               : String.valueOf(value);
    }

    private static Result<Integer> inRange(String section, String key, long value, int max) {
        return Verify.ensure(value,
                             Verify.Is::between,
                             0L,
                             (long) max,
                             ConfigError.Invalid.FACTORY.apply(section,
                                                               key,
                                                               cause("must be from 0 to " + max + ", was " + value)))
                     .map(Math::toIntExact);
    }

    /// `table` as it is, after a WARN for each key not in `known`. A misspelt or misplaced key is otherwise ignored
    /// without a word, which for an optional key means its default silently applies (`memFloorGib = 6` leaves the
    /// memory floor off). Never refuses, so an existing config with an extra key keeps loading.
    static Map<String, Object> warnedUnknownKeys(Map<String, Object> table, String section, Set<String> known) {
        unknownKeys(table, known).forEach(key -> LOG.warn("[{}] {}: unknown key '{}' ignored (misspelt or misplaced?)",
                                                          section,
                                                          option(table.get("name")).or("?"),
                                                          key));

        return table;
    }

    static List<String> unknownKeys(Map<String, Object> table, Set<String> known) {
        return table.keySet()
                    .stream()
                    .filter(key -> !known.contains(key))
                    .sorted()
                    .toList();
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

    /// `~` or `~/x` → under `home`; anything else as written.
    static Path expand(String raw, Path home) {
        return raw.equals("~") || raw.startsWith("~/")
               ? home.resolve(raw.substring(1).replaceFirst("^/", ""))
               : Path.of(raw);
    }

    private static Result<String> nonBlank(String section, String key, String value) {
        return Verify.ensure(value, Verify.Is::notBlank, invalid(section, key, value));
    }

    private static ConfigError invalid(String section, String key, Object value) {
        return ConfigError.Invalid.FACTORY.apply(section, key, cause("value '" + value + "'"));
    }

    record unused() implements TomlValues {}
}
