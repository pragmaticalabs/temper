package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The T1 comparison of one SHA (m1-design §8.1 step 4), pure: bb-report's numbers and temper's record, rows and
/// manifest against the criterion of [ShaComparison].
///
/// - temper's count is the result record's `counts.testcases` (`<testcase>` elements), never `counts.testsAttr`.
/// - temper's failed classes are the classes of its failed or error rows, each named after its report FILE as
///   bb-report names it (`FAILED: <class>`). Which file holds a case depends on the surefire version: 3.5.4 writes a
///   `@Nested` class's cases into its outermost class's file, and a class's own `@DisplayName` replaces its
///   `classname` (ManifestRules, ReportNaming, T1 2026-10-14); 3.5.2 writes one file per `@Nested` class and files the
///   outer class's own cases under one of them (T1 run 2, pragmatica `jbct`). So neither `$` nor the classname
///   recovers the file under every version. A row is named by the file of bb's XML holding its class and name;
///   when several files hold it, each failure is still named once (by the files bb failed it in when the counts
///   agree, else as one [#AMBIGUOUS] case). A row bb's XML lacks falls back to its classname up to the first `$` (a
///   display-named class only temper ran stays named by its display name, still a disagreement).
/// - The case-level diff (diagnostic) compares `class#name outcome` occurrences of temper's rows and bb's XML, read
///   with the same `ReportParser`; it lists at most [#MAX_DIFF_LINES] lines.
public sealed interface TrustComparison {
    String FORK_CRASH = "The forked VM terminated";
    String EMPTY_VERDICT = "EMPTY";
    int MAX_DIFF_LINES = 200;
    int MAX_CRASH_LINES = 5;
    char NESTED_SEPARATOR = '$';
    String REPORT_PREFIX = "TEST-";
    String REPORT_SUFFIX = ".xml";
    /// Prefix of a failed case temper's rows cannot attribute to one of bb's report files.
    String AMBIGUOUS = "ambiguous: ";

    /// `bbCrash`: the fork-crash lines of bb's build log ([Snapshot#bbCrashEvidence]).
    static ShaComparison compare(String sha,
                                 BbReport bb,
                                 Option<String> bbCrash,
                                 TemperSide temper,
                                 List<ReportFile> bbReports) {
        var temperFailed = failedClasses(temper.rows(), bbReports);

        return ShaComparison.shaComparison(sha,
                                           bb.testcases() == temper.result().counts().testcases(),
                                           difference(bb.failedClasses(), temperFailed),
                                           difference(temperFailed, bb.failedClasses()),
                                           bb.empty() == isEmpty(temper.result()),
                                           temper.manifest().computed(),
                                           Set.copyOf(temper.manifest().missing()),
                                           Set.copyOf(temper.manifest().drift()),
                                           crashEvidence(temper.logTail()),
                                           bbCrash,
                                           caseLevelDiff(temper.rows(), testcases(bbReports)));
    }

    /// The report-file classes of temper's failed or error rows, by bb's XML.
    static Set<String> failedClasses(List<TestcaseDto> rows, List<ReportFile> bbReports) {
        var files = caseFiles(bbReports);

        return failuresByCase(rows).stream()
                             .flatMap(failed -> reportClassesOf(failed, files))
                             .collect(Collectors.toUnmodifiableSet());
    }

    /// A `<testcase>` identity: `classname` and `name`.
    record CaseKey(String className, String name) {
        static CaseKey caseKey(String className, String name) {
            return new CaseKey(className, name);
        }
    }

    /// How many of temper's rows of one case failed.
    record CaseFailures(CaseKey key, long failures) {
        static CaseFailures caseFailures(Map.Entry<CaseKey, Long> counted) {
            return new CaseFailures(counted.getKey(), counted.getValue());
        }
    }

    /// One `<testcase>` of bb's XML: the report-file class holding it, and whether bb failed it there.
    record CaseFile(String reportClass, boolean failed) {
        static CaseFile caseFile(String reportClass, boolean failed) {
            return new CaseFile(reportClass, failed);
        }
    }

    /// How many of temper's rows of each case failed.
    private static List<CaseFailures> failuresByCase(List<TestcaseDto> rows) {
        return failureCounts(rows).entrySet()
                            .stream()
                            .map(CaseFailures::caseFailures)
                            .toList();
    }

    private static Map<CaseKey, Long> failureCounts(List<TestcaseDto> rows) {
        return rows.stream()
                   .filter(TrustComparison::failure)
                   .collect(Collectors.groupingBy(row -> CaseKey.caseKey(row.className(),
                                                                         row.name()),
                                                  Collectors.counting()));
    }

    /// The report-file classes a case's failed rows belong to; its outermost class when bb's XML has none.
    private static Stream<String> reportClassesOf(CaseFailures failed, Map<CaseKey, List<CaseFile>> files) {
        return option(files.get(failed.key())).map(candidates -> attributed(failed.key(),
                                                                            failed.failures(),
                                                                            candidates))
                     .or(() -> Stream.of(outermost(failed.key().className())));
    }

    /// One file holds the case: that file. Several do (two outer classes whose `@Nested` classes share a
    /// `@DisplayName` and a method, an inherited `@Nested` class in two subclasses' files): temper's rows carry no
    /// file, so the failures are matched to the files bb failed the case in when the counts agree, a match by count,
    /// never verified per row. Otherwise the case is named [#AMBIGUOUS] with its files, one disagreement, never one
    /// failure counted against two files (verification F-B, 2026-10-14).
    private static Stream<String> attributed(CaseKey key, long failures, List<CaseFile> candidates) {
        var holding = reportClasses(candidates.stream());

        return holding.size() == 1
               ? holding.stream()
               : byFailureCount(key, failures, candidates, holding);
    }

