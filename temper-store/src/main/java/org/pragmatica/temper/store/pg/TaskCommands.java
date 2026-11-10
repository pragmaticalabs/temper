package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.ParkRequest;
import org.pragmatica.temper.domain.task.RulingDecision;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskEvent;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskMachine;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.domain.task.Transition;
import org.pragmatica.temper.protocol.api.ParkRequestDto;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskRow;


/// One task command inside a caller's transaction (m3-design §5.3, the projection rule): [#locked] takes the row,
/// [#applied] runs `TaskMachine` and writes the new state under a [Fence], [#recorded] appends the events with the
/// state's after-image and the ruling a park or a rule writes. Nothing changes the row but [#applied], and nothing
/// calls it without [#recorded] in the same transaction, so `tasks` is always the fold of `task_events`.
///
/// The row lock is `FOR NO KEY UPDATE` (the row lock of §5.3's `FOR UPDATE`, minus the key lock, so the FK checks of the
/// task's own event and job inserts never wait on it, as `JobLock` does for jobs).
sealed interface TaskCommands {
    /// A row lock: wait for it, or skip a row another transaction holds (a claim, the sweeper).
    enum Lock {
        WAIT(" FOR NO KEY UPDATE"),
        SKIP(" FOR NO KEY UPDATE SKIP LOCKED");
        private final String clause;
        Lock(String clause) {
            this.clause = clause;
        }
        String clause() {
            return clause;
        }
    }

    /// What the UPDATE must still match beyond the id (`$24`… after [TaskRows#SET]), and the failure when it matches
    /// nothing.
    record Fence(String clause, List<Object> params, Cause lost) {
        /// T2: the task is still READY.
        static final Fence READY = new Fence(" AND status = 'READY'", List.of(), TaskError.General.NOT_READY);

        /// §6.4: the caller's epoch and token hash are the row's, and the task is LEASED.
        static Fence lease(Lease lease) {
            return new Fence(" AND status = 'LEASED' AND lease_epoch = $24 AND lease_hash = $25",
                             List.of(lease.epoch(), lease.tokenHash()),
                             TaskError.General.LEASE_LOST);
        }

        /// A command of the maintainer or of temper: the row is as it was read (it is locked, so it is; defence in depth).
        static Fence unchanged(TaskState before) {
            return new Fence(" AND lease_epoch = $24 AND status = $25",
                             List.of(before.epoch(),
                                     before.status().name()),
                             StoreError.General.NO_ROW_RETURNED);
        }
    }

    /// A decided and written transition, its events not yet recorded.
    record Applied(TaskRow before, Command command, Transition transition) {
        TaskRow after() {
            return TaskRow.taskRow(before.id(),
                                   before.slug(),
                                   before.title(),
                                   before.issues(),
                                   before.brief(),
                                   before.repo(),
                                   before.baseRef(),
                                   before.branch(),
                                   before.critical(),
                                   before.priority(),
                                   before.after(),
                                   before.createdBy(),
                                   before.createdAt(),
                                   transition.state());
        }

        /// The same with `facts` added to the first event (a claim's `directed`, a linked submit's `job`).
        Applied withFacts(Map<String, String> facts) {
            return new Applied(before,
                               command,
                               Transition.transition(transition.state(),
                                                     Stream.concat(Stream.of(withAll(transition.events().getFirst(),
                                                                                     facts)),
                                                                   transition.events().stream().skip(1))
                                                           .toList(),
                                                     transition.parked()));
        }

        private static TaskEvent withAll(TaskEvent event, Map<String, String> facts) {
            return facts.entrySet()
                        .stream()
                        .reduce(event,
                                (current, fact) -> current.with(fact.getKey(),
                                                                fact.getValue()),
                                (left, _) -> left);
        }
    }

    /// The option recorded on a request the task's close ended unruled.
    String CLOSED_OPTION = "closed";

