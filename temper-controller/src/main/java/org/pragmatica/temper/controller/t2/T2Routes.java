package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.controller.fleet.FleetRoutes;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.store.api.Committed;
import org.pragmatica.temper.store.api.ControlPath;
import org.pragmatica.temper.store.api.InvalidatedGo;
import org.pragmatica.temper.store.api.NewVerdict;
import org.pragmatica.temper.store.api.QueueEntry;
import org.pragmatica.temper.store.api.QueuedPr;
import org.pragmatica.temper.store.api.RegisteredVerdict;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.T2Store;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;


/// The T2 routes WP-A1 serves, each behind the fleet stream's `NOT_READY` gate:
///
/// - `POST fleet/verdicts` (VERDICT): a verdict, reported or carried; one transaction under the T2 lock inserts it,
///   invalidates the PR's outstanding GOs (`verdict-changed`) and emits `verdict-registered` and the `go-invalidated`
///   events. 201 `{"id","at","reportSha256","invalidated"}`; 422 `VERDICT_LINE_MISMATCH`, `CARRY_NO_ACCEPT`.
/// - `GET fleet/merge-queue?repo=&base=` (READ); `POST fleet/merge-queue`, `…/remove`, `…/move` (VERDICT): the serial
///   queue, positions dense per (repo, base). An `add` without `base` asks kiln's `pr-fresh` (R1 only) for it.
/// - `GET fleet/ci-control-paths?repo=` (READ); `POST fleet/ci-control-paths`, `…/remove` (VERDICT): G9's globs.
/// - `POST fleet/mac` (MAC) is T3's ([org.pragmatica.temper.controller.t3.MacRoutes]).
///
/// Every refusal is the wire code the contract names, so the CLI maps it without parsing prose.
public record T2Routes(Responses responses,
                       JsonMapper mapper,
                       T2Store store,
                       FleetWriter writer,
                       Option<KilnPull> kiln,
                       Supplier<Instant> clock,
                       BooleanSupplier ready) {
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern VERDICT = Pattern.compile("^(ACCEPT|FIX|REJECT)$");
    /// `declared_by` / `added_by` (C1): 1 to 64 printable ASCII, no space.
    private static final Pattern BY = Pattern.compile("^[!-~]{1,64}$");
    /// A base ref: 1 to 255 printable ASCII, no space (C1 allows 255; the `VERDICT:` line's `base=(\S+)` no space).
    private static final Pattern BASE = Pattern.compile("^[!-~]{1,255}$");
    /// A `reportPath` with no control character (Postgres `text` refuses NUL; a newline or DEL is no path anyone means).
    private static final Pattern NO_CONTROL = Pattern.compile("^[^\\x00-\\x1F\\x7F]*\\z");
    /// C1's `ci_control_paths.glob` CHECK, the three parts in one.
    private static final Pattern GLOB = Pattern.compile("^(?!/)(?!(?:.*/)?\\.\\.(?:/|$))[A-Za-z0-9_.*/-]+$");

    /// R2-5: the line the report's last `VERDICT: ` line must be; the server parses it again.
    static final Pattern VERDICT_LINE = Pattern.compile("^VERDICT: (ACCEPT|FIX|REJECT) ([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#([1-9][0-9]*)@([0-9a-f]{40}) base=(\\S+)\\s*$");

    private static final int MAX_REPORT_PATH = 4096;
    private static final String ACCEPT = "ACCEPT";

    // ---- bodies ---------------------------------------------------------------------------------------------
    record VerdictBody(String repo,
                       long pr,
                       String sha,
                       String base,
                       String verdict,
                       String by,
                       Option<String> reportPath,
                       Option<String> reportSha256,
                       Option<String> verdictLine,
                       Option<String> carriedFrom) {}

    record QueueAddBody(String repo, long pr, Option<String> base, String addedBy) {}

    record QueueRemoveBody(String repo, long pr, Option<String> base) {}

    record QueueMoveBody(String repo, long pr, Option<Integer> to, Option<String> base) {}

    record PathAddBody(String repo, String glob, String addedBy) {}

    record PathRemoveBody(String repo, String glob) {}

    /// The fields every verdict has, validated.
    record VerdictHead(String repo, long pr, String sha, String base, String verdict, String by) {}

    /// The report fields of a reported verdict, validated (the line is checked against the head after).
    record ReportFields(String path, String sha256, String line) {}

    record QueueAdd(String repo, long pr, Option<String> base, String addedBy) {}

    record QueueRemove(String repo, long pr, Option<String> base) {}

    record QueueMove(String repo, long pr, int to, Option<String> base) {}

    record PathAdd(String repo, String glob, String addedBy) {}

    record PathRemove(String repo, String glob) {}

    // ---- replies --------------------------------------------------------------------------------------------
    record Registered(long id, Instant at, String reportSha256, List<String> invalidated) {}

    record QueueGo(String id, String sha, Instant expiresAt) {}

    record QueueRow(int position, long pr, String addedBy, Instant addedAt, Option<QueueGo> go) {}

    record Queue(String repo, String base, List<QueueRow> entries) {}

    record Queues(List<Queue> queues) {}

    record QueueKey(String repo, String base) {}

    record QueuePosition(String repo, String base, long pr, int position) {}

    record PathRow(String glob, String addedBy, Instant addedAt) {}

    record Paths(String repo, List<PathRow> globs) {}

    record PathAdded(String repo, String glob, String addedBy, Instant addedAt) {}

    record PathRemoved(String repo, String glob) {}

    public static T2Routes t2Routes(Responses responses,
                                    JsonMapper mapper,
                                    T2Store store,
                                    FleetWriter writer,
                                    Option<KilnPull> kiln,
                                    Supplier<Instant> clock,
                                    BooleanSupplier ready) {
        return new T2Routes(responses, mapper, store, writer, kiln, clock, ready);
    }

    /// The scope table's WP-A1 rows.
    public List<Route> routes() {
        return List.of(scopedRoute(HttpMethod.POST, "fleet/verdicts", Route.Scope.VERDICT, gated(this::verdict)),
                       readRoute("fleet/merge-queue", gated(this::queues)),
                       scopedRoute(HttpMethod.POST, "fleet/merge-queue", Route.Scope.VERDICT, gated(this::enqueue)),
                       scopedRoute(HttpMethod.POST,
                                   "fleet/merge-queue/remove",
                                   Route.Scope.VERDICT,
                                   gated(this::dequeue)),
                       scopedRoute(HttpMethod.POST, "fleet/merge-queue/move", Route.Scope.VERDICT, gated(this::move)),
                       readRoute("fleet/ci-control-paths", gated(this::paths)),
                       scopedRoute(HttpMethod.POST, "fleet/ci-control-paths", Route.Scope.VERDICT, gated(this::addPath)),
                       scopedRoute(HttpMethod.POST,
                                   "fleet/ci-control-paths/remove",
                                   Route.Scope.VERDICT,
                                   gated(this::removePath)));
    }

    private Fn1<Promise<ApiResponse>, ApiRequest> gated(Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return FleetRoutes.gated(responses, ready, handler);
    }

    // ---- verdicts -------------------------------------------------------------------------------------------
    private Promise<ApiResponse> verdict(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                VerdictBody.class)
                     .flatMap(T2Routes::present)
                     .mapError(ApiError.BadRequest::badRequest)
                     .flatMap(T2Routes::newVerdict)
                     .async()
                     .flatMap(this::registered)
                     .map(registered -> responses.json(HttpStatus.CREATED,
                                                       registeredReply(registered)))
                     .mapError(T2Routes::classify);
    }

    /// The verdict's insert, the GO invalidation and the events in one store transaction; the events are checked and
    /// wake the stream as every producer's ([FleetWriter#transaction]).
    private Promise<RegisteredVerdict> registered(NewVerdict verdict) {
        return writer.transaction(() -> store.register(verdict,
                                                       registered -> T2Events.verdictEvents(clock.get(),
                                                                                            verdict,
                                                                                            registered)),
                                  Committed::seqs)
                     .map(Committed::value);
    }

    private static Registered registeredReply(RegisteredVerdict registered) {
        return new Registered(registered.id(),
                              iso(registered.at()),
                              registered.reportSha256(),
                              registered.invalidated().stream().map(InvalidatedGo::id).toList());
    }

    /// A body that does not parse into a verdict is a 400; a reported verdict whose line does not equal it, a 422.
    static Result<NewVerdict> newVerdict(VerdictBody body) {
        return verdictHead(body).mapError(ApiError.BadRequest::badRequest)
                          .flatMap(head -> body.carriedFrom()
                                               .map(from -> carried(head, body, from))
                                               .or(() -> reported(head, body)));
    }

    private static Result<VerdictHead> verdictHead(VerdictBody body) {
        return Result.all(matching("repo",
                                   body.repo(),
                                   REPO),
                          positive("pr",
                                   body.pr()),
                          matching("sha",
                                   lower(body.sha()),
                                   SHA40),
                          matching("base",
                                   body.base(),
                                   BASE),
                          matching("verdict",
                                   body.verdict(),
                                   VERDICT),
                          matching("by",
                                   body.by(),
                                   BY))
                     .map(VerdictHead::new);
    }

    /// A carry (F8): no report fields, ACCEPT only (both 400); the store checks the ACCEPT it carries.
    private static Result<NewVerdict> carried(VerdictHead head, VerdictBody body, String from) {
        return Result.all(absent("reportPath",
                                 body.reportPath()),
                          absent("reportSha256",
                                 body.reportSha256()),
                          absent("verdictLine",
                                 body.verdictLine()),
                          Verify.ensure(head.verdict(),
                                        Verify.Is::equalTo,
                                        ACCEPT,
                                        cause("a carried verdict must be ACCEPT")),
                          matching("carriedFrom",
                                   lower(from),
                                   SHA40))
                     .map((_, _, _, _, sha) -> verdictOf(head,
                                                         new NewVerdict.Basis.Carried(sha)))
                     .mapError(ApiError.BadRequest::badRequest);
    }

    private static Result<NewVerdict> reported(VerdictHead head, VerdictBody body) {
        return Result.all(required("reportPath",
                                   body.reportPath()).flatMap(T2Routes::reportPath),
                          required("reportSha256",
                                   body.reportSha256()).flatMap(sha256 -> matching("reportSha256",
                                                                                   lower(sha256),
                                                                                   SHA256)),
                          required("verdictLine",
                                   body.verdictLine()))
                     .map(ReportFields::new)
                     .mapError(ApiError.BadRequest::badRequest)
                     .flatMap(fields -> lineMatches(head,
                                                    fields.line()).map(_ -> verdictOf(head,
                                                                                      new NewVerdict.Basis.Report(fields.path(),
                                                                                                                  fields.sha256()))));
    }

    private static NewVerdict verdictOf(VerdictHead head, NewVerdict.Basis basis) {
        return NewVerdict.newVerdict(head.repo(), head.pr(), head.sha(), head.base(), head.verdict(), head.by(), basis);
    }

    /// The line parses with the regex and names (verdict, repo, pr, sha, base) of the body, field by field.
    static Result<String> lineMatches(VerdictHead head, String line) {
        return Verify.ensure(line, candidate -> namesTheHead(candidate, head), ApiError.General.VERDICT_LINE_MISMATCH);
    }

    private static boolean namesTheHead(String line, VerdictHead head) {
        var matcher = VERDICT_LINE.matcher(line);

        return matcher.matches()
               && matcher.group(1)
                         .equals(head.verdict())
               && matcher.group(2)
                         .equals(head.repo())
               && matcher.group(3)
                         .equals(Long.toString(head.pr()))
               && matcher.group(4)
                         .equals(head.sha())
               && matcher.group(5)
                         .equals(head.base());
    }

    private static Result<String> reportPath(String path) {
        return Verify.ensure(path,
                             candidate -> candidate.startsWith("/") && candidate.length() <= MAX_REPORT_PATH,
                             cause("reportPath must be absolute and at most " + MAX_REPORT_PATH + " characters"))
                     .flatMap(T2Routes::withoutControl);
    }

    private static Result<String> withoutControl(String path) {
        return Verify.ensure(path,
                             Verify.Is::matches,
                             NO_CONTROL,
                             cause("reportPath must not contain a control character"));
    }

    // ---- merge queue ----------------------------------------------------------------------------------------
    private Promise<ApiResponse> queues(ApiRequest request) {
        return store.queues(request.queryParam("repo"),
                            request.queryParam("base"))
                    .map(rows -> responses.ok(new Queues(grouped(rows))));
    }

    /// The rows arrive ordered by repo, base, position; one queue per (repo, base), in that order.
    private static List<Queue> grouped(List<QueuedPr> rows) {
        return rows.stream()
                   .collect(Collectors.groupingBy(row -> new QueueKey(row.entry().repo(),
                                                                      row.entry().base()),
                                                  LinkedHashMap::new,
                                                  Collectors.mapping(T2Routes::queueRow,
                                                                     Collectors.toList())))
                   .entrySet()
                   .stream()
                   .map(queue -> new Queue(queue.getKey().repo(),
                                           queue.getKey().base(),
                                           queue.getValue()))
                   .toList();
    }

    private static QueueRow queueRow(QueuedPr row) {
        return new QueueRow(row.entry().position(),
                            row.entry().pr(),
                            row.addedBy(),
                            iso(row.addedAt()),
                            row.go().map(go -> new QueueGo(go.id(), go.sha(), iso(go.expiresAt()))));
    }

    private Promise<ApiResponse> enqueue(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                QueueAddBody.class)
                     .flatMap(T2Routes::present)
                     .flatMap(T2Routes::queueAdd)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(this::appended)
                     .map(entry -> responses.json(HttpStatus.CREATED,
                                                  position(entry)))
                     .mapError(T2Routes::classify);
    }

    private static Result<QueueAdd> queueAdd(QueueAddBody body) {
        return Result.all(matching("repo",
                                   body.repo(),
                                   REPO),
                          positive("pr",
                                   body.pr()),
                          optionalBase(body.base()),
                          matching("addedBy",
                                   body.addedBy(),
                                   BY))
                     .map(QueueAdd::new);
    }

    private Promise<QueueEntry> appended(QueueAdd add) {
        return add.base()
                  .map(Promise::success)
                  .or(() -> openBase(add.repo(),
                                     add.pr()))
                  .flatMap(base -> store.enqueue(add.repo(),
                                                 base,
                                                 add.pr(),
                                                 add.addedBy()));
    }

    /// No `base` in the body → kiln's `pr-fresh` without `sha` decides it; kiln unavailable (or not configured)
    /// → 503 (a kiln 404 → 404 `repo not configured`); the PR not open → 422 `NOT_OPEN`.
    private Promise<String> openBase(String repo, long pr) {
        return kiln.async(ApiError.Unavailable.FACTORY.apply("kiln",
                                                             cause("no [fleet] kilnUrl is configured")))
                   .flatMap(client -> client.pull(repo, pr)
                                            .mapError(T2Routes::kilnFailure))
                   .flatMap(T2Routes::openPull);
    }

    /// Lead ruling 2026-11-04: a kiln 404 is the repo kiln does not configure (404 `repo not configured`);
    /// every other kiln failure is 503.
    private static Cause kilnFailure(Cause cause) {
        return switch (cause) {
            case KilnPull.Failure.REPO_NOT_CONFIGURED -> ApiError.General.REPO_NOT_CONFIGURED;
            default -> ApiError.Unavailable.FACTORY.apply("kiln", cause);
        };
    }

    private static Promise<String> openPull(KilnPull.PullState pull) {
        return pull.open()
               ? kilnBase(pull.base())
               : ApiError.General.NOT_OPEN.promise();
    }

    /// kiln's `pull.base` is held to the body `base`'s pattern; a base kiln answers outside it is kiln's bad payload, 503.
    private static Promise<String> kilnBase(String base) {
        return matching("base", base, BASE).mapError(ApiError.Unavailable.of("kiln"))
                       .async();
    }

    private Promise<ApiResponse> dequeue(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                QueueRemoveBody.class)
                     .flatMap(T2Routes::present)
                     .flatMap(T2Routes::queueRemove)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(remove -> store.dequeue(remove.repo(),
                                                      remove.pr(),
                                                      remove.base()))
                     .map(entry -> responses.ok(position(entry)))
                     .mapError(T2Routes::classify);
    }

    private static Result<QueueRemove> queueRemove(QueueRemoveBody body) {
        return Result.all(matching("repo",
                                   body.repo(),
                                   REPO),
                          positive("pr",
                                   body.pr()),
                          optionalBase(body.base()))
                     .map(QueueRemove::new);
    }

    private Promise<ApiResponse> move(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                QueueMoveBody.class)
                     .flatMap(T2Routes::present)
                     .flatMap(T2Routes::queueMove)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(move -> store.move(move.repo(),
                                                 move.pr(),
                                                 move.to(),
                                                 move.base()))
                     .map(entry -> responses.ok(position(entry)))
                     .mapError(T2Routes::classify);
    }

    /// `to` is required (400); its range is the queue's, decided under the lock (422 `POSITION_OUT_OF_RANGE`).
    private static Result<QueueMove> queueMove(QueueMoveBody body) {
        return Result.all(matching("repo",
                                   body.repo(),
                                   REPO),
                          positive("pr",
                                   body.pr()),
                          required("to",
                                   body.to()),
                          optionalBase(body.base()))
                     .map(QueueMove::new);
    }

    private static QueuePosition position(QueueEntry entry) {
        return new QueuePosition(entry.repo(), entry.base(), entry.pr(), entry.position());
    }

    // ---- control paths --------------------------------------------------------------------------------------
    private Promise<ApiResponse> paths(ApiRequest request) {
        return matching("repo",
                        request.queryParam("repo").or(""),
                        REPO).mapError(ApiError.BadRequest::badRequest)
                       .async()
                       .flatMap(repo -> store.controlPaths(repo)
                                             .map(rows -> new Paths(repo,
                                                                    rows.stream().map(T2Routes::pathRow).toList())))
                       .map(responses::ok);
    }

    private static PathRow pathRow(ControlPath path) {
        return new PathRow(path.glob(), path.addedBy(), iso(path.addedAt()));
    }

    private Promise<ApiResponse> addPath(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                PathAddBody.class)
                     .flatMap(T2Routes::present)
                     .flatMap(body -> Result.all(matching("repo",
                                                          body.repo(),
                                                          REPO),
                                                 matching("glob",
                                                          body.glob(),
                                                          GLOB),
                                                 matching("addedBy",
                                                          body.addedBy(),
                                                          BY))
                                            .map(PathAdd::new))
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(add -> store.addControlPath(add.repo(),
                                                          add.glob(),
                                                          add.addedBy())
                                          .map(path -> new PathAdded(add.repo(),
                                                                     path.glob(),
                                                                     path.addedBy(),
                                                                     iso(path.addedAt()))))
                     .map(added -> responses.json(HttpStatus.CREATED, added))
                     .mapError(T2Routes::classify);
    }

    /// The glob is matched exactly; one the C1 CHECK refuses cannot be stored, so it is simply not found.
    private Promise<ApiResponse> removePath(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                PathRemoveBody.class)
                     .flatMap(T2Routes::present)
                     .flatMap(body -> Result.all(matching("repo",
                                                          body.repo(),
                                                          REPO),
                                                 required("glob",
                                                          option(body.glob())))
                                            .map(PathRemove::new))
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(remove -> store.removeControlPath(remove.repo(),
                                                                remove.glob())
                                             .map(_ -> new PathRemoved(remove.repo(),
                                                                       remove.glob())))
                     .map(responses::ok)
                     .mapError(T2Routes::classify);
    }

    // ---- shared ---------------------------------------------------------------------------------------------
    /// The store's refusals as the wire codes; the refusal's data is in the code (`ALREADY_QUEUED <base>
    /// <position>`) or carries none the client can act on, so nothing else of it is shown.
    static Cause classify(Cause cause) {
        return switch (cause) {
            case ApiError error -> error;
            case StoreError.CarryNoAccept _ -> ApiError.General.CARRY_NO_ACCEPT;
            case StoreError.AlreadyQueued queued -> ApiError.Conflict.FACTORY.apply("ALREADY_QUEUED " + queued.base() + " " + queued.position());
            case StoreError.NotQueued _ -> ApiError.General.NOT_QUEUED;
            case StoreError.PositionOutOfRange _ -> ApiError.General.POSITION_OUT_OF_RANGE;
            case StoreError.PathExists _ -> ApiError.General.PATH_EXISTS;
            case StoreError.PathNotFound _ -> ApiError.General.PATH_NOT_FOUND;
            default -> cause;
        };
    }

    /// ISO-8601 UTC at second precision (the contract's ISO).
    private static Instant iso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }

    private static String lower(String raw) {
        return option(raw).map(value -> value.toLowerCase(Locale.ROOT))
                     .or("");
    }

    private static Result<String> matching(String name, String raw, Pattern pattern) {
        return Verify.ensure(option(raw).or(""), Verify.Is::matches, pattern, cause("invalid " + name));
    }

    private static Result<Long> positive(String name, long value) {
        return Verify.ensure(value, Verify.Is::positive, cause(name + " must be a positive integer"));
    }

    private static Result<Option<String>> optionalBase(Option<String> base) {
        return base.map(value -> matching("base", value, BASE).map(Option::some))
                   .or(() -> Result.success(none()));
    }

    private static <T> Result<T> required(String name, Option<T> value) {
        return value.toResult(cause(name + " required"));
    }

    private static <T> Result<Option<T>> absent(String name, Option<T> value) {
        return Verify.ensure(value, Option::isEmpty, cause(name + " must be absent for a carried verdict"));
    }

    private static <T> Result<T> present(T body) {
        return option(body).toResult(cause("body required"));
    }
}
