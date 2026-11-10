package org.pragmatica.temper.worker.facts;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;
import static org.pragmatica.temper.domain.DomainError.unparseable;


/// Pure parsers for the Linux files the facts come from (m0-design §7.7). Each takes the file's content.
public sealed interface ProcParsers {
    Pattern LOADAVG = Pattern.compile("^\\s*(\\S+)\\s+(\\S+)");
    Pattern MEM_AVAILABLE = Pattern.compile("(?m)^MemAvailable:\\s+(\\d+)\\s*kB\\s*$");
    Pattern CPU_STAT_LINE = Pattern.compile("^\\s*(\\S+)\\s+(-?\\d+)\\s*$");
    /// `starttime` in proc(5): field 22 of `/proc/<pid>/stat`, counted from 1.
    int STAT_START_TIME_FIELD = 22;

    /// `/proc/loadavg`: `0.52 0.58 0.59 1/389 12345`.
    static Result<LoadAverage> loadAverage(String text) {
        return matched(LOADAVG, text).toResult(invalid("loadavg").apply(text))
                      .flatMap(ProcParsers::loadAverageOf);
    }

    /// `MemAvailable` of `/proc/meminfo`, in kB.
    static Result<Long> memAvailableKb(String meminfo) {
        return matched(MEM_AVAILABLE, meminfo).toResult(invalid("meminfo").apply("no MemAvailable line"))
                      .flatMap(ProcParsers::firstLong);
    }

    /// `/proc/<pid>/cmdline`: NUL-separated argv, joined with spaces.
    static String cmdline(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8).replace('\0', ' ')
                                                      .trim();
    }

    /// Field 22 of `/proc/<pid>/stat`, the process start time in clock ticks since boot. The fields are counted
    /// after the LAST `)`, because field 2 (the command name in parentheses) may itself hold spaces and `)`.
    static Option<Long> startTicks(String stat) {
        var close = stat.lastIndexOf(')');

        return close < 0
               ? none()
               : fieldAfterCommand(stat.substring(close + 1).trim().split("\\s+"),
                                   STAT_START_TIME_FIELD);
    }

    private static Option<Long> fieldAfterCommand(String[] fields, int field) {
        var index = field - 3;

        return index < fields.length
               ? Number.parseLong(fields[index]).option()
               : none();
    }

    /// cgroup v2 `cpu.stat`: `key value` lines; lines that do not parse are skipped.
    static Map<String, Long> cpuStat(String text) {
        return text.lines()
                   .map(ProcParsers::cpuEntry)
                   .flatMap(Option::stream)
                   .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                         Map.Entry::getValue,
                                                         (first, _) -> first));
    }

    /// cgroup v2 `memory.peak`: one integer of bytes.
    static Option<Long> memoryPeak(String text) {
        return Number.parseLong(text.trim()).option();
    }

    private static Option<Matcher> matched(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);

        return matcher.find()
               ? some(matcher)
               : none();
    }

    private static Result<LoadAverage> loadAverageOf(Matcher fields) {
        return Result.all(decimal("load1",
                                  fields.group(1)),
                          decimal("load5",
                                  fields.group(2)))
                     .map(LoadAverage::loadAverage);
    }

    private static Result<Long> firstLong(Matcher fields) {
        return Number.parseLong(fields.group(1)).mapError(unparseable("MemAvailable", fields.group(1)));
    }

    private static Option<Map.Entry<String, Long>> cpuEntry(String line) {
        return matched(CPU_STAT_LINE, line).flatMap(ProcParsers::entryOf);
    }

    private static Option<Map.Entry<String, Long>> entryOf(Matcher fields) {
        var key = fields.group(1);

        return Number.parseLong(fields.group(2))
                     .option()
                     .map(value -> Map.entry(key, value));
    }

    private static Result<Double> decimal(String field, String raw) {
        return Number.parseDouble(raw).mapError(unparseable(field, raw));
    }

    /// The 1- and 5-minute load averages.
    record LoadAverage(double load1, double load5) {
        public static LoadAverage loadAverage(double load1, double load5) {
            return new LoadAverage(load1, load5);
        }
    }

    record unused() implements ProcParsers {}
}
