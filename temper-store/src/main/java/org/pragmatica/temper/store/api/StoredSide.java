package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.TestcaseRow;


/// One side of `temper diff` as [ResultStore#diffSides] reads it (m2-design §3.2): the job with its units, its result
/// record once terminal, and every testcase row stored for it (empty once retention deleted them, 90 d).
public record StoredSide(JobRow job, Option<ResultRecord> record, List<TestcaseRow> testcases) {
    public StoredSide {
        testcases = List.copyOf(testcases);
    }

    public static StoredSide storedSide(JobRow job, Option<ResultRecord> record, List<TestcaseRow> testcases) {
        return new StoredSide(job, record, testcases);
    }
}
