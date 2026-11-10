package org.pragmatica.temper.domain.rerun;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.UnitTimeout;

import static org.pragmatica.lang.Option.option;


/// The rerun unit's wall-clock bound (maintainer ruling on the rerun bound, replacing the fixed RERUN 30 min):
///
/// `bound = max(30 min, Σ class durations × 1.5 + install allowance + 5 min slack)`, capped at the unit-timeout maximum
/// (6 h), and rounded UP to the whole minute [G].
///
/// A target class's duration is, first, its history: the slowest of its last runs that temper stored (the store's
/// read); then its weight in pragmatica's `tools/ember-shard-weights.txt` at the job's SHA (by the simple name of its
/// outermost class [G]: the file lists top-level classes, and a `@Nested` class runs under its outer one); then
/// [#DEFAULT_CLASS] (10 min [G]), which the derivation names. Pure; the controller gathers the inputs at submit.
public sealed interface RerunBounds {
    Duration FLOOR = Duration.ofMinutes(30);
    Duration SLACK = Duration.ofMinutes(5);
    Duration DEFAULT_CLASS = Duration.ofMinutes(10);
    /// The factor over the summed class durations.
    BigDecimal FACTOR = new BigDecimal("1.5");
    /// The weights file in the rerun's tree.
    String WEIGHTS_PATH = "tools/ember-shard-weights.txt";
    /// `<SimpleClassName> <seconds>`; `#` lines are comments. At most 9 integer digits: a larger weight is no weight
    /// (malformed), never an overflow.
    Pattern WEIGHT_LINE = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)\\s+(\\d{1,9}(?:\\.\\d{1,3})?)\\s*$");

    /// One target's duration and where it came from.
    record ClassTime(String className, Duration duration, Source source) {
        public static ClassTime classTime(String className, Duration duration, Source source) {
            return new ClassTime(className, duration, source);
        }
    }

    enum Source {
        HISTORY,
        WEIGHTS,
        DEFAULT
    }

    /// The bound of `classes`, from their stored history (by class), the weights (by simple name) and the install
    /// allowance.
    static Recipe.Rerun.Bound bound(List<String> classes,
                                    Map<String, Duration> history,
                                    Map<String, Duration> weights,
                                    Duration installAllowance) {
        return bound(classes.stream().map(className -> classTime(className, history, weights)).toList(),
                     installAllowance);
    }

    /// History first, then the weight of the outermost class's simple name, then the default.
    static ClassTime classTime(String className, Map<String, Duration> history, Map<String, Duration> weights) {
        return option(history.get(className)).map(duration -> ClassTime.classTime(className, duration, Source.HISTORY))
                     .orElse(() -> fromWeights(className, weights))
                     .or(() -> ClassTime.classTime(className, DEFAULT_CLASS, Source.DEFAULT));
    }

    private static Option<ClassTime> fromWeights(String className, Map<String, Duration> weights) {
        return option(weights.get(simpleName(className))).map(duration -> ClassTime.classTime(className,
                                                                                              duration,
                                                                                              Source.WEIGHTS));
    }

    static Recipe.Rerun.Bound bound(List<ClassTime> times, Duration installAllowance) {
        var sum = times.stream()
                       .map(ClassTime::duration)
                       .map(RerunBounds::capped)
                       .reduce(Duration.ZERO, Duration::plus);
        var derived = scaled(sum).plus(installAllowance).plus(SLACK);

        return Recipe.Rerun.Bound.bound(rounded(derived), derivation(sum, derived, installAllowance) + defaults(times));
    }

    /// One class longer than the cap caps the bound anyway; capping it first keeps the sum far from overflow, whatever a
    /// stored row or a weight says.
    private static Duration capped(Duration duration) {
        return min(duration, UnitTimeout.MAX);
    }

    /// `Σ × 1.5` to the millisecond.
    private static Duration scaled(Duration sum) {
        return Duration.ofMillis(FACTOR.multiply(BigDecimal.valueOf(sum.toMillis()))
                                       .setScale(0, RoundingMode.CEILING)
                                       .longValueExact());
    }

    /// The floor, the cap, then up to the whole minute.
    private static Duration rounded(Duration derived) {
        var clamped = derived.compareTo(FLOOR) < 0
                      ? FLOOR
                      : min(derived, UnitTimeout.MAX);

        return Duration.ofMinutes(ceilMinutes(clamped));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0
               ? a
               : b;
    }

    private static long ceilMinutes(Duration duration) {
        return (duration.toMillis() + 59_999) / 60_000;
    }

    private static String derivation(Duration sum, Duration derived, Duration installAllowance) {
        return derived.compareTo(FLOOR) < 0
               ? "floor; derived " + tenths(derived)
               : aboveFloor(sum, derived, installAllowance);
    }

    private static String aboveFloor(Duration sum, Duration derived, Duration installAllowance) {
        return derived.compareTo(UnitTimeout.MAX) > 0
               ? "cap; derived " + tenths(derived)
               : "Σ " + tenths(sum) + " ×1.5 + install " + minutes(installAllowance) + " + slack " + minutes(SLACK);
    }

    /// `; default 10m: a.B,c.D` for the classes neither history nor weights knew; nothing otherwise.
    private static String defaults(List<ClassTime> times) {
        var unknown = times.stream().filter(time -> time.source() == Source.DEFAULT).map(ClassTime::className).toList();

        return unknown.isEmpty()
               ? ""
               : "; default " + minutes(DEFAULT_CLASS) + ": " + String.join(",", unknown);
    }

    /// Minutes to one decimal, half up: `25.1m`.
    static String tenths(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis())
                         .divide(BigDecimal.valueOf(60_000),
                                 1,
                                 RoundingMode.HALF_UP)
                         .toPlainString()
             + "m";
    }

    /// Whole minutes when whole (`5m`), else one decimal (`1.5m`).
    static String minutes(Duration duration) {
        return duration.toMillis() % 60_000 == 0
               ? duration.toMinutes() + "m"
               : tenths(duration);
    }

    /// The simple name of a class's outermost class: `org.x.Outer$Inner` → `Outer`.
    static String simpleName(String className) {
        var outermost = className.replaceFirst("\\$.*", "");

        return outermost.substring(outermost.lastIndexOf('.') + 1);
    }

    /// The weights file's lines `<SimpleClassName> <seconds>`; comments, blank and malformed lines are skipped [G].
    static Map<String, Duration> weights(String text) {
        return lines(text).filter(Matcher::matches)
                    .flatMap(matcher -> weight(matcher).stream())
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                          Map.Entry::getValue,
                                                          (first, _) -> first));
    }

    private static Stream<Matcher> lines(String text) {
        return Arrays.stream(text.split("\n"))
                     .map(String::strip)
                     .map(WEIGHT_LINE::matcher);
    }

    private static Option<Map.Entry<String, Duration>> weight(Matcher line) {
        return Number.parseBigDecimal(line.group(2))
                     .option()
                     .map(seconds -> Map.entry(line.group(1),
                                               seconds(seconds)));
    }

    private static Duration seconds(BigDecimal seconds) {
        return Duration.ofMillis(seconds.multiply(BigDecimal.valueOf(1000))
                                        .setScale(0, RoundingMode.CEILING)
                                        .longValueExact());
    }

    record unused() implements RerunBounds {}
}
