package org.pragmatica.temper.store.pg;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.task.Audit;
import org.pragmatica.temper.domain.task.Counters;
import org.pragmatica.temper.domain.task.DispatchOrder;
import org.pragmatica.temper.domain.task.LeaseState;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskEvent;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskRow;

import static org.pragmatica.lang.Option.option;


/// The `tasks` and `task_events` columns and their parsing (m3-design §5.3): every stored value goes back through its
/// domain factory, and one that no longer parses is a `StoreError.CorruptRow` of its table ([Rows#corrupt]).
sealed interface TaskRows {
    String COLUMNS = "id, slug, title, to_json(issues)::text AS issues, brief::text AS brief, repo, base_ref, branch, pr, critical, "
                   + "priority, to_json(after)::text AS after, stage, status, raised_from, round, round_cap, ci_rounds, visit, "
                   + "visit_expiries, visit_failures, lease_epoch, lease_hash, lease_holder, lease_until, step_deadline, read_point, "
                   + "head, ready_since, to_json(wait_jobs)::text AS wait_jobs, wait_ci, audit, created_by, created_at, closed_at";

    /// The projected columns as `$2`..`$23` of an UPDATE whose `$1` is the id ([#stateParams] binds them in this order).
    String SET = "stage = $2, status = $3, raised_from = $4, round = $5, round_cap = $6, ci_rounds = $7, visit = $8, "
               + "visit_expiries = $9, visit_failures = $10, lease_epoch = $11, lease_hash = $12, lease_holder = $13, "
               + "lease_until = $14, step_deadline = $15, read_point = $16, head = $17, pr = $18, ready_since = $19, "
               + "wait_jobs = $20::text[]::bigint[], wait_ci = $21, audit = $22, closed_at = $23";

    /// Job states that hold a lease's TTL and keep a wait open, derived from the one domain rule
    /// (`JobState.isTerminal`).
    String ACTIVE_JOB_STATES = Arrays.stream(JobState.values())
                                     .filter(state -> !state.isTerminal())
                                     .map(state -> "'" + state.name() + "'")
                                     .collect(Collectors.joining(",",
                                                                 "(",
                                                                 ")"));

    /// The stages a task in `after` must have reached (T1, ruling R-M3-18), from `Stage.isVerifyOrLater`.
    String VERIFY_OR_LATER = Arrays.stream(Stage.values())
                                   .filter(Stage::isVerifyOrLater)
                                   .map(stage -> "'" + stage.name() + "'")
                                   .collect(Collectors.joining(",",
                                                               "(",
                                                               ")"));

    /// The worker stages, from `Stage.isWorkerStage`.
    String WORKER_STAGES = Arrays.stream(Stage.values())
                                 .filter(Stage::isWorkerStage)
                                 .map(stage -> "'" + stage.name() + "'")
                                 .collect(Collectors.joining(",",
                                                             "(",
                                                             ")"));

    /// The dispatcher order (§8): critical, the stage rank of `DispatchOrder.rank`, priority, ready since, id.
    String DISPATCH_ORDER = "critical DESC, CASE stage " + Arrays.stream(Stage.values())
                                                                 .map(stage -> "WHEN '" + stage.name()
                                                                              + "' THEN " + DispatchOrder.rank(stage))
                                                                 .collect(Collectors.joining(" "))
                          + " END, priority DESC, ready_since, id";

    /// `$2`..`$23` of [#SET].
    static Object[] stateParams(TaskState state) {
        var counters = state.counters();
        var lease = state.lease();

        return new Object[]{state.stage()
                                 .name(), state.status()
                                               .name(), Sql.nullable(state.raisedFrom().map(Stage::name)), counters.round(), counters.roundCap(), counters.ciRounds(), counters.visit(), counters.visitExpiries(), counters.visitFailures(), lease.epoch(), Sql.nullable(lease.hash()), Sql.nullable(lease.holder()), Sql.nullable(lease.until()
                                                                                                                                                                                                                                                                                                                                        .map(Sql::utc)), Sql.nullable(lease.stepDeadline()
                                                                                                                                                                                                                                                                                                                                                                           .map(Sql::utc)), Sql.nullable(lease.readPoint()
                                                                                                                                                                                                                                                                                                                                                                                                              .map(Sha::value)), Sql.nullable(state.head()
                                                                                                                                                                                                                                                                                                                                                                                                                                                   .map(Sha::value)), Sql.nullable(state.pr()), Sql.nullable(state.readySince()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  .map(Sql::utc)), Sql.texts(state.waiting()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  .jobs()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  .stream()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  .map(JobId::toString)), state.waiting()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               .ci(), state.audit()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           .wire(), Sql.nullable(state.closedAt()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      .map(Sql::utc))};
    }

