package org.pragmatica.temper.domain.report;

import java.util.List;

import org.pragmatica.temper.domain.model.MarkerEpoch;


/// The report filter (spec §4.4): only reports strictly newer than the marker's TEXT epoch count. The
/// marker file's own mtime is not an input at all, which closes the copied-marker gap (A2).
public sealed interface ReportSelection {
    static List<ReportCandidate> newerThan(MarkerEpoch marker, List<ReportCandidate> all) {
        return all.stream()
                  .filter(candidate -> candidate.mtimeEpochNanos() > marker.nanos())
                  .toList();
    }

    record unused() implements ReportSelection {}
}
