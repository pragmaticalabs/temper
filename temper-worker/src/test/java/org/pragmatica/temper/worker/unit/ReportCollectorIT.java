package org.pragmatica.temper.worker.unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// A2 with real files (m0-design §9): the stale-XML and copied-marker cases through the production collector,
/// with mtimes set by `Files.setLastModifiedTime`. The marker file's own mtime is never an input.
class ReportCollectorIT {
    private static final Instant T = Instant.parse("2026-10-13T12:00:00Z");
    private static final String XML = "<testsuite name=\"a.B\" tests=\"1\"><testcase classname=\"a.B\" name=\"t\" time=\"1\"/></testsuite>";

    @TempDir
    Path home;

    private WorkerPaths paths;
    private UnitFiles files;
    private final UnitRef ref = Fixtures.ref(5, "main");

    @BeforeEach
    void setUp() {
        paths = WorkerPaths.workerPaths(home);
        files = UnitFiles.unitFiles(paths);
    }

    private void marker(Instant text, Instant fileMtime) throws IOException {
        ok(files.store(ref, UnitFile.MARKER, Long.toString(ok(EpochNanos.epochNanos(text)))));
        Files.setLastModifiedTime(files.path(ref, UnitFile.MARKER), FileTime.from(fileMtime));
    }

    private void report(String relative, Instant mtime) throws IOException {
        var file = paths.workspace(ref.job())
                        .resolve(relative);

        Files.createDirectories(file.getParent());
        Files.writeString(file, XML);
        Files.setLastModifiedTime(file, FileTime.from(mtime));
    }

    @Test
    void collect_excludesStaleXmlOlderThanTheMarker() throws IOException {
        marker(T, T);
        report("core/target/surefire-reports/TEST-stale.xml", T.minusSeconds(60));
        report("core/target/surefire-reports/TEST-fresh.xml", T.plusSeconds(60));

        var outcome = ok(ReportCollector.reportCollector(paths, files)
                                        .collect(ref)
                                        .await());

        assertThat(outcome.reportFiles()).isEqualTo(1);
        assertThat(paths.reportsDir(ref)
                        .resolve("core/target/surefire-reports/TEST-fresh.xml")).exists();
        assertThat(paths.reportsDir(ref)
                        .resolve("core/target/surefire-reports/TEST-stale.xml")).doesNotExist();
    }

    @Test
    void collect_excludesAReport_whenTheMarkerWasCopied() throws IOException {
        // Marker text T, report written at T-1s, the marker FILE touched at T+10s (a copy): excluded.
        marker(T, T.plusSeconds(10));
        report("core/target/failsafe-reports/TEST-copied.xml", T.minusSeconds(1));

        var outcome = ok(ReportCollector.reportCollector(paths, files)
                                        .collect(ref)
                                        .await());

        assertThat(outcome.reportFiles()).isZero();
        assertThat(outcome.rows()).isEmpty();
    }

    @Test
    void collect_excludesAReportExactlyAtTheMarker_andIncludesOneJustAfter() throws IOException {
        marker(T, T);
        report("a/target/surefire-reports/TEST-equal.xml", T);
        report("b/target/failsafe-reports/TEST-after.xml", T.plus(Duration.ofMillis(1)));

        var outcome = ok(ReportCollector.reportCollector(paths, files)
                                        .collect(ref)
                                        .await());

        assertThat(outcome.reportFiles()).isEqualTo(1);
    }

    @Test
    void collect_ignoresXmlOutsideReportDirectories_andTheWorkspaceRepository() throws IOException {
        marker(T, T);
        report(".m2-local/x/target/surefire-reports/TEST-repo.xml", T.plusSeconds(5));
        report("core/target/TEST-loose.xml", T.plusSeconds(5));
        report("core/surefire-reports/TEST-notarget.xml", T.plusSeconds(5));
        report("core/target/surefire-reports/other.xml", T.plusSeconds(5));

        assertThat(ok(ReportCollector.reportCollector(paths, files)
                                     .collect(ref)
                                     .await()).reportFiles()).isZero();
    }

    @Test
    void collect_selectsNothing_whenTheMarkerIsMissing() throws IOException {
        report("core/target/surefire-reports/TEST-fresh.xml", T.plusSeconds(60));

        var outcome = ok(ReportCollector.reportCollector(paths, files)
                                        .collect(ref)
                                        .await());

        assertThat(outcome.errorReason()).isEqualTo(Option.some("marker"));
        assertThat(outcome.reportFiles()).isZero();
    }

    @Test
    void parseCopied_readsBackWhatCollectCopied() throws IOException {
        marker(T, T);
        report("core/target/surefire-reports/TEST-fresh.xml", T.plusSeconds(60));

        var collector = ReportCollector.reportCollector(paths, files);
        var collected = ok(collector.collect(ref)
                                    .await());

        assertThat(ok(collector.parseCopied(ref)
                               .await())).isEqualTo(collected);
    }
}
