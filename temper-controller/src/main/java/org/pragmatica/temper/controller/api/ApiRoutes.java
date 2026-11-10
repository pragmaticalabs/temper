package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
import org.pragmatica.temper.controller.cancel.CancelJob;
import org.pragmatica.temper.controller.events.EventFeed;
import org.pragmatica.temper.controller.flow.Collect;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.LeaseToken;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.submit.GitError;
import org.pragmatica.temper.controller.submit.SubmitJob;
import org.pragmatica.temper.domain.DomainError;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.refusal.NewRefusal;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.api.HostLossDto;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.MaintenanceRunDto;
import org.pragmatica.temper.protocol.api.MaintenanceView;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.RefusalAddRequest;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SubmitRequest;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.api.WsClientMessage;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobQuery;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;
import org.pragmatica.temper.store.api.RefusalRow;
import org.pragmatica.temper.store.api.RefusalStore;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.UnitStore;
import org.pragmatica.temper.store.pg.WireRecords;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.route;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;
import static org.pragmatica.lang.Result.success;


/// The routes below `/api/v1` (m0-design §6.2, m1-design §6). Every one requires the bearer token
/// ([org.pragmatica.temper.controller.http.Router]).
///
/// | Method | Path | Answer |
/// |---|---|---|
/// | POST | `jobs` | 200 `SubmitResponse` · 422 `RefusedResponse` (with the REFUSED job's id) · 400 · 503 (git down) · 409 `lease-lost` (a `task` link at a stale epoch) |
/// | GET | `jobs/{id}` | 200 `JobView` · 404 |
/// | GET | `jobs/{id}/result` | 200 `ResultRecordDto` · 404 until terminal |
/// | GET | `jobs/{id}/testcases?outcome=` | 200 `[TestcaseDto]` |
/// | GET | `jobs/{id}/manifest` | 200 `ManifestView` · 404 (no unit of the job has a manifest spec) |
/// | POST | `jobs/{id}/cancel` | 200 `CancelResponse` · 404 |
/// | GET | `jobs/{id}/log?unit=&tail=` | 200 text · 404 · 503 (host link down) |
/// | GET | `jobs?state=&submittedBy=&sha=&ref=&limit=&before=` | 200 `[JobView]` (newest first, limit 1..1000, default 50; `before=<id>` the next page) |
/// | GET | `hosts` | 200 `[HostView]` (A7: `reactors = foreign + active`) |
/// | GET | `maintenance` | 200 `MaintenanceView` (the latest `pg_dump` run and when the latest good one ended) |
/// | GET | `digest?since=&max=&format=` | 200 `DigestView`, or its text with `format=text` · 400 (bad parameter, cursor ahead of the event log) · 409 (`CURSOR_FOREIGN`); [DigestRoutes] |
/// | GET | `gate/{sha}` | 200 `GateView` (7-40 hex; a NONE answer when no Gate is there) · 400 (not hex, or Gates at two SHAs); [GateRoutes] |
/// | GET | `diff?a=&b=` or `diff?a=&baseSha=` | 200 `DiffAnswer` (`{"diff":DiffView}` or a typed `{"refused":DiffRefusal}`) · 400 (not exactly one form, a bad id or SHA, Gates at two SHAs) · 404 (unknown job); [DiffRoutes] |
/// | GET | `subscribers/{name}/next?terminal=` | 200 `{"event":…}` / `{"gap":…}` · 204 after 30 s with nothing |
/// | POST | `subscribers/{name}/ack` | body `{"ack":{"seq":N}}` → 204 · 404 unknown subscriber |
/// | PUT | `subscribers/{name}/selectors` | body `SelectorChange` → 200 `SubscriberView` · 400 · 404 (an added job is unknown); [SubscriberRoutes] |
/// | GET | `refusals?all=true\|false` | 200 `[RefusalDto]` (active only unless `all=true`) |
/// | POST | `refusals` | body `RefusalAddRequest` → 201 `RefusalDto` · 400 (bad or structural code, unknown matcher) · 409 (the code exists) |
/// | POST | `refusals/{code}/expire` | 200 `RefusalDto` (its `code` and `expiredAt`) · 404 |
/// | GET, POST | `known`, `known/{id}/expire` | the known-failure registry, [KnownRoutes] |
/// | POST | `jobs/{id}/rerun` | an explicit rerun, answered as a submit, 404 for an unknown parent; [RerunRoutes] |
///
/// Every job state answered (a view, a list and its `state` filter, a deduplicated submit) is the one [Views#jobState]
/// derives (ruling R-13, amended) from the units this controller knows run with no recorded start
/// (`HostLinks.unrecordedStarts`), snapshotted BEFORE the rows are read so a start recorded in between is never missed
/// by both, and from `startBound` (`LinkTiming.startBound`), the backstop for a controller with no such memory.
///
/// The two `subscribers` routes are the m0-design §6.5 long-poll fallback that replaces `WS /events`.
///
/// Scope (D3): the GETs are READ routes, which the read token may call, except `subscribers/{name}/next`, which is a
/// GET but WRITE: it creates the subscriber it names, a stored cursor that the next `watch` under that name resumes
/// from. Every POST is WRITE (the [Route#route] default), except `POST jobs`, which a lease token may call too
/// (m3-design §6.4): a submit with `task: {id, epoch}` authenticates with that task's lease token and links the job to
/// the task at that epoch; a lease-token submit without `task`, and a full-token submit with one, are 400.
public record ApiRoutes(Responses responses,
                        JsonMapper mapper,
                        SubmitJob submit,
                        CancelJob cancel,
                        TailLog tailLog,
                        JobStore jobs,
                        ResultStore results,
                        UnitStore units,
                        RefusalStore refusals,
                        MaintenanceStore maintenance,
                        HostLinks links,
                        EventFeed feed,
                        Supplier<Instant> clock,
                        Duration startBound) {
    public static final int DEFAULT_LIST_LIMIT = 50;

    public static ApiRoutes apiRoutes(Responses responses,
                                      JsonMapper mapper,
                                      SubmitJob submit,
                                      CancelJob cancel,
                                      TailLog tailLog,
                                      JobStore jobs,
                                      ResultStore results,
                                      UnitStore units,
                                      RefusalStore refusals,
                                      MaintenanceStore maintenance,
                                      HostLinks links,
                                      EventFeed feed,
                                      Supplier<Instant> clock,
                                      Duration startBound) {
        return new ApiRoutes(responses,
                             mapper,
                             submit,
                             cancel,
                             tailLog,
                             jobs,
                             results,
                             units,
                             refusals,
                             maintenance,
                             links,
                             feed,
                             clock,
                             startBound);
    }

    /// `GET subscribers/{name}/next` stays WRITE although a GET: it creates the subscriber it names when the name is
    /// new, a stored cursor, so the read token must not reach it.
    public List<Route> routes() {
        return List.of(scopedRoute(HttpMethod.POST, "jobs", Route.Scope.LEASE_OR_WRITE, this::submitJob),
                       readRoute("jobs", this::listJobs),
                       readRoute("jobs/{id}", this::getJob),
                       readRoute("jobs/{id}/result", this::getResult),
                       readRoute("jobs/{id}/testcases", this::listTestcases),
                       readRoute("jobs/{id}/manifest", this::getManifest),
                       route(HttpMethod.POST, "jobs/{id}/cancel", this::cancelJob),
                       readRoute("jobs/{id}/log", this::tail),
                       readRoute("hosts", this::listHosts),
                       readRoute("maintenance", this::maintenanceRuns),
                       scopedRoute(HttpMethod.GET,
                                   "subscribers/{name}/next",
                                   Route.Scope.WRITE,
                                   Route.Effect.WRITES,
                                   this::nextEvent),
                       route(HttpMethod.POST, "subscribers/{name}/ack", this::ackEvent),
                       readRoute("refusals", this::listRefusals),
                       route(HttpMethod.POST, "refusals", this::addRefusal),
                       route(HttpMethod.POST, "refusals/{code}/expire", this::expireRefusal));
    }

    // ---- jobs --------------------------------------------------------------------------------------------------
    private Promise<ApiResponse> submitJob(ApiRequest request) {
        return submitRequest(request).async()
                            .flatMap(submit::execute)
                            .flatMap(this::submitted)
                            .mapError(ApiRoutes::classify);
    }

    private Result<SubmitJob.Request> submitRequest(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                SubmitRequest.class)
                     .flatMap(body -> submitOf(body,
                                               LeaseToken.presented(request.headers()),
                                               clock.get()));
    }

    /// A lease token must name its task; the full token must not (its submit is not under a lease). A linked submit
    /// carries `now`, the controller's clock, into the lease's TTL restart.
    private static Result<SubmitJob.Request> submitOf(SubmitRequest body, Option<String> leaseToken, Instant now) {
        return leaseToken.map(token -> underLease(body, token, now))
                         .or(() -> unlinked(body));
    }

    private static Result<SubmitJob.Request> underLease(SubmitRequest body, String token, Instant now) {
        return Result.all(body.toDomain(),
                          leaseOf(body, token))
                     .map((job, lease) -> SubmitJob.Request.underLease(job, lease, now));
    }

    private static Result<Lease> leaseOf(SubmitRequest body, String token) {
        return body.task()
                   .toResult(invalid("task").apply("missing: a lease-token submit names its task"))
                   .flatMap(link -> linkedLease(link, token));
    }

    private static Result<Lease> linkedLease(SubmitRequest.TaskLink link, String token) {
        return Result.all(TaskId.taskId(link.id()),
                          LeaseToken.hash(token))
                     .map((id, hash) -> Lease.lease(id,
                                                    link.epoch(),
                                                    hash));
    }

    private static Result<SubmitJob.Request> unlinked(SubmitRequest body) {
        return Verify.ensure(body,
                             present -> present.task()
                                               .isEmpty(),
                             invalid("task").apply("a submit for a task authenticates with its lease token"))
                     .flatMap(SubmitRequest::toDomain)
                     .map(SubmitJob.Request::request);
    }

    /// The submit answer as the route sends it; [RerunRoutes] answers a rerun the same way.
    Promise<ApiResponse> submitted(SubmitJob.Response answer) {
        return switch (answer) {
            case SubmitJob.Response.Submitted submitted -> submitResponse(submitted.outcome()).map(responses::ok);
            case SubmitJob.Response.Refused refused -> Promise.success(responses.json(HttpStatus.UNPROCESSABLE_ENTITY,
                                                                                      RefusedResponse.refusedResponse(refused.refusal(),
                                                                                                                      some(refused.job().value()))));
        };
    }

    private Promise<SubmitResponse> submitResponse(SubmitOutcome outcome) {
        return switch (outcome) {
            case SubmitOutcome.Accepted accepted -> Promise.success(SubmitResponse.submitResponse(accepted.job().value(),
                                                                                                  JobState.QUEUED,
                                                                                                  false));
            case SubmitOutcome.Deduplicated deduplicated -> reusedJob(deduplicated);
        };
    }

    /// The reused job's state as a view of it shows it ([Views#jobState]): a reused job whose start was not recorded
    /// answers UNKNOWN, not QUEUED. The state the submit read stands only when the job is gone by now. A failed read
    /// fails the answer (no recovery): the resubmit that follows deduplicates onto the same job again.
    private Promise<SubmitResponse> reusedJob(SubmitOutcome.Deduplicated deduplicated) {
        var unrecorded = links.unrecordedStarts();

        return jobs.job(deduplicated.job())
                   .map(row -> reused(deduplicated, row, unrecorded));
    }

    private SubmitResponse reused(SubmitOutcome.Deduplicated deduplicated,
                                  Option<JobRow> row,
                                  Set<UnitAttempt> unrecorded) {
        var now = clock.get();

        return SubmitResponse.submitResponse(deduplicated.job().value(),
                                             row.map(found -> Views.jobState(found, now, startBound, unrecorded))
                                                .or(deduplicated.state()),
                                             true);
    }

    private Promise<ApiResponse> getJob(ApiRequest request) {
        return jobId(request).async()
                    .flatMap(this::shownJob)
                    .mapError(ApiRoutes::classify);
    }

    private Promise<ApiResponse> shownJob(JobId id) {
        var unrecorded = links.unrecordedStarts();

        return jobs.job(id)
                   .flatMap(ApiRoutes::existingJob)
                   .map(row -> jobResponse(row, unrecorded));
    }

    private static Promise<JobRow> existingJob(Option<JobRow> row) {
        return row.async(ApiError.NotFound.FACTORY.apply("job"));
    }

    private ApiResponse jobResponse(JobRow row, Set<UnitAttempt> unrecorded) {
        return responses.ok(Views.jobView(row, clock.get(), startBound, unrecorded));
    }

    private Promise<ApiResponse> getResult(ApiRequest request) {
        return jobId(request).async()
                    .flatMap(results::result)
                    .flatMap(ApiRoutes::existingResult)
                    .map(responses::ok)
                    .mapError(ApiRoutes::classify);
    }

    private static Promise<ResultRecordDto> existingResult(Option<ResultRecord> record) {
        return record.map(WireRecords::resultRecordDto)
                     .async(ApiError.NotFound.FACTORY.apply("result"));
    }

    private Promise<ApiResponse> listTestcases(ApiRequest request) {
        return Result.all(jobId(request),
                          Values.optional(request.queryParam("outcome"),
                                          Outcome::outcome))
                     .async()
                     .flatMap(results::testcases)
                     .map(this::testcasesResponse)
                     .mapError(ApiRoutes::classify);
    }

    private ApiResponse testcasesResponse(List<TestcaseRow> rows) {
        return responses.ok(rows.stream().map(TestcaseDto::testcaseDto).toList());
    }

    /// The testcases are read only once the manifest exists (404 otherwise).
    private Promise<ApiResponse> getManifest(ApiRequest request) {
        return jobId(request).async()
                    .flatMap(this::manifestOf)
                    .map(responses::ok)
                    .mapError(ApiRoutes::classify);
    }

    private Promise<ManifestView> manifestOf(JobId job) {
        return results.manifest(job)
                      .flatMap(ApiRoutes::existingManifest)
                      .mapWith(_ -> results.testcases(job,
                                                      none()),
                               ManifestViews::manifestView);
    }

    private static Promise<JobManifest> existingManifest(Option<JobManifest> manifest) {
        return manifest.async(ApiError.NotFound.FACTORY.apply("manifest"));
    }

    private Promise<ApiResponse> cancelJob(ApiRequest request) {
        return jobId(request).async()
                    .map(CancelJob.Request::request)
                    .flatMap(cancel::execute)
                    .map(this::cancelResponse)
                    .mapError(ApiRoutes::classify);
    }

    private ApiResponse cancelResponse(CancelJob.Response response) {
        return responses.ok(CancelResponse.cancelResponse(response.outcome().state()));
    }

    private Promise<ApiResponse> tail(ApiRequest request) {
        return Result.all(jobId(request),
                          Values.optional(request.queryParam("unit"),
                                          UnitName::unitName),
                          integer(request, "tail"))
                     .async()
                     .flatMap(this::tailOf)
                     .map(ApiRoutes::textResponse)
                     .mapError(ApiRoutes::classify);
    }

    private Promise<Message.LogChunk> tailOf(JobId job, Option<UnitName> unit, Option<Integer> lines) {
        return tailLog.execute(TailLog.Request.request(job, unit, lines.or(TailLog.DEFAULT_LINES)));
    }

    private static ApiResponse textResponse(Message.LogChunk chunk) {
        return Responses.text(HttpStatus.OK, chunk.text());
    }

    private Promise<ApiResponse> listJobs(ApiRequest request) {
        return jobQuery(request).async()
                       .flatMap(this::listed)
                       .map(responses::ok)
                       .mapError(ApiRoutes::classify);
    }

    /// The `state` filter matches the state the views show ([Views#jobState]): a stored QUEUED job can show UNKNOWN,
    /// so `state=UNKNOWN` reads the stored QUEUED jobs too, and `state=QUEUED` leaves out those that show UNKNOWN.
    /// The filter runs on the shown state, after the store's `limit`, so each store read is paged ([#matching]) until
    /// `limit` jobs match or the store has no older one: a page that the filter thins never hides an older match.
    /// Paging, not a filter in the store's SQL, keeps [Views] the one place that derives a shown state.
    private Promise<List<JobView>> listed(JobQuery query) {
        var unrecorded = links.unrecordedStarts();

        return query.state()
                    .map(state -> listedIn(query, state, unrecorded))
                    .or(() -> matching(query,
                                       _ -> true,
                                       unrecorded,
                                       List.of()));
    }

    /// `state=UNKNOWN` reads the stored QUEUED jobs that show UNKNOWN, then the stored UNKNOWN jobs.
    private Promise<List<JobView>> listedIn(JobQuery query, JobState state, Set<UnitAttempt> unrecorded) {
        return state == JobState.UNKNOWN
               ? listedUnknown(query, unrecorded)
               : matching(query, view -> view.state() == state, unrecorded, List.of());
    }

    /// Sequential, QUEUED read first: a job's UNKNOWN mark moves it from stored QUEUED to stored UNKNOWN, never back,
    /// so a job that shows UNKNOWN throughout is in the second read if the mark commits after the first one. Read in
    /// the other order, or concurrently, a mark committing between the two reads leaves the job out of both.
    private Promise<List<JobView>> listedUnknown(JobQuery query, Set<UnitAttempt> unrecorded) {
        var storedQueued = query.withState(JobState.QUEUED);

        return shownUnknownOf(storedQueued, unrecorded).flatMap(queued -> withStoredUnknown(query, unrecorded, queued));
    }

    private Promise<List<JobView>> withStoredUnknown(JobQuery query,
                                                     Set<UnitAttempt> unrecorded,
                                                     List<JobView> queued) {
        return shownUnknownOf(query, unrecorded).map(unknown -> newestFirst(unknown, queued, query.limit()));
    }

    private Promise<List<JobView>> shownUnknownOf(JobQuery stored, Set<UnitAttempt> unrecorded) {
        return matching(stored, ApiRoutes::shownUnknown, unrecorded, List.of());
    }

    private static boolean shownUnknown(JobView view) {
        return view.state() == JobState.UNKNOWN;
    }

    /// Iteration over the store's pages, newest first: the views of `stored` that `wanted` admits, until `limit` of them
    /// are found or a page comes back short (the store has no older job). Every page is derived with the one snapshot
    /// of `unrecorded` taken before the first page was read.
    private Promise<List<JobView>> matching(JobQuery stored,
                                            Predicate<JobView> wanted,
                                            Set<UnitAttempt> unrecorded,
                                            List<JobView> found) {
        return jobs.list(stored)
                   .flatMap(page -> nextPage(stored, wanted, unrecorded, found, page));
    }

    private Promise<List<JobView>> nextPage(JobQuery stored,
                                            Predicate<JobView> wanted,
                                            Set<UnitAttempt> unrecorded,
                                            List<JobView> found,
                                            List<JobRow> page) {
        var more = Stream.concat(found.stream(), shown(page, unrecorded).filter(wanted)).limit(stored.limit()).toList();

        return more.size() == stored.limit() || page.size() < stored.limit()
               ? Promise.success(more)
               : matching(stored.after(page.getLast().id()),
                          wanted,
                          unrecorded,
                          more);
    }

    /// The two reads are separate snapshots, so a job that changed state between them can be in both: it is kept once.
    private static List<JobView> newestFirst(List<JobView> unknown, List<JobView> queued, int limit) {
        return Stream.concat(unknown.stream(),
                             queued.stream())
                     .collect(Collectors.toMap(JobView::job,
                                               view -> view,
                                               (first, _) -> first,
                                               TreeMap::new))
                     .descendingMap()
                     .values()
                     .stream()
                     .limit(limit)
                     .toList();
    }

    private Stream<JobView> shown(List<JobRow> rows, Set<UnitAttempt> unrecorded) {
        var now = clock.get();

        return rows.stream()
                   .map(row -> Views.jobView(row, now, startBound, unrecorded));
    }

    private static Result<JobQuery> jobQuery(ApiRequest request) {
        return Result.all(Values.optional(request.queryParam("state"),
                                          ApiRoutes::jobState),
                          Values.optional(request.queryParam("submittedBy"),
                                          OwnerTag::ownerTag),
                          Values.optional(request.queryParam("sha"),
                                          Sha::sha),
                          Values.optional(request.queryParam("ref"),
                                          Ref::ref),
                          integer(request, "limit"),
                          Values.optional(request.queryParam("before"),
                                          ApiRoutes::jobIdOf))
                     .flatMap((state, owner, sha, ref, limit, before) -> JobQuery.jobQuery(state,
                                                                                           owner,
                                                                                           sha,
                                                                                           ref,
                                                                                           limit.or(DEFAULT_LIST_LIMIT))
                                                                                 .map(query -> paged(query, before)));
    }

    /// `before=<id>` (WP-C, `temper jobs`): the next page, the jobs older than that id.
    private static JobQuery paged(JobQuery query, Option<JobId> before) {
        return before.map(query::after)
                     .or(query);
    }

    private static Result<JobId> jobIdOf(String raw) {
        return Number.parseLong(raw)
                     .mapError(DomainError.unparseable("job id", raw))
                     .flatMap(JobId::jobId);
    }

    private static Result<JobState> jobState(String raw) {
        return Option.from(Arrays.stream(JobState.values())
                                 .filter(state -> state.name()
                                                       .equalsIgnoreCase(raw))
                                 .findFirst()).toResult(invalid("state").apply(raw));
    }

    // ---- hosts -------------------------------------------------------------------------------------------------
    private Promise<ApiResponse> listHosts(ApiRequest request) {
        return hostViews().map(responses::ok)
                        .mapError(ApiRoutes::classify);
    }

    /// Fork-join over the configured hosts. `Collect.all`: no store failure of `active` has an [ApiError] status, so the
    /// answer is 500 either way, and the router's log names every host that could not be read.
    private Promise<List<HostView>> hostViews() {
        var now = clock.get();

        return Collect.all(links.all().stream().map(link -> hostView(link, now)).toList());
    }

    private Promise<HostView> hostView(HostLink link, Instant now) {
        return units.active(link.name())
                    .map(active -> hostViewOf(link,
                                              active.size(),
                                              now));
    }

    /// A7: `reactors = foreign (worker facts) + active (store)`; temper units are never counted from `ps`. M4
    /// (m4-design §11): `state` is `LOST` while the link holds a LOST mark, `link` the link's own state; `retired` is
    /// judged at `now`, read once per request, so every host is judged at the same instant.
    private static HostView hostViewOf(HostLink link, int active, Instant now) {
        var foreign = link.facts().map(HostFacts::foreignReactors).or(0);

        return HostView.hostView(link.name().value(),
                                 link.state().wire(),
                                 link.lost().map(loss -> HostLossDto.hostLossDto(loss.at(), loss.reason())),
                                 link.config().cap(),
                                 foreign + active,
                                 foreign,
                                 active,
                                 link.facts().map(HostFactsDto::hostFactsDto),
                                 link.config().sharedCpu(),
                                 link.config().retireAt(),
                                 link.config().retiredSince(now).isPresent());
    }

    // ---- maintenance (m1-design §3.4) ---------------------------------------------------------------------------
    /// Fork-join of the two reads; they are separate snapshots, so a run recorded between them can show as the latest
    /// run while the latest good one is still the older, which only delays a cleared `dump-stale` by one check.
    private Promise<ApiResponse> maintenanceRuns(ApiRequest request) {
        return Promise.all(maintenance.latest(MaintenanceKind.PG_DUMP),
                           maintenance.lastGood(MaintenanceKind.PG_DUMP))
                      .map(ApiRoutes::maintenanceView)
                      .map(responses::ok)
                      .mapError(ApiRoutes::classify);
    }

    /// Also the digest's `maintenance` part ([DigestViews]).
    static MaintenanceView maintenanceView(Option<MaintenanceRun> latest, Option<MaintenanceRun> lastGood) {
        return MaintenanceView.maintenanceView(latest.map(ApiRoutes::maintenanceRunDto),
                                               lastGood.map(MaintenanceRun::endedAt));
    }

    private static MaintenanceRunDto maintenanceRunDto(MaintenanceRun run) {
        return MaintenanceRunDto.maintenanceRunDto(run.kind().wire(),
                                                   run.startedAt(),
                                                   run.endedAt(),
                                                   run.ok(),
                                                   run.detail());
    }

    // ---- events ------------------------------------------------------------------------------------------------
    private Promise<ApiResponse> nextEvent(ApiRequest request) {
        return feed.next(subscriber(request),
                         terminal(request))
                   .map(this::eventResponse)
                   .mapError(ApiRoutes::classify);
    }

    private ApiResponse eventResponse(Option<WsServerMessage> found) {
        return found.map(responses::ok)
                    .or(Responses::noContent);
    }

    private static String subscriber(ApiRequest request) {
        return request.param("name")
                      .or("");
    }

    private static boolean terminal(ApiRequest request) {
        return request.queryParam("terminal")
                      .filter("true"::equalsIgnoreCase)
                      .isPresent();
    }

    private Promise<ApiResponse> ackEvent(ApiRequest request) {
        return ackSeq(request).async()
                     .flatMap(seq -> feed.ack(subscriber(request),
                                              seq))
                     .map(_ -> Responses.noContent())
                     .mapError(ApiRoutes::classify);
    }

    /// The body is `{"ack":{"seq":N}}` (`WsClientMessage.WsAck`).
    private Result<Long> ackSeq(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                WsClientMessage.class)
                     .flatMap(ApiRoutes::ackSeq);
    }

    private static Result<Long> ackSeq(WsClientMessage message) {
        return switch (message) {
            case WsClientMessage.WsAck ack -> success(ack.seq());
            case WsClientMessage.WsSubscribe _ -> invalid("ack body").apply("subscribe").result();
        };
    }

    // ---- refusals (m1-design §6) -------------------------------------------------------------------------------
    private Promise<ApiResponse> listRefusals(ApiRequest request) {
        var all = request.queryParam("all").filter("true"::equalsIgnoreCase).isPresent();

        return refusals.list()
                       .map(rows -> refusalsResponse(rows, all))
                       .mapError(ApiRoutes::classify);
    }

    private ApiResponse refusalsResponse(List<RefusalRow> rows, boolean all) {
        return responses.ok(rows.stream()
                                .filter(row -> all || row.expiredAt()
                                                         .isEmpty())
                                .map(ApiRoutes::refusalDto)
                                .toList());
    }

    private Promise<ApiResponse> addRefusal(ApiRequest request) {
        return newRefusal(request).async()
                         .flatMap(this::stored)
                         .map(row -> responses.json(HttpStatus.CREATED,
                                                    refusalDto(row)))
                         .mapError(ApiRoutes::classify);
    }

    private Promise<RefusalRow> stored(NewRefusal refusal) {
        return refusals.add(refusal);
    }

    /// The body parses into a refusal the domain can evaluate, or it is a 400.
    private Result<NewRefusal> newRefusal(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                RefusalAddRequest.class)
                     .flatMap(RefusalAddRequest::toDomain);
    }

    private Promise<ApiResponse> expireRefusal(ApiRequest request) {
        return refusals.expire(request.param("code").or(""))
                       .map(row -> responses.ok(refusalDto(row)))
                       .mapError(ApiRoutes::classify);
    }

    private static RefusalDto refusalDto(RefusalRow row) {
        return RefusalDto.refusalDto(row.rule(), row.issue(), row.reason(), row.until(), row.expiredAt());
    }

    // ---- shared ------------------------------------------------------------------------------------------------
    private static Result<Option<Integer>> integer(ApiRequest request, String name) {
        return Values.optional(request.queryParam(name),
                               raw -> Number.parseInt(raw).mapError(DomainError.unparseable(name, raw)));
    }

    static Result<JobId> jobId(ApiRequest request) {
        var raw = request.param("id").or("");

        return Number.parseLong(raw)
                     .mapError(DomainError.unparseable("job id", raw))
                     .flatMap(JobId::jobId);
    }

    private static Cause gitUnavailable(Cause cause) {
        return ApiError.Unavailable.FACTORY.apply("git", cause);
    }

    private static boolean allUnparseable(Causes.CompositeCause composite) {
        return composite.stream()
                        .allMatch(ApiRoutes::unparseable);
    }

    /// A composite counts when all its members do: `Result.all` over parts that are themselves collected by
    /// `Result.all` (the digest's query) nests one composite in another.
    private static boolean unparseable(Cause cause) {
        return switch (cause) {
            case DomainError _, JsonError _ -> true;
            case Causes.CompositeCause composite -> allUnparseable(composite);
            default -> false;
        };
    }

    /// Failures with a meaning for the client: the request does not parse (one failure, or several collected by
    /// `Result.all`), or a digest cursor is ahead of the event log → 400; the job/subscriber/refusal is unknown → 404;
    /// the refusal code exists, or a digest cursor is not from this event log (`CURSOR_FOREIGN`) → 409; git is down → 503. Anything else stays as it is
    /// (→ 500, or its own [ApiError] status).
    static Cause classify(Cause cause) {
        return switch (cause) {
            case ApiError error -> error;
            case DomainError _, JsonError _ -> ApiError.BadRequest.badRequest(cause);
            case Causes.CompositeCause composite when allUnparseable(composite) -> ApiError.BadRequest.badRequest(cause);
            case StoreError.JobNotFound missing -> ApiError.NotFound.FACTORY.apply("job " + missing.job());
            case StoreError.SubscriberNotFound missing -> ApiError.NotFound.FACTORY.apply("subscriber " + missing.name());
            case StoreError.RefusalNotFound _, StoreError.RefusalExists _ -> refusalFailure(cause);
            case StoreError.CursorAhead _ -> ApiError.BadRequest.badRequest(cause);
            case StoreError.CursorForeign foreign -> ApiError.Conflict.FACTORY.apply(foreign.message());
            case GitError _ -> gitUnavailable(cause);
            case TaskError _ -> TaskRoutes.classify(cause);
            default -> cause;
        };
    }

    /// An unknown refusal code is 404; one that exists is 409 (codes are never reused).
    private static Cause refusalFailure(Cause cause) {
        return switch (cause) {
            case StoreError.RefusalNotFound missing -> ApiError.NotFound.FACTORY.apply("refusal " + missing.code());
            case StoreError.RefusalExists exists -> ApiError.Conflict.FACTORY.apply(exists.message());
            default -> cause;
        };
    }
}
