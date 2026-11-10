package org.pragmatica.kiln.core.summary;

import java.time.Instant;
import java.util.List;


/// The `summary` answer (spec §7, Q-6): at most 25 lines, and `asOf`, which the caller passes back as its next `since`.
public record Summary(List<String> lines, Instant asOf) {
    public Summary {
        lines = List.copyOf(lines);
    }
}
