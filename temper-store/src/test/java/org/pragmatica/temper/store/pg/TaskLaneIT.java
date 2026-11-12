package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.ReleaseReason;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.store.api.Checkpoint;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskReport;
import org.pragmatica.temper.store.api.TaskStore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The lane registry's store (V20): checkpoints are versioned per task and fenced by the lease; a heartbeat stores its
/// tree report in its own transaction; a claim restarts the heartbeat clock and hands over the predecessor's last
/// report and the latest checkpoint; `observe` writes only the observed columns.
@Tag("pg")
class TaskLaneIT {
    private static final String SHA = "c".repeat(40);
    private static StoreHarness harness;
    private static TaskStore tasks;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
        tasks = harness.store.tasks();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private static Checkpoint.New body(String note) {
        return new Checkpoint.New(SHA,
                                  List.of(new Checkpoint.Item("a", true)),
                                  List.of(new Checkpoint.Entry("f.md", "1-2")),
                                  note);
    }

    private static Lease leased(String label) {
        var row = TaskFixture.inCode(tasks, TaskFixture.T0);

        return TaskFixture.claim(tasks,
                                 row.id(),
                                 label,
                                 TaskFixture.T0)
                          .unwrap();
    }

    private static Checkpoint checkpoint(Lease lease, String note) {
        return tasks.checkpoint(lease,
                                body(note),
                                TaskFixture.T0.plusSeconds(5))
                    .await(PgFixture.WAIT)
                    .unwrap();
    }

