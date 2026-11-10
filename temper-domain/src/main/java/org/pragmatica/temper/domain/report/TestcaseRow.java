package org.pragmatica.temper.domain.report;

import org.pragmatica.temper.domain.model.UnitName;


/// A testcase attributed to its unit; the row stored in `testcases` and evaluated by the verdict.
public record TestcaseRow(UnitName unit,
                          String className,
                          String name,
                          Outcome outcome,
                          double timeSeconds,
                          boolean rerun) {
    public static TestcaseRow testcaseRow(UnitName unit,
                                          String className,
                                          String name,
                                          Outcome outcome,
                                          double timeSeconds,
                                          boolean rerun) {
        return new TestcaseRow(unit, className, name, outcome, timeSeconds, rerun);
    }

    /// Attributes a parsed testcase to `unit` (not a rerun; reruns are M2).
    public static TestcaseRow testcaseRow(UnitName unit, ParsedTestcase testcase) {
        return new TestcaseRow(unit,
                               testcase.className(),
                               testcase.name(),
                               testcase.outcome(),
                               testcase.timeSeconds(),
                               false);
    }
}
