package org.pragmatica.temper.acceptance.sizing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;


/// The Maven "Reactor Summary" of a log (m1-design §8.3: per-module wall time, compile time included), pure. A
/// summary line is `[INFO] <name> ....... SUCCESS [  9.120 s]` (a name too long for the dots has none: `[INFO] <name>
/// SUCCESS [...]`, as on bigboy's S1 build); the bracket is `S.mmm s`, `MM:SS min` or `HH:MM h` (Maven 3.9). Lines with no bracket (SKIPPED) have no time. Modules are keyed by their Maven display name, as the
/// summary prints them; the LAST summary of the log wins (a log tail can hold the end of an earlier one).
public sealed interface ReactorSummary {
    String HEADER_TEXT = "[INFO] Reactor Summary";

    Pattern LINE = Pattern.compile("^\\[INFO\\] (.+?)(?: \\.+)? +(SUCCESS|FAILURE) \\[\\s*([0-9:.]+) (s|min|h)\\]\\s*$");

    Pattern MINUTES = Pattern.compile("^(\\d+):(\\d+)$");

    /// Module display name → wall seconds, in summary order.
    static Map<String, Double> parse(String log) {
        return entries(afterLastHeader(log)).collect(Collectors.toMap(Map.Entry::getKey,
                                                                      Map.Entry::getValue,
                                                                      (_, last) -> last,
                                                                      LinkedHashMap::new));
    }

    /// The log from its last Reactor Summary header on (all of it when there is none).
    private static String afterLastHeader(String log) {
        var header = log.lastIndexOf(HEADER_TEXT);

        return header < 0
               ? log
               : log.substring(header);
    }

    private static Stream<Map.Entry<String, Double>> entries(String summary) {
        return summary.lines()
                      .map(LINE::matcher)
                      .filter(Matcher::matches)
                      .flatMap(ReactorSummary::entry);
    }

    private static Stream<Map.Entry<String, Double>> entry(Matcher line) {
        var name = line.group(1);

        return seconds(line.group(3),
                       line.group(4)).map(value -> Map.entry(name, value))
                      .stream();
    }

    /// `9.120` `s` → 9.12; `01:31` `min` → 91; `01:02` `h` → 3720.
    static Option<Double> seconds(String value, String unit) {
        return switch (unit) {
            case "s" -> decimal(value);
            case "min" -> pair(value, 60);
            case "h" -> pair(value, 3600);
            default -> none();
        };
    }

    private static Option<Double> decimal(String value) {
        return Number.parseDouble(value).option();
    }

    private static Option<Double> pair(String value, int scale) {
        var matcher = MINUTES.matcher(value);

        return matcher.matches()
               ? Result.all(Number.parseDouble(matcher.group(1)),
                            Number.parseDouble(matcher.group(2)))
                       .map((major, minor) -> major * scale + minor * scale / 60)
                       .option()
               : none();
    }

    record unused() implements ReactorSummary {}
}
