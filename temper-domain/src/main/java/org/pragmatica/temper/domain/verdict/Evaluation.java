package org.pragmatica.temper.domain.verdict;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.pragmatica.temper.domain.report.TestcaseRow;


/// The verdict with everything the result record shows. `failed` and `observedFailures` are filled for every
/// verdict, so VOID and ERROR never hide the reds that were observed (spec §5.3). `missing` is
/// `required − reported` and `drift` is `reported − (required ∪ optional)` (ruling R-2), both empty without a manifest; `missing` names
/// the classes behind `FAILED(missing-classes)`, a crashed fork's class among them (T2). Both are sorted by class
/// name, so the result record and the manifest view list them in the same order on every run.
public record Evaluation(Verdict verdict,
                         Counts counts,
                         List<TestcaseRow> failed,
                         int observedFailures,
                         List<String> annotations,
                         Set<String> missing,
                         Set<String> drift) {
    public Evaluation {
        failed = List.copyOf(failed);
        annotations = List.copyOf(annotations);
        missing = sorted(missing);
        drift = sorted(drift);
    }

    public static Evaluation evaluation(Verdict verdict,
                                        Counts counts,
                                        List<TestcaseRow> failed,
                                        int observedFailures,
                                        List<String> annotations,
                                        Set<String> missing,
                                        Set<String> drift) {
        return new Evaluation(verdict, counts, failed, observedFailures, annotations, missing, drift);
    }

    private static Set<String> sorted(Set<String> names) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(names));
    }
}
