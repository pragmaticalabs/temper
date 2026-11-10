package org.pragmatica.temper.merge.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// The failing test names in a GitHub Actions job log of a Maven build (surefire or failsafe). Each log line starts
/// with GitHub's timestamp, which is ignored.
///
/// The per-method lines are the source: `[ERROR] <class>.<method> -- Time elapsed: … <<< FAILURE!` (or `ERROR!`),
/// giving `<class>.<method>`. When a log has none (an older surefire), the `Results:` summary is used instead: the
/// `[ERROR]   <Class>.<method>:<line> …` lines below `[ERROR] Failures:` or `[ERROR] Errors:`. Order of first
/// appearance, no duplicates. A log with neither (a gate job that only reports another job's result) yields nothing.
public interface FailedTests {
    Pattern PER_METHOD = Pattern.compile("\\[ERROR\\] (\\S+) -- Time elapsed: .*<<< (?:FAILURE|ERROR)!");
    Pattern SUMMARY_HEAD = Pattern.compile("\\[ERROR\\] (?:Failures|Errors): *$");
    Pattern SUMMARY_LINE = Pattern.compile("\\[ERROR\\]   (\\S+?)(?::\\d+)?(?: .*)?$");

    static List<String> in(String log) {
        var lines = log.lines().toList();
        var perMethod = matched(lines, PER_METHOD);

        return perMethod.isEmpty()
               ? summarised(lines)
               : perMethod;
    }

    private static List<String> matched(List<String> lines, Pattern pattern) {
        var found = new LinkedHashSet<String>();

        lines.forEach(line -> added(found, pattern.matcher(line)));

        return List.copyOf(found);
    }

    private static void added(LinkedHashSet<String> found, Matcher matcher) {
        if (matcher.find()) {
            found.add(matcher.group(1));
        }
    }

    private static List<String> summarised(List<String> lines) {
        var found = new LinkedHashSet<String>();
        var inSummary = false;

        for (var line : lines) {
            if (SUMMARY_HEAD.matcher(line).find()) {
                inSummary = true;
                continue;
            }

            var entry = SUMMARY_LINE.matcher(line);

            if (inSummary && entry.find()) {
                found.add(entry.group(1));
            } else {
                inSummary = false;
            }
        }

        return List.copyOf(found);
    }
}
