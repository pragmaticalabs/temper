package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.EvidenceOutcome;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.ParkRequest;
import org.pragmatica.temper.domain.task.RulingKind;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.TaskState;
import org.pragmatica.temper.protocol.api.ParkRequestDto;
import org.pragmatica.temper.store.api.ClaimGrant;
import org.pragmatica.temper.store.api.ClaimedTask;
import org.pragmatica.temper.store.api.NewEvidence;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SweepOutcome;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskEventRow;
import org.pragmatica.temper.store.api.TaskQuery;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// [TaskStore] over Postgres (m3-design §5, §6), every command through [TaskCommands] in its own transaction.
record PgTaskStore(Db db) implements TaskStore {
    private static final Logger LOG = LoggerFactory.getLogger(PgTaskStore.class);
    /// The actor of the sweeper's and the dispatcher's events.
    static final String TEMPER = "temper";

    private static final String INSERT_TASK = "INSERT INTO tasks (slug, title, issues, brief, repo, base_ref, branch, critical, priority, "
                                            + "after, created_by, created_at, stage, status, round_cap, pr, ready_since) "
                                            + "VALUES ($1, $2, $3::text[]::int[], $4::jsonb, $5, $6, $7, $8, $9, $10::text[]::bigint[], $11, "
                                            + "$12, $13, $14, $15, $16, $17) RETURNING " + TaskRows.COLUMNS;

    private static final String INSERT_EVIDENCE = "INSERT INTO task_evidence (task_id, epoch, stage, schema, accepted, document, checks, sha256, at) "
                                                + "VALUES ($1, $2, $3, $4, true, $5::jsonb, $6::jsonb, $7, $8) RETURNING id";

    private static final String EVENT_COLUMNS = "seq, at, task_id, kind, actor, payload::text AS payload";

    /// LEASED tasks with a clock passed at `$1` (§6.2): the step deadline always, the TTL only while no job linked at
    /// the current epoch is active. The machine decides again under the row lock.
    private static final String EXPIRED = "SELECT id FROM tasks t WHERE status = 'LEASED' AND (step_deadline < $1 OR (lease_until < $1 "
                                        + "AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.task_id = t.id AND j.task_epoch = t.lease_epoch "
                                        + "AND j.state IN " + TaskRows.ACTIVE_JOB_STATES
                                        + "))) ORDER BY id";

    /// WAITING tasks, not on CI, none of whose awaited jobs is still active (§6.6). A job that is gone (retention deletes
    /// terminal jobs only) counts as finished, so it can never hold a wait open.
    private static final String SETTLED = "SELECT id FROM tasks t WHERE status = 'WAITING' AND NOT wait_ci AND cardinality(wait_jobs) > 0 "
                                        + "AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.id = ANY(t.wait_jobs) AND j.state IN " + TaskRows.ACTIVE_JOB_STATES
                                        + ") ORDER BY id";

    private static final String LEASED = "SELECT id FROM tasks WHERE status = 'LEASED' ORDER BY id";
    private static final String TRIAGED = "SELECT id FROM tasks WHERE stage = 'TRIAGED' AND status = 'READY' ORDER BY id";

    @Override
    public Promise<TaskRow> create(NewTask task, Instant requested) {
        var now = TaskCommands.stored(requested);

        return db.inTransaction(tx -> knownAfter(tx, task).flatMap(_ -> inserted(tx, task, now))
                                                .flatMap(row -> TaskCommands.created(tx, row, now)));
    }

