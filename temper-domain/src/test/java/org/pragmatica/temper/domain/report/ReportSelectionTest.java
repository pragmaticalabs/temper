package org.pragmatica.temper.domain.report;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.MarkerEpoch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// A2 stale-XML and copied-marker cases. The selection takes the marker's TEXT epoch; the marker file's own
/// mtime is not even an input, so a copied marker (text T, file mtime T+10 s) cannot admit stale reports.
class ReportSelectionTest {
    private static final long T = 1_760_000_000_000_000_000L;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void newerThan_excludesStaleReportOlderThanMarker() {
        var marker = ok(MarkerEpoch.markerEpoch(Long.toString(T)));
        var stale = ReportCandidate.reportCandidate("a/target/surefire-reports/TEST-Old.xml", T - SECOND);
        var fresh = ReportCandidate.reportCandidate("a/target/surefire-reports/TEST-New.xml", T + SECOND);

        assertThat(ReportSelection.newerThan(marker, List.of(stale, fresh))).containsExactly(fresh);
    }

    @Test
    void newerThan_isStrict_atTheMarkerEpoch() {
        var marker = ok(MarkerEpoch.markerEpoch(Long.toString(T)));
        var same = ReportCandidate.reportCandidate("TEST-Same.xml", T);

        assertThat(ReportSelection.newerThan(marker, List.of(same))).isEmpty();
    }

    @Test
    void newerThan_usesMarkerTextNotMarkerMtime_forCopiedMarker() {
        // marker text T; the copied marker file's mtime is T + 10 s and is NOT an input
        var copiedMarker = ok(MarkerEpoch.markerEpoch(" " + T + "\n"));
        var olderThanText = ReportCandidate.reportCandidate("TEST-Stale.xml", T - SECOND);
        var newerThanTextOlderThanMarkerMtime = ReportCandidate.reportCandidate("TEST-Fresh.xml", T + 5 * SECOND);

        assertThat(ReportSelection.newerThan(copiedMarker, List.of(olderThanText, newerThanTextOlderThanMarkerMtime)))
            .containsExactly(newerThanTextOlderThanMarkerMtime);
    }
}
