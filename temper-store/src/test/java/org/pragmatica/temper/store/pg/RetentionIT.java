package org.pragmatica.temper.store.pg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgColumn;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.retention.DumpFile;
import org.pragmatica.temper.domain.retention.EvidenceItem;
import org.pragmatica.temper.domain.retention.JobRetention;
import org.pragmatica.temper.domain.retention.RetentionAction;
import org.pragmatica.temper.domain.retention.RetentionPlan;
import org.pragmatica.temper.domain.retention.RetentionPolicy;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.RetentionStore;
import org.pragmatica.temper.store.api.StoredEvidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Retention in the database (m1-design §4.1, §4.2, Q-M1-11 / ruling M1-11): `testcases`, `manifests` and
/// `unit_facts` rows go only through `SET LOCAL temper.retention = 'on'`; `manifest_units`, `results`, `jobs`,
/// `units` and `evidence` rows stay; nothing of a `keep` or unfinished job goes, whatever the caller asks; events go
/// by age, pinned jobs' events included. Each test gets its own database.
@Tag("pg")
class RetentionIT {
    private StoreHarness harness;
    private RetentionStore retention;

    /// The controller's evidence root for the tests that delete real files.
    @TempDir
    private Path evidenceRoot;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        retention = harness.store.retention();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void deleteJobRows_finishedJob_deletesTheRowClasses_keepsResultsJobsUnitsEvidence() {
        var job = finished(false);
        var record = record(job);

        assertThat(rowCounts(job)).containsExactly(2L, 1L, 1L, 2L);
        assertThat(deleted(job)).isEqualTo(5);

        assertThat(rowCounts(job)).as("manifest_units is not in ruling M1-11's list").containsExactly(0L, 0L, 1L, 0L);
        assertThat(harness.store.results().manifest(job).await(PgFixture.WAIT).unwrap().map(JobManifest::computed)).isEqualTo(some(true));
        assertThat(count("results", job)).isEqualTo(1);
        assertThat(count("jobs", job)).isEqualTo(1);
        assertThat(count("units", job)).isEqualTo(1);
        assertThat(count("evidence", job)).isEqualTo(1);
        assertThat(record(job)).isEqualTo(record);
    }

    /// Ruling R-7: once retention deleted a job's `manifests` rows its manifest reads `pruned`, not computed-and-empty.
    @Test
    void deleteJobRows_manifestReadsPruned() {
        var job = finished(false);

        assertThat(manifest(job).map(JobManifest::pruned)).isEqualTo(some(false));
        deleted(job);

        assertThat(manifest(job).map(JobManifest::pruned)).isEqualTo(some(true));
        assertThat(manifest(job).map(JobManifest::computed)).isEqualTo(some(true));
        assertThat(manifest(job).map(JobManifest::entries)).isEqualTo(some(List.of()));
    }