    @Override
    public Promise<Option<TaskRow>> task(TaskId id) {
        return Sql.query(db.executor(),
                         "SELECT " + TaskRows.COLUMNS + " FROM tasks WHERE id = $1",
                         id.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, TaskRows::taskRow));
    }

    @Override
    public Promise<List<TaskRow>> list(TaskQuery query) {
        return Sql.query(db.executor(),
                         "SELECT " + TaskRows.COLUMNS
                        + " FROM tasks WHERE ($1::text IS NULL OR stage = $1::text) "
                        + "AND ($2::text IS NULL OR status = $2::text) AND ($4::bigint IS NULL OR id < $4::bigint) "
                        + "ORDER BY id DESC LIMIT $3",
                         Sql.nullable(query.stage().map(Stage::name)),
                         Sql.nullable(query.status().map(Enum::name)),
                         query.limit(),
                         Sql.nullable(query.before().map(TaskId::value)))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, TaskRows::taskRow));
    }

    @Override
    public Promise<List<TaskEventRow>> events(TaskId id) {
        return Sql.query(db.executor(),
                         "SELECT " + EVENT_COLUMNS + " FROM task_events WHERE task_id = $1 ORDER BY seq",
                         id.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, TaskRows::eventRow));
    }

    /// One read-only snapshot, so the parts agree with each other.
    @Override
    public Promise<TaskDetail> detail(TaskId id) {
        return db.inTransaction(tx -> Sql.snapshot(tx).flatMap(_ -> detailIn(tx, id)));
    }

    @Override
    public Promise<List<TaskRow>> ready(Option<Stage> stage, int limit) {
        return Sql.query(db.executor(),
                         "SELECT " + TaskRows.COLUMNS
                        + " FROM tasks WHERE status = 'READY' AND stage IN " + TaskRows.WORKER_STAGES
                        + " AND ($1::text IS NULL OR stage = $1::text) ORDER BY " + TaskRows.DISPATCH_ORDER
                        + " LIMIT $2",
                         Sql.nullable(stage.map(Stage::name)),
                         limit)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, TaskRows::taskRow));
    }

    /// A row another transaction holds locked is skipped, which reads as NOT_READY: the holder wins or leaves it READY.
    @Override
    public Promise<ClaimedTask> claim(TaskId id, ClaimGrant grant, Instant now) {
        var at = TaskCommands.stored(now);

        return db.inTransaction(tx -> unlocked(tx, id).flatMap(row -> claimed(tx, row, grant, at))
                                              .mapWith(row -> detailIn(tx,
                                                                       row.id()),
                                                       ClaimedTask::claimedTask));
    }

    /// A read only: the lease holds now (`(id, LEASED, epoch, hash)`), else `LEASE_LOST`. The writes stay fenced by their
    /// own conditional UPDATE; this lets a caller refuse a stale lease before any work of its own.
    @Override
    public Promise<Unit> holds(Lease lease) {
        return Sql.query(db.executor(),
                         "SELECT count(*) AS n FROM tasks WHERE id = $1 AND status = 'LEASED' AND lease_epoch = $2 AND lease_hash = $3",
                         lease.task().value(),
                         lease.epoch(),
                         lease.tokenHash())
                  .map(PgTaskStore::count)
                  .flatMap(PgTaskStore::held);
    }

    private static Promise<Unit> held(long matching) {
        return matching == 1
               ? Promise.unitPromise()
               : TaskError.General.LEASE_LOST.promise();
    }

    @Override
    public Promise<TaskRow> leased(Lease lease, Command command, Instant requested) {
        var now = TaskCommands.stored(requested);

        return db.inTransaction(tx -> TaskCommands.lockedOrMissing(tx,
                                                                   lease.task())
                                                  .flatMap(row -> leaseCommand(tx, row, lease, command, now)));
    }

    @Override
    public Promise<TaskRow> evidence(Lease lease, NewEvidence evidence, EvidenceOutcome outcome, Instant requested) {
        var now = TaskCommands.stored(requested);

        return db.inTransaction(tx -> TaskCommands.lockedOrMissing(tx,
                                                                   lease.task())
                                                  .flatMap(row -> withEvidence(tx, row, lease, evidence, outcome, now)));
    }

    @Override
    public Promise<TaskRow> command(TaskId id, Command command, String actor, Instant requested) {
        var now = TaskCommands.stored(requested);

        return db.inTransaction(tx -> TaskCommands.lockedOrMissing(tx, id).flatMap(row -> ctoCommand(tx,
                                                                                                     row,
                                                                                                     command,
                                                                                                     actor,
                                                                                                     now)));
    }

    /// Expiries, then resumes, then advances, one after another.
    @Override
    public Promise<SweepOutcome> sweep(Instant requested) {
        var now = TaskCommands.stored(requested);

        return expiredAll(now).mapWith(_ -> resumedAll(now),
                                       Swept::new)
                         .mapWith(_ -> advancedAll(now),
                                  PgTaskStore::sweepOutcome);
    }

    // ---- create ------------------------------------------------------------------------------------------------------
    /// Every id in `after` is a task; the lowest that is not is the failure.
    private static Promise<Unit> knownAfter(QueryExecutor tx, NewTask task) {
        return Sql.query(tx,
                         "SELECT x.id FROM unnest($1::text[]::bigint[]) AS x(id) LEFT JOIN tasks t ON t.id = x.id "
                        + "WHERE t.id IS NULL ORDER BY x.id LIMIT 1",
                         (Object) Sql.texts(task.after().stream().map(TaskId::toString)))
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgTaskStore::idOf))
                  .flatMap(PgTaskStore::noneMissing);
    }

    private static Promise<Unit> noneMissing(Option<TaskId> missing) {
        return missing.map(StoreError.AfterUnknown.FACTORY)
                      .map(StoreError.AfterUnknown::<Unit> promise)
                      .or(Promise::unitPromise);
    }

    private static Promise<TaskRow> inserted(QueryExecutor tx, NewTask task, Instant now) {
        var state = TaskState.created(task.roundCap(), task.pr(), now);

        return Json.write(TaskJson.BriefJson.briefJson(task.brief()))
                   .async()
                   .flatMap(brief -> Sql.query(tx,
                                               INSERT_TASK,
                                               task.slug(),
                                               task.title(),
                                               Sql.texts(task.issues().stream().map(String::valueOf)),
                                               brief,
                                               task.repo(),
                                               task.baseRef().value(),
                                               task.branch().value(),
                                               task.critical(),
                                               task.priority(),
                                               Sql.texts(task.after().stream().map(TaskId::toString)),
                                               task.createdBy().value(),
                                               Sql.utc(now),
                                               state.stage().name(),
                                               state.status().name(),
                                               state.counters().roundCap(),
                                               Sql.nullable(state.pr()),
                                               Sql.utc(now)))
                   .flatMap(resultSet -> Sql.parseFirst(resultSet, TaskRows::taskRow))
                   .flatMap(row -> row.async(StoreError.General.NO_ROW_RETURNED));
    }

    // ---- claim -------------------------------------------------------------------------------------------------------
    private static Promise<TaskRow> claimed(QueryExecutor tx, TaskRow row, ClaimGrant grant, Instant now) {
        return readPoint(row, grant).async()
                        .map(issued -> claimOf(grant, issued))
                        .flatMap(claim -> TaskCommands.applied(tx, row, claim, TaskCommands.Fence.READY, now))
                        .map(applied -> applied.withFacts(directed(grant)))
                        .flatMap(applied -> TaskCommands.recorded(tx,
                                                                  applied,
                                                                  grant.holder(),
                                                                  now));
    }

    /// The task's head, else the base tip the controller resolved (§6.5); neither is `READ_POINT_UNKNOWN`.
    private static Result<Sha> readPoint(TaskRow row, ClaimGrant grant) {
        return row.state()
                  .head()
                  .orElse(grant.baseTip())
                  .toResult(TaskError.General.READ_POINT_UNKNOWN);
    }

    private static Command claimOf(ClaimGrant grant, Sha readPoint) {
        return new Command.Claim(grant.tokenHash(), grant.holder(), readPoint);
    }

    /// A directed claim past the head of its stage is recorded on the `leased` event (§6.1).
    private static Map<String, String> directed(ClaimGrant grant) {
        return grant.passedOver()
                    .map(head -> Map.of("directed",
                                        "true",
                                        "head",
                                        head.toString()))
                    .or(Map.of());
    }

    // ---- lease-token commands ----------------------------------------------------------------------------------------
    private static Promise<TaskRow> leaseCommand(QueryExecutor tx,
                                                 TaskRow row,
                                                 Lease lease,
                                                 Command command,
                                                 Instant now) {
        return checkedLinks(tx, row, command).flatMap(checked -> TaskCommands.applied(tx,
                                                                                      row,
                                                                                      checked,
                                                                                      TaskCommands.Fence.lease(lease),
                                                                                      now))
                           .flatMap(applied -> TaskCommands.recorded(tx,
                                                                     applied,
                                                                     TaskCommands.holder(row),
                                                                     now));
    }

    /// A wait names only jobs linked to this task (§6.6); the lowest one that is not is the failure.
    private static Promise<Command> checkedLinks(QueryExecutor tx, TaskRow row, Command command) {
        return switch (command) {
            case Command.Wait wait -> firstUnlinked(tx,
                                                    row.id(),
                                                    wait.awaited().jobs()).flatMap(unlinked -> linkedOnly(unlinked,
                                                                                                          command));
            default -> Promise.success(command);
        };
    }

    private static Promise<Command> linkedOnly(Option<JobId> unlinked, Command command) {
        return unlinked.map(StoreError.JobNotLinked.FACTORY)
                       .map(StoreError.JobNotLinked::<Command> promise)
                       .or(() -> Promise.success(command));
    }

    private static Promise<Option<JobId>> firstUnlinked(QueryExecutor tx, TaskId task, List<JobId> jobs) {
        return Sql.query(tx,
                         "SELECT x.id FROM unnest($2::text[]::bigint[]) AS x(id) LEFT JOIN jobs j ON j.id = x.id AND j.task_id = $1 "
                        + "WHERE j.id IS NULL ORDER BY x.id LIMIT 1",
                         task.value(),
                         Sql.texts(jobs.stream().map(JobId::toString)))
                  .flatMap(resultSet -> Sql.parseFirst(resultSet,
                                                       found -> JobId.jobId(found.getLong("id"))));
    }

    /// The evidence row first (its id is in the command), then the transition under the lease fence; a lost lease rolls
    /// the evidence row back with it.
    private static Promise<TaskRow> withEvidence(QueryExecutor tx,
                                                 TaskRow row,
                                                 Lease lease,
                                                 NewEvidence evidence,
                                                 EvidenceOutcome outcome,
                                                 Instant now) {
        return Sql.query(tx,
                         INSERT_EVIDENCE,
                         row.id().value(),
                         lease.epoch(),
                         row.state().stage().name(),
                         evidence.schema(),
                         evidence.document(),
                         evidence.checks(),
                         evidence.sha256(),
                         Sql.utc(now))
                  .flatMap(PgTaskStore::returnedId)
                  .flatMap(id -> evidenceCommand(tx,
                                                 row.id(),
                                                 id,
                                                 outcome))
                  .flatMap(command -> TaskCommands.applied(tx,
                                                           row,
                                                           command,
                                                           TaskCommands.Fence.lease(lease),
                                                           now))
                  .flatMap(applied -> TaskCommands.recorded(tx,
                                                            applied,
                                                            TaskCommands.holder(row),
                                                            now));
    }

    private static Promise<Long> returnedId(PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .async(StoreError.General.NO_ROW_RETURNED)
                  .map(row -> row.getLong("id"));
    }

    /// The task's latest accepted FIX evidence travels with the command, for a round-cap park (§7.6).
    private static Promise<Command> evidenceCommand(QueryExecutor tx,
                                                    TaskId task,
                                                    long evidence,
                                                    EvidenceOutcome outcome) {
        return Sql.query(tx,
                         "SELECT max(id) AS id FROM task_evidence WHERE task_id = $1 AND accepted AND stage = 'FIX'",
                         task.value())
                  .map(PgTaskStore::maxId)
                  .map(lastFix -> new Command.Evidence(evidence, lastFix, outcome));
    }

    private static Option<Long> maxId(PgResultSet resultSet) {
        return Sql.first(resultSet).flatMap(found -> option(found.getLong("id")));
    }

    // ---- the maintainer's commands ------------------------------------------------------------------------------------------
    private static Promise<TaskRow> ctoCommand(QueryExecutor tx,
                                               TaskRow row,
                                               Command command,
                                               String actor,
                                               Instant now) {
        return Command.isLeaseScoped(command)
               ? TaskError.Refused.FACTORY.apply(command.name(),
                                                 row.state().stage(),
                                                 row.state().status())
                                          .promise()
               : ctoApplied(tx, row, command, actor, now);
    }

    /// A lease-scoped command never goes through here: its fence is the lease, and the maintainer holds none.
    private static Promise<TaskRow> ctoApplied(QueryExecutor tx,
                                               TaskRow row,
                                               Command command,
                                               String actor,
                                               Instant now) {
        return offered(tx, row, command).flatMap(checked -> TaskCommands.applied(tx,
                                                                                 row,
                                                                                 checked,
                                                                                 TaskCommands.Fence.unchanged(row.state()),
                                                                                 now))
                      .flatMap(applied -> TaskCommands.recorded(tx, applied, actor, now));
    }

    /// A rule names an option the open request offered (§5.5); with no open request the machine answers NOT_HELD.
    private static Promise<Command> offered(QueryExecutor tx, TaskRow row, Command command) {
        return switch (command) {
            case Command.Rule rule -> openRequest(tx, row.id()).flatMap(request -> offeredBy(request, rule));
            default -> Promise.success(command);
        };
    }

    private static Promise<Command> offeredBy(Option<ParkRequest> request, Command.Rule rule) {
        var option = rule.decision().option();

        return request.filter(open -> !open.isOffered(option))
                      .map(_ -> StoreError.OptionNotOffered.FACTORY.apply(option).<Command> promise())
                      .or(() -> Promise.success(rule));
    }

    private static Promise<Option<ParkRequest>> openRequest(QueryExecutor tx, TaskId task) {
        return Sql.query(tx,
                         "SELECT request::text AS request FROM task_rulings WHERE task_id = $1 AND ruled_at IS NULL",
                         task.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgTaskStore::storedRequest));
    }

    private static Result<ParkRequest> storedRequest(PgRow row) {
        return Json.read(row.getString("request"),
                         ParkRequestDto.class)
                   .flatMap(ParkRequestDto::toDomain)
                   .mapError(Rows.corrupt("task_rulings"));
    }

    // ---- detail ------------------------------------------------------------------------------------------------------
    /// The checklist and the rulings, then the evidence ids, then the linked jobs: one statement at a time on the
    /// snapshot's connection.
    record ItemsAndRulings(List<TaskDetail.Item> items, List<TaskDetail.Ruling> rulings) {}

    record WithEvidence(ItemsAndRulings first, List<Long> evidence) {}

    private static Promise<TaskDetail> detailIn(QueryExecutor tx, TaskId id) {
        return items(tx, id).mapWith(_ -> rulings(tx, id),
                                     ItemsAndRulings::new)
                    .mapWith(_ -> evidenceIds(tx, id),
                             WithEvidence::new)
                    .mapWith(_ -> linkedJobs(tx, id),
                             PgTaskStore::taskDetail);
    }

    private static TaskDetail taskDetail(WithEvidence gathered, List<TaskDetail.LinkedJob> jobs) {
        return TaskDetail.taskDetail(gathered.first().items(),
                                     gathered.first().rulings(),
                                     gathered.evidence(),
                                     jobs);
    }

    private static Promise<List<TaskDetail.Item>> items(QueryExecutor tx, TaskId id) {
        return Sql.query(tx,
                         "SELECT item, raised_round, status FROM task_items WHERE task_id = $1 ORDER BY item",
                         id.value())
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgTaskStore::item)
                                       .toList());
    }

    private static TaskDetail.Item item(PgRow row) {
        return TaskDetail.Item.item(row.getString("item"), row.getInt("raised_round"), row.getString("status"));
    }

    private static Promise<List<TaskDetail.Ruling>> rulings(QueryExecutor tx, TaskId id) {
        return Sql.query(tx,
                         "SELECT id, raised_from, raised_visit, kind, request::text AS request, raised_at, raised_by, ruled_at, "
                        + "ruled_by, option, ruling_text, know_sha FROM task_rulings WHERE task_id = $1 ORDER BY id",
                         id.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgTaskStore::ruling));
    }

    private static Result<TaskDetail.Ruling> ruling(PgRow row) {
        return Result.all(Stage.stage(row.getString("raised_from")),
                          RulingKind.rulingKind(row.getString("kind")),
                          storedRequest(row),
                          Values.optional(Rows.text(row, "know_sha"),
                                          Sha::sha))
                     .map((from, kind, request, know) -> TaskDetail.Ruling.ruling(row.getLong("id"),
                                                                                  from,
                                                                                  row.getInt("raised_visit"),
                                                                                  kind,
                                                                                  request,
                                                                                  row.getInstant("raised_at"),
                                                                                  row.getString("raised_by"),
                                                                                  given(row, know)))
                     .mapError(Rows.corrupt("task_rulings"));
    }

    private static Option<TaskDetail.Given> given(PgRow row, Option<Sha> know) {
        return option(row.getInstant("ruled_at")).map(at -> TaskDetail.Given.given(at,
                                                                                   row.getString("ruled_by"),
                                                                                   row.getString("option"),
                                                                                   row.getString("ruling_text"),
                                                                                   know));
    }

    private static Promise<List<Long>> evidenceIds(QueryExecutor tx, TaskId id) {
        return Sql.query(tx,
                         "SELECT id FROM task_evidence WHERE task_id = $1 AND accepted ORDER BY id",
                         id.value())
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(row -> row.getLong("id"))
                                       .toList());
    }

    private static Promise<List<TaskDetail.LinkedJob>> linkedJobs(QueryExecutor tx, TaskId id) {
        return Sql.query(tx,
                         "SELECT id, task_epoch, state FROM jobs WHERE task_id = $1 ORDER BY id",
                         id.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgTaskStore::linkedJob));
    }

    private static Result<TaskDetail.LinkedJob> linkedJob(PgRow row) {
        return Result.all(Rows.jobId(row, "id"),
                          Rows.enumValue(JobState.class,
                                         row.getString("state")))
                     .map((job, state) -> TaskDetail.LinkedJob.linkedJob(job,
                                                                         row.getLong("task_epoch"),
                                                                         state))
                     .mapError(Rows.corrupt("jobs"));
    }

    // ---- controller start --------------------------------------------------------------------------------------------
    @Override
    public Promise<List<TaskId>> restarted(Instant requested) {
        var now = TaskCommands.stored(requested);

        return ids(LEASED).flatMap(found -> Sql.sequence(found,
                                                         id -> extendedOne(id, now)))
                  .map(extended -> extended.stream()
                                           .flatMap(Option::stream)
                                           .toList());
    }

    /// FER: a lease the start does not extend (live, past its step deadline, extended at this epoch already, held by
    /// another transaction, a store failure) is left as it is; the sweeper then judges it by its own clocks. Guarantee:
    /// nothing is written for it.
    private Promise<Option<TaskId>> extendedOne(TaskId id, Instant now) {
        return db.inTransaction(tx -> unlocked(tx, id).flatMap(row -> extendedAtStart(tx, row, now)))
                 .map(row -> Option.some(row.id()))
                 .recover(_ -> Option.none());
    }

    /// At most once per epoch (§6.2): a `lease-extended` event at the task's current epoch means it was extended
    /// already.
    private static Promise<TaskRow> extendedAtStart(QueryExecutor tx, TaskRow row, Instant now) {
        return Sql.query(tx,
                         "SELECT count(*) AS n FROM task_events WHERE task_id = $1 AND epoch = $2 AND kind = 'lease-extended'",
                         row.id().value(),
                         row.state().epoch())
                  .map(PgTaskStore::count)
                  .map(extended -> new Command.ControllerStart(extended > 0))
                  .flatMap(start -> byTemper(tx, row, start, now));
    }

    // ---- sweep -------------------------------------------------------------------------------------------------------
    /// How many leases expired and how many waits resumed, before the advances.
    record Swept(int expired, int resumed) {}

    private static SweepOutcome sweepOutcome(Swept swept, int advanced) {
        return SweepOutcome.sweepOutcome(swept.expired(), swept.resumed(), advanced);
    }

    private Promise<Integer> expiredAll(Instant now) {
        return ids(EXPIRED, Sql.utc(now)).flatMap(found -> swept(found, id -> expireOne(id, now)));
    }

    private Promise<Integer> resumedAll(Instant now) {
        return ids(SETTLED).flatMap(found -> swept(found, id -> resumeOne(id, now)));
    }

    private Promise<Integer> advancedAll(Instant now) {
        return ids(TRIAGED).flatMap(found -> swept(found, id -> advanceOne(id, now)));
    }

    private Promise<List<TaskId>> ids(String sql, Object... params) {
        return Sql.query(db.executor(),
                         sql,
                         params)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgTaskStore::idOf));
    }

    private static Result<TaskId> idOf(PgRow row) {
        return TaskId.taskId(row.getLong("id"));
    }

    /// Strictly one task after another, each its own transaction; answers how many moved.
    private static Promise<Integer> swept(List<TaskId> ids, Fn1<Promise<TaskRow>, TaskId> step) {
        return Sql.sequence(ids, id -> movedOne(id, step)).map(moved -> moved.stream()
                                                                             .mapToInt(Integer::intValue)
                                                                             .sum());
    }

    /// FER: a task the step could not move (held by another transaction, its clock or guard no longer passed, a store
    /// failure) is left exactly as it was, logged at DEBUG, and seen again at the next sweep (every 30 s); one task never
    /// stops the sweep of the others. Guarantee: nothing is written for that task by this sweep.
    private static Promise<Integer> movedOne(TaskId id, Fn1<Promise<TaskRow>, TaskId> step) {
        return step.apply(id)
                   .map(_ -> 1)
                   .onFailure(cause -> LOG.debug("task {} not swept: {}",
                                                 id,
                                                 cause.message()))
                   .recover(_ -> 0);
    }

    private Promise<TaskRow> expireOne(TaskId id, Instant now) {
        return db.inTransaction(tx -> unlocked(tx, id).flatMap(row -> expired(tx, row, now)));
    }

    private Promise<TaskRow> resumeOne(TaskId id, Instant now) {
        return db.inTransaction(tx -> unlocked(tx, id).flatMap(row -> resumed(tx, row, now)));
    }

    private Promise<TaskRow> advanceOne(TaskId id, Instant now) {
        return db.inTransaction(tx -> unlocked(tx, id).flatMap(row -> advanced(tx, row, now)));
    }

    /// The row, unless another transaction holds it (then NOT_READY: the claim or the sweep skips it).
    private static Promise<TaskRow> unlocked(QueryExecutor tx, TaskId id) {
        return TaskCommands.locked(tx, id, TaskCommands.Lock.SKIP).flatMap(row -> row.async(TaskError.General.NOT_READY));
    }

    private static Promise<TaskRow> expired(QueryExecutor tx, TaskRow row, Instant now) {
        return linkedActive(tx, row).map(Command.Expire::new)
                           .flatMap(expire -> byTemper(tx, row, expire, now));
    }

    private static Promise<TaskRow> resumed(QueryExecutor tx, TaskRow row, Instant now) {
        return settled(tx, row).map(Command.Resume::new)
                      .flatMap(resume -> byTemper(tx, row, resume, now));
    }

    private static Promise<TaskRow> advanced(QueryExecutor tx, TaskRow row, Instant now) {
        return afterReached(tx, row).map(reached -> new Command.Advance(row.brief().design(),
                                                                        reached))
                           .flatMap(advance -> byTemper(tx, row, advance, now));
    }

    private static Promise<TaskRow> byTemper(QueryExecutor tx, TaskRow row, Command command, Instant now) {
        return TaskCommands.applied(tx,
                                    row,
                                    command,
                                    TaskCommands.Fence.unchanged(row.state()),
                                    now)
                           .flatMap(applied -> TaskCommands.recorded(tx, applied, TEMPER, now));
    }

    /// A non-terminal job linked at the task's current epoch holds the TTL (§6.2).
    private static Promise<Boolean> linkedActive(QueryExecutor tx, TaskRow row) {
        return Sql.query(tx,
                         "SELECT count(*) AS n FROM jobs WHERE task_id = $1 AND task_epoch = $2 AND state IN " + TaskRows.ACTIVE_JOB_STATES,
                         row.id().value(),
                         row.state().epoch())
                  .map(PgTaskStore::count)
                  .map(active -> active > 0);
    }

    /// Every awaited job is terminal (§6.6).
    /// No awaited job is still active (§6.6); a job that is gone counts as finished, never as a wait without end.
    private static Promise<Boolean> settled(QueryExecutor tx, TaskRow row) {
        var awaited = row.state().waiting().jobs();

        return Sql.query(tx,
                         "SELECT count(*) AS n FROM jobs WHERE id = ANY($1::text[]::bigint[]) AND state IN " + TaskRows.ACTIVE_JOB_STATES,
                         (Object) Sql.texts(awaited.stream().map(JobId::toString)))
                  .map(PgTaskStore::count)
                  .map(active -> active == 0);
    }

    /// Every task in `after` has an event at VERIFY or later (ruling R-M3-18: has reached it, not is in it now).
    private static Promise<Boolean> afterReached(QueryExecutor tx, TaskRow row) {
        return Sql.query(tx,
                         "SELECT count(DISTINCT task_id) AS n FROM task_events WHERE task_id = ANY($1::text[]::bigint[]) AND stage IN " + TaskRows.VERIFY_OR_LATER,
                         (Object) Sql.texts(row.after().stream().map(TaskId::toString)))
                  .map(PgTaskStore::count)
                  .map(reached -> reached == row.after()
                                                .size());
    }

    private static long count(PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .map(found -> found.getLong("n"))
                  .or(0L);
    }
}
