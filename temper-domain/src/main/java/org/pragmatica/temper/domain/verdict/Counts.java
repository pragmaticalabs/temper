package org.pragmatica.temper.domain.verdict;

import org.pragmatica.lang.Option;


/// Result counts (spec §5.4). `testsAttr` is informational only. `manifest`, `missing` and `drift` are present
/// exactly when the job has a manifest (m1-design §2.5): the result record caps its class lists, and these are
/// the uncapped sizes.
public record Counts(int testcases,
                     int passed,
                     int failed,
                     int error,
                     int skipped,
                     long testsAttr,
                     int classes,
                     Option<Integer> manifest,
                     Option<Integer> missing,
                     Option<Integer> drift) {
    public static Counts counts(int testcases,
                                int passed,
                                int failed,
                                int error,
                                int skipped,
                                long testsAttr,
                                int classes,
                                Option<Integer> manifest,
                                Option<Integer> missing,
                                Option<Integer> drift) {
        return new Counts(testcases, passed, failed, error, skipped, testsAttr, classes, manifest, missing, drift);
    }
}