    String INSERT_EVENT = "INSERT INTO task_events (at, task_id, kind, stage, status, visit, epoch, actor, payload) "
                        + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)";

    String INSERT_RULING = "INSERT INTO task_rulings (task_id, raised_from, raised_visit, kind, request, raised_at, raised_by) "
                         + "VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7)";

    /// The ruling columns go from NULL to their values once (`temper_ruling_once`, V10).
    String RULE = "UPDATE task_rulings SET ruled_at = $2, ruled_by = $3, option = $4, ruling_text = $5, know_sha = $6 "
                + "WHERE task_id = $1 AND ruled_at IS NULL";

    /// `now` as `timestamptz` keeps it: microseconds. Every time a command writes, into the row and into its events'
    /// after-image alike, is this one, so the fold of the events equals the row to the microsecond.
    static Instant stored(Instant now) {
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    static Promise<Option<TaskRow>> locked(QueryExecutor tx, TaskId id, Lock lock) {
        return Sql.query(tx,
                         "SELECT " + TaskRows.COLUMNS + " FROM tasks WHERE id = $1" + lock.clause(),
                         id.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, TaskRows::taskRow));
    }

    /// The row, waiting for its lock; an unknown id is `TaskNotFound`.
    static Promise<TaskRow> lockedOrMissing(QueryExecutor tx, TaskId id) {
        return locked(tx, id, Lock.WAIT).flatMap(row -> row.async(StoreError.TaskNotFound.FACTORY.apply(id)));
    }

    /// `TaskMachine`, then the UPDATE of every projected column under `fence`; a fence that matches no row fails with
    /// its cause, and the caller's transaction rolls back.
    static Promise<Applied> applied(QueryExecutor tx, TaskRow row, Command command, Fence fence, Instant now) {
        return TaskMachine.apply(row.state(),
                                 command,
                                 now)
                          .async()
                          .flatMap(transition -> written(tx,
                                                         new Applied(row, command, transition),
                                                         fence));
    }

    private static Promise<Applied> written(QueryExecutor tx, Applied applied, Fence fence) {
        return Sql.update(tx,
                          "UPDATE tasks SET " + TaskRows.SET + " WHERE id = $1" + fence.clause(),
                          Stream.of(Stream.of(applied.before().id().value()),
                                    Stream.of(TaskRows.stateParams(applied.transition().state())),
                                    fence.params().stream())
                                .flatMap(part -> part)
                                .toArray())
                  .flatMap(updated -> fenced(updated, applied, fence));
    }

    private static Promise<Applied> fenced(int updated, Applied applied, Fence fence) {
        return updated == 1
               ? Promise.success(applied)
               : fence.lost()
                      .promise();
    }

    /// The events with the after-image, then the ruling a park raises or a rule gives; answers the row as it now is.
    static Promise<TaskRow> recorded(QueryExecutor tx, Applied applied, String actor, Instant now) {
        return appendedAll(tx,
                           applied.before().id(),
                           applied.transition().state(),
                           applied.transition().events(),
                           actor,
                           now).flatMap(_ -> raised(tx, applied, actor, now))
                          .flatMap(_ -> ruled(tx, applied, actor, now))
                          .map(_ -> applied.after());
    }

    /// A created task's `created` and `ready(created)` (§5.4, §9.1), carrying its first state.
    static Promise<TaskRow> created(QueryExecutor tx, TaskRow row, Instant now) {
        return appendedAll(tx,
                           row.id(),
                           row.state(),
                           List.of(TaskEvent.taskEvent(TaskEvent.Kind.CREATED),
                                   TaskEvent.taskEvent(TaskEvent.Kind.READY, Map.of("reason", "created"))),
                           row.createdBy().value(),
                           now).map(_ -> row);
    }

    private static Promise<List<Unit>> appendedAll(QueryExecutor tx,
                                                   TaskId id,
                                                   TaskState state,
                                                   List<TaskEvent> events,
                                                   String actor,
                                                   Instant now) {
        return Sql.sequence(events, event -> appended(tx, id, state, event, actor, now));
    }

    private static Promise<Unit> appended(QueryExecutor tx,
                                          TaskId id,
                                          TaskState state,
                                          TaskEvent event,
                                          String actor,
                                          Instant now) {
        return Json.write(new TaskJson.PayloadJson(event.facts(),
                                                   TaskJson.StateImage.stateImage(state)))
                   .async()
                   .flatMap(payload -> Sql.execute(tx,
                                                   INSERT_EVENT,
                                                   Sql.utc(now),
                                                   id.value(),
                                                   event.kind().wire(),
                                                   state.stage().name(),
                                                   state.status().name(),
                                                   state.counters().visit(),
                                                   state.epoch(),
                                                   actor,
                                                   payload));
    }

    /// A park stores its request, raised from the stage it left at the visit it was in.
    private static Promise<Unit> raised(QueryExecutor tx, Applied applied, String actor, Instant now) {
        return applied.transition()
                      .parked()
                      .map(request -> inserted(tx, applied, request, actor, now))
                      .or(Promise::unitPromise);
    }

    private static Promise<Unit> inserted(QueryExecutor tx,
                                          Applied applied,
                                          ParkRequest request,
                                          String actor,
                                          Instant now) {
        var before = applied.before().state();

        return Json.write(ParkRequestDto.parkRequestDto(request))
                   .async()
                   .flatMap(json -> Sql.execute(tx,
                                                INSERT_RULING,
                                                applied.before().id().value(),
                                                before.stage().name(),
                                                before.counters().visit(),
                                                request.kind().wire(),
                                                json,
                                                Sql.utc(now),
                                                actor));
    }

    private static Promise<Unit> ruled(QueryExecutor tx, Applied applied, String actor, Instant now) {
        return switch (applied.command()) {
            case Command.CloseAbandoned _ when applied.before().state().stage() == Stage.NEEDS_RULING -> closedRuling(tx,
                                                                                                                      applied.before().id(),
                                                                                                                      actor,
                                                                                                                      now);
            case Command.Rule rule -> givenRuling(tx,
                                                  applied.before().id(),
                                                  rule.decision(),
                                                  actor,
                                                  now);
            default -> Promise.unitPromise();
        };
    }

    private static Promise<Unit> givenRuling(QueryExecutor tx,
                                             TaskId id,
                                             RulingDecision decision,
                                             String actor,
                                             Instant now) {
        return Sql.update(tx,
                          RULE,
                          id.value(),
                          Sql.utc(now),
                          actor,
                          decision.option(),
                          decision.text(),
                          Sql.nullable(decision.know().map(Sha::value)))
                  .flatMap(TaskCommands::ruledOnce);
    }

    /// The open ruling was there under the task's lock, so this holds unless a hand edit removed it.
    /// A task closed while NEEDS_RULING leaves no request open: its ruling is written once, as closed (§5.5).
    private static Promise<Unit> closedRuling(QueryExecutor tx, TaskId id, String actor, Instant now) {
        return Sql.update(tx,
                          RULE,
                          id.value(),
                          Sql.utc(now),
                          actor,
                          CLOSED_OPTION,
                          "task closed",
                          null)
                  .flatMap(TaskCommands::ruledOnce);
    }

    private static Promise<Unit> ruledOnce(int updated) {
        return updated == 1
               ? Promise.unitPromise()
               : TaskError.General.NOT_HELD.promise();
    }

    /// A linked submit's fence (§6.4): the task locked, the lease checked and its TTL restarted, inside the submit's
    /// transaction; [#recorded] runs once the job's id is known.
    static Promise<Applied> linked(QueryExecutor tx, Lease lease, Instant now) {
        return lockedOrMissing(tx, lease.task()).flatMap(row -> applied(tx,
                                                                        row,
                                                                        new Command.LinkJob(),
                                                                        Fence.lease(lease),
                                                                        now));
    }

    /// The lease holder, the actor of a lease-token command's events.
    static String holder(TaskRow row) {
        return row.state()
                  .lease()
                  .holder()
                  .or("worker");
    }

    record unused() implements TaskCommands {}
}
