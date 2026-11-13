package org.pragmatica.temper.controller.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.Audit;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Counters;
import org.pragmatica.temper.domain.task.EvidenceOutcome;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.LeaseState;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskBrief;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.ClaimedTask;
import org.pragmatica.temper.store.api.NewEvidence;
import org.pragmatica.temper.store.api.SweepOutcome;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskQuery;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.Checkpoint;
import org.pragmatica.temper.store.api.TaskReport;
import org.pragmatica.temper.store.api.TaskStore;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// Review #5: the claim answer, the token included, comes from the claim's own result. A store whose separate
/// `detail()` read fails after the claim committed must not lose the answer (the lease would be held with no token
/// anyone knows).
///
/// Probe: build the answer from a separate `detail()` read after the claim: `claim_answers_evenWhenASeparateDetailReadFails`
/// goes red.
class ClaimTaskTest {
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final TaskId ID = TaskId.taskId(42).unwrap();

    private static TaskRow row(TaskState state) {
        return TaskRow.taskRow(ID,
                               "s",
                               "T",
                               List.of(),
                               TaskBrief.taskBrief("b.md", none(), false, Map.of()).unwrap(),
                               "a/b",
                               Ref.ref("main").unwrap(),
                               Ref.ref("agent/s").unwrap(),
                               false,
                               0,
                               List.of(),
                               OwnerTag.ownerTag("cto").unwrap(),
                               NOW,
                               state);
    }

    /// A store whose claim succeeds with its answer, and whose separate detail read always fails.
    private static final class Store implements TaskStore {
        final AtomicInteger detailReads = new AtomicInteger();

        final TaskRow ready = row(TaskState.taskState(Stage.CODE,
                                                      Status.READY,
                                                      none(),
                                                      Counters.counters(0, 3, 0, 1, 0, 0),
                                                      LeaseState.NONE,
                                                      none(),
                                                      none(),
                                                      some(NOW),
                                                      Waiting.NOTHING,
                                                      Audit.NONE,
                                                      none()));

        static final TaskDetail DETAIL = TaskDetail.taskDetail(List.of(), List.of(), List.of(7L), List.of());

        volatile ClaimedTask granted = ClaimedTask.claimedTask(ready, DETAIL);

        @Override
        public Promise<ClaimedTask> claim(TaskId id, ClaimGrant grant, Instant now) {
            return Promise.success(granted);
        }

        @Override
        public Promise<TaskDetail> detail(TaskId id) {
            detailReads.incrementAndGet();

            return Causes.cause("the connection dropped after the commit").promise();
        }

        @Override
        public Promise<Option<TaskRow>> task(TaskId id) {
            return Promise.success(some(ready));
        }

        @Override
        public Promise<List<TaskRow>> ready(Option<Stage> stage, int limit) {
            return Promise.success(List.of(ready));
        }

