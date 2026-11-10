package org.pragmatica.kiln.service.store;

import java.util.Arrays;
import java.util.Locale;

import org.pragmatica.lang.Option;


/// A work package's phase (`work_event.phase`), in the Work tab's column order: the lifecycle from `design` to `merged`,
/// then `parked`, which is off the lifecycle and so sits in the last column, visible but out of the flow.
public enum WorkPhase {
    DESIGN,
    CODING,
    VERIFY,
    FIXING,
    ACCEPTED,
    INTEGRATED,
    IN_PR,
    MERGED,
    PARKED;
    /// The phase as the ledger and its API spell it: `design`, …, `in-pr`, `merged`, `parked`.
    public String label() {
        return name().toLowerCase(Locale.ROOT)
                   .replace('_', '-');
    }
    /// The phase spelled exactly as [#label] spells it; any other text is no phase.
    public static Option<WorkPhase> workPhase(String label) {
        return Option.from(Arrays.stream(values()).filter(phase -> phase.label()
                                                                        .equals(label)).findFirst());
    }
}