    /// Ruling R-7: a computed manifest that expected nothing stays not pruned after retention: it still means no tests.
    @Test
    void deleteJobRows_emptyManifest_staysNotPruned() {
        var job = harness.runWith(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", false)), false),
                                  "gate",
                                  List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)),
                                  some(TestJobs.computed()),
                                  true);

        deleted(job);

        assertThat(manifest(job).map(JobManifest::pruned)).isEqualTo(some(false));
        assertThat(manifest(job).map(JobManifest::entries)).isEqualTo(some(List.of()));
    }

    private Option<JobManifest> manifest(JobId job) {
        return harness.store.results().manifest(job).await(PgFixture.WAIT).unwrap();
    }

    @Test
    void deleteJobRows_keepJob_deletesNothing() {
        var job = finished(true);

        assertThat(retention.deleteJobRows(job).await(PgFixture.WAIT).unwrap()).isZero();
        assertThat(rowCounts(job)).containsExactly(2L, 1L, 1L, 2L);
    }

    @Test
    void deleteJobRows_unfinishedJob_deletesNothing() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("one", false), TestJobs.specUnit("two", false)), false));
        var ref = TestJobs.ref(job, "one");

        harness.grantHead();
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        harness.store.units()
                     .complete(TestJobs.HOST, ref, 1, TestJobs.exited(0), List.of(TestJobs.row("one", "a.ATest", "t", Outcome.PASSED)), none(), none(),
                               TestJobs::evaluate)
                     .await(PgFixture.WAIT)
                     .unwrap();

        assertThat(count("testcases", job)).isEqualTo(1);
        assertThat(retention.deleteJobRows(job).await(PgFixture.WAIT).unwrap()).isZero();
        assertThat(count("testcases", job)).isEqualTo(1);
    }

    /// More testcases than one batch: every batch runs, and each one inside the retention setting.
    @Test
    void deleteJobRows_moreThanOneBatch_deletesEveryTestcase() {
        var rows = IntStream.range(0, PgRetentionStore.BATCH * 2 + 5)
                            .mapToObj(index -> TestJobs.row("gate", "a.ATest", "t" + index, Outcome.PASSED))
                            .toList();
        var job = harness.runWith(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", false)), false), "gate", rows, none(), false);

        assertThat(count("testcases", job)).isEqualTo(rows.size());
        assertThat(deleted(job)).as("every testcase plus the start and end facts").isEqualTo(rows.size() + 2);
        assertThat(count("testcases", job)).isZero();
    }

    /// A batch that fails keeps what the batches before it deleted: each batch commits on its own.
    @Test
    void deleteJobRows_batchFails_earlierBatchesStayDeleted() {
        var rows = IntStream.range(0, PgRetentionStore.BATCH * 2 + 5)
                            .mapToObj(index -> TestJobs.row("gate", "a.ATest", "t" + index, Outcome.PASSED))
                            .toList();
        var job = harness.runWith(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", false)), false), "gate", rows, none(), false);

        failDeletesAfter("testcases", PgRetentionStore.BATCH * 2);
        assertThat(retention.deleteJobRows(job).await(PgFixture.WAIT).isFailure()).as("the third batch fails").isTrue();
        assertThat(count("testcases", job)).as("the first two batches committed").isEqualTo(5);
    }

    @Test
    void deleteJobRows_unknownJob_deletesNothing() {
        var unknown = JobId.jobId(Long.MAX_VALUE).unwrap();

        assertThat(retention.deleteJobRows(unknown).await(PgFixture.WAIT).unwrap()).isZero();
    }

    /// The candidates, their evidence and their hosts are read in one snapshot: evidence another transaction marks
    /// deleted between the candidate query and the evidence query is still listed with the job it made a candidate.
    @Test
    void jobs_evidenceMarkedDeletedBetweenStatements_candidateKeepsItsItems() {
        var job = finished(false);

        age(job, Duration.ofDays(2));
        retention.deleteJobRows(job).await(PgFixture.WAIT).unwrap();
        retention.checkoutGone(TestJobs.HOST, job).await(PgFixture.WAIT).unwrap();
        var id = harness.count("SELECT id AS n FROM evidence WHERE job_id = $1", job.value());
        var row = harness.store.evidence().evidence(id).await(PgFixture.WAIT).unwrap().unwrap();
        var hooked = new PgRetentionStore(new BeforeItems(harness.raw, () -> harness.store.evidence().deleted(id, row.storedAt())));

        var listed = hooked.jobs(Instant.now().minus(Duration.ofDays(1)), none(), 100).await(PgFixture.WAIT).unwrap();

        assertThat(harness.count("SELECT count(*) AS n FROM evidence WHERE id = $1 AND deleted_at IS NOT NULL", id)).as("the mark committed")
                                                                                                                 .isEqualTo(1);
        assertThat(listed).extracting(JobRetention::job).containsExactly(job);
        assertThat(listed.getFirst().items()).extracting(EvidenceItem::id).containsExactly(id);
    }

    /// Retention deletes the file, a re-pull then writes it again and replaces the row (same id), and only then does
    /// retention mark the row: the mark for the planned `stored_at` leaves the row live with its new file.
    @Test
    void evidence_rePullAfterTheFileDelete_rowStaysLiveWithTheNewFile() throws IOException {
        var job = withFull();
        var planned = plannedFull(job);

        Files.delete(fullFile());
        rePull(job);
        harness.store.evidence().deleted(planned.id(), planned.storedAt()).await(PgFixture.WAIT).unwrap();

        assertThat(Files.exists(fullFile())).isTrue();
        assertThat(liveEvidence(job)).as("the new file has a live row").contains("full");
    }

    /// A re-pull between the plan and the file delete: the delete removes the new file and the mark leaves the row
    /// live, a row without a file. The next tick's candidate read lists it again with its new `stored_at`, and
    /// executing that tick's plan marks it deleted.
    @Test
    void evidence_rePullBetweenPlanAndFileDelete_nextPlanFinishesIt() throws IOException {
        var job = withFull();
        var planned = plannedFull(job);

        rePull(job);
        Files.delete(fullFile());
        harness.store.evidence().deleted(planned.id(), planned.storedAt()).await(PgFixture.WAIT).unwrap();

        assertThat(liveEvidence(job)).as("a live row without a file, until the next plan").contains("full");
        var rePulled = harness.store.evidence().evidence(planned.id()).await(PgFixture.WAIT).unwrap().unwrap().storedAt();

        age(job, Duration.ofDays(15));
        var now = Instant.now();
        var nextTick = RetentionPlan.plan(RetentionPolicy.DEFAULT, now, jobs(now, none(), 1000), none(), List.of())
                                    .stream()
                                    .filter(RetentionAction.DeleteEvidence.class::isInstance)
                                    .toList();

        assertThat(rePulled).isAfter(planned.storedAt());
        assertThat(nextTick).as("the next candidate read lists the row again, with its new stored_at")
                            .containsExactly(new RetentionAction.DeleteEvidence(planned.id(), rePulled));
        Files.deleteIfExists(fullFile());
        nextTick.forEach(action -> execute(action).await(PgFixture.WAIT).unwrap());
        assertThat(liveEvidence(job)).doesNotContain("full");
        assertThat(Files.exists(fullFile())).isFalse();
    }

    @Test
    void jobs_listsFinishedUnpinnedJobsWithSomethingLeft_untilNothingIsLeft() {
        var old = finished(false);
        var pinned = finished(true);
        var young = finished(false);

        harness.store.evidence().recorded(TestJobs.ref(old, "gate"), EvidenceKind.FULL, TestJobs.evidence(old, "gate", "full.tar.gz", 99))
               .await(PgFixture.WAIT)
               .unwrap();
        age(old, Duration.ofDays(2));
        age(pinned, Duration.ofDays(2));

        var listed = jobs(Instant.now().minus(Duration.ofDays(1)), none(), 100);

        assertThat(listed).extracting(JobRetention::job).as("not pinned, not too young (" + young + ")").containsExactly(old);
        var candidate = listed.getFirst();

        assertThat(candidate.keep()).isFalse();
        assertThat(candidate.terminal()).isTrue();
        assertThat(candidate.hasTestcases()).isTrue();
        assertThat(candidate.hostsWithCheckout()).containsExactly(TestJobs.HOST.value());
        assertThat(candidate.items()).extracting(item -> item.kind()).containsExactlyInAnyOrder(EvidenceKind.SMALL, EvidenceKind.FULL);

        retention.deleteJobRows(old).await(PgFixture.WAIT).unwrap();
        retention.checkoutGone(TestJobs.HOST, old).await(PgFixture.WAIT).unwrap();
        retention.checkoutGone(TestJobs.HOST, old).await(PgFixture.WAIT).unwrap();
        candidate.items().forEach(item -> harness.store.evidence().deleted(item.id(), item.storedAt()).await(PgFixture.WAIT).unwrap());

        assertThat(jobs(Instant.now().minus(Duration.ofDays(1)), none(), 100)).isEmpty();
        assertThat(count("evidence", old)).isEqualTo(2);
    }

    /// Events age by their own time, whatever job they belong to: a pinned job's old events go too (ruling M1-11).
    @Test
    void eventHorizon_includesPinnedJobsEvents_andDeleteRemovesThem() {
        var pinned = finished(true);
        var maxSeq = harness.count("SELECT max(seq) AS n FROM events");

        harness.execute("UPDATE events SET at = now() - interval '31 days'");
        var fresh = finished(false);
        var horizon = retention.eventHorizon(Instant.now().minus(Duration.ofDays(30))).await(PgFixture.WAIT).unwrap();

        assertThat(horizon).isEqualTo(some(maxSeq));
        retention.deleteEventsUpTo(maxSeq).await(PgFixture.WAIT).unwrap();
        assertThat(count("events", pinned)).isZero();
        assertThat(count("events", fresh)).isPositive();
        assertThat(retention.eventHorizon(Instant.now().minus(Duration.ofDays(30))).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
    }

    /// The plan over the store's candidates, executed against the store, per data class (Q-M1-11): small evidence and
    /// rows at 90 d, full at 14 d, the checkout at 24 h, events at 30 d (pinned included), nothing of a pinned job,
    /// and the newest dump always kept. Jobs are created in age order, so `seq` order matches event age as in
    /// production (events go by `seq` up to the horizon).
    @Test
    void plan_overStoreCandidates_appliesEachRetentionClass() {
        var now = Instant.now();
        var old = finished(false);
        var pinned = finished(true);
        var mid = finished(false);
        var young = finished(false);

        List.of(old, mid, young, pinned).forEach(this::addFull);
        age(old, Duration.ofDays(91));
        age(mid, Duration.ofDays(15));
        age(young, Duration.ofHours(1));
        age(pinned, Duration.ofDays(91));
        harness.execute("UPDATE events SET at = now() - interval '31 days' WHERE job_id = ANY($1::bigint[])",
                        (Object) new Long[]{old.value(), pinned.value()});
        var dumps = List.of(DumpFile.dumpFile("temper-old.dump", now.minus(Duration.ofDays(20))),
                            DumpFile.dumpFile("temper-newest.dump", now.minus(Duration.ofDays(16))));

        var actions = RetentionPlan.plan(RetentionPolicy.DEFAULT,
                                         now,
                                         jobs(now, none(), 1000),
                                         retention.eventHorizon(now.minus(RetentionPolicy.DEFAULT.events())).await(PgFixture.WAIT).unwrap(),
                                         dumps);

        assertThat(actions).filteredOn(RetentionAction.DeleteTestcases.class::isInstance)
                           .containsExactly(new RetentionAction.DeleteTestcases(old));
        assertThat(actions).filteredOn(RetentionAction.DeleteCheckout.class::isInstance)
                           .containsExactlyInAnyOrder(new RetentionAction.DeleteCheckout(TestJobs.HOST.value(), old),
                                                      new RetentionAction.DeleteCheckout(TestJobs.HOST.value(), mid));
        assertThat(actions).filteredOn(RetentionAction.DeletePgDump.class::isInstance)
                           .containsExactly(new RetentionAction.DeletePgDump("temper-old.dump"));
        assertThat(actions).filteredOn(RetentionAction.DeleteEventsUpTo.class::isInstance).hasSize(1);
        assertThat(actions).filteredOn(RetentionAction.DeleteEvidence.class::isInstance).hasSize(3);

        actions.forEach(action -> execute(action).await(PgFixture.WAIT).unwrap());

        assertThat(liveEvidence(old)).isEmpty();
        assertThat(liveEvidence(mid)).containsExactly("small");
        assertThat(liveEvidence(young)).containsExactlyInAnyOrder("small", "full");
        assertThat(liveEvidence(pinned)).containsExactlyInAnyOrder("small", "full");
        assertThat(count("testcases", old)).isZero();
        assertThat(count("testcases", mid)).isPositive();
        assertThat(count("testcases", pinned)).isPositive();
        assertThat(count("events", old)).isZero();
        assertThat(count("events", pinned)).isZero();
        assertThat(count("events", mid)).isPositive();
        assertThat(count("events", young)).isPositive();
        List.of(old, mid, young, pinned).forEach(job -> assertThat(count("results", job)).as("result of " + job).isEqualTo(1));
    }

    /// `deleteJobRows`, failing the test with the origin of a store failure (a write-once rejection names itself).
    private long deleted(JobId job) {
        return retention.deleteJobRows(job)
                        .await(PgFixture.WAIT)
                        .onFailure(cause -> fail(cause.message() + " <- " + cause.source().map(Cause::message).or("no origin")))
                        .unwrap();
    }

    /// Jobs that stay listed (a checkout the worker keeps) never hide the ones after them: the caller pages by id.
    @Test
    void jobs_pagedByIdCursor_reachesTheJobBehindStuckOnes() {
        var stuck = List.of(finished(false), finished(false));
        var collectable = finished(false);

        List.of(stuck.get(0), stuck.get(1), collectable).forEach(job -> age(job, Duration.ofDays(2)));
        List.of(stuck.get(0), stuck.get(1)).forEach(job -> retention.deleteJobRows(job).await(PgFixture.WAIT).unwrap());
        var cutoff = Instant.now().minus(Duration.ofDays(1));
        var first = jobs(cutoff, none(), 2);
        var second = jobs(cutoff, some(first.getLast().job()), 2);

        assertThat(first).extracting(JobRetention::job).containsExactlyElementsOf(stuck);
        assertThat(second).extracting(JobRetention::job).containsExactly(collectable);
        assertThat(second.getFirst().hasTestcases()).isTrue();
    }

    /// A requeue clears `units.host`, but the checkout an earlier attempt made on that host is still listed.
    @Test
    void jobs_checkoutOfARequeuedAttempt_staysListed() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", false)), false));
        var ref = TestJobs.ref(job, "gate");

        harness.grantHead();
        harness.store.units().requeue(TestJobs.HOST, ref, 1).await(PgFixture.WAIT).unwrap();
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        age(job, Duration.ofDays(2));

        var listed = jobs(Instant.now().minus(Duration.ofDays(1)), none(), 100);

        assertThat(listed).extracting(JobRetention::job).containsExactly(job);
        assertThat(listed.getFirst().hostsWithCheckout()).containsExactly(TestJobs.HOST.value());
    }

    /// More events than one step: every step deletes and raises the floor; the floor ends at `upTo`.
    @Test
    void deleteEventsUpTo_moreThanOneStep_deletesAll_floorAtUpTo() {
        harness.execute("INSERT INTO events (kind, payload) SELECT 'queued', '{}' FROM generate_series(1, $1::int)",
                        PgRetentionStore.BATCH * 2 + 3);
        var top = harness.count("SELECT max(seq) AS n FROM events");
        var before = harness.count("SELECT count(*) AS n FROM events");

        assertThat(retention.deleteEventsUpTo(top).await(PgFixture.WAIT).unwrap()).isEqualTo(before);
        assertThat(harness.count("SELECT count(*) AS n FROM events")).isZero();
        assertThat(harness.store.events().floor().await(PgFixture.WAIT).unwrap()).isEqualTo(top);
    }

    /// A step that fails keeps what the steps before it deleted, and the floor at the top of the last committed step:
    /// each step is bounded and its own transaction, and only the last step raises the floor to `upTo`.
    @Test
    void deleteEventsUpTo_stepFails_earlierStepsStayDeleted_floorAtTheirTop() {
        harness.execute("INSERT INTO events (kind, payload) SELECT 'queued', '{}' FROM generate_series(1, $1::int)",
                        PgRetentionStore.BATCH * 2 + 3);
        var top = harness.count("SELECT max(seq) AS n FROM events");
        var before = harness.count("SELECT count(*) AS n FROM events");
        var secondStepTop = harness.count("SELECT seq AS n FROM events ORDER BY seq OFFSET $1 LIMIT 1", PgRetentionStore.BATCH * 2 - 1);

        failDeletesAfter("events", PgRetentionStore.BATCH * 2);
        assertThat(retention.deleteEventsUpTo(top).await(PgFixture.WAIT).isFailure()).as("the third step fails").isTrue();
        assertThat(harness.count("SELECT count(*) AS n FROM events")).as("two bounded steps committed")
                                                                      .isEqualTo(before - PgRetentionStore.BATCH * 2);
        assertThat(harness.store.events().floor().await(PgFixture.WAIT).unwrap()).as("the floor never passes a stored event")
                                                                                 .isEqualTo(secondStepTop);
    }

    /// Every row deleted from `table` after the first `allowed` (counted across transactions by a sequence) fails.
    private void failDeletesAfter(String table, int allowed) {
        harness.execute("CREATE SEQUENCE deletes_seen");
        harness.execute("CREATE FUNCTION fail_late_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        + "IF nextval('deletes_seen') > " + allowed + " THEN RAISE EXCEPTION 'injected delete failure'; END IF; "
                        + "RETURN OLD; END $$");
        harness.execute("CREATE TRIGGER fail_late_delete BEFORE DELETE ON " + table + " FOR EACH ROW EXECUTE FUNCTION fail_late_delete()");
    }

    private List<JobRetention> jobs(Instant finishedBefore, Option<JobId> after, int limit) {
        return retention.jobs(finishedBefore, after, limit).await(PgFixture.WAIT).unwrap();
    }

    private Promise<Unit> execute(RetentionAction action) {
        return switch (action) {
            case RetentionAction.DeleteTestcases deleteRows -> retention.deleteJobRows(deleteRows.job()).mapToUnit();
            case RetentionAction.DeleteEvidence deleteEvidence -> harness.store.evidence().deleted(deleteEvidence.evidenceId(), deleteEvidence.storedAt());
            case RetentionAction.DeleteEventsUpTo deleteEvents -> retention.deleteEventsUpTo(deleteEvents.seq()).mapToUnit();
            case RetentionAction.DeleteCheckout _, RetentionAction.DeletePgDump _ -> Promise.unitPromise();
        };
    }

    /// A finished single-unit job with two testcases, a computed manifest of one class, start and end facts, and
    /// its small evidence.
    private JobId finished(boolean keep) {
        List<TestcaseRow> rows = List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED),
                                         TestJobs.row("gate", "a.ATest", "two", Outcome.PASSED));

        return harness.runWith(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", false)), keep),
                               "gate",
                               rows,
                               some(TestJobs.computed("a.ATest")),
                               true);
    }

    /// A finished job, collectable by age, whose full tarball is a real file under [#evidenceRoot], pulled when the
    /// job ended (its `stored_at` before `finished_at`, as in production; a re-pull's is after it).
    private JobId withFull() throws IOException {
        var job = finished(false);

        Files.writeString(fullFile(), "first pull");
        recordFull(job);
        age(job, Duration.ofDays(2));
        harness.execute("UPDATE evidence SET stored_at = now() - interval '3 days' WHERE job_id = $1 AND kind = 'full'", job.value());
        return job;
    }

    /// A later pull of the same tarball: the file is written again at the same path and the live row replaced.
    private void rePull(JobId job) throws IOException {
        Files.writeString(fullFile(), "second pull");
        recordFull(job);
    }

    private void recordFull(JobId job) {
        harness.store.evidence()
               .recorded(TestJobs.ref(job, "gate"), EvidenceKind.FULL, StoredEvidence.storedEvidence(fullFile(), 10, TestJobs.randomHex(64)))
               .await(PgFixture.WAIT)
               .unwrap();
    }

    /// The full tarball's item as the retention read lists it.
    private EvidenceItem plannedFull(JobId job) {
        return jobs(Instant.now().minus(Duration.ofDays(1)), none(), 100).stream()
                                                                         .filter(candidate -> candidate.job().equals(job))
                                                                         .flatMap(candidate -> candidate.items().stream())
                                                                         .filter(item -> item.kind() == EvidenceKind.FULL)
                                                                         .findFirst()
                                                                         .orElseThrow();
    }

    private Path fullFile() {
        return evidenceRoot.resolve("full.tar.gz");
    }

    private void addFull(JobId job) {
        harness.store.evidence()
               .recorded(TestJobs.ref(job, "gate"), EvidenceKind.FULL, TestJobs.evidence(job, "gate", "full.tar.gz", 99))
               .await(PgFixture.WAIT)
               .unwrap();
    }

    private void age(JobId job, Duration age) {
        harness.execute("UPDATE jobs SET finished_at = now() - make_interval(secs => $2) WHERE id = $1", job.value(),
                        (double) age.toSeconds());
    }

    /// testcases, manifests, manifest_units, unit_facts.
    private List<Long> rowCounts(JobId job) {
        return List.of(count("testcases", job),
                       count("manifests", job),
                       harness.count("SELECT count(*) AS n FROM manifest_units m JOIN units u ON u.id = m.unit_id WHERE u.job_id = $1",
                                     job.value()),
                       harness.count("SELECT count(*) AS n FROM unit_facts f JOIN units u ON u.id = f.unit_id WHERE u.job_id = $1",
                                     job.value()));
    }

    private List<String> liveEvidence(JobId job) {
        return Sql.rows(harness.raw.executor()
                                   .completeQuery("SELECT kind FROM evidence WHERE job_id = $1 AND deleted_at IS NULL", job.value())
                                   .await(PgFixture.WAIT)
                                   .unwrap())
                  .stream()
                  .map(row -> row.getString("kind"))
                  .toList();
    }

    private long count(String table, JobId job) {
        var column = table.equals("jobs") ? "id" : "job_id";

        return harness.count("SELECT count(*) AS n FROM " + table + " WHERE " + column + " = $1", job.value());
    }

    private ResultRecordDto record(JobId job) {
        return harness.store.results().result(job).await(PgFixture.WAIT).unwrap().map(WireRecords::resultRecordDto).unwrap();
    }

    /// `db`, whose transactions run `before` on another connection, to commit, ahead of their evidence query.
    private record BeforeItems(Db db, Supplier<Promise<Unit>> before) implements Db {
        @Override
        public <T> Promise<T> inTransaction(Fn1<Promise<T>, Transaction> work) {
            return db.inTransaction(tx -> work.apply(new Hooked(tx, before)));
        }

        @Override
        public <T> Promise<T> withConnection(Fn1<Promise<T>, Connection> work) {
            return db.withConnection(work);
        }

        @Override
        public QueryExecutor executor() {
            return db.executor();
        }

        @Override
        public LockSessions locks() {
            return db.locks();
        }

        @Override
        public Promise<Unit> close() {
            return db.close();
        }
    }

    private record Hooked(Transaction tx, Supplier<Promise<Unit>> before) implements Transaction {
        @Override
        public Promise<Integer> query(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                      Consumer<PgRow> onRow,
                                      String sql,
                                      Object... params) {
            return sql.contains("FROM evidence e JOIN units u")
                   ? before.get().flatMap(_ -> tx.query(onColumns, onRow, sql, params))
                   : tx.query(onColumns, onRow, sql, params);
        }

        @Override
        public Promise<Unit> script(BiConsumer<Map<String, PgColumn>, PgColumn[]> onColumns,
                                    Consumer<PgRow> onRow,
                                    Consumer<Integer> onAffected,
                                    String sql) {
            return tx.script(onColumns, onRow, onAffected, sql);
        }

        @Override
        public Promise<Unit> commit() {
            return tx.commit();
        }

        @Override
        public Promise<Unit> rollback() {
            return tx.rollback();
        }

        @Override
        public Promise<Unit> close() {
            return tx.close();
        }

        @Override
        public Connection getConnection() {
            return tx.getConnection();
        }

        @Override
        public Promise<Transaction> begin() {
            return tx.begin();
        }
    }
}
