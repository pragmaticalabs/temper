package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.EvidenceOutcome;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.ParkRequest;
import org.pragmatica.temper.domain.task.RulingKind;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskBrief;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.domain.task.LeaseState;
import org.pragmatica.temper.protocol.digest.Digests;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.NewEvidence;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Task builders and checks for the `pg` task tests: tasks with unique slugs, claims with a known token hash, evidence
/// documents, and the projection check (the last event's after-image equals the row, the token hash aside).
final class TaskFixture {
    static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
    static final Sha BASE_TIP = Sha.sha("b".repeat(40)).unwrap();
    static final Sha HEAD = Sha.sha("c".repeat(40)).unwrap();
    private static final AtomicInteger SLUGS = new AtomicInteger();

    private TaskFixture() {}

    static NewTask newTask(List<TaskId> after, boolean design) {
        return NewTask.newTask("t-" + SLUGS.incrementAndGet(),
                               "A task",
                               List.of(2334),
                               TaskBrief.taskBrief("briefs/a.md", none(), design, Map.of()).unwrap(),
                               "pragmaticalabs/pragmatica",
                               "main",
                               "agent/t",
                               none(),
                               false,
                               0,
                               after.stream().map(TaskId::value).toList(),
                               3,
                               "cto")
                      .unwrap();
    }

    static TaskRow created(TaskStore tasks, Instant at) {
        return tasks.create(newTask(List.of(), false), at).await(PgFixture.WAIT).unwrap();
    }

    /// A created task advanced to CODE/READY by a sweep.
    static TaskRow inCode(TaskStore tasks, Instant at) {
        var row = created(tasks, at);

        tasks.sweep(at).await(PgFixture.WAIT).unwrap();
        return tasks.task(row.id()).await(PgFixture.WAIT).unwrap().unwrap();
    }

    /// Claims `id` with token hash `hash`; answers the lease at the new epoch.
    /// Claims `id` with the token hash of `label` ([#hash]); answers the lease at the new epoch.
    static Result<Lease> claim(TaskStore tasks, TaskId id, String label, Instant at) {
        return tasks.claim(id, ClaimGrant.claimGrant(hash(label), "w", some(BASE_TIP), none()), at)
                    .await(PgFixture.WAIT)
                    .map(claimed -> Lease.lease(id, claimed.row().state().epoch(), hash(label)));
    }

    /// A token hash as the store keeps one (sha256, 64 hex; V10's CHECK), named by `label`.
    static String hash(String label) {
        return Digests.sha256Hex(label).unwrap();
    }

    static NewEvidence evidence() {
        return NewEvidence.newEvidence("temper.evidence.code/1", "{\"schema\":\"temper.evidence.code/1\"}", "{}", "0".repeat(64));
    }

    static EvidenceOutcome next(Stage stage) {
        return EvidenceOutcome.evidenceOutcome(some(stage), EvidenceOutcome.Verdict.NONE, false, some(HEAD), some(12), List.of());
    }

    static EvidenceOutcome verdict(EvidenceOutcome.Verdict verdict) {
        return EvidenceOutcome.evidenceOutcome(verdict == EvidenceOutcome.Verdict.PASS
                                               ? some(Stage.CI)
                                               : none(),
                                               verdict,
                                               false,
                                               some(HEAD),
                                               none(),
                                               List.of("i1"));
    }

    static ParkRequest request() {
        return ParkRequest.requested(RulingKind.DESIGN,
                                     "Which shape?",
                                     List.of(ParkRequest.Choice.choice("a", "Shape A").unwrap(),
                                             ParkRequest.Choice.choice("b", "Shape B").unwrap()),
                                     ParkRequest.Recommendation.recommendation("a", "smaller", false).unwrap(),
                                     ParkRequest.Blocked.blocked(List.of(), "nothing").unwrap())
                          .unwrap();
    }

    /// The projection rule (m3-design §5.3): the task's row equals the after-image of its last event, the token hash
    /// aside (the log never holds it).
    static void assertFoldEqualsRow(TaskStore tasks, TaskId id, String context) {
        var row = tasks.task(id).await(PgFixture.WAIT).unwrap().unwrap();
        var events = tasks.events(id).await(PgFixture.WAIT).unwrap();

        assertThat(events).as(context + ": no events").isNotEmpty();
        assertThat(folded(events)).as(context).isEqualTo(withoutHash(row.state()));
    }

    /// The fold: each event carries the whole after-image, so the fold is the last one.
    static TaskState folded(List<TaskEventRow> events) {
        return events.stream()
                     .reduce((earlier, later) -> later)
                     .map(TaskEventRow::state)
                     .orElseThrow();
    }

    static TaskState withoutHash(TaskState state) {
        var lease = state.lease();

        return TaskState.taskState(state.stage(),
                                   state.status(),
                                   state.raisedFrom(),
                                   state.counters(),
                                   LeaseState.leaseState(lease.epoch(), Option.none(), lease.holder(), lease.until(), lease.stepDeadline(), lease.readPoint()),
                                   state.head(),
                                   state.pr(),
                                   state.readySince(),
                                   state.waiting(),
                                   state.audit(),
                                   state.closedAt());
    }

    static long count(StoreHarness harness, String sql, Object... params) {
        return harness.count(sql, params);
    }
}
