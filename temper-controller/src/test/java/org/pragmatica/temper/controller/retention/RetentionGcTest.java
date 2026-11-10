package org.pragmatica.temper.controller.retention;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.ResultFiles;
import org.pragmatica.temper.controller.fake.FakeRetention;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.retention.EvidenceItem;
import org.pragmatica.temper.domain.retention.JobRetention;
import org.pragmatica.temper.domain.retention.RetentionPolicy;
import org.pragmatica.temper.store.api.EvidenceRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The retention GC's execution (m1-design §4.2): the plan's actions reach the right store calls and files, the
/// file goes before its row, nothing of a `keep` job is touched, only verified dumps are candidates, and the
/// `result.json` sweep runs with every tick.
class RetentionGcTest {
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final Instant STORED = NOW.minus(Duration.ofDays(100));
    @TempDir
    Path root;
    private final FakeRetention retention = new FakeRetention();
    private final FakeStores.Evidence evidence = new FakeStores.Evidence();
    private final FakeStores.Results results = new FakeStores.Results();
    private EvidenceLayout layout;
    private RetentionGc gc;

    @BeforeEach
    void setUp() {
        layout = EvidenceLayout.evidenceLayout(root);
        gc = RetentionGc.retentionGc(retention,
                                     evidence,
                                     HostLinks.hostLinks(List.of(), new FakeStores.Jobs()),
                                     ResultFiles.resultFiles(layout, results, () -> NOW),
                                     layout,
                                     RetentionPolicy.DEFAULT,
                                     () -> NOW);
    }

    private static JobId job(long id) {
        return JobId.jobId(id).unwrap();
    }

    private static JobRetention finished(long id, Duration ago, boolean keep, List<EvidenceItem> items, boolean hasTestcases) {
        return JobRetention.jobRetention(job(id), keep, true, some(NOW.minus(ago)), items, List.of(), hasTestcases);
    }