    private static Stream<String> byFailureCount(CaseKey key,
                                                 long failures,
                                                 List<CaseFile> candidates,
                                                 Set<String> holding) {
        var failing = candidates.stream().filter(CaseFile::failed).toList();

        return failing.size() == failures
               ? reportClasses(failing.stream()).stream()
               : Stream.of(ambiguous(key, holding));
    }

    /// `ambiguous: <class>#<name> in <file>, <file>`.
    private static String ambiguous(CaseKey key, Set<String> holding) {
        return AMBIGUOUS + caseOf(key.className(), key.name()) + " in " + String.join(", ", holding);
    }

    private static Set<String> reportClasses(Stream<CaseFile> files) {
        return files.map(CaseFile::reportClass)
                    .collect(Collectors.toCollection(TreeSet::new));
    }

    /// `class#name` → each `<testcase>` of bb's XML with it, by report file.
    private static Map<CaseKey, List<CaseFile>> caseFiles(List<ReportFile> reports) {
        return reports.stream()
                      .flatMap(TrustComparison::caseFiles)
                      .collect(Collectors.groupingBy(Map.Entry::getKey,
                                                     Collectors.mapping(Map.Entry::getValue,
                                                                        Collectors.toList())));
    }

    /// Each `<testcase>` of `report` as its case → its file and outcome.
    private static Stream<Map.Entry<CaseKey, CaseFile>> caseFiles(ReportFile report) {
        var reportClass = reportClass(report.fileName());

        return report.testcases()
                     .stream()
                     .map(testcase -> Map.entry(CaseKey.caseKey(testcase.className(),
                                                                testcase.name()),
                                                CaseFile.caseFile(reportClass,
                                                                  testcase.outcome().isFailure())));
    }

    /// `TEST-a.B.xml` → `a.B`, as bb-report's `sed 's|.*/TEST-||; s|\.xml$||'` names it.
    static String reportClass(String fileName) {
        var name = fileName.startsWith(REPORT_PREFIX)
                   ? fileName.substring(REPORT_PREFIX.length())
                   : fileName;

        return name.endsWith(REPORT_SUFFIX)
               ? name.substring(0,
                                name.length() - REPORT_SUFFIX.length())
               : name;
    }

    private static String caseOf(String className, String name) {
        return className + "#" + name;
    }

    private static List<ParsedTestcase> testcases(List<ReportFile> reports) {
        return reports.stream()
                      .flatMap(report -> report.testcases()
                                               .stream())
                      .toList();
    }

    /// FER (degrade forward): a row whose outcome does not parse counts as failed, so it surfaces in the failed-set
    /// comparison instead of vanishing from it. The controller only ever writes the four wire forms.
    private static boolean failure(TestcaseDto row) {
        return Outcome.outcome(row.outcome())
                      .map(Outcome::isFailure)
                      .or(true);
    }

    /// `a.B$C$D` → `a.B`: the class whose report file holds the case under surefire 3.5.4; only the fallback for a row
    /// bb's XML lacks, which disagrees under any name.
    static String outermost(String className) {
        var nested = className.indexOf(NESTED_SEPARATOR);

        return nested < 0
               ? className
               : className.substring(0, nested);
    }

    private static Set<String> difference(Set<String> from, Set<String> minus) {
        return from.stream()
                   .filter(name -> !minus.contains(name))
                   .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isEmpty(ResultRecordDto result) {
        return EMPTY_VERDICT.equals(result.verdict());
    }

    /// The log tail's fork-crash lines (at most [#MAX_CRASH_LINES]); none when it has none.
    static Option<String> crashEvidence(String logTail) {
        return crashEvidence(logTail.lines());
    }

    /// The fork-crash lines among `log` (at most [#MAX_CRASH_LINES]); none when it has none.
    static Option<String> crashEvidence(Stream<String> log) {
        var lines = log.filter(line -> line.contains(FORK_CRASH)).limit(MAX_CRASH_LINES).toList();

        return lines.isEmpty()
               ? none()
               : some(String.join("\n", lines));
    }

    /// `only bb: <key>` / `only temper: <key>` per surplus occurrence, sorted, capped.
    static List<String> caseLevelDiff(List<TestcaseDto> temperRows, List<ParsedTestcase> bbRows) {
        var temper = occurrences(temperRows.stream().map(TrustComparison::key));
        var bb = occurrences(bbRows.stream().map(TrustComparison::key));

        return keys(temper, bb).flatMap(key -> surplus(key,
                                                       count(bb, key),
                                                       count(temper, key)))
                   .limit(MAX_DIFF_LINES)
                   .toList();
    }

    private static Stream<String> keys(Map<String, Long> temper, Map<String, Long> bb) {
        return Stream.concat(temper.keySet().stream(),
                             bb.keySet().stream())
                     .distinct()
                     .sorted();
    }

    private static long count(Map<String, Long> occurrences, String key) {
        return occurrences.getOrDefault(key, 0L);
    }

    private static String key(TestcaseDto row) {
        return key(row.className(), row.name(), row.outcome());
    }

    private static String key(ParsedTestcase row) {
        return key(row.className(),
                   row.name(),
                   row.outcome().wire());
    }

    private static Map<String, Long> occurrences(Stream<String> keys) {
        return keys.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static String key(String className, String name, String outcome) {
        return className + "#" + name + " " + outcome;
    }

    private static Stream<String> surplus(String key, long bb, long temper) {
        var line = (bb > temper
                    ? "only bb: "
                    : "only temper: ") + key;

        return LongStream.range(0,
                                Math.abs(bb - temper))
                         .mapToObj(_ -> line);
    }

    record unused() implements TrustComparison {}
}
