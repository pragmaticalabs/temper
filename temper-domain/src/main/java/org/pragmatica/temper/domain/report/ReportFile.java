package org.pragmatica.temper.domain.report;

import java.util.List;


/// One parsed report file. `testsAttr` is the sum of `tests=` over every `<testsuite>`; it is stored for
/// comparison only and never drives a verdict (spec §5.1), because `@Nested` suites undercount it.
public record ReportFile(String fileName, long testsAttr, List<ParsedTestcase> testcases) {
    public ReportFile {
        testcases = List.copyOf(testcases);
    }

    public static ReportFile reportFile(String fileName, long testsAttr, List<ParsedTestcase> testcases) {
        return new ReportFile(fileName, testsAttr, testcases);
    }
}