        @Override
        public Promise<TaskRow> create(NewTask task, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<List<TaskRow>> list(TaskQuery query) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<List<TaskEventRow>> events(TaskId id) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Unit> holds(Lease lease) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<TaskRow> leased(Lease lease, Command command, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<TaskRow> evidence(Lease lease, NewEvidence evidence, EvidenceOutcome outcome, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<TaskRow> command(TaskId id, Command command, String actor, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<SweepOutcome> sweep(Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<List<TaskId>> restarted(Instant start) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<TaskRow> heartbeat(Lease lease, Option<TaskReport.Tree> tree, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Checkpoint> checkpoint(Lease lease, Checkpoint.New checkpoint, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Option<Checkpoint>> latestCheckpoint(TaskId id) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Map<TaskId, TaskReport>> reports(List<TaskId> ids) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Unit> observe(TaskId id, int ctxK, int turns, Instant now) {
            return Causes.cause("unused").promise();
        }
    }

    private static final Mirror MIRROR = new Mirror() {
        @Override
        public Promise<Unit> fetch() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Boolean> containedInBranch(Sha sha) {
            return Promise.success(true);
        }

        @Override
        public Promise<List<PullNumber>> pullHeadsContaining(Sha sha) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Option<Sha>> branchTip(Ref branch) {
            return Promise.success(Sha.sha("b".repeat(40)).option());
        }

        @Override
        public Promise<Boolean> branchContains(Ref branch, Sha sha) {
            return Promise.success(true);
        }
    };

    @Test
    void claim_answers_evenWhenASeparateDetailReadFails() {
        var store = new Store();
        var claim = ClaimTask.claimTask(ClaimTask.Steps.steps(store,
                                                              MIRROR,
                                                              () -> Result.success(ClaimTask.Minted.minted("the-token",
                                                                                                           "0".repeat(64))),
                                                              "git@example:a/b.git",
                                                              () -> NOW));
        var answer = claim.execute(ClaimTask.Request.directed(ID, "w")).await();

        assertThat(answer.isSuccess()).as(answer.toString()).isTrue();
        assertThat(answer.unwrap().unwrap().token()).isEqualTo("the-token");
        assertThat(answer.unwrap().unwrap().detail().evidence()).containsExactly(7L);
        assertThat(store.detailReads.get()).isZero();
    }

    private static ClaimTask claimWith(Store store) {
        return ClaimTask.claimTask(ClaimTask.Steps.steps(store,
                                                         MIRROR,
                                                         () -> Result.success(ClaimTask.Minted.minted("the-token",
                                                                                                      "0".repeat(64))),
                                                         "git@example:a/b.git",
                                                         () -> NOW));
    }

    private static TaskReport reported(int dirty, int unpushed) {
        return new TaskReport(some(NOW),
                              some(new TaskReport.Reported("a".repeat(40), dirty, unpushed, 1, NOW)),
                              none());
    }

    /// Mutation: drop the `isDirty` filter in `Claimed.claimed` (warn on any report) → the clean case warns.
    @Test
    void claim_warnsDirtyPredecessor_forDirtyFilesOrUnpushedCommits_notForACleanOrAbsentReport() {
        var store = new Store();
        var cases = List.of(Map.entry("dirty files", reported(2, 0)),
                            Map.entry("unpushed commits", reported(0, 1)),
                            Map.entry("both", reported(3, 3)));

        cases.forEach(entry -> {
            store.granted = ClaimedTask.claimedTask(store.ready, Store.DETAIL, none(), entry.getValue());
            assertThat(claimWith(store).execute(ClaimTask.Request.directed(ID, "w"))
                                .await()
                                .unwrap()
                                .unwrap()
                                .warnings()).as(entry.getKey())
                      .containsExactly(ClaimTask.DIRTY_PREDECESSOR);
        });
        store.granted = ClaimedTask.claimedTask(store.ready, Store.DETAIL, none(), reported(0, 0));
        assertThat(claimWith(store).execute(ClaimTask.Request.directed(ID, "w")).await().unwrap().unwrap().warnings()).as("clean")
                  .isEmpty();
        store.granted = ClaimedTask.claimedTask(store.ready, Store.DETAIL);
        assertThat(claimWith(store).execute(ClaimTask.Request.directed(ID, "w")).await().unwrap().unwrap().warnings()).as("no report")
                  .isEmpty();
    }

    @Test
    void claim_carriesTheLatestCheckpointFromTheStore() {
        var store = new Store();
        var checkpoint = new Checkpoint(3,
                                        1,
                                        "c".repeat(40),
                                        List.of(new Checkpoint.Item("a", true)),
                                        List.of(),
                                        "n",
                                        NOW);

        store.granted = ClaimedTask.claimedTask(store.ready, Store.DETAIL, some(checkpoint), TaskReport.NONE);
        assertThat(claimWith(store).execute(ClaimTask.Request.directed(ID, "w"))
                            .await()
                            .unwrap()
                            .unwrap()
                            .checkpoint()
                            .unwrap()).isEqualTo(checkpoint);
    }
}
