package org.pragmatica.kiln.service.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.github.PullsPage;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.temper.HostsRead;
import org.pragmatica.kiln.core.summary.Counts;
import org.pragmatica.kiln.core.summary.Summary;
import org.pragmatica.kiln.core.summary.SummaryText;
import org.pragmatica.kiln.service.conflict.ConflictBoard;
import org.pragmatica.kiln.service.flow.Steps;
import org.pragmatica.kiln.service.github.GitHubBudget;
import org.pragmatica.kiln.service.http.ApiError;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.http.ApiResponse;
import org.pragmatica.kiln.service.http.Route;
import org.pragmatica.kiln.service.scan.MacIngest;
import org.pragmatica.kiln.service.pipeline.PipelineView;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.NonGitView;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.web.CheckoutPages;
import org.pragmatica.kiln.service.web.Dashboard;
import org.pragmatica.kiln.service.web.DashboardView;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.kiln.service.web.RateBudget;
import org.pragmatica.kiln.service.web.Sources;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.vo.Uuid;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Every route of kiln-f1-design §4 (spec §6, §7), read-only but for the Mac ingest. Each answer is built from the
/// store's tables and the source states, never from "the last pass" (§3.4), and every page and every JSON answer shows
/// the state of each source it rests on, a missing one as `NONE` / "no state recorded": no source failure makes a count
/// read 0 unannounced (WP9 open item 12).
///
/// - `GET /?tab=`: the dashboard ([Dashboard]), its open tab an allowlisted section id (Open PRs otherwise), over the
///   configured bases and hosts, the GitHub poller's budget and skipped PRs, every host's last passes, the listed rows of
///   every host and every Mac row (WP9 open items 1, 2, 4).
/// - `GET /checkouts?status=<NAME>&host=&page=`: a drill-down page; `status` is a [CheckoutStatus] name (`IN_USE`, the
///   links' own spelling, WP9 open item 5).
/// - `GET /non-git?host=&root=`: a host's non-git directories under one root.
/// - `GET /api/v1/github-budget`: kiln's GitHub rate sample, credential mode and drift reads ([Views#githubBudget]).
/// - `GET /api/v1/prs`, `/api/v1/checkouts?status=&host=&page=`, `/api/v1/sources`: [Views] JSON.
/// - `GET /api/v1/summary?since=&format=text`: [SummaryText], `asOf` the store's committed watermark, never a bare `now`
///   (WP9 open item 10); without `since`, the last 60 minutes (spec §7).
/// - `GET /api/v1/changes?epoch=&after=&max=`: the change feed, one of [FeedReply]'s three shapes,
///   with every `source_state` row's last success and its `configured` period, never the stretched one: data a rate
///   limit slows is stale data, and the reader must see it ([Views#ages]); `after` ≥ 0 (default 0), `max` 1..500
///   (default 500), `epoch` a UUID when given.
/// - `POST /api/v1/ingest/mac`: the body to [MacIngest]; answers `{"outcome":…,"rows":N}`. Only with
///   `X-Kiln-Ingest: 1` and no `Origin` (403 otherwise).
/// - `GET /api/v1/conflicts`: the merge-tree conflict early warning's board (temper #100, [Views#conflicts]): the
///   release tip, current and carried predictions with hot paths, and the cost of the last checks. Textual only.
/// - `GET /api/v1/work`: the work ledger's board ([Views#work]). `POST /api/v1/ingest/work`: one ledger event
///   ([WorkRequest]), under the same header rule as the Mac ingest; answers [Views.WorkRecordedJson]. The dashboard's
///   Work tab hides merged items older than a week unless the page is asked with `merged=all`.
/// - `GET /api/v1/pipeline/baseline`: the pipeline baseline (#102, [PipelineJson]): its state, "measured of total", the
///   weekly rows and the per-PR rows. Read only; the Pipeline tab shows the same.
public record ApiRoutes(KilnStore store,
                        MacIngest ingest,
                        List<BranchName> bases,
                        List<HostName> hosts,
                        Supplier<Option<Observed<RateBudget>>> budget,
                        Supplier<List<Observed<PullsPage.Skipped>>> skipped,
                        Supplier<Periods> periods,
                        Periods configured,
                        Supplier<Instant> clock,
                        Supplier<GitHubBudget> githubBudget,
                        Supplier<Option<Observed<HostsRead.Answered>>> fleet,
                        Supplier<Promise<PipelineView>> pipeline,
                        Supplier<Option<ConflictBoard>> conflicts) {
    /// Without `since`, the summary covers the last 60 minutes (spec §7).
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(60);
    /// The header only `kiln-ingest` sends with a Mac push.
    public static final String INGEST_HEADER = "X-Kiln-Ingest";
    /// The most rows one `/api/v1/changes` page lists, and its default.
    public static final int MAX_FEED = 500;
    /// Every status: the Mac's rows are listed in full (spec §5.4).
    static final EnumSet<CheckoutStatus> EVERY = EnumSet.allOf(CheckoutStatus.class);

    public ApiRoutes {
        bases = List.copyOf(bases);
        hosts = List.copyOf(hosts);
    }

    public static ApiRoutes apiRoutes(KilnStore store,
                                      MacIngest ingest,
                                      List<BranchName> bases,
                                      List<HostName> hosts,
                                      Supplier<Option<Observed<RateBudget>>> budget,
                                      Supplier<List<Observed<PullsPage.Skipped>>> skipped,
                                      Supplier<Periods> periods,
                                      Periods configured,
                                      Supplier<Instant> clock,
                                      Supplier<GitHubBudget> githubBudget) {
        return apiRoutes(store,
                         ingest,
                         bases,
                         hosts,
                         budget,
                         skipped,
                         periods,
                         configured,
                         clock,
                         githubBudget,
                         Option::none);
    }

    /// Without the pipeline baseline: its tab and route show [PipelineView#NONE].
    public static ApiRoutes apiRoutes(KilnStore store,
                                      MacIngest ingest,
                                      List<BranchName> bases,
                                      List<HostName> hosts,
                                      Supplier<Option<Observed<RateBudget>>> budget,
                                      Supplier<List<Observed<PullsPage.Skipped>>> skipped,
                                      Supplier<Periods> periods,
                                      Periods configured,
                                      Supplier<Instant> clock,
                                      Supplier<GitHubBudget> githubBudget,
                                      Supplier<Option<Observed<HostsRead.Answered>>> fleet) {
        return apiRoutes(store,
                         ingest,
                         bases,
                         hosts,
                         budget,
                         skipped,
                         periods,
                         configured,
                         clock,
                         githubBudget,
                         fleet,
                         () -> Promise.success(PipelineView.NONE),
                         Option::none);
    }

    /// With the pipeline baseline and the conflict watch's board (temper #100).
    public static ApiRoutes apiRoutes(KilnStore store,
                                      MacIngest ingest,
                                      List<BranchName> bases,
                                      List<HostName> hosts,
                                      Supplier<Option<Observed<RateBudget>>> budget,
                                      Supplier<List<Observed<PullsPage.Skipped>>> skipped,
                                      Supplier<Periods> periods,
                                      Periods configured,
                                      Supplier<Instant> clock,
                                      Supplier<GitHubBudget> githubBudget,
                                      Supplier<Option<Observed<HostsRead.Answered>>> fleet,
                                      Supplier<Promise<PipelineView>> pipeline,
                                      Supplier<Option<ConflictBoard>> conflicts) {
        return new ApiRoutes(store,
                             ingest,
                             bases,
                             hosts,
                             budget,
                             skipped,
                             periods,
                             configured,
                             clock,
                             githubBudget,
                             fleet,
                             pipeline,
                             conflicts);
    }

    public List<Route> routes() {
        return List.of(Route.get("/", this::dashboard),
                       Route.get("/checkouts", this::drillDown),
                       Route.get("/non-git", this::nonGit),
                       Route.get("/api/v1/prs", _ -> prs()),
                       Route.get("/api/v1/checkouts", this::checkouts),
                       Route.get("/api/v1/sources", _ -> sources()),
                       Route.get("/api/v1/github-budget", _ -> githubBudgetView()),
                       Route.get("/api/v1/summary", this::summary),
                       Route.get("/api/v1/changes", this::changes),
                       Route.get("/api/v1/work", _ -> work()),
                       Route.get("/api/v1/pipeline/baseline", _ -> pipelineBaseline()),
                       Route.get("/api/v1/conflicts",
                                 _ -> json(Views.conflicts(conflicts.get()))),
                       Route.post("/api/v1/ingest/mac", this::ingested),
                       Route.post("/api/v1/ingest/work", this::workIngested));
    }

    /// GitHub, the mirror, temper, the fleet alarms, every configured host, and the Mac: the sources every answer reports.
    public List<Source> expected() {
        return Stream.of(Stream.of(Source.GITHUB, Source.MIRROR, Source.TEMPER, Source.HOSTS),
                         hosts.stream().map(Source::host),
                         Stream.of(Source.MAC))
                     .flatMap(Function.identity())
                     .toList();
    }

    private Promise<Sources> sourceStates() {
        return store.sources()
                    .all()
                    .map(all -> new Sources(all,
                                            periods.get()));
    }

    // ---- pages -------------------------------------------------------------------------------------------------------
    private Promise<ApiResponse> dashboard(ApiRequest request) {
        var allMerged = request.param("merged").filter("all"::equals).isPresent();

        return gathered().map(view -> ApiResponse.html(Dashboard.render(view,
                                                                        clock.get(),
                                                                        request.param("tab"),
                                                                        allMerged)));
    }

    /// The dashboard's inputs: one read of each view, then the non-git list of each host it shows.
    Promise<DashboardView> gathered() {
        var views = store.views();

        return Promise.all(sourceStates(),
                           views.passes(),
                           views.openPrs(),
                           views.bases(),
                           views.counts(),
                           views.listed(Dashboard.LISTED,
                                        none()),
                           views.listed(EVERY,
                                        some(HostName.MAC)),
                           views.digest(),
                           store.work().board())
                      .flatMap((sources, passes, prs, runs, counts, listed, mac, digest, work) -> withNonGit(new DashboardView(sources,
                                                                                                                               bases,
                                                                                                                               hosts,
                                                                                                                               budget.get(),
                                                                                                                               skipped.get(),
                                                                                                                               passes,
                                                                                                                               prs,
                                                                                                                               runs,
                                                                                                                               counts,
                                                                                                                               listed,
                                                                                                                               mac,
                                                                                                                               Map.of(),
                                                                                                                               digest,
                                                                                                                               work,
                                                                                                                               fleet.get(),
                                                                                                                               PipelineView.NONE,
                                                                                                                               conflicts.get())))
                      .flatMap(view -> pipeline.get()
                                               .map(view::withPipeline));
    }

    private Promise<DashboardView> withNonGit(DashboardView view) {
        var shown = Dashboard.hosts(view);

        return Steps.sequence(shown,
                              host -> store.views()
                                           .nonGit(host))
                    .map(lists -> nonGitOf(shown, lists))
                    .map(nonGit -> new DashboardView(view.sources(),
                                                     view.bases(),
                                                     view.hosts(),
                                                     view.budget(),
                                                     view.skipped(),
                                                     view.passes(),
                                                     view.prs(),
                                                     view.runs(),
                                                     view.counts(),
                                                     view.listed(),
                                                     view.mac(),
                                                     nonGit,
                                                     view.digest(),
                                                     view.work(),
                                                     view.fleet(),
                                                     view.pipeline(),
                                                     view.conflicts()));
    }

    private static Map<HostName, List<NonGitView>> nonGitOf(List<HostName> hosts, List<List<NonGitView>> lists) {
        return IntStream.range(0,
                               hosts.size())
                        .boxed()
                        .collect(Collectors.toMap(hosts::get, lists::get));
    }

    /// A drill-down's parameters: the status, one host or every host, the page.
    record Drill(Option<CheckoutStatus> status, Option<HostName> host, int page) {}

    /// The non-git page's parameters.
    record NonGitQuery(HostName host, String root) {}

    private Promise<ApiResponse> drillDown(ApiRequest request) {
        return Result.all(status(request.param("status").toResult(ApiError.badRequest("status", "required"))).map(Option::some),
                          host(request.param("host")),
                          page(request.param("page")))
                     .map(Drill::new)
                     .async()
                     .flatMap(drill -> Promise.all(store.views().checkouts(drill.status(),
                                                                           drill.host(),
                                                                           drill.page()),
                                                   sourceStates())
                                              .map((rows, sources) -> CheckoutPages.drillDown(drill.status().unwrap(),
                                                                                              drill.host(),
                                                                                              rows,
                                                                                              hosts,
                                                                                              sources,
                                                                                              clock.get())))
                     .map(ApiResponse::html);
    }

    private Promise<ApiResponse> nonGit(ApiRequest request) {
        return Result.all(request.param("host")
                                 .toResult(ApiError.badRequest("host", "required"))
                                 .flatMap(ApiRoutes::hostName),
                          request.param("root").toResult(ApiError.badRequest("root", "required")))
                     .map(NonGitQuery::new)
                     .async()
                     .flatMap(query -> Promise.all(store.views().nonGit(query.host()),
                                                   sourceStates())
                                              .map((rows, sources) -> CheckoutPages.nonGit(query.host(),
                                                                                           query.root(),
                                                                                           rows,
                                                                                           hosts,
                                                                                           sources,
                                                                                           clock.get())))
                     .map(ApiResponse::html);
    }

    // ---- JSON --------------------------------------------------------------------------------------------------------
    private Promise<ApiResponse> prs() {
        return Promise.all(sourceStates(),
                           store.views().openPrs())
                      .map((sources, prs) -> new Views.PrsJson(Views.sources(expected(),
                                                                             sources,
                                                                             clock.get()),
                                                               prs.stream().map(Views::pr).toList()))
                      .flatMap(ApiRoutes::json);
    }

    private Promise<ApiResponse> checkouts(ApiRequest request) {
        return Result.all(optionalStatus(request.param("status")),
                          host(request.param("host")),
                          page(request.param("page")))
                     .map(Drill::new)
                     .async()
                     .flatMap(drill -> Promise.all(sourceStates(),
                                                   store.views().counts(),
                                                   store.views().checkouts(drill.status(),
                                                                           drill.host(),
                                                                           drill.page()))
                                              .map((sources, counts, rows) -> new Views.CheckoutsJson(Views.sources(expected(),
                                                                                                                    sources,
                                                                                                                    clock.get()),
                                                                                                      counts.stream()
                                                                                                            .map(Views::counts)
                                                                                                            .toList(),
                                                                                                      rows.page(),
                                                                                                      rows.pages(),
                                                                                                      rows.rows()
                                                                                                          .stream()
                                                                                                          .map(Views::checkout)
                                                                                                          .toList())))
                     .flatMap(ApiRoutes::json);
    }

    private Promise<ApiResponse> sources() {
        return Promise.all(sourceStates(),
                           store.views().passes())
                      .map((sources, passes) -> new Views.SourcesJson(Views.sources(expected(),
                                                                                    sources,
                                                                                    clock.get()),
                                                                      Views.rate(budget.get()).or((Views.RateJson) null),
                                                                      skipped.get()
                                                                             .stream()
                                                                             .map(Views::skipped)
                                                                             .toList(),
                                                                      passes.stream().map(Views::pass).toList()))
                      .flatMap(ApiRoutes::json);
    }

    /// kiln's GitHub budget for the controller's 60 s read.
    private Promise<ApiResponse> githubBudgetView() {
        return json(Views.githubBudget(githubBudget.get()));
    }

    /// The watermark is read first: every change recorded at or before it is committed, so the page never lists a
    /// change whose earlier-stamped neighbour is still in flight (lead ruling R-B).
    private Promise<ApiResponse> summary(ApiRequest request) {
        var asOf = store.changes().watermark();

        return since(request.param("since"),
                     clock.get()).async()
                    .flatMap(since -> summaryOf(since, asOf))
                    .flatMap(summary -> answer(summary,
                                               request.param("format")));
    }

    private Promise<Summary> summaryOf(Instant since, Instant asOf) {
        return Promise.all(store.changes().since(since, SummaryText.MAX_LINES),
                           store.changes().oldest(),
                           store.sources().all(),
                           store.views().counts())
                      .map((changes, oldest, statuses, counts) -> SummaryText.render(since,
                                                                                     asOf,
                                                                                     expected(),
                                                                                     statuses,
                                                                                     changes,
                                                                                     totals(counts),
                                                                                     oldest));
    }

    static Counts totals(List<HostCounts> counts) {
        return new Counts(total(counts, CheckoutStatus.NEEDS_SALVAGE), total(counts, CheckoutStatus.STALE_UNOWNED));
    }

    private static int total(List<HostCounts> counts, CheckoutStatus status) {
        return counts.stream()
                     .mapToInt(host -> host.counts()
                                           .getOrDefault(status, 0))
                     .sum();
    }

    private static Promise<ApiResponse> answer(Summary summary, Option<String> format) {
        return format.filter("text"::equals)
                     .map(_ -> Promise.success(ApiResponse.text(String.join("\n",
                                                                            summary.lines()) + "\n")))
                     .or(() -> json(new Views.SummaryJson(summary.lines(),
                                                          summary.asOf().toString())));
    }

    /// The feed's parameters: the epoch the caller holds (none on a first read), its cursor and the page size.
    record FeedQuery(Option<Uuid> epoch, long after, int max) {}

    /// The feed and the source rows are read independently; the decision ([FeedReply#reply]) needs only the feed.
    private Promise<ApiResponse> changes(ApiRequest request) {
        return feedQuery(request).async()
                        .flatMap(this::feedAnswer)
                        .flatMap(ApiRoutes::json);
    }

    private Promise<Object> feedAnswer(FeedQuery query) {
        return Promise.all(store.changes().feed(query.after(),
                                                query.max()),
                           store.sources().all())
                      .flatMap((feed, statuses) -> Views.feed(FeedReply.reply(query.epoch(),
                                                                              query.after(),
                                                                              feed),
                                                              Views.ages(statuses,
                                                                         configured,
                                                                         feed.highWaterOkAt()),
                                                              clock.get())
                                                        .async());
    }

    static Result<FeedQuery> feedQuery(ApiRequest request) {
        return Result.all(epoch(request.param("epoch")),
                          after(request.param("after")),
                          max(request.param("max")))
                     .map(FeedQuery::new);
    }

    private static Result<Option<Uuid>> epoch(Option<String> raw) {
        return raw.map(text -> Uuid.uuid(text)
                                   .mapError(_ -> ApiError.badRequest("epoch", "not a UUID"))
                                   .map(Option::some))
                  .or(success(none()));
    }

    /// A feed cursor ≥ 0; absent is 0, the start of the epoch.
    static Result<Long> after(Option<String> raw) {
        return raw.map(text -> Number.parseLong(text)
                                     .mapError(_ -> ApiError.badRequest("after", "must be 0 or more"))
                                     .filter(ApiError.badRequest("after", "must be 0 or more"),
                                             Verify.Is::nonNegative))
                  .or(success(0L));
    }

    /// A page size in 1..[#MAX_FEED]; absent is [#MAX_FEED].
    static Result<Integer> max(Option<String> raw) {
        return raw.map(text -> Number.parseInt(text)
                                     .mapError(_ -> ApiError.badRequest("max", "must be 1 to " + MAX_FEED))
                                     .filter(ApiError.badRequest("max", "must be 1 to " + MAX_FEED),
                                             value -> Verify.Is.between(value, 1, MAX_FEED)))
                  .or(success(MAX_FEED));
    }

    /// Only `kiln-ingest` writes (WP9 verification, CSRF): the push must carry [#INGEST_HEADER] `1`, which a browser
    /// cannot send without a preflight kiln never grants, and must carry no `Origin`, which every browser request does.
    private Promise<ApiResponse> ingested(ApiRequest request) {
        return fromKilnIngest(request).async()
                             .flatMap(body -> ingest.ingest(body))
                             .map(record -> ApiResponse.json(MacIngest.answer(record)));
    }

    /// One ledger event, under the Mac ingest's header rule ([#fromKilnIngest]): only `kiln-ingest work` writes.
    private Promise<ApiResponse> workIngested(ApiRequest request) {
        return fromKilnIngest(request).flatMap(body -> WorkRequest.workEvent(body,
                                                                             clock.get()))
                             .async()
                             .flatMap(this::recorded)
                             .flatMap(ApiRoutes::json);
    }

    private Promise<Views.WorkRecordedJson> recorded(WorkEvent event) {
        return store.work()
                    .record(event)
                    .map(id -> Views.recorded(event, id));
    }

    private Promise<ApiResponse> pipelineBaseline() {
        return pipeline.get()
                       .map(PipelineJson::pipelineJson)
                       .flatMap(ApiRoutes::json);
    }

    private Promise<ApiResponse> work() {
        return store.work()
                    .board()
                    .map(Views::work)
                    .flatMap(ApiRoutes::json);
    }

    static Result<String> fromKilnIngest(ApiRequest request) {
        return request.header("Origin")
                      .isPresent()
               ? ApiError.forbidden("a request from a browser page").result()
               : request.header(INGEST_HEADER)
                        .filter("1"::equals)
                        .map(_ -> success(request.body()))
                        .or(() -> ApiError.forbidden(INGEST_HEADER + ": 1 is required").result());
    }

    private static Promise<ApiResponse> json(Object value) {
        return Views.json(value)
                    .map(ApiResponse::json)
                    .async();
    }

    // ---- parameters --------------------------------------------------------------------------------------------------
    /// A [CheckoutStatus] by its exact name, as the pages link it (`IN_USE`); the display spelling (`IN-USE`) is refused.
    static Result<CheckoutStatus> status(Result<String> raw) {
        return raw.flatMap(name -> Option.from(Arrays.stream(CheckoutStatus.values())
                                                     .filter(status -> status.name()
                                                                             .equals(name))
                                                     .findFirst()).toResult(ApiError.badRequest("status",
                                                                                                "not a checkout status")));
    }

    private static Result<Option<CheckoutStatus>> optionalStatus(Option<String> raw) {
        return raw.map(name -> status(success(name)).map(Option::some))
                  .or(success(none()));
    }

    private static Result<Option<HostName>> host(Option<String> raw) {
        return raw.map(name -> hostName(name).map(Option::some))
                  .or(success(none()));
    }

    /// A configured host's name, or `mac`.
    static Result<HostName> hostName(String raw) {
        return HostName.MAC.value().equals(raw)
               ? success(HostName.MAC)
               : HostName.hostName(raw).mapError(_ -> ApiError.badRequest("host", "not a host name"));
    }

    /// A page number ≥ 1; absent is page 1.
    static Result<Integer> page(Option<String> raw) {
        return raw.map(text -> Number.parseInt(text)
                                     .mapError(_ -> ApiError.badRequest("page", "must be 1 or more"))
                                     .filter(ApiError.badRequest("page", "must be 1 or more"),
                                             value -> value >= 1))
                  .or(success(1));
    }

    /// An ISO instant; absent is [#DEFAULT_WINDOW] before now (spec §7: the last 60 minutes). A `since` at or after
    /// `asOf` lists nothing and still states `asOf`.
    static Result<Instant> since(Option<String> raw, Instant now) {
        return raw.map(ApiRoutes::instant)
                  .or(success(now.minus(DEFAULT_WINDOW)));
    }

    private static Result<Instant> instant(String raw) {
        return DateTime.parseInstant(raw).mapError(_ -> ApiError.badRequest("since", "not an ISO-8601 instant"));
    }
}
