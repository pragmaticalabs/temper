package org.pragmatica.temper.domain.verdict;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.TestcaseRow;


/// Everything the verdict needs to know about one finished unit. `voidReason` / `errorReason` are set by the
/// worker or controller (disk, lost, report, infra, seed-isolation).
public record UnitOutcome(UnitName name,
                          boolean requestsTests,
                          boolean rerun,
                          Option<Integer> rc,
                          Option<KillCause> killed,
                          Option<String> voidReason,
                          Option<String> errorReason,
                          List<TestcaseRow> testcases,
                          long testsAttr) {
    public UnitOutcome {
        testcases = List.copyOf(testcases);
    }

    public static UnitOutcome unitOutcome(UnitName name,
                                          boolean requestsTests,
                                          boolean rerun,
                                          Option<Integer> rc,
                                          Option<KillCause> killed,
                                          Option<String> voidReason,
                                          Option<String> errorReason,
                                          List<TestcaseRow> testcases,
                                          long testsAttr) {
        return new UnitOutcome(name, requestsTests, rerun, rc, killed, voidReason, errorReason, testcases, testsAttr);
    }

    /// The unit exited with rc 0.
    public boolean exitedClean() {
        return rc.filter(code -> code == 0)
                 .isPresent();
    }

    /// Killed by its timeout (spec §4.3).
    public boolean timedOut() {
        return killed.filter(cause -> cause == KillCause.TIMEOUT)
                     .isPresent();
    }
}
