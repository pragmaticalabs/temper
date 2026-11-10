package org.pragmatica.temper.acceptance.trust;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Result.success;


/// Parses what `bb-report <checkout>` printed, with its exit code (m1-design §8.1; the format is the script's own
/// `echo` lines, read from the bigboy copy archived with the captures in this module's test resources):
///
/// | Line | Meaning |
/// |---|---|
/// | `rc=<n> wall=<s>s` | the build's exit code (required) |
/// | `<testcase> elements: N  failures: F  errors: E  skipped: S  (testsuite tests= attribute sum: A, …)` | counts; absent when no report XML is newer than the marker, which counts as zero |
/// | `FAILED: <class>` | one report file with a failure or an error |
/// | `WARNING: <n> dependency-resolution failure line(s) …` | modules were never built or tested |
/// | `EMPTY RUN (0 testcases): … (args: <args>) but no <testcase> was reported` | exit 3 |
///
/// Exit 0 is a report, 3 an EMPTY RUN (which must carry its line), 1 "no rc yet" ([TrustError.General#BB_REPORT_NOT_FINISHED]);
/// any other exit is [TrustError.UnknownBbReportExit].
public sealed interface BbReportParser {
    int EXIT_REPORT = 0;
    int EXIT_NOT_FINISHED = 1;
    int EXIT_EMPTY = 3;
    Pattern RC = Pattern.compile("^rc=(\\d+) wall=-?\\d+s$");

    Pattern COUNTS = Pattern.compile("^<testcase> elements: (\\d+)\\s+failures: (\\d+)\\s+errors: (\\d+)\\s+skipped: (\\d+)\\s+\\(testsuite tests= attribute sum: (\\d+),.*$");

    Pattern FAILED = Pattern.compile("^FAILED: (\\S+)$");

    Pattern EMPTY = Pattern.compile("^EMPTY RUN \\(0 testcases\\): .*\\(args: (.*)\\) but no <testcase> was reported.*$");

    String DEPENDENCY_WARNING = "WARNING: ";
    String DEPENDENCY_WARNING_TEXT = "dependency-resolution failure line(s)";

    static Result<BbReport> parse(String stdout, int exitCode) {
        var lines = stdout.lines().map(String::strip).toList();

        return reportExit(exitCode).flatMap(exit -> report(lines, exit));
    }

    private static Result<Integer> reportExit(int exitCode) {
        return switch (exitCode) {
            case EXIT_REPORT, EXIT_EMPTY -> success(exitCode);
            case EXIT_NOT_FINISHED -> TrustError.General.BB_REPORT_NOT_FINISHED.result();
            default -> TrustError.UnknownBbReportExit.FACTORY.apply(exitCode).result();
        };
    }

    private static Result<BbReport> report(List<String> lines, int exit) {
        return Result.all(rc(lines), counts(lines), emptyArgs(lines, exit)).map((rc, counts, args) -> BbReport.bbReport(rc,
                                                                                                                        counts.testcases(),
                                                                                                                        counts.failures(),
                                                                                                                        counts.errors(),
                                                                                                                        counts.skipped(),
                                                                                                                        counts.testsAttr(),
                                                                                                                        failedClasses(lines),
                                                                                                                        exit == EXIT_EMPTY,
                                                                                                                        dependencyWarning(lines),
                                                                                                                        args));
    }

    private static Result<Integer> rc(List<String> lines) {
        return firstMatch(lines, RC).toResult(TrustError.MalformedBbReport.FACTORY.apply("no rc= line"))
                         .flatMap(matcher -> Number.parseInt(matcher.group(1)));
    }

    /// The counts line, or zero counts when bb-report found no report XML newer than the marker.
    private static Result<Counts> counts(List<String> lines) {
        return firstMatch(lines, COUNTS).map(BbReportParser::countsOf)
                         .or(() -> success(Counts.ZERO));
    }

    private static Result<Counts> countsOf(Matcher matcher) {
        return Result.all(Number.parseLong(matcher.group(1)),
                          Number.parseLong(matcher.group(2)),
                          Number.parseLong(matcher.group(3)),
                          Number.parseLong(matcher.group(4)),
                          Number.parseLong(matcher.group(5)))
                     .map(Counts::counts);
    }

    /// The args words of an EMPTY RUN; an exit 3 without its line is malformed, and so is the line without exit 3.
    private static Result<Set<String>> emptyArgs(List<String> lines, int exit) {
        var line = firstMatch(lines, EMPTY);

        return line.isPresent() == (exit == EXIT_EMPTY)
               ? success(line.map(matcher -> words(matcher.group(1))).or(Set.of()))
               : TrustError.MalformedBbReport.FACTORY.apply("EMPTY RUN line and exit " + exit + " disagree").result();
    }

    private static Set<String> words(String args) {
        return Arrays.stream(args.split("\\s+"))
                     .filter(word -> !word.isEmpty())
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> failedClasses(List<String> lines) {
        return lines.stream()
                    .map(FAILED::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean dependencyWarning(List<String> lines) {
        return lines.stream()
                    .anyMatch(line -> line.startsWith(DEPENDENCY_WARNING) && line.contains(DEPENDENCY_WARNING_TEXT));
    }

    private static Option<Matcher> firstMatch(List<String> lines, Pattern pattern) {
        return Option.from(lines.stream().map(pattern::matcher).filter(Matcher::matches).findFirst());
    }

    /// The numbers of the counts line.
    record Counts(long testcases, long failures, long errors, long skipped, long testsAttr) {
        static final Counts ZERO = counts(0, 0, 0, 0, 0);

        static Counts counts(long testcases, long failures, long errors, long skipped, long testsAttr) {
            return new Counts(testcases, failures, errors, skipped, testsAttr);
        }
    }

    record unused() implements BbReportParser {}
}
