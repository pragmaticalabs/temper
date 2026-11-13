package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonError;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.LeaseToken;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.task.ClaimTask;
import org.pragmatica.temper.controller.task.CloseTask;
import org.pragmatica.temper.controller.task.CreateTask;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.controller.task.TaskRefusal;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.ParkRequest;
import org.pragmatica.temper.domain.task.ReleaseReason;
import org.pragmatica.temper.domain.task.RulingDecision;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.Status;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.domain.task.Waiting;
import org.pragmatica.temper.protocol.api.ClaimRequest;
import org.pragmatica.temper.protocol.api.LeaseCall;
import org.pragmatica.temper.protocol.api.ObserveCall;
import org.pragmatica.temper.protocol.api.ParkCall;
import org.pragmatica.temper.protocol.api.TaskCreateRequest;
import org.pragmatica.temper.protocol.api.TaskDecision;
import org.pragmatica.temper.protocol.api.TaskView;
import org.pragmatica.temper.store.api.Checkpoint;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskDetail;
import org.pragmatica.temper.store.api.TaskQuery;
import org.pragmatica.temper.store.api.TaskReport;
import org.pragmatica.temper.store.api.TaskRow;
import org.pragmatica.temper.store.api.TaskStore;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.route;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// The task API (m3-design §11.1), WP-1's part:
///
/// | Method | Path | Scope | Answer |
/// |---|---|---|---|
/// | POST | `tasks` | full | 201 `TaskView` · 400 · 422 `base-unknown`, `protected-branch`, `after-unknown` |
/// | GET | `tasks?stage=&status=&limit=&before=` | read | `[TaskView]`, newest first |
/// | GET | `tasks/next?stage=` | read | the head of each worker stage in dispatcher order (§8), or of `stage` |
/// | GET | `tasks/{id}` | read | `TaskView` with its detail · 404 |
/// | GET | `tasks/{id}/events` | read | `[TaskEventDto]` · 404 |
/// | POST | `tasks/{id}/claim`, `tasks/claim?stage=S` | worker | 200 `ClaimResponse` · 404 · 409 `not-ready` · 204 nothing ready |
/// | POST | `tasks/{id}/heartbeat` | lease | 204 · 409 `lease-lost`; the optional `report` `{head, dirty, unpushed}` is the agent's SELF-REPORT |
/// | POST | `tasks/{id}/checkpoint` | lease | 201 `CheckpointDto` · 409 `lease-lost` · 422 `checkpoint-too-large` (body over 16 KB), `pushed-sha-not-on-branch` |
/// | POST | `tasks/{id}/observe` | full | 204 · 404 · 400; context and turns MEASURED by the parent, never by the lease holder |
/// | POST | `tasks/{id}/wait` | lease | 204 · 422 (`job-not-linked`, `nothing-awaited`) · 409 |
/// | POST | `tasks/{id}/release` | lease | 204 · 409 |
/// | POST | `tasks/{id}/park` | lease or full | 200 `TaskView` · 422 (request invalid) · 409 |
/// | POST | `tasks/{id}/rule`, `close`, `bounce` | full | 200 `TaskView` · 404 · 409 `not-held` · 422 |
///
/// A lease-token call sends `Authorization: Lease <token>` and its epoch in the body; the store accepts it only at that
/// epoch under that token (409 `lease-lost` otherwise, §6.4). `POST tasks/{id}/evidence`, `tasks/{id}/evidence/{eid}`,
/// `tasks/{id}/audit` and `tasks/metrics` are WP-2's and WP-3's and are not served yet: the evidence route needs the
/// body rules of §7 before any document may move a task.
public record TaskRoutes(Responses responses,
                         JsonMapper mapper,
                         TaskStore tasks,
                         CreateTask create,
                         ClaimTask claim,
                         CloseTask close,
                         Mirror mirror,
                         Duration staleAfter,
                         Supplier<Instant> clock) {
    /// The maintainer's commands carry no identity of their own: one full token (attribution, not authority).
    public static final String CTO = "cto";
    public static final String DEFAULT_HOLDER = "worker";
    /// A lease holder's name (`claim --worker NAME`) [G]: the owner alphabet plus `.` and `-`.
    public static final Pattern HOLDER = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    public static final int MAX_ITEM = 400;
    /// A checkpoint request body is at most 16 KB (lane registry WP1); a larger one is 422 `checkpoint-too-large`.
    public static final int MAX_CHECKPOINT_BYTES = 16 * 1024;
    /// The bounds of a parent observation: context in thousands of tokens, turns.
    public static final int MAX_OBSERVED = 10_000_000;
    private static final String MERGED = "merged";
    private static final Set<String> CLOSE_HOW = Set.of(MERGED, "abandoned");

    public static TaskRoutes taskRoutes(Responses responses,
                                        JsonMapper mapper,
                                        TaskStore tasks,
                                        CreateTask create,
                                        ClaimTask claim,
                                        CloseTask close,
                                        Mirror mirror,
                                        Duration staleAfter,
                                        Supplier<Instant> clock) {
        return new TaskRoutes(responses, mapper, tasks, create, claim, close, mirror, staleAfter, clock);
    }

    /// `tasks/next` precedes `tasks/{id}`: the router takes the first match.
    public List<Route> routes() {
        return List.of(route(HttpMethod.POST, "tasks", this::createTask),
                       readRoute("tasks", this::listTasks),
                       readRoute("tasks/next", this::nextTasks),
                       scopedRoute(HttpMethod.POST, "tasks/claim", Route.Scope.WORKER, this::claimHead),
                       readRoute("tasks/{id}", this::showTask),
                       readRoute("tasks/{id}/events", this::taskEvents),
                       scopedRoute(HttpMethod.POST, "tasks/{id}/claim", Route.Scope.WORKER, this::claimTask),
                       scopedRoute(HttpMethod.POST, "tasks/{id}/heartbeat", Route.Scope.LEASE, this::heartbeat),
                       scopedRoute(HttpMethod.POST, "tasks/{id}/checkpoint", Route.Scope.LEASE, this::checkpoint),
                       route(HttpMethod.POST, "tasks/{id}/observe", this::observe),
                       scopedRoute(HttpMethod.POST, "tasks/{id}/wait", Route.Scope.LEASE, this::waitOn),
                       scopedRoute(HttpMethod.POST, "tasks/{id}/release", Route.Scope.LEASE, this::release),
                       scopedRoute(HttpMethod.POST, "tasks/{id}/park", Route.Scope.LEASE_OR_WRITE, this::park),
                       route(HttpMethod.POST, "tasks/{id}/rule", this::rule),
                       route(HttpMethod.POST, "tasks/{id}/close", this::closeTask),
                       route(HttpMethod.POST, "tasks/{id}/bounce", this::bounce));
    }

    // ---- create and read ---------------------------------------------------------------------------------------------
    private Promise<ApiResponse> createTask(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                TaskCreateRequest.class)
                     .flatMap(TaskCreateRequest::toDomain)
                     .async()
                     .map(CreateTask.Request::request)
                     .flatMap(create::execute)
                     .flatMap(row -> viewOf(row,
                                            none()))
                     .map(view -> responses.json(HttpStatus.CREATED, view))
                     .mapError(TaskRoutes::classify);
    }

    private Promise<ApiResponse> listTasks(ApiRequest request) {
        return taskQuery(request).async()
                        .flatMap(tasks::list)
                        .flatMap(this::viewsResponse)
                        .mapError(TaskRoutes::classify);
    }

    private Promise<ApiResponse> viewsResponse(List<TaskRow> rows) {
        return tasks.reports(rows.stream().map(TaskRow::id).toList())
                    .map(reports -> responses.ok(views(rows,
                                                       reports,
                                                       clock.get())));
    }

    private List<TaskView> views(List<TaskRow> rows, Map<TaskId, TaskReport> reports, Instant now) {
        return rows.stream()
                   .map(row -> TaskViews.taskView(row,
                                                  none(),
                                                  reports.getOrDefault(row.id(),
                                                                       TaskReport.NONE),
                                                  now,
                                                  staleAfter))
                   .toList();
    }

    /// The task as shown: its lane report read beside it, the heartbeat clock against one `now`.
    private Promise<TaskView> viewOf(TaskRow row, Option<TaskDetail> detail) {
        return tasks.reports(List.of(row.id()))
                    .map(reports -> TaskViews.taskView(row,
                                                       detail,
                                                       reports.getOrDefault(row.id(),
                                                                            TaskReport.NONE),
                                                       clock.get(),
                                                       staleAfter));
    }

    private static Result<TaskQuery> taskQuery(ApiRequest request) {
        return Result.all(Values.optional(request.queryParam("stage"),
                                          Stage::stage),
                          Values.optional(request.queryParam("status"),
                                          Status::status),
                          integer(request, "limit"),
                          Values.optional(request.queryParam("before"),
                                          TaskRoutes::taskIdOf))
                     .flatMap((stage, status, limit, before) -> TaskQuery.taskQuery(stage,
                                                                                    status,
                                                                                    limit.or(TaskQuery.DEFAULT_LIMIT),
                                                                                    before));
    }

    /// With `stage`, its head; without, the head of each worker stage, in dispatcher order (the first READY task of
    /// each stage in the store's one ordered read).
    private Promise<ApiResponse> nextTasks(ApiRequest request) {
        return Values.optional(request.queryParam("stage"),
                               Stage::stage)
                     .async()
                     .flatMap(stage -> tasks.ready(stage, TaskQuery.MAX_LIMIT))
                     .map(TaskRoutes::firstPerStage)
                     .flatMap(this::viewsResponse)
                     .mapError(TaskRoutes::classify);
    }

    private static List<TaskRow> firstPerStage(List<TaskRow> ready) {
        return ready.stream()
                    .collect(Collectors.toMap(row -> row.state()
                                                        .stage(),
                                              row -> row,
                                              (first, _) -> first,
                                              LinkedHashMap::new))
                    .values()
                    .stream()
                    .toList();
    }

    private Promise<ApiResponse> showTask(ApiRequest request) {
        return taskId(request).async()
                     .flatMap(this::existing)
                     .flatMap(row -> tasks.detail(row.id())
                                          .flatMap(detail -> viewOf(row,
                                                                    some(detail))))
                     .map(responses::ok)
                     .mapError(TaskRoutes::classify);
    }

    private Promise<TaskRow> existing(TaskId id) {
        return tasks.task(id)
                    .flatMap(row -> row.async(StoreError.TaskNotFound.FACTORY.apply(id)));
    }

    private Promise<ApiResponse> taskEvents(ApiRequest request) {
        return taskId(request).async()
                     .flatMap(this::existing)
                     .flatMap(row -> tasks.events(row.id()))
                     .map(rows -> responses.ok(rows.stream().map(TaskViews::taskEventDto).toList()))
                     .mapError(TaskRoutes::classify);
    }

    // ---- claim -------------------------------------------------------------------------------------------------------
    private Promise<ApiResponse> claimTask(ApiRequest request) {
        return Result.all(taskId(request),
                          holder(request))
                     .async()
                     .map(ClaimTask.Request::directed)
                     .flatMap(claim::execute)
                     .map(this::claimResponse)
                     .mapError(TaskRoutes::classify);
    }

    private Promise<ApiResponse> claimHead(ApiRequest request) {
        return Result.all(request.queryParam("stage").toResult(invalid("stage").apply("missing")).flatMap(Stage::stage),
                          holder(request))
                     .async()
                     .map(ClaimTask.Request::head)
                     .flatMap(claim::execute)
                     .map(this::claimResponse)
                     .mapError(TaskRoutes::classify);
    }

    private ApiResponse claimResponse(Option<ClaimTask.Claimed> claimed) {
        return claimed.map(TaskViews::claimResponse)
                      .map(responses::ok)
                      .or(Responses::noContent);
    }

    /// The body is optional (`{"worker": NAME}`); without one, or without `worker`, the holder is [#DEFAULT_HOLDER].
    private Result<String> holder(ApiRequest request) {
        return claimRequest(request).flatMap(body -> body.worker()
                                                         .map(TaskRoutes::holderName)
                                                         .or(() -> success(DEFAULT_HOLDER)));
    }

    private Result<ClaimRequest> claimRequest(ApiRequest request) {
        return request.body().length == 0
               ? success(new ClaimRequest(none()))
               : mapper.readBytes(request.body(), ClaimRequest.class);
    }

    private static Result<String> holderName(String raw) {
        return Values.matching(raw, HOLDER, "worker");
    }

    // ---- lease-token calls (§6.4) ------------------------------------------------------------------------------------
    /// A lease-token call: the lease it presents and the command it asks for.
    record LeasedCommand(Lease lease, Command command) {}

    /// A command of the maintainer's on a task.
    record TaskCommand(TaskId task, Command command) {}

    /// T3, and with a `report` the tree the agent SELF-REPORTS (dirty files, unpushed commits) as of this heartbeat; the
    /// controller cannot observe the tree. Without one the heartbeat is exactly what it was (plus its time, for `hbAge`).
    private Promise<ApiResponse> heartbeat(ApiRequest request) {
        return parsed(request, LeaseCall.Heartbeat.class).flatMap(body -> Result.all(lease(request,
                                                                                           body.epoch()),
                                                                                     tree(body.report()))
                                                                                .map(HeartbeatCall::new))
                     .async()
                     .flatMap(call -> tasks.heartbeat(call.lease(),
                                                      call.tree(),
                                                      clock.get()))
                     .map(_ -> Responses.noContent())
                     .mapError(TaskRoutes::classify);
    }

    record HeartbeatCall(Lease lease, Option<TaskReport.Tree> tree) {}

    record CheckpointCall(Lease lease, Checkpoint.New body) {}

    record Observation(TaskId task, int ctxK, int turns) {}

    private static Result<Option<TaskReport.Tree>> tree(Option<LeaseCall.Tree> report) {
        return Values.optional(report, TaskRoutes::treeOf);
    }

    private static Result<TaskReport.Tree> treeOf(LeaseCall.Tree body) {
        return Result.all(Sha.sha(body.head()),
                          count(body.dirty(),
                                "dirty"),
                          count(body.unpushed(),
                                "unpushed"))
                     .map((head, dirty, unpushed) -> new TaskReport.Tree(head.value(),
                                                                         dirty,
                                                                         unpushed));
    }

    private static Result<Integer> count(int value, String name) {
        return Verify.ensure(value, Verify.Is::between, 0, MAX_OBSERVED, invalid(name));
    }

    // ---- checkpoint (lane registry WP1) -----------------------------------------------------------------------------
    /// Fenced first (409 `lease-lost` before anything is judged), then the pushed sha must be the task branch's tip or an
    /// ancestor of it in the mirror (422 `pushed-sha-not-on-branch`), then stored as the next version under the same fence
    /// held atomically by the insert. Does not extend the TTL.
    private Promise<ApiResponse> checkpoint(ApiRequest request) {
        return request.body().length > MAX_CHECKPOINT_BYTES
               ? TaskRefusal.CHECKPOINT_TOO_LARGE.<ApiResponse> promise().mapError(TaskRoutes::classify)
               : parsed(request, LeaseCall.Checkpoint.class).flatMap(body -> Result.all(lease(request,
                                                                                              body.epoch()),
                                                                                        checkpointOf(body))
                                                                                   .map(CheckpointCall::new))
                       .async()
                       .flatMap(this::checkpointed)
                       .map(stored -> responses.json(HttpStatus.CREATED,
                                                     TaskViews.checkpointDto(stored)))
                       .mapError(TaskRoutes::classify);
    }

    private Promise<Checkpoint> checkpointed(CheckpointCall call) {
        return tasks.holds(call.lease())
                    .flatMap(_ -> existing(call.lease().task()))
                    .flatMap(row -> Sha.sha(call.body().pushedSha())
                                       .async()
                                       .flatMap(sha -> mirror.branchContains(row.branch(),
                                                                             sha)))
                    .flatMap(onBranch -> onBranch
                                         ? tasks.checkpoint(call.lease(),
                                                            call.body(),
                                                            clock.get())
                                         : TaskRefusal.PUSHED_SHA_NOT_ON_BRANCH.<Checkpoint> promise());
    }

    private static Result<Checkpoint.New> checkpointOf(LeaseCall.Checkpoint body) {
        return Result.all(Sha.sha(body.pushedSha()),
                          entries(body.checklist(),
                                  item -> item.item() != null
                                          && !item.item()
                                                  .isBlank()
                                          && item.item()
                                                 .length() <= MAX_ITEM,
                                  "checklist"),
                          entries(body.readMap(),
                                  entry -> entry.path() != null
                                           && !entry.path()
                                                    .isBlank()
                                           && entry.lines() != null,
                                  "readMap"))
                     .map((sha, checklist, readMap) -> new Checkpoint.New(sha.value(),
                                                                          checklist.stream()
                                                                                   .map(item -> new Checkpoint.Item(item.item(),
                                                                                                                    item.done()))
                                                                                   .toList(),
                                                                          readMap.stream()
                                                                                 .map(entry -> new Checkpoint.Entry(entry.path(),
                                                                                                                    entry.lines()))
                                                                                 .toList(),
                                                                          Option.option(body.note()).or("")));
    }

    private static <T> Result<List<T>> entries(List<T> raw, java.util.function.Predicate<T> valid, String name) {
        var entries = Option.option(raw).or(List.of());

        return entries.stream()
                      .allMatch(valid)
               ? success(entries)
               : invalid(name).apply("entry invalid")
                        .result();
    }

    // ---- observe (parent side) ---------------------------------------------------------------------------------------
    /// The parent's measurement of an agent: full token only, since the agent's lease token must not be able to write the
    /// number its own stop rule is judged by. Any task state; an unknown task is 404.
    private Promise<ApiResponse> observe(ApiRequest request) {
        return parsed(request, ObserveCall.class).flatMap(body -> Result.all(taskId(request),
                                                                             count(body.ctxK(),
                                                                                   "ctxK"),
                                                                             count(body.turns(),
                                                                                   "turns"))
                                                                        .map(Observation::new))
                     .async()
                     .flatMap(seen -> tasks.observe(seen.task(),
                                                    seen.ctxK(),
                                                    seen.turns(),
                                                    clock.get()))
                     .map(_ -> Responses.noContent())
                     .mapError(TaskRoutes::classify);
    }

    private Promise<ApiResponse> waitOn(ApiRequest request) {
        return parsed(request, LeaseCall.Wait.class).flatMap(body -> leased(request,
                                                                            body.epoch(),
                                                                            awaited(body)))
                     .async()
                     .flatMap(this::appliedLeased)
                     .map(_ -> Responses.noContent())
                     .mapError(TaskRoutes::classify);
    }

    private static Result<Command> awaited(LeaseCall.Wait body) {
        return Result.allOf(body.jobs().or(List.of()).stream().map(JobId::jobId).toList()).map(jobs -> new Command.Wait(Waiting.waiting(jobs,
                                                                                                                                        body.ci()
                                                                                                                                            .or(false))));
    }

    private Promise<ApiResponse> release(ApiRequest request) {
        return parsed(request, LeaseCall.Release.class).flatMap(body -> leased(request,
                                                                               body.epoch(),
                                                                               ReleaseReason.releaseReason(body.reason()).map(Command.Release::new)))
                     .async()
                     .flatMap(this::appliedLeased)
                     .map(_ -> Responses.noContent())
                     .mapError(TaskRoutes::classify);
    }

    /// The lease holder parks with its lease (the body names its epoch); the maintainer with the full token, no epoch.
    private Promise<ApiResponse> park(ApiRequest request) {
        return parsed(request, ParkCall.class).async()
                     .flatMap(body -> parked(request, body))
                     .flatMap(this::viewResponse)
                     .mapError(TaskRoutes::classify);
    }

    private Promise<TaskRow> parked(ApiRequest request, ParkCall body) {
        return LeaseToken.presented(request.headers())
                         .map(_ -> parkedByLease(request, body))
                         .or(() -> parkedByCto(request, body));
    }

    private Promise<TaskRow> parkedByLease(ApiRequest request, ParkCall body) {
        return body.epoch()
                   .toResult(invalid("epoch").apply("missing"))
                   .flatMap(epoch -> leased(request,
                                            epoch,
                                            parkRequest(body).map(parkRequest -> new Command.Park(parkRequest, true))))
                   .async()
                   .flatMap(this::appliedLeased);
    }

    private Promise<TaskRow> parkedByCto(ApiRequest request, ParkCall body) {
        return ofTask(request,
                      parkRequest(body).map(parkRequest -> new Command.Park(parkRequest, false))).async()
                     .flatMap(this::appliedByCto);
    }

    /// A request §5.5 refuses (fewer than two options, a recommendation naming none of them, a temper-raised kind, …) is
    /// 422 `park-request-invalid` with the reasons, not a 400: the body parsed.
    private static Result<ParkRequest> parkRequest(ParkCall body) {
        return Option.option(body.request())
                     .toResult(invalid("request").apply("missing"))
                     .flatMap(request -> request.requested()
                                                .mapError(TaskRoutes::invalidRequest));
    }

    private static Cause invalidRequest(Cause cause) {
        return ApiError.Unprocessable.FACTORY.apply("park-request-invalid: " + cause.message());
    }

    private Promise<TaskRow> appliedLeased(LeasedCommand call) {
        return tasks.leased(call.lease(), call.command(), clock.get());
    }

    private Promise<TaskRow> appliedByCto(TaskCommand call) {
        return tasks.command(call.task(), call.command(), CTO, clock.get());
    }

    /// The lease a call presents (its task, the epoch it names, the sha256 of its token) with the command it asks for.
    private static Result<LeasedCommand> leased(ApiRequest request, long epoch, Result<Command> command) {
        return Result.all(lease(request, epoch), command).map(LeasedCommand::new);
    }

    private static Result<Lease> lease(ApiRequest request, long epoch) {
        return Result.all(taskId(request),
                          LeaseToken.presented(request.headers())
                                    .toResult(ApiError.General.UNAUTHORIZED)
                                    .flatMap(LeaseToken::hash))
                     .map((id, hash) -> Lease.lease(id, epoch, hash));
    }

    private static Result<TaskCommand> ofTask(ApiRequest request, Result<Command> command) {
        return Result.all(taskId(request), command).map(TaskCommand::new);
    }

    // ---- the maintainer's commands ------------------------------------------------------------------------------------------
    private Promise<ApiResponse> rule(ApiRequest request) {
        return parsed(request, TaskDecision.Rule.class).flatMap(body -> ofTask(request,
                                                                               ruleOf(body)))
                     .async()
                     .flatMap(this::appliedByCto)
                     .flatMap(this::viewResponse)
                     .mapError(TaskRoutes::classify);
    }

    private static Result<Command> ruleOf(TaskDecision.Rule body) {
        return RulingDecision.rulingDecision(body.option(),
                                             body.text(),
                                             body.know(),
                                             body.extraRounds())
                             .map(Command.Rule::new);
    }

    private Promise<ApiResponse> closeTask(ApiRequest request) {
        return parsed(request, TaskDecision.Close.class).flatMap(body -> closeOf(request, body))
                     .async()
                     .flatMap(close::execute)
                     .flatMap(this::viewResponse)
                     .mapError(TaskRoutes::classify);
    }

    private static Result<CloseTask.Request> closeOf(ApiRequest request, TaskDecision.Close body) {
        return Result.all(taskId(request),
                          Verify.ensure(body.how(),
                                        CLOSE_HOW::contains,
                                        invalid("how")))
                     .map((id, how) -> CloseTask.Request.request(id,
                                                                 MERGED.equals(how),
                                                                 CTO));
    }

    private Promise<ApiResponse> bounce(ApiRequest request) {
        return parsed(request, TaskDecision.Bounce.class).flatMap(body -> ofTask(request,
                                                                                 item(body.item()).map(Command.Bounce::new)))
                     .async()
                     .flatMap(this::appliedByCto)
                     .flatMap(this::viewResponse)
                     .mapError(TaskRoutes::classify);
    }

    private Promise<ApiResponse> viewResponse(TaskRow row) {
        return viewOf(row, none()).map(responses::ok);
    }

    /// The maintainer's item, free text [G]: present, at most [#MAX_ITEM] characters.
    private static Result<String> item(String raw) {
        return Verify.ensure(raw, Verify.Is::present, invalid("item")).flatMap(present -> Verify.ensure(present,
                                                                                                        Verify.Is::lenBetween,
                                                                                                        1,
                                                                                                        MAX_ITEM,
                                                                                                        invalid("item")));
    }

    // ---- shared ------------------------------------------------------------------------------------------------------
    private <T> Result<T> parsed(ApiRequest request, Class<T> type) {
        return mapper.readBytes(request.body(), type);
    }

    static Result<TaskId> taskId(ApiRequest request) {
        return taskIdOf(request.param("id").or(""));
    }

    private static Result<TaskId> taskIdOf(String raw) {
        return Number.parseLong(raw)
                     .mapError(DomainError.unparseable("task id", raw))
                     .flatMap(TaskId::taskId);
    }

    private static Result<Option<Integer>> integer(ApiRequest request, String name) {
        return Values.optional(request.queryParam(name),
                               raw -> Number.parseInt(raw).mapError(DomainError.unparseable(name, raw)));
    }

    /// The task failures with a meaning for the client (§11.1): a lost lease, a task not READY or not HELD, a command
    /// no row admits, a read point that cannot be issued → 409; a guard or a refusal of the request's content → 422; an
    /// unknown task → 404. A composite (parts collected by `Result.all`) whose parts are not all parse failures answers
    /// as its first part that is not one (a parse failure among them is then the lesser news). Anything else as every
    /// route classifies it.
    static Cause classify(Cause cause) {
        return switch (cause) {
            case Causes.CompositeCause composite -> classifyComposite(composite);
            case TaskError.General general -> taskFailure(general);
            case TaskError.Refused refused -> ApiError.Conflict.FACTORY.apply(refused.message());
            case TaskRefusal refusal -> ApiError.Unprocessable.FACTORY.apply(refusal.message());
            case StoreError.TaskNotFound missing -> ApiError.NotFound.FACTORY.apply("task " + missing.task());
            case StoreError.AfterUnknown _, StoreError.JobNotLinked _, StoreError.OptionNotOffered _ -> ApiError.Unprocessable.FACTORY.apply(cause.message());
            default -> ApiRoutes.classify(cause);
        };
    }

    private static Cause classifyComposite(Causes.CompositeCause composite) {
        return Option.from(composite.stream()
                                    .filter(member -> !(member instanceof DomainError || member instanceof JsonError))
                                    .findFirst())
                     .map(TaskRoutes::classify)
                     .or(() -> ApiRoutes.classify(composite));
    }

    private static Cause taskFailure(TaskError.General general) {
        return switch (general) {
            case LEASE_LOST, NOT_READY, NOT_HELD, READ_POINT_UNKNOWN -> ApiError.Conflict.FACTORY.apply(general.message());
            case BAD_NEXT, AFTER_PENDING, NOTHING_AWAITED, NOT_SETTLED, NOT_EXPIRED, NOT_MERGED, AUDIT_PENDING, CI_WAIT_OUTSIDE_CI -> ApiError.Unprocessable.FACTORY.apply(general.message());
        };
    }
}