    /// Mutation: take `lease_epoch = $2` out of `INSERT_CHECKPOINT`'s `held` → the stale-epoch case stores a row.
    @Test
    void checkpoint_isFencedByEpochAndToken_andStoresNothingWhenRefused() {
        var lease = leased("lane-fence");
        var before = harness.count("SELECT count(*) AS n FROM task_checkpoint WHERE task_id = " + lease.task().value());
        var stale = tasks.checkpoint(Lease.lease(lease.task(),
                                                 lease.epoch() - 1,
                                                 lease.tokenHash()),
                                     body("x"),
                                     TaskFixture.T0)
                         .await(PgFixture.WAIT);
        var otherToken = tasks.checkpoint(Lease.lease(lease.task(),
                                                      lease.epoch(),
                                                      TaskFixture.hash("another")),
                                          body("x"),
                                          TaskFixture.T0)
                              .await(PgFixture.WAIT);

        stale.onSuccess(stored -> org.junit.jupiter.api.Assertions.fail("stored: " + stored))
             .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.LEASE_LOST));
        otherToken.onSuccess(stored -> org.junit.jupiter.api.Assertions.fail("stored: " + stored))
                  .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.LEASE_LOST));
        assertThat(harness.count("SELECT count(*) AS n FROM task_checkpoint WHERE task_id = " + lease.task().value())).isEqualTo(before);
    }

    @Test
    void checkpoint_afterTheLeaseEnded_isLeaseLost() {
        var lease = leased("lane-ended");

        tasks.leased(lease,
                     new Command.Release(ReleaseReason.releaseReason("handoff").unwrap()),
                     TaskFixture.T0.plusSeconds(1))
             .await(PgFixture.WAIT)
             .unwrap();
        tasks.checkpoint(lease,
                         body("zombie"),
                         TaskFixture.T0.plusSeconds(2))
             .await(PgFixture.WAIT)
             .onSuccess(stored -> org.junit.jupiter.api.Assertions.fail("stored: " + stored))
             .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.LEASE_LOST));
    }

    /// Mutation: drop `FOR UPDATE` from the `held` CTE → concurrent checkpoints read the same max(seq) and one fails on the key.
    @Test
    void checkpoint_versionsAreConsecutive_underConcurrentWriters() {
        var lease = leased("lane-seq");
        var writers = IntStream.range(0, 8)
                               .mapToObj(i -> CompletableFuture.supplyAsync(() -> tasks.checkpoint(lease,
                                                                                                   body("n" + i),
                                                                                                   TaskFixture.T0.plusSeconds(10 + i))
                                                                                       .await(PgFixture.WAIT)))
                               .toList();
        var seqs = writers.stream().map(CompletableFuture::join).map(result -> result.unwrap()
                                                                                     .seq()).sorted().toList();

        assertThat(seqs).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(tasks.latestCheckpoint(lease.task()).await(PgFixture.WAIT).unwrap().unwrap().seq()).isEqualTo(8);
    }

    @Test
    void latestCheckpoint_roundTripsItsDocuments_andNoneForATaskWithout() {
        var lease = leased("lane-round");
        var stored = checkpoint(lease, "the note");

        assertThat(stored.seq()).isEqualTo(1);
        assertThat(stored.epoch()).isEqualTo(lease.epoch());
        assertThat(tasks.latestCheckpoint(lease.task()).await(PgFixture.WAIT).unwrap().unwrap()).isEqualTo(stored);
        assertThat(tasks.latestCheckpoint(TaskFixture.created(tasks, TaskFixture.T0).id())
                        .await(PgFixture.WAIT)
                        .unwrap()
                        .isEmpty()).isTrue();
    }

    /// Mutation: write the report before the fence (outside the transaction) → the refused heartbeat leaves a report.
    @Test
    void heartbeat_storesItsTree_aRefusedOneStoresNothing_andTheFoldStillHolds() {
        var lease = leased("lane-hb");
        var tree = some(new TaskReport.Tree(SHA, 3, 2));
        var at = TaskFixture.T0.plusSeconds(30);

        tasks.heartbeat(Lease.lease(lease.task(),
                                    lease.epoch() - 1,
                                    lease.tokenHash()),
                        tree,
                        at)
             .await(PgFixture.WAIT)
             .onSuccess(row -> org.junit.jupiter.api.Assertions.fail("accepted: " + row))
             .onFailure(cause -> assertThat(cause).isEqualTo(TaskError.General.LEASE_LOST));
        assertThat(tasks.reports(List.of(lease.task()))
                        .await(PgFixture.WAIT)
                        .unwrap()
                        .get(lease.task())
                        .reported()
                        .isEmpty()).as("claim only")
                  .isTrue();
        tasks.heartbeat(lease, tree, at).await(PgFixture.WAIT).unwrap();
        var report = tasks.reports(List.of(lease.task())).await(PgFixture.WAIT).unwrap().get(lease.task());

        assertThat(report.lastHbAt().unwrap()).isEqualTo(at);
        assertThat(report.reported().unwrap()).isEqualTo(new TaskReport.Reported(SHA, 3, 2, lease.epoch(), at));
        TaskFixture.assertFoldEqualsRow(tasks, lease.task(), "after a heartbeat with a report");
        tasks.heartbeat(lease, none(), at.plusSeconds(60)).await(PgFixture.WAIT).unwrap();
        var bare = tasks.reports(List.of(lease.task())).await(PgFixture.WAIT).unwrap().get(lease.task());

        assertThat(bare.lastHbAt().unwrap()).isEqualTo(at.plusSeconds(60));
        assertThat(bare.reported().unwrap().dirty()).as("a bare heartbeat keeps the last report").isEqualTo(3);
    }

    /// The claim hands the successor the predecessor's last report and the latest checkpoint, and restarts the heartbeat
    /// clock. Mutation: have `TaskLane.claimed` reset the report columns → `predecessor.reported()` is empty.
    @Test
    void claim_returnsThePredecessorsReportAndLatestCheckpoint_andRestartsTheClock() {
        var first = leased("lane-claim-1");
        var heartbeatAt = TaskFixture.T0.plusSeconds(100);

        tasks.heartbeat(first, some(new TaskReport.Tree(SHA, 0, 5)), heartbeatAt).await(PgFixture.WAIT).unwrap();
        var stored = checkpoint(first, "handoff");

        tasks.leased(first,
                     new Command.Release(ReleaseReason.releaseReason("handoff").unwrap()),
                     heartbeatAt.plusSeconds(1))
             .await(PgFixture.WAIT)
             .unwrap();
        var claimAt = heartbeatAt.plus(Duration.ofMinutes(3));
        var second = tasks.claim(first.task(),
                                 ClaimGrant.claimGrant(TaskFixture.hash("lane-claim-2"),
                                                       "w2",
                                                       some(TaskFixture.BASE_TIP),
                                                       none()),
                                 claimAt)
                          .await(PgFixture.WAIT)
                          .unwrap();
        var after = tasks.reports(List.of(first.task())).await(PgFixture.WAIT).unwrap().get(first.task());

        assertThat(second.predecessor().reported().unwrap().unpushed()).isEqualTo(5);
        assertThat(second.predecessor().reported().unwrap().epoch()).isEqualTo(first.epoch());
        assertThat(second.checkpoint().unwrap()).isEqualTo(stored);
        assertThat(after.lastHbAt().unwrap()).as("the claim restarts the heartbeat clock").isEqualTo(claimAt);
        assertThat(after.reported().unwrap().unpushed()).as("the predecessor's report stays until a heartbeat replaces it")
                  .isEqualTo(5);
    }

    @Test
    void observe_writesOnlyTheObservedColumns_inAnyOrderWithHeartbeats_andAnUnknownTaskIs404() {
        var lease = leased("lane-observe");
        var at = TaskFixture.T0.plusSeconds(200);

        tasks.observe(lease.task(), 150, 140, at).await(PgFixture.WAIT).unwrap();
        tasks.heartbeat(lease, some(new TaskReport.Tree(SHA, 1, 0)), at.plusSeconds(1)).await(PgFixture.WAIT).unwrap();
        var both = tasks.reports(List.of(lease.task())).await(PgFixture.WAIT).unwrap().get(lease.task());

        assertThat(both.observed().unwrap()).isEqualTo(new TaskReport.Observed(150, 140, at));
        assertThat(both.reported().unwrap().dirty()).isEqualTo(1);
        tasks.observe(lease.task(), 10, 9, at.plusSeconds(2)).await(PgFixture.WAIT).unwrap();
        assertThat(tasks.reports(List.of(lease.task()))
                        .await(PgFixture.WAIT)
                        .unwrap()
                        .get(lease.task())
                        .reported()
                        .unwrap()
                        .dirty()).as("observe leaves the report")
                  .isEqualTo(1);
        var missing = TaskId.taskId(987_654_321).unwrap();

        tasks.observe(missing, 1, 1, at)
             .await(PgFixture.WAIT)
             .onSuccess(unit -> org.junit.jupiter.api.Assertions.fail("observed a task that does not exist"))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.TaskNotFound.FACTORY.apply(missing)));
    }

    @Test
    void reports_ofNoIds_isEmpty_andOfATaskWithoutARow_isAbsent() {
        var untouched = TaskFixture.created(tasks, TaskFixture.T0);

        assertThat(tasks.reports(List.of()).await(PgFixture.WAIT).unwrap()).isEmpty();
        assertThat(tasks.reports(List.of(untouched.id())).await(PgFixture.WAIT).unwrap()).doesNotContainKey(untouched.id());
    }
}
