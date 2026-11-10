package org.pragmatica.temper.controller.task;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.EvidenceOutcome;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.ClaimedTask;
import org.pragmatica.temper.store.api.NewEvidence;
import org.pragmatica.temper.store.api.SweepOutcome;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskQuery;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifier V2 [G]: at controller start the sweeper first restarts the TTL of leases the downtime let lapse, and only
/// then sweeps, so an outage is never counted as an expiry.
///
/// Probe: sweep at start without the restart (`TaskSweeper.started` skips `restarted`): red.
class TaskSweeperTest {
    private static final Instant START = Instant.parse("2026-10-20T12:00:00Z");

    /// Records the store calls the sweeper makes, in order.
    private static final class Recording implements TaskStore {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Promise<List<TaskId>> restarted(Instant start) {
            calls.add("restarted@" + start);
            return Promise.success(List.of(TaskId.taskId(42).unwrap()));
        }

        @Override
        public Promise<SweepOutcome> sweep(Instant now) {
            calls.add("sweep@" + now);
            return Promise.success(SweepOutcome.sweepOutcome(0, 0, 0));
        }

        @Override
        public Promise<TaskRow> create(NewTask task, Instant now) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Option<TaskRow>> task(TaskId id) {
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
        public Promise<TaskDetail> detail(TaskId id) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<List<TaskRow>> ready(Option<Stage> stage, int limit) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<ClaimedTask> claim(TaskId id, ClaimGrant grant, Instant now) {
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
    }

    @Test
    void started_restartsLapsedLeases_beforeTheFirstSweep() {
        var store = new Recording();

        TaskSweeper.taskSweeper(store, () -> START).started().await().unwrap();

        assertThat(store.calls).containsExactly("restarted@" + START, "sweep@" + START);
    }
}