    static Result<TaskRow> taskRow(PgRow row) {
        return Result.all(TaskId.taskId(row.getLong("id")),
                          Json.read(row.getString("issues"),
                                    Integer[].class),
                          Json.read(row.getString("brief"),
                                    TaskJson.BriefJson.class)
                              .flatMap(TaskJson.BriefJson::toDomain),
                          Ref.ref(row.getString("base_ref")),
                          Ref.ref(row.getString("branch")),
                          Json.read(row.getString("after"),
                                    Long[].class).flatMap(TaskRows::taskIds),
                          OwnerTag.ownerTag(row.getString("created_by"),
                                            "createdBy"),
                          state(row))
                     .map((id, issues, brief, base, branch, after, owner, state) -> TaskRow.taskRow(id,
                                                                                                    row.getString("slug"),
                                                                                                    row.getString("title"),
                                                                                                    List.of(issues),
                                                                                                    brief,
                                                                                                    row.getString("repo"),
                                                                                                    base,
                                                                                                    branch,
                                                                                                    row.getBoolean("critical"),
                                                                                                    row.getInt("priority"),
                                                                                                    after,
                                                                                                    owner,
                                                                                                    row.getInstant("created_at"),
                                                                                                    state))
                     .mapError(Rows.corrupt("tasks"));
    }

    private static Result<List<TaskId>> taskIds(Long[] ids) {
        return Result.allOf(Arrays.stream(ids).map(TaskId::taskId).toList());
    }

    private static Result<TaskState> state(PgRow row) {
        return Result.all(Stage.stage(row.getString("stage")),
                          Status.status(row.getString("status")),
                          Values.optional(Rows.text(row, "raised_from"),
                                          Stage::stage),
                          Values.optional(Rows.text(row, "read_point"),
                                          Sha::sha),
                          Values.optional(Rows.text(row, "head"),
                                          Sha::sha),
                          Json.read(row.getString("wait_jobs"),
                                    Long[].class).flatMap(TaskRows::jobIds),
                          Audit.audit(row.getString("audit")))
                     .map((stage, status, raisedFrom, readPoint, head, waitJobs, audit) -> TaskState.taskState(stage,
                                                                                                               status,
                                                                                                               raisedFrom,
                                                                                                               counters(row),
                                                                                                               LeaseState.leaseState(row.getLong("lease_epoch"),
                                                                                                                                     Rows.text(row,
                                                                                                                                               "lease_hash"),
                                                                                                                                     Rows.text(row,
                                                                                                                                               "lease_holder"),
                                                                                                                                     option(row.getInstant("lease_until")),
                                                                                                                                     option(row.getInstant("step_deadline")),
                                                                                                                                     readPoint),
                                                                                                               head,
                                                                                                               option(row.getInt("pr")),
                                                                                                               option(row.getInstant("ready_since")),
                                                                                                               Waiting.waiting(waitJobs,
                                                                                                                               row.getBoolean("wait_ci")),
                                                                                                               audit,
                                                                                                               option(row.getInstant("closed_at"))));
    }

    private static Counters counters(PgRow row) {
        return Counters.counters(row.getInt("round"),
                                 row.getInt("round_cap"),
                                 row.getInt("ci_rounds"),
                                 row.getInt("visit"),
                                 row.getInt("visit_expiries"),
                                 row.getInt("visit_failures"));
    }

    private static Result<List<JobId>> jobIds(Long[] ids) {
        return Result.allOf(Arrays.stream(ids).map(JobId::jobId).toList());
    }

    /// A `task_events` row: `seq, at, task_id, kind, actor, payload::text AS payload`.
    static Result<TaskEventRow> eventRow(PgRow row) {
        return Result.all(TaskId.taskId(row.getLong("task_id")),
                          TaskEvent.Kind.kind(row.getString("kind")),
                          Json.read(row.getString("payload"),
                                    TaskJson.PayloadJson.class))
                     .flatMap((task, kind, payload) -> eventRow(row, task, kind, payload))
                     .mapError(Rows.corrupt("task_events"));
    }

    private static Result<TaskEventRow> eventRow(PgRow row,
                                                 TaskId task,
                                                 TaskEvent.Kind kind,
                                                 TaskJson.PayloadJson payload) {
        return payload.state()
                      .toState()
                      .map(state -> TaskEventRow.taskEventRow(row.getLong("seq"),
                                                              row.getInstant("at"),
                                                              task,
                                                              TaskEvent.taskEvent(kind,
                                                                                  payload.facts()),
                                                              row.getString("actor"),
                                                              state));
    }

    record unused() implements TaskRows {}
}
