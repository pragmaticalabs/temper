package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.manifest.ManifestEntry;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.EvidenceDto;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredEvidence;
import org.pragmatica.temper.store.api.UnitManifestRows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Manifest and coverage rows (m1-design §2.5) and evidence rows (§3.2) written by `complete`, and the result
/// record built from them: `Finishing` passes the manifest to the verdict only when every unit with a spec
/// delivered a computed one, so a crashed fork's class is `FAILED(missing-classes)` (T2), not `FAILED(rc)`.
@Tag("pg")
class ManifestRowsIT {
    private static StoreHarness harness;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void grant_carriesManifestSpec_portGroup_andPriority() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", true)), false));
        Granted granted = harness.grantHead().getFirst();

        assertThat(granted.ref()).isEqualTo(TestJobs.ref(job, "gate"));
        assertThat(granted.manifest()).isEqualTo(some(TestJobs.SPEC));
        assertThat(granted.needsPortGroup()).isTrue();
        assertThat(granted.priority()).isEqualTo(7);
        finishQuietly(job, "gate");
    }

    @Test
    void grant_unitWithoutSpec_hasNoManifest() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var granted = harness.grantHead().getFirst();

        assertThat(granted.manifest()).isEqualTo(none());
        assertThat(granted.needsPortGroup()).isFalse();
        finishQuietly(job, "main");
    }

    /// T2: rc 1, the manifest names a class with no rows → FAILED(missing-classes) naming it.
    @Test
    void complete_crashedFork_failsMissingClasses_namingTheClass() {
        var job = complete(1, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), some(TestJobs.computed("a.ATest", "b.CrashTest")));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("FAILED");
        assertThat(record.reason()).isEqualTo(some("missing-classes"));
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::missing)).isEqualTo(some(List.of("b.CrashTest")));
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::computed)).isEqualTo(some(true));
        assertThat(record.counts().missing()).isEqualTo(some(1));
    }

    @Test
    void complete_computedManifest_writesRowsPerUnitAndRunner_andRecordsDrift() {
        var job = complete(0,
                           List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED), TestJobs.row("gate", "x.Extra", "e", Outcome.PASSED)),
                           some(UnitManifestRows.unitManifestRows(ManifestOutcome.Computed.computed(Set.of(ManifestEntry.manifestEntry("mod", "a.ATest", Runner.SUREFIRE),
                                                                                                          ManifestEntry.manifestEntry("mod", "a.ATest", Runner.FAILSAFE)),
                                                                                                   Set.of(),
                                                                                                   List.of("late-module"),
                                                                                                   Set.of("org.example.Gone")))));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("PASSED");
        assertThat(record.annotations()).contains("manifest-drift=1");
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::drift)).isEqualTo(some(List.of("x.Extra")));
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::notReached)).isEqualTo(some(List.of("late-module")));
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::unresolvedAnnotations)).isEqualTo(some(1));
        assertThat(harness.count("SELECT count(*) AS n FROM manifests m JOIN units u ON u.id = m.unit_id "
                                 + "WHERE m.job_id = $1 AND u.name = 'gate'", job.value())).isEqualTo(2);
        assertThat(harness.count("SELECT count(*) AS n FROM manifest_units m JOIN units u ON u.id = m.unit_id "
                                 + "WHERE u.job_id = $1 AND m.status = 'computed'", job.value())).isEqualTo(1);

        var view = harness.store.results().manifest(job).await(PgFixture.WAIT).unwrap();

        assertThat(view.map(JobManifest::computed)).isEqualTo(some(true));
        assertThat(view.map(JobManifest::entries).or(List.of())).containsExactly(ManifestEntry.manifestEntry("mod", "a.ATest", Runner.FAILSAFE),
                                                                                 ManifestEntry.manifestEntry("mod", "a.ATest", Runner.SUREFIRE));
        assertThat(view.map(JobManifest::notReached)).isEqualTo(some(List.of("late-module")));
    }

    /// Ruling R-2: optional rows are stored as such and read back apart from the required ones; a reporting optional
    /// class is not drift and a silent one is not missing.
    @Test
    void complete_optionalClasses_storedAsOptional_neitherMissingNorDrift() {
        var outcome = ManifestOutcome.Computed.computed(Set.of(ManifestEntry.manifestEntry("mod", "a.ATest", Runner.SUREFIRE)),
                                                        Set.of(ManifestEntry.manifestEntry("mod", "o.FactoryTest", Runner.SUREFIRE),
                                                               ManifestEntry.manifestEntry("mod", "o.SilentTest", Runner.SUREFIRE)),
                                                        List.of(),
                                                        Set.of());
        var job = complete(0,
                           List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED), TestJobs.row("gate", "o.FactoryTest", "r", Outcome.PASSED)),
                           some(UnitManifestRows.unitManifestRows(outcome)));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("PASSED");
        assertThat(record.annotations()).noneMatch(annotation -> annotation.startsWith("manifest-drift"));
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::missing)).isEqualTo(some(List.of()));
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::drift)).isEqualTo(some(List.of()));
        assertThat(record.counts().manifest()).isEqualTo(some(1));
        assertThat(harness.count("SELECT count(*) AS n FROM manifests WHERE job_id = $1 AND optional", job.value())).isEqualTo(2);

        var view = harness.store.results().manifest(job).await(PgFixture.WAIT).unwrap();

        assertThat(view.map(JobManifest::entries).or(List.of())).containsExactly(ManifestEntry.manifestEntry("mod", "a.ATest", Runner.SUREFIRE));
        assertThat(view.map(JobManifest::optional).or(List.of())).containsExactly(ManifestEntry.manifestEntry("mod", "o.FactoryTest", Runner.SUREFIRE),
                                                                                  ManifestEntry.manifestEntry("mod", "o.SilentTest", Runner.SUREFIRE));
        assertThat(view.map(JobManifest::pruned)).isEqualTo(some(false));
    }

    /// Ruling R-7: a computed manifest that expects nothing reads back computed, empty and not pruned.
    @Test
    void complete_computedEmptyManifest_isNotPruned() {
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), some(TestJobs.computed()));
        var view = harness.store.results().manifest(job).await(PgFixture.WAIT).unwrap();

        assertThat(view.map(JobManifest::computed)).isEqualTo(some(true));
        assertThat(view.map(JobManifest::entries)).isEqualTo(some(List.of()));
        assertThat(view.map(JobManifest::pruned)).isEqualTo(some(false));
    }

    /// Q-M1-7: an unsupported configuration is ERROR(manifest), never a pass without coverage. The verdict keeps its
    /// token; the unit's stored reason and the record's `reasonDetail` keep what was unsupported.
    @Test
    void complete_unsupportedManifest_isErrorManifest_andNotComputed() {
        var unsupported = UnitManifestRows.unitManifestRows(ManifestOutcome.Unsupported.unsupported(Causes.cause("includesFile is not supported")));
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), some(unsupported));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("ERROR");
        assertThat(record.reason()).isEqualTo(some("manifest"));
        assertThat(record.reasonDetail()).isEqualTo(some("includesFile is not supported"));
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND reason = 'manifest: includesFile is not supported'",
                                 job.value())).isEqualTo(1);
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::computed)).isEqualTo(some(false));
        assertThat(harness.count("SELECT count(*) AS n FROM manifest_units m JOIN units u ON u.id = m.unit_id "
                                 + "WHERE u.job_id = $1 AND m.status = 'unsupported' AND m.detail = 'includesFile is not supported'",
                                 job.value())).isEqualTo(1);
    }

    /// A unit with a spec that delivered no manifest: no coverage judgement (manifest none), and the record says so.
    @Test
    void complete_specUnitWithoutManifest_recordNotComputed_verdictWithoutCoverage() {
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), none());
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("PASSED");
        assertThat(record.manifest().map(ResultRecordDto.ManifestDto::computed)).isEqualTo(some(false));
        assertThat(record.counts().manifest()).isEqualTo(none());
    }

    @Test
    void complete_unitWithoutSpec_recordHasNoManifestBlock() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false), TestJobs.exited(0), List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));

        assertThat(record(job).manifest()).isEqualTo(none());
        assertThat(harness.store.results().manifest(job).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
    }

    @Test
    void complete_repeated_writesManifestAndEvidenceOnce() {
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), some(TestJobs.computed("a.ATest")));
        var again = harness.store.units()
                                 .complete(TestJobs.HOST, TestJobs.ref(job, "gate"), 1, TestJobs.exited(0), List.of(), some(TestJobs.computed("z.Other")),
                                           some(TestJobs.evidence(job, "gate", "evidence.tar.gz", 1)), TestJobs::evaluate)
                                 .await(PgFixture.WAIT)
                                 .unwrap();

        assertThat(again).isInstanceOf(CompleteOutcome.AlreadyComplete.class);
        assertThat(harness.count("SELECT count(*) AS n FROM manifests WHERE job_id = $1", job.value())).isEqualTo(1);
        assertThat(harness.count("SELECT bytes AS n FROM evidence WHERE job_id = $1", job.value())).isEqualTo(1234);
    }

    @Test
    void complete_withSmallEvidence_recordStoredWithJobDirectoryAndBytes() {
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), some(TestJobs.computed("a.ATest")));
        var record = record(job);

        assertThat(record.evidence()).isEqualTo(EvidenceDto.evidenceDto(true, some("/ev/jobs/" + job.value()), some(1234L)));
        assertThat(record.annotations()).doesNotContain(EvidenceDto.MISSING_ANNOTATION);
    }

    /// Design §5: `stored:true` only if every unit that ran has its small tarball. Of two units with a marker, the one
    /// without it makes the record `stored:false` with `evidence=missing`, though the other's tarball is stored.
    @Test
    void complete_oneOfTwoRunUnitsWithoutSmallEvidence_recordNotStored() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("one", false), TestJobs.specUnit("two", false)), false));

        harness.grantHead();
        harness.grantHead();
        finishWith(job, "one", some(TestJobs.evidence(job, "one", "evidence.tar.gz", 1234)));
        finishWith(job, "two", none());
        var record = record(job);

        assertThat(harness.count("SELECT count(*) AS n FROM evidence WHERE job_id = $1 AND kind = 'small'", job.value())).isEqualTo(1);
        assertThat(record.evidence()).isEqualTo(EvidenceDto.NOT_STORED);
        assertThat(record.annotations()).contains(EvidenceDto.MISSING_ANNOTATION);
    }

    /// A unit that never started has no marker and nothing to pull: a job cancelled before any start is not
    /// `evidence=missing`.
    @Test
    void cancel_beforeAnyStart_noEvidenceMissingAnnotation() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("CANCELLED");
        assertThat(record.evidence()).isEqualTo(EvidenceDto.NOT_STORED);
        assertThat(record.annotations()).doesNotContain(EvidenceDto.MISSING_ANNOTATION);
    }

    @Test
    void evidenceStore_recordsFull_replacesLiveRow_marksDeleted() {
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), none());
        var evidence = harness.store.evidence();
        var ref = TestJobs.ref(job, "gate");
        var recorded = record(job).evidence();

        evidence.recorded(ref, EvidenceKind.FULL, TestJobs.evidence(job, "gate", "full.tar.gz", 10)).await(PgFixture.WAIT).unwrap();
        evidence.recorded(ref, EvidenceKind.FULL, TestJobs.evidence(job, "gate", "full.tar.gz", 20)).await(PgFixture.WAIT).unwrap();
        var id = harness.count("SELECT id AS n FROM evidence WHERE job_id = $1 AND kind = 'full'", job.value());
        var row = evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(row.bytes()).isEqualTo(20);
        assertThat(row.unit()).isEqualTo(ref);
        assertThat(row.kind()).isEqualTo(EvidenceKind.FULL);
        assertThat(row.path().toString()).isEqualTo("/ev/jobs/" + job.value() + "/units/gate/full.tar.gz");
        assertThat(row.deletedAt()).isEqualTo(none());

        evidence.deleted(id, row.storedAt()).await(PgFixture.WAIT).unwrap();
        var deletedAt = evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap().deletedAt();

        assertThat(deletedAt.isPresent()).isTrue();
        evidence.deleted(id, row.storedAt()).await(PgFixture.WAIT).unwrap();
        assertThat(evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap().deletedAt()).isEqualTo(deletedAt);
        evidence.recorded(ref, EvidenceKind.FULL, TestJobs.evidence(job, "gate", "full.tar.gz", 30)).await(PgFixture.WAIT).unwrap();
        assertThat(harness.count("SELECT count(*) AS n FROM evidence WHERE job_id = $1 AND kind = 'full'", job.value())).isEqualTo(2);
        assertThat(record(job).evidence()).as("a later pull never changes the record").isEqualTo(recorded);
    }

    /// A pull that replaced the row after retention listed it (`recorded` keeps the id) wrote a new file: the mark
    /// for the planned `stored_at` leaves the row live, so its file is not orphaned under a deleted row.
    @Test
    void evidenceStore_deletedOfARowReplacedSinceThePlan_keepsItLive() {
        var job = complete(0, List.of(), none());
        var evidence = harness.store.evidence();
        var ref = TestJobs.ref(job, "gate");

        evidence.recorded(ref, EvidenceKind.FULL, TestJobs.evidence(job, "gate", "full.tar.gz", 10)).await(PgFixture.WAIT).unwrap();
        var id = harness.count("SELECT id AS n FROM evidence WHERE job_id = $1 AND kind = 'full'", job.value());
        var planned = evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap().storedAt();

        evidence.recorded(ref, EvidenceKind.FULL, TestJobs.evidence(job, "gate", "full.tar.gz", 20)).await(PgFixture.WAIT).unwrap();
        var replaced = evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(replaced.storedAt()).as("the re-pull's own time").isNotEqualTo(planned);
        evidence.deleted(id, planned).await(PgFixture.WAIT).unwrap();
        assertThat(evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap().deletedAt()).isEqualTo(none());
        evidence.deleted(id, replaced.storedAt()).await(PgFixture.WAIT).unwrap();
        assertThat(evidence.evidence(id).await(PgFixture.WAIT).unwrap().unwrap().deletedAt().isPresent()).isTrue();
    }

    @Test
    void evidenceStore_unknownUnitOrId_fail() {
        var job = complete(0, List.of(), none());

        harness.store.evidence()
               .recorded(TestJobs.ref(job, "nope"), EvidenceKind.FULL, TestJobs.evidence(job, "nope", "full.tar.gz", 1))
               .await(PgFixture.WAIT)
               .onSuccess(_ -> fail("expected UnitNotFound"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.UnitNotFound.class));
        harness.store.evidence()
               .deleted(Long.MAX_VALUE, Instant.now())
               .await(PgFixture.WAIT)
               .onSuccess(_ -> fail("expected EvidenceNotFound"))
               .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.EvidenceNotFound.FACTORY.apply(Long.MAX_VALUE)));
        assertThat(harness.store.evidence().evidence(Long.MAX_VALUE).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
    }

    @Test
    void results_recordJson_isTheStoredRecord_andFinishedSinceListsIt() {
        var job = complete(0, List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), none());
        var json = harness.store.results().recordJson(job).await(PgFixture.WAIT).unwrap().unwrap();
        var stored = Sql.rows(harness.raw.executor()
                                         .completeQuery("SELECT record::text AS r FROM results WHERE job_id = $1", job.value())
                                         .await(PgFixture.WAIT)
                                         .unwrap())
                        .getFirst()
                        .getString("r");

        assertThat(json).isEqualTo(stored);
        assertThat(harness.store.results().finishedSince(Instant.now().minusSeconds(3600)).await(PgFixture.WAIT).unwrap()).contains(job);
        assertThat(harness.store.results().finishedSince(Instant.now().plusSeconds(3600)).await(PgFixture.WAIT).unwrap()).doesNotContain(job);
        var queued = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        assertThat(harness.store.results().recordJson(queued).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
        harness.store.jobs().requestCancel(queued).await(PgFixture.WAIT).unwrap();
    }

    private static JobId complete(int rc, List<TestcaseRow> rows, Option<UnitManifestRows> manifest) {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", false)), false));
        var ref = TestJobs.ref(job, "gate");

        harness.grantHead();
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        var outcome = harness.store.units()
                                   .complete(TestJobs.HOST, ref, 1, TestJobs.exited(rc), rows, manifest, some(TestJobs.evidence(job, "gate", "evidence.tar.gz", 1234)),
                                             TestJobs::evaluate)
                                   .await(PgFixture.WAIT)
                                   .unwrap();

        assertThat(outcome).isInstanceOf(CompleteOutcome.Completed.class);
        return job;
    }

    private static void finishQuietly(JobId job, String unit) {
        var ref = TestJobs.ref(job, unit);

        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        harness.store.units().complete(TestJobs.HOST, ref, 1, TestJobs.exited(0), List.of(), none(), none(), TestJobs::evaluate).await(PgFixture.WAIT).unwrap();
        assertThat(harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst().state()).isNotEqualTo(UnitState.GRANTED);
    }

    /// started → complete of a granted `unit`, with or without its small tarball.
    private static void finishWith(JobId job, String unit, Option<StoredEvidence> evidence) {
        var ref = TestJobs.ref(job, unit);

        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        harness.store.units().complete(TestJobs.HOST, ref, 1, TestJobs.exited(0), List.of(), none(), evidence, TestJobs::evaluate).await(PgFixture.WAIT).unwrap();
    }

    private static ResultRecordDto record(JobId job) {
        return harness.store.results().result(job).await(PgFixture.WAIT).unwrap().map(WireRecords::resultRecordDto).unwrap();
    }
}
