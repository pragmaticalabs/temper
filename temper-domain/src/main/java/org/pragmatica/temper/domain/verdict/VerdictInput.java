package org.pragmatica.temper.domain.verdict;

import java.util.List;

import org.pragmatica.lang.Option;


/// Input to [Verdicts#evaluate]: every unit of the job, whether it was cancelled (by request or supersede),
/// whether it was superseded (an annotation only), and the manifest when one exists (M1).
public record VerdictInput(List<UnitOutcome> units, boolean cancelled, boolean superseded, Option<Manifest> manifest) {
    public VerdictInput {
        units = List.copyOf(units);
    }

    public static VerdictInput verdictInput(List<UnitOutcome> units,
                                            boolean cancelled,
                                            boolean superseded,
                                            Option<Manifest> manifest) {
        return new VerdictInput(units, cancelled, superseded, manifest);
    }
}
