package org.pragmatica.temper.domain.report;

/// One `<testcase>` as read from a report file.
public record ParsedTestcase(String className, String name, Outcome outcome, double timeSeconds) {
    public static ParsedTestcase parsedTestcase(String className, String name, Outcome outcome, double timeSeconds) {
        return new ParsedTestcase(className, name, outcome, timeSeconds);
    }
}