    private Path storedTarball(long id, long job, EvidenceKind kind) throws Exception {
        var ref = UnitRef.unitRef(job(job), UnitName.MAIN);
        var file = layout.tarball(ref, 1, kind);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "tarball");
        evidence.rows.put(id, EvidenceRow.evidenceRow(id, ref, kind, file, 7, "ab", STORED, none()));
        return file;
    }

    @Test
    void run_deletesExpiredTarballs_fileThenRow_andTheJobsRows() throws Exception {
        var full = storedTarball(1, 5, EvidenceKind.FULL);
        var small = storedTarball(2, 5, EvidenceKind.SMALL);
        retention.jobs.add(finished(5, Duration.ofDays(20), false,
                                    List.of(EvidenceItem.evidenceItem(1, UnitName.MAIN, EvidenceKind.FULL, STORED),
                                            EvidenceItem.evidenceItem(2, UnitName.MAIN, EvidenceKind.SMALL, STORED)),
                                    true));

        var report = gc.run().await().unwrap();

        assertThat(full).doesNotExist();
        assertThat(small).as("small evidence lives 90 d").exists();
        assertThat(evidence.deleted).containsExactly(1L);
        assertThat(retention.rowsDeleted).as("rows live 90 d").isEmpty();
        assertThat(report.failed()).isZero();
    }

    @Test
    void run_after90Days_deletesSmallEvidenceAndRows() throws Exception {
        var small = storedTarball(2, 6, EvidenceKind.SMALL);
        retention.jobs.add(finished(6, Duration.ofDays(91), false,
                                    List.of(EvidenceItem.evidenceItem(2, UnitName.MAIN, EvidenceKind.SMALL, STORED)), true));

        gc.run().await().unwrap();

        assertThat(small).doesNotExist();
        assertThat(evidence.deleted).containsExactly(2L);
        assertThat(retention.rowsDeleted).containsExactly(job(6));
    }

    /// A tarball re-pulled after the plan read its row (a new `stored_at`, same id) is a new file: neither the file
    /// nor the row is touched; the next tick plans it with its new `storedAt`.
    @Test
    void run_leavesATarballRePulledAfterThePlan() throws Exception {
        var small = storedTarball(2, 8, EvidenceKind.SMALL);
        retention.jobs.add(finished(8, Duration.ofDays(91), false,
                                    List.of(EvidenceItem.evidenceItem(2, UnitName.MAIN, EvidenceKind.SMALL, STORED.minusSeconds(60))),
                                    false));

        gc.run().await().unwrap();

        assertThat(small).exists();
        assertThat(evidence.deleted).isEmpty();
    }

    /// A row retention already marked deleted is not planned again, even when its file is back on disk: the file is
    /// left and the row is not marked a second time.
    @Test
    void run_skipsARowAlreadyMarkedDeleted() throws Exception {
        var small = storedTarball(2, 10, EvidenceKind.SMALL);
        var row = evidence.rows.get(2L);
        evidence.rows.put(2L, EvidenceRow.evidenceRow(2, row.unit(), row.kind(), row.path(), 7, "ab", STORED, some(NOW.minusSeconds(3600))));
        retention.jobs.add(finished(10, Duration.ofDays(91), false,
                                    List.of(EvidenceItem.evidenceItem(2, UnitName.MAIN, EvidenceKind.SMALL, STORED)), false));

        gc.run().await().unwrap();

        assertThat(small).exists();
        assertThat(evidence.deleted).isEmpty();
    }

    @Test
    void run_touchesNothingOfAKeepJob() throws Exception {
        var small = storedTarball(2, 7, EvidenceKind.SMALL);
        retention.jobs.add(finished(7, Duration.ofDays(400), true,
                                    List.of(EvidenceItem.evidenceItem(2, UnitName.MAIN, EvidenceKind.SMALL, STORED)), true));

        gc.run().await().unwrap();

        assertThat(small).exists();
        assertThat(evidence.deleted).isEmpty();
        assertThat(retention.rowsDeleted).isEmpty();
    }

    @Test
    void run_deletesEventsUpToTheHorizon_askedAt30Days() {
        retention.horizon = some(42L);

        gc.run().await().unwrap();

        assertThat(retention.eventsDeletedUpTo).containsExactly(42L);
        assertThat(retention.horizonAsked).containsExactly(NOW.minus(Duration.ofDays(30)));
        assertThat(retention.finishedBeforeAsked.getFirst()).as("the shortest job clock: the 24 h checkout")
                                                            .isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void run_deletesOldDumps_keepingTheNewest_andIgnoringPartials() throws Exception {
        var dumps = Files.createDirectories(layout.pgDumps());
        var old = Files.writeString(dumps.resolve("temper-20260901T033000Z.dump"), "x");
        var older = Files.writeString(dumps.resolve("temper-20260801T033000Z.dump"), "x");
        var partial = Files.writeString(dumps.resolve("temper-20260701T033000Z.dump.partial"), "x");
        var newest = Files.writeString(dumps.resolve("temper-20260920T033000Z.dump"), "x");

        gc.run().await().unwrap();

        assertThat(old).doesNotExist();
        assertThat(older).doesNotExist();
        assertThat(newest).as("older than 14 d, but the newest good dump").exists();
        assertThat(partial).exists();
    }

    @Test
    void run_pagesThroughTheCandidates() {
        for (var id = 1; id <= RetentionGc.PAGE + 1; id++) {
            retention.jobs.add(finished(id, Duration.ofDays(2), false, List.of(), false));
        }

        gc.run().await().unwrap();

        assertThat(retention.pages.get()).isEqualTo(2);
    }

    @Test
    void sweep_writesTheMissingResultFiles() {
        results.json.put(job(9), "{\"job\":9}");

        assertThat(gc.sweep().await().unwrap()).isEqualTo(1);
        assertThat(layout.resultJson(job(9))).hasContent("{\"job\":9}");
    }

    /// m1-design §3.3 "within one sweep interval": the sweep runs on its own schedule, so neither a retention run
    /// that never ends (a slow host clean-up) nor one whose store reads fail delays it.
    @Test
    void sweep_keepsItsInterval_whileTheRetentionRunHangsOrFails() throws Exception {
        for (var failing : List.of(false, true)) {
            var store = new FakeRetention();
            store.hangJobs = !failing;
            store.failJobs = failing;
            var results = new FakeStores.Results();
            var gc = RetentionGc.retentionGc(store, evidence, HostLinks.hostLinks(List.of(), new FakeStores.Jobs()),
                                             ResultFiles.resultFiles(layout, results, () -> NOW), layout, RetentionPolicy.DEFAULT,
                                             () -> NOW);
            var file = layout.resultJson(job(failing ? 12 : 11));
            gc.start(TimeSpan.timeSpan(150).millis());
            try {
                Thread.sleep(400);
                results.json.put(failing ? job(12) : job(11), "{}");
                var deadline = System.nanoTime() + 1_000_000_000L;
                while (!Files.exists(file) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }

                assertThat(file).as(failing ? "retention reads failing" : "retention run hung").exists();
                assertThat(store.pages.get()).as("the retention run did start").isPositive();
            } finally {
                gc.stop();
            }
        }
    }

    /// Non-reentrant: a tick while a run is in flight starts nothing.
    @Test
    void tick_isSkipped_whileARunIsInFlight() {
        retention.hangJobs = true;

        gc.tick();
        gc.tick();

        assertThat(retention.pages.get()).isEqualTo(1);
    }
}
