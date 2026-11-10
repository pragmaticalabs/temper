package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.known.KnownEntry;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.KnownRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.UnitFinishRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// m2-design §4.5 `KnownFinishIT`: KNOWN marks are applied in `Finishing.finish`, inside the completing transaction.
///
/// Guarantee pinned (ruling R-M2-14): a record carries KNOWN for exactly the entries active at the known read, one
/// statement inside the completing transaction, after the job lock; the verdict is never changed; the record and its
/// event carry the same list. `theReadIsTheCompletingTransactions…` is the pin of lock-then-read: a read before the
/// lock, or outside the transaction, goes red there.
///
/// - an entry active at finish marks `failed[].known` and adds `KNOWN(#n)`, and the `finished` event carries it; the
///   verdict stays FAILED even when every failure is known (the bold probe: let KNOWN change the verdict → red);
/// - VOID, ERROR and CANCELLED records are marked as well (their `failed` lists the observed reds), and so is the
///   `cancelled` event;
/// - an expired entry marks nothing; an entry added after the finish leaves the written record as it was;
/// - the read is the completing transaction's: a fixture holds the job's row lock so the completion waits, then commits
///   an add and an expiry; the record shows the state after both (probe: read `known` outside the completing
///   transaction, before it waits → red).
@Tag("pg")
class KnownFinishIT {
    private static final String LOCK_WAIT = "SELECT count(*) AS n FROM pg_stat_activity WHERE datname = current_database() "
                                            + "AND wait_event_type = 'Lock' AND query LIKE '%FROM jobs WHERE id = $1 FOR NO KEY UPDATE%'";
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
    void activeAtFinish_marksTheCoveredRows_andAnnotatesEachIssueOnce_verdictStaysFailed() {
        add("k1.ATest#one", 1679);
        add("k1.BTest", 21);
        var rows = List.of(TestJobs.row("k1.ATest", "one", Outcome.FAILED),
                           TestJobs.row("k1.ATest", "two", Outcome.PASSED),
                           TestJobs.row("k1.BTest", "x", Outcome.ERROR),
                           TestJobs.row("k1.BTest", "y", Outcome.FAILED),
                           TestJobs.row("k1.CTest", "z", Outcome.FAILED));
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false), TestJobs.exited(1), rows);
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("FAILED");
        assertThat(record.failed()).containsExactlyInAnyOrder(ResultRecord.Failed.failed("k1.ATest", "one", some("#1679")),
                                                              ResultRecord.Failed.failed("k1.BTest", "x", some("#21")),
                                                              ResultRecord.Failed.failed("k1.BTest", "y", some("#21")),
                                                              ResultRecord.Failed.failed("k1.CTest", "z", none()));
        assertThat(record.annotations()).containsSubsequence("KNOWN(#21)", "KNOWN(#1679)");
        assertThat(record.annotations()).filteredOn(annotation -> annotation.startsWith("KNOWN")).hasSize(2);
        assertThat(finishedEvent(job).known()).containsExactly("KNOWN(#21)", "KNOWN(#1679)");
        assertThat(finishedEvent(job).known()).as("the event carries the record's KNOWN list")
                                               .isEqualTo(record.annotations().stream().filter(annotation -> annotation.startsWith("KNOWN(")).toList());
        assertThat(finishedEvent(job).verdict()).isEqualTo(some("FAILED"));
    }

    /// The bold probe of §4.5: every failure is known, and the job is still FAILED everywhere a verdict is stored.
    @Test
    void everyFailureKnown_verdictStaysFailed_inTheRecordTheResultRowTheJobAndTheEvent() {
        add("k2.ATest", 7);
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(1),
                              List.of(TestJobs.row("k2.ATest", "one", Outcome.FAILED), TestJobs.row("k2.ATest", "two", Outcome.PASSED)));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("FAILED");
        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("k2.ATest", "one", some("#7")));
        assertThat(record.annotations()).contains("KNOWN(#7)");
        assertThat(text("SELECT verdict AS v FROM results WHERE job_id = $1", job.value())).isEqualTo("FAILED");
        assertThat(text("SELECT state AS v FROM jobs WHERE id = $1", job.value())).isEqualTo("FAILED");
        assertThat(finishedEvent(job).verdict()).isEqualTo(some("FAILED"));
        assertThat(finishedEvent(job).known()).containsExactly("KNOWN(#7)");
    }

    /// VOID and ERROR never hide reds (spec §5.3): their observed failures are marked as well.
    @Test
    void errorRecord_marksItsObservedFailures() {
        add("k3.ATest", 8);
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.lost(),
                              List.of(TestJobs.row("k3.ATest", "one", Outcome.FAILED)));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("ERROR");
        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("k3.ATest", "one", some("#8")));
        assertThat(record.annotations()).contains("KNOWN(#8)");
    }

    @Test
    void voidRecord_marksItsObservedFailures() {
        add("k7.ATest", 13);
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              UnitFinishRow.unitFinishRow(some(1),
                                                          none(),
                                                          Instant.parse("2026-10-13T10:31:00Z"),
                                                          none(),
                                                          1,
                                                          some("disk-floor"),
                                                          none()),
                              List.of(TestJobs.row("k7.ATest", "one", Outcome.FAILED)));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("VOID");
        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("k7.ATest", "one", some("#13")));
        assertThat(record.annotations()).contains("KNOWN(#13)");
        assertThat(finishedEvent(job).known()).containsExactly("KNOWN(#13)");
    }

    /// A running job cancelled finishes through the same transaction; its record and its `cancelled` event are marked.
    @Test
    void cancelledRecord_andItsCancelledEvent_areMarked() {
        add("k8.ATest", 14);
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        harness.start(job);
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        harness.complete(job, TestJobs.killed(KillCause.CANCEL), List.of(TestJobs.row("k8.ATest", "one", Outcome.FAILED)));
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("CANCELLED");
        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("k8.ATest", "one", some("#14")));
        assertThat(record.annotations()).contains("KNOWN(#14)");
        assertThat(event(job, "cancelled").known()).containsExactly("KNOWN(#14)");
        assertThat(event(job, "cancelled").verdict()).isEqualTo(some("CANCELLED"));
    }

    /// The other finishing path: a cancel with nothing active (`Cancellation.cancelNow`) writes the record at once. A
    /// two-unit job whose first unit ended red and whose second is still queued is cancelled; its record lists the red
    /// row, marked, and so does the `cancelled` event.
    @Test
    void cancelNow_marksTheRedsOfTheUnitsThatRan() {
        add("k10.ATest", 16);
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.unit("first"), TestJobs.unit("second")), false));
        var granted = harness.grantHead().getFirst().ref();

        harness.store.units().started(TestJobs.HOST, granted, 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        harness.store.units()
                     .complete(TestJobs.HOST, granted, 1,
                               TestJobs.exited(1),
                               List.of(TestJobs.row(granted.unit().value(), "k10.ATest", "one", Outcome.FAILED)),
                               none(),
                               none(),
                               TestJobs::evaluate)
                     .await(PgFixture.WAIT)
                     .unwrap();
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        var record = record(job);

        assertThat(record.verdict()).isEqualTo("CANCELLED");
        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("k10.ATest", "one", some("#16")));
        assertThat(record.annotations()).contains("KNOWN(#16)");
        assertThat(event(job, "cancelled").known()).containsExactly("KNOWN(#16)");
    }

    @Test
    void expiredEntry_marksNothing() {
        expire(add("k4.ATest", 9));
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(1),
                              List.of(TestJobs.row("k4.ATest", "one", Outcome.FAILED)));
        var record = record(job);

        assertThat(record.failed()).containsExactly(ResultRecord.Failed.failed("k4.ATest", "one", none()));
        assertThat(record.annotations()).noneMatch(annotation -> annotation.startsWith("KNOWN"));
        assertThat(finishedEvent(job).known()).isEmpty();
    }

    @Test
    void entryAddedAfterFinish_leavesTheRecordAsWritten() {
        var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                              TestJobs.exited(1),
                              List.of(TestJobs.row("k5.ATest", "one", Outcome.FAILED)));
        var before = harness.store.results().recordJson(job).await(PgFixture.WAIT).unwrap();

        add("k5.ATest", 10);

        assertThat(harness.store.results().recordJson(job).await(PgFixture.WAIT).unwrap()).isEqualTo(before);
        assertThat(record(job).annotations()).noneMatch(annotation -> annotation.startsWith("KNOWN"));
        assertThat(record(job).failed()).containsExactly(ResultRecord.Failed.failed("k5.ATest", "one", none()));
    }

    /// The probe's fixture (§4.5): the completion waits on the job's row lock; meanwhile an add of B and an expiry of A
    /// commit. A read inside the completing transaction (after its lock) sees B active and A expired; a read before the
    /// transaction, or before its lock, sees the opposite. The wait is confirmed in `pg_stat_activity` before anything
    /// commits, so the instrument cannot pass by finishing first.
    @Test
    void theReadIsTheCompletingTransactions_aChangeCommittedWhileItWaitsIsSeen() {
        var expiredMeanwhile = add("k6.ATest", 11);
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        harness.start(job);

        var locked = Promise.<Unit> promise();
        var release = Promise.<Unit> promise();
        var holder = harness.raw.inTransaction(tx -> holdJobLock(tx, job, locked, release));

        locked.await(PgFixture.WAIT).unwrap();
        var completing = harness.store.units()
                                      .complete(TestJobs.HOST, TestJobs.main(job), 1,
                                                TestJobs.exited(1),
                                                List.of(TestJobs.row("k6.ATest", "one", Outcome.FAILED),
                                                        TestJobs.row("k6.BTest", "one", Outcome.FAILED)),
                                                none(),
                                                none(),
                                                TestJobs::evaluate);

        awaitLockWait();
        assertThat(completing.isResolved()).as("the completion waits for the job lock").isFalse();
        add("k6.BTest", 12);
        expire(expiredMeanwhile);
        release.succeed(unit());
        holder.await(PgFixture.WAIT).unwrap();

        assertThat(completing.await(PgFixture.WAIT).unwrap()).isInstanceOf(CompleteOutcome.Completed.class);
        var record = record(job);

        assertThat(record.failed()).containsExactlyInAnyOrder(ResultRecord.Failed.failed("k6.ATest", "one", none()),
                                                              ResultRecord.Failed.failed("k6.BTest", "one", some("#12")));
        assertThat(record.annotations()).contains("KNOWN(#12)").doesNotContain("KNOWN(#11)");
        assertThat(finishedEvent(job).known()).as("the event carries the same read").containsExactly("KNOWN(#12)");
    }

    /// The KNOWN annotations follow the evaluation's own (here `superseded`, a `keep` job that ran on after its ref
    /// moved) [G]: the record lists what the verdict said before what the registry adds.
    @Test
    void knownAnnotations_followTheEvaluationsOwn() {
        add("k9.ATest", 15);
        var job = harness.submit(TestJobs.byRef("known/k9", TestJobs.sha(), true, 0));

        harness.grantHead();
        harness.start(job);
        var superseding = harness.submit(TestJobs.byRef("known/k9", TestJobs.sha(), false, 0));
        harness.complete(job, TestJobs.exited(1), List.of(TestJobs.row("k9.ATest", "one", Outcome.FAILED)));
        harness.store.jobs().requestCancel(superseding).await(PgFixture.WAIT).unwrap();  // leaves the queue to the next test

        assertThat(record(job).annotations()).startsWith("superseded", "KNOWN(#15)");
    }

    private static Promise<Unit> holdJobLock(Transaction tx, JobId job, Promise<Unit> locked, Promise<Unit> release) {
        return tx.completeQuery("SELECT id FROM jobs WHERE id = $1 FOR UPDATE", job.value())
                 .withSuccess(_ -> locked.succeed(unit()))
                 .flatMap(_ -> release);
    }

    /// Until the completion's job lock (`JobLock`'s `FOR NO KEY UPDATE` on `jobs`) waits in this database, polling every
    /// 5 ms, at most the wait bound. Matching the statement keeps any other lock wait from passing for it.
    private static void awaitLockWait() {
        var deadline = System.nanoTime() + PgFixture.WAIT.duration().toNanos();

        while (harness.count(LOCK_WAIT) == 0) {
            assertThat(System.nanoTime()).as("a completion waiting on the job lock").isLessThan(deadline);
            LockSupport.parkNanos(5_000_000L);
        }
    }

    private static KnownRow add(String testId, int issue) {
        return harness.store.known()
                            .add(KnownEntry.knownEntry(testId, issue, "flaky", "#" + issue + " closes", "cto").unwrap())
                            .await(PgFixture.WAIT)
                            .unwrap();
    }

    private static void expire(KnownRow row) {
        harness.store.known().expire(row.id()).await(PgFixture.WAIT).unwrap();
    }

    private static ResultRecord record(JobId job) {
        return harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap();
    }

    /// The `finished` event's payload, read back through the store as `watch` and `digest` read it.
    private static EventPayload finishedEvent(JobId job) {
        return event(job, "finished");
    }

    private static EventPayload event(JobId job, String kind) {
        return harness.store.events()
                            .after(0, Set.of(kind), 10_000)
                            .await(PgFixture.WAIT)
                            .unwrap()
                            .stream()
                            .filter(event -> event.job().equals(some(job)))
                            .findFirst()
                            .orElseThrow()
                            .payload();
    }

    private static String text(String sql, Object... params) {
        return harness.raw.executor().completeQuery(sql, params).await(PgFixture.WAIT).unwrap().index(0).getString("v");
    }
}
