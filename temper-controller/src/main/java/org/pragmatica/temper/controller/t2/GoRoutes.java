package org.pragmatica.temper.controller.t2;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.controller.fleet.FleetRoutes;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.store.api.Committed;
import org.pragmatica.temper.store.api.ConsumedGo;
import org.pragmatica.temper.store.api.ControlPath;
import org.pragmatica.temper.store.api.CreatedGo;
import org.pragmatica.temper.store.api.FleetHead;
import org.pragmatica.temper.store.api.GoBasis;
import org.pragmatica.temper.store.api.GoOutcome;
import org.pragmatica.temper.store.api.InvalidatedGo;
import org.pragmatica.temper.store.api.NewGo;
import org.pragmatica.temper.store.api.RecordedOutcome;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.T2Store;
import org.pragmatica.temper.store.api.VerdictRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;


/// The GO routes WP-A2 serves, each behind the fleet stream's `NOT_READY` gate:
///
/// - `GET fleet/pr-fresh?repo=&pr=&sha=` (READ, writes nothing): the fleet head first, then kiln's `pr-fresh`,
///   G9 against the repo's `ci_control_paths`, G6's server part against the latest verdict, every reason in the print
///   order, and a `freshToken` ([FreshToken]) only when there is none. kiln unreachable → 200 with the single G7
///   reason; a kiln 404 → 404 `repo not configured`. Without `sha` only `pull` is answered.
/// - `POST fleet/go` (READ, writes the row, Q6): the token (422 `FRESH_TOKEN_INVALID`), its age (422
///   `FRESH_TOKEN_EXPIRED` past 45 s), then one store transaction under the T2 lock: the epoch (409 `EPOCH_CHANGED`),
///   the verdict id and the scan of the fleet rows after `fleetSeq` (409 `GO_INVALIDATED <reason>`), the insert (201).
/// - `POST fleet/go/{id}/consume` (VERDICT): single use, siblings invalidated, the GO's verdict report fields; 404 `GO_NOT_FOUND`, 409 `go-used`, `go-invalidated <reason>`, `go-expired`.
/// - `POST fleet/go/{id}/outcome` (VERDICT): the allowed transitions (409 `BAD_TRANSITION <from>-><to>`); `merged`
///   records the merge (route `temper-merge`), emits `merged` and advances the PR's queue, once per PR (R3-2.i).
///
/// The token's age is judged before the transaction opens: the lock is held by T2 writers only, for milliseconds, and
/// the creation scan covers every event committed after `fleetSeq` whatever the wait.
public record GoRoutes(Responses responses,
                       JsonMapper mapper,
                       T2Store store,
                       FleetWriter writer,
                       Option<KilnFresh> kiln,
                       FreshToken token,
                       Supplier<Instant> clock,
                       BooleanSupplier ready) {
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern BASE = Pattern.compile("^[!-~]{1,255}$");

    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Duration FRESH_FOR = Duration.ofSeconds(45);
    private static final Duration GO_LIFETIME = Duration.ofMinutes(10);
    private static final Set<String> OUTCOMES = Set.of("refused", "merging", "merged", "merge-failed");
    private static final int MAX_DETAIL = 500;

    record FreshQuery(String repo, long pr, Option<String> sha) {}

    /// kiln's reply, read by key: `pull` and its base (`changedFiles`, the file list and kiln's reasons only with a sha).
    record KilnView(JsonNode reply,
                    String base,
                    long changedFiles,
                    long filesRead,
                    List<String> paths,
                    List<T2Checks.Reason> reasons) {}

    /// One evaluation: what the gate read and when.
    record Evaluation(FreshQuery query, FleetHead head, Instant checkedAt) {}

    record GoBody(String repo,
                  long pr,
                  String sha,
                  String base,
                  String baseTip,
                  Option<Long> verdictId,
                  String fleetEpoch,
                  Option<Long> fleetSeq,
                  String checkedAt,
                  String freshToken) {}

    record ValidGo(NewGo go, Instant checkedAt, String freshToken) {}

    record GoCreated(String id, Instant createdAt, Instant expiresAt) {}

    record ConsumedVerdict(String reportPath, String reportSha256) {}

    record Consumed(String id,
                    String repo,
                    long pr,
                    String sha,
                    String base,
                    String baseTip,
                    long verdictId,
                    Instant consumedAt,
                    List<String> invalidated,
                    ConsumedVerdict verdict) {}

    record OutcomeBody(Option<String> outcome, Option<String> detail, Option<String> baseTipBefore) {}

    record OutcomeEvent(String kind, long seq) {}

    record Outcome(String id, String outcome, Option<OutcomeEvent> event) {}

    public static GoRoutes goRoutes(Responses responses,
                                    JsonMapper mapper,
                                    T2Store store,
                                    FleetWriter writer,
                                    Option<KilnFresh> kiln,
                                    FreshToken token,
                                    Supplier<Instant> clock,
                                    BooleanSupplier ready) {
        return new GoRoutes(responses, mapper, store, writer, kiln, token, clock, ready);
    }

    /// The scope table's WP-A2 rows.
    public List<Route> routes() {
        return List.of(readRoute("fleet/pr-fresh", gated(this::prFresh)),
                       scopedRoute(HttpMethod.POST,
                                   "fleet/go",
                                   Route.Scope.READ,
                                   Route.Effect.WRITES,
                                   gated(this::create)),
                       scopedRoute(HttpMethod.POST, "fleet/go/{id}/consume", Route.Scope.VERDICT, gated(this::consume)),
                       scopedRoute(HttpMethod.POST, "fleet/go/{id}/outcome", Route.Scope.VERDICT, gated(this::outcome)));
    }

    private Fn1<Promise<ApiResponse>, ApiRequest> gated(Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return FleetRoutes.gated(responses, ready, handler);
    }

    // ---- pr-fresh ---------------------------------------------------------------------------------------
    private Promise<ApiResponse> prFresh(ApiRequest request) {
        return freshQuery(request).mapError(ApiError.BadRequest::badRequest)
                         .async()
                         .flatMap(this::evaluated)
                         .map(responses::ok)
                         .mapError(GoRoutes::classify);
    }

    private static Result<FreshQuery> freshQuery(ApiRequest request) {
        return Result.all(matching("repo",
                                   request.queryParam("repo").or(""),
                                   REPO),
                          request.queryParam("pr")
                                 .toResult(cause("pr required"))
                                 .flatMap(Number::parseLong)
                                 .flatMap(pr -> positive("pr", pr)),
                          optionalSha(request.queryParam("sha")))
                     .map(FreshQuery::new);
    }

    /// The fleet head is read before anything else; `checkedAt` is taken first, at second precision.
    private Promise<ObjectNode> evaluated(FreshQuery query) {
        var checkedAt = clock.get().truncatedTo(ChronoUnit.SECONDS);

        return writer.store()
                     .head()
                     .map(head -> new Evaluation(query, head, checkedAt))
                     .flatMap(this::asked);
    }

    /// A kiln 404 is the caller's 404; any other kiln failure is the single G7 reason.
    private Promise<ObjectNode> asked(Evaluation evaluation) {
        return kiln.map(client -> client.fresh(evaluation.query().repo(),
                                               evaluation.query().pr(),
                                               evaluation.query().sha()))
                   .or(() -> KilnFresh.Unavailable.FACTORY.apply("not-configured").promise())
                   .flatMap(reply -> kilnView(reply,
                                              evaluation.query().sha().isPresent()))
                   .fold(result -> result.fold(cause -> unasked(evaluation, cause),
                                               view -> judged(evaluation, view)));
    }

    private Promise<ObjectNode> unasked(Evaluation evaluation, Cause cause) {
        return cause == KilnPull.Failure.REPO_NOT_CONFIGURED
               ? ApiError.General.REPO_NOT_CONFIGURED.promise()
               : Promise.success(reply(evaluation,
                                       none(),
                                       none(),
                                       List.of(T2Checks.Reason.reason("G7",
                                                                      "source-unavailable kiln " + KilnFresh.detail(cause))),
                                       none()));
    }

    /// Without a sha only `pull` is answered; with one, G9 and G6 join kiln's reasons, and the token is issued only when
    /// none remains.
    private Promise<ObjectNode> judged(Evaluation evaluation, KilnView view) {
        return evaluation.query()
                         .sha()
                         .map(sha -> checked(evaluation, view, sha))
                         .or(() -> Promise.success(reply(evaluation,
                                                         some(view),
                                                         none(),
                                                         List.of(),
                                                         none())));
    }

    private Promise<ObjectNode> checked(Evaluation evaluation, KilnView view, String sha) {
        return Promise.all(store.controlPaths(evaluation.query().repo()),
                           store.latestVerdict(evaluation.query().repo(),
                                               evaluation.query().pr(),
                                               sha))
                      .flatMap((paths, verdict) -> tokened(evaluation,
                                                           view,
                                                           sha,
                                                           verdict,
                                                           reasons(view, paths, verdict)).async());
    }

    private static List<T2Checks.Reason> reasons(KilnView view, List<ControlPath> paths, Option<VerdictRow> verdict) {
        return T2Checks.ordered(Stream.of(view.reasons(),
                                          T2Checks.g9(view.filesRead(),
                                                      view.changedFiles(),
                                                      view.paths(),
                                                      paths.stream().map(ControlPath::glob).toList()),
                                          T2Checks.g6(verdict,
                                                      view.base()))
                                      .flatMap(List::stream)
                                      .toList());
    }

    private Result<ObjectNode> tokened(Evaluation evaluation,
                                       KilnView view,
                                       String sha,
                                       Option<VerdictRow> verdict,
                                       List<T2Checks.Reason> reasons) {
        return freshToken(evaluation, view, sha, verdict, reasons).map(issued -> reply(evaluation,
                                                                                       some(view),
                                                                                       verdict,
                                                                                       reasons,
                                                                                       issued));
    }

    /// A token only for an evaluation with no reason (so a verdict row is present).
    private Result<Option<String>> freshToken(Evaluation evaluation,
                                              KilnView view,
                                              String sha,
                                              Option<VerdictRow> verdict,
                                              List<T2Checks.Reason> reasons) {
        return verdict.filter(_ -> reasons.isEmpty())
                      .map(row -> token.token(FreshToken.Fields.fields(evaluation.query().repo(),
                                                                       evaluation.query().pr(),
                                                                       sha,
                                                                       view.base(),
                                                                       baseTipSha(view),
                                                                       row.id(),
                                                                       evaluation.head().epoch(),
                                                                       evaluation.head().seq(),
                                                                       evaluation.checkedAt().toEpochMilli()))
                                       .map(Option::some))
                      .or(() -> Result.success(none()));
    }

    /// The `baseTip.sha` the reply serves, the empty string when kiln gave none (the token's `baseTip`).
    private static String baseTipSha(KilnView view) {
        return text(view.reply().path("baseTip"),
                    "sha").or("");
    }

    /// kiln's reply read by key; `pull` with a `base` is always required, the file count and list and the reasons
    /// with a sha. A reply without them is kiln's bad payload, the G7 reason.
    static Promise<KilnView> kilnView(JsonNode reply, boolean withSha) {
        return text(reply.path("pull"),
                    "base").filter(_ -> reply.path("pull")
                                             .isObject())
                   .flatMap(base -> view(reply, base, withSha))
                   .async(KilnFresh.Unavailable.FACTORY.apply("bad-payload"));
    }

    private static Option<KilnView> view(JsonNode reply, String base, boolean withSha) {
        return withSha
               ? judgedView(reply, base)
               : some(new KilnView(reply, base, 0, 0, List.of(), List.of()));
    }

    private static Option<KilnView> judgedView(JsonNode reply, String base) {
        return Option.all(count(reply.path("pull"),
                                "changedFiles"),
                          count(reply.path("files"),
                                "read"),
                          array(reply.path("files"),
                                "paths").flatMap(GoRoutes::texts),
                          array(reply, "reasons").flatMap(GoRoutes::kilnReasons))
                     .map((changed, read, paths, reasons) -> new KilnView(reply, base, changed, read, paths, reasons));
    }

    private static Option<Long> count(JsonNode node, String key) {
        return some(node.path(key)).filter(JsonNode::isIntegralNumber)
                   .map(JsonNode::longValue);
    }

    private static Option<JsonNode> array(JsonNode node, String key) {
        return some(node.path(key)).filter(JsonNode::isArray);
    }

    private static Option<List<String>> texts(JsonNode array) {
        return Option.allOf(array.valueStream()
                                 .map(item -> some(item).filter(JsonNode::isString)
                                                  .map(JsonNode::stringValue))
                                 .toList());
    }

    private static Option<List<T2Checks.Reason>> kilnReasons(JsonNode array) {
        return Option.allOf(array.valueStream()
                                 .map(item -> Option.all(text(item, "check"),
                                                         text(item, "line")).map(T2Checks.Reason::reason))
                                 .toList());
    }

    private static Option<String> text(JsonNode node, String key) {
        return some(node.path(key)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue);
    }

    /// The reply: `pull`, `base`, `baseTip` and `checks` as kiln answered them (null and empty when kiln could
    /// not be asked).
    private ObjectNode reply(Evaluation evaluation,
                             Option<KilnView> view,
                             Option<VerdictRow> verdict,
                             List<T2Checks.Reason> reasons,
                             Option<String> freshToken) {
        var reply = JsonNodeFactory.instance.objectNode();
        var fleet = reply.objectNode();

        reply.put("repo",
                  evaluation.query().repo());
        reply.put("pr",
                  evaluation.query().pr());
        reply.set("sha",
                  textOrNull(evaluation.query().sha()));
        reply.put("checkedAt",
                  evaluation.checkedAt().toString());
        fleet.put("epoch",
                  evaluation.head().epoch());
        fleet.put("seq",
                  evaluation.head().seq());
        reply.set("fleet", fleet);
        reply.set("pull",
                  view.map(kilnView -> kilnView.reply()
                                               .path("pull")).or(reply.nullNode()));
        reply.set("base", textOrNull(view.map(KilnView::base)));
        reply.set("baseTip",
                  view.map(kilnView -> nullable(kilnView.reply().path("baseTip"))).or(reply.nullNode()));
        reply.set("checks",
                  view.map(kilnView -> some(kilnView.reply().path("checks")).filter(JsonNode::isArray)
                                           .or(reply.arrayNode()))
                      .or(reply.arrayNode()));
        reply.set("verdict",
                  verdict.<JsonNode> map(GoRoutes::verdictNode).or(reply.nullNode()));
        reply.set("reasons", reasonsNode(reasons));
        reply.set("freshToken", textOrNull(freshToken));

        return reply;
    }

    private static JsonNode textOrNull(Option<String> value) {
        return value.<JsonNode> map(JsonNodeFactory.instance::stringNode)
                    .or(JsonNodeFactory.instance.nullNode());
    }

    private static JsonNode nullable(JsonNode node) {
        return node.isMissingNode()
               ? JsonNodeFactory.instance.nullNode()
               : node;
    }

    private static ObjectNode verdictNode(VerdictRow row) {
        var node = JsonNodeFactory.instance.objectNode();

        node.put("id", row.id());
        node.put("verdict", row.verdict());
        node.put("by", row.by());
        node.put("at",
                 iso(row.at()).toString());
        node.put("base", row.base());
        node.put("reportPath", row.reportPath());
        node.put("reportSha256", row.reportSha256());
        node.set("carriedFrom", textOrNull(row.carriedFrom()));

        return node;
    }

    private static ArrayNode reasonsNode(List<T2Checks.Reason> reasons) {
        var array = JsonNodeFactory.instance.arrayNode();

        reasons.forEach(reason -> array.addObject()
                                       .put("check",
                                            reason.check())
                                       .put("line",
                                            reason.line()));

        return array;
    }

    // ---- create -----------------------------------------------------------------------------------------
    private Promise<ApiResponse> create(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                GoBody.class)
                     .flatMap(GoRoutes::present)
                     .flatMap(GoRoutes::validGo)
                     .mapError(ApiError.BadRequest::badRequest)
                     .flatMap(this::admitted)
                     .async()
                     .flatMap(valid -> store.createGo(valid.go(),
                                                      rows -> GoRules.first(valid.go().basis(),
                                                                            rows)))
                     .map(created -> responses.json(HttpStatus.CREATED,
                                                    goCreated(created)))
                     .mapError(GoRoutes::classify);
    }

    private static Result<ValidGo> validGo(GoBody body) {
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
                          matching("baseTip",
                                   lower(body.baseTip()),
                                   SHA40),
                          required("verdictId",
                                   body.verdictId()).flatMap(id -> positive("verdictId", id)),
                          matching("fleetEpoch",
                                   body.fleetEpoch(),
                                   UUID_TEXT),
                          required("fleetSeq",
                                   body.fleetSeq()).flatMap(GoRoutes::notNegative),
                          DateTime.parseInstant(option(body.checkedAt()).or("")),
                          option(body.freshToken()).toResult(cause("freshToken required")))
                     .map((repo, pr, sha, base, baseTip, verdictId, epoch, fleetSeq, checkedAt, token) -> new ValidGo(NewGo.newGo(GoBasis.goBasis(repo,
                                                                                                                                                  pr,
                                                                                                                                                  sha,
                                                                                                                                                  base,
                                                                                                                                                  baseTip,
                                                                                                                                                  fleetSeq),
                                                                                                                                  verdictId,
                                                                                                                                  epoch),
                                                                                                                      checkedAt,
                                                                                                                      token));
    }

    /// The token is the one this controller issued for these fields, and at most 45 s old.
    private Result<ValidGo> admitted(ValidGo valid) {
        return token.valid(valid.freshToken(),
                           fields(valid))
                    .filter(ApiError.General.FRESH_TOKEN_INVALID, Boolean::booleanValue)
                    .map(_ -> valid)
                    .filter(ApiError.General.FRESH_TOKEN_EXPIRED,
                            _ -> !Duration.between(valid.checkedAt(),
                                                   clock.get())
                                          .minus(FRESH_FOR)
                                          .isPositive());
    }

    private static FreshToken.Fields fields(ValidGo valid) {
        var basis = valid.go().basis();

        return FreshToken.Fields.fields(basis.repo(),
                                        basis.pr(),
                                        basis.sha(),
                                        basis.base(),
                                        basis.baseTip(),
                                        valid.go().verdictId(),
                                        valid.go().fleetEpoch(),
                                        basis.fleetSeq(),
                                        valid.checkedAt().toEpochMilli());
    }

    private static GoCreated goCreated(CreatedGo created) {
        return new GoCreated(created.id(),
                             iso(created.createdAt()),
                             iso(created.createdAt().plus(GO_LIFETIME)));
    }

    // ---- consume and outcome ----------------------------------------------------------------------------
    private Promise<ApiResponse> consume(ApiRequest request) {
        return goId(request).async()
                   .flatMap(id -> writer.transaction(() -> store.consume(id,
                                                                         consumed -> T2Events.consumeEvents(clock.get(),
                                                                                                            consumed)),
                                                     Committed::seqs))
                   .map(committed -> responses.ok(consumedReply(committed.value())))
                   .mapError(GoRoutes::classify);
    }

    /// A path id that is not a GO's uuid names no GO.
    private static Result<String> goId(ApiRequest request) {
        return request.param("id")
                      .map(GoRoutes::lower)
                      .filter(UUID_TEXT.asMatchPredicate())
                      .toResult(ApiError.General.GO_NOT_FOUND);
    }

    private static Consumed consumedReply(ConsumedGo consumed) {
        var basis = consumed.basis();

        return new Consumed(consumed.id(),
                            basis.repo(),
                            basis.pr(),
                            basis.sha(),
                            basis.base(),
                            basis.baseTip(),
                            consumed.verdictId(),
                            iso(consumed.consumedAt()),
                            consumed.invalidated().stream().map(InvalidatedGo::id).toList(),
                            new ConsumedVerdict(consumed.reportPath(), consumed.reportSha256()));
    }

    private Promise<ApiResponse> outcome(ApiRequest request) {
        return goId(request).flatMap(id -> mapper.readBytes(request.body(),
                                                            OutcomeBody.class)
                                                 .flatMap(GoRoutes::present)
                                                 .flatMap(body -> goOutcome(id, body))
                                                 .mapError(ApiError.BadRequest::badRequest))
                   .async()
                   .flatMap(outcome -> writer.transaction(() -> store.outcome(outcome,
                                                                              recorded -> T2Events.outcomeEvents(clock.get(),
                                                                                                                 recorded)),
                                                          Committed::seqs))
                   .map(committed -> responses.ok(outcomeReply(committed)))
                   .mapError(GoRoutes::classify);
    }

    /// `outcome` one of the four, `detail` at most 500 characters, `baseTipBefore` a sha40 and required for `merging`.
    private static Result<GoOutcome> goOutcome(String id, OutcomeBody body) {
        return Result.all(required("outcome",
                                   body.outcome()).filter(cause("invalid outcome"),
                                                          OUTCOMES::contains),
                          optional(body.detail(),
                                   detail -> Verify.ensure(detail,
                                                           Verify.Is::lenBetween,
                                                           0,
                                                           MAX_DETAIL,
                                                           cause("detail longer than " + MAX_DETAIL + " characters"))),
                          optional(body.baseTipBefore(),
                                   tip -> matching("baseTipBefore",
                                                   lower(tip),
                                                   SHA40)))
                     .flatMap((outcome, detail, tip) -> merging(outcome, tip).map(_ -> GoOutcome.goOutcome(id,
                                                                                                           outcome,
                                                                                                           detail,
                                                                                                           tip)));
    }

    private static Result<Option<String>> merging(String outcome, Option<String> baseTipBefore) {
        return Verify.ensure(baseTipBefore,
                             tip -> tip.isPresent() || !outcome.equals("merging"),
                             cause("baseTipBefore required for merging"));
    }

    /// `event` is the `merged` this outcome emitted, the first event of its transaction; null otherwise.
    private static Outcome outcomeReply(Committed<RecordedOutcome> committed) {
        var recorded = committed.value();

        return new Outcome(recorded.id(),
                           recorded.outcome(),
                           recorded.merge()
                                   .flatMap(_ -> committed.seqs())
                                   .map(seqs -> new OutcomeEvent(T2Plan.MERGED,
                                                                 seqs.first())));
    }

    // ---- shared ---------------------------------------------------------------------------------------------
    /// The store's GO refusals as the wire codes; the rest as the WP-A1 routes classify them.
    static Cause classify(Cause cause) {
        return switch (cause) {
            case StoreError.GoNotFound _ -> ApiError.General.GO_NOT_FOUND;
            case StoreError.GoUsed _ -> ApiError.General.GO_USED;
            case StoreError.GoExpired _ -> ApiError.General.GO_EXPIRED;
            case StoreError.GoInvalidated invalidated -> ApiError.Conflict.FACTORY.apply("go-invalidated " + invalidated.reason());
            case StoreError.EpochChanged _ -> ApiError.General.EPOCH_CHANGED;
            case StoreError.CreationInvalidated invalidated -> ApiError.Conflict.FACTORY.apply("GO_INVALIDATED " + invalidated.reason());
            case StoreError.BadTransition bad -> ApiError.Conflict.FACTORY.apply("BAD_TRANSITION " + bad.from() + "->" + bad.to());
            default -> T2Routes.classify(cause);
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

    private static Result<Long> notNegative(long value) {
        return Verify.ensure(value, Verify.Is::nonNegative, cause("fleetSeq must not be negative"));
    }

    private static Result<Option<String>> optionalSha(Option<String> sha) {
        return optional(sha, value -> matching("sha", lower(value), SHA40));
    }

    private static <T> Result<Option<T>> optional(Option<T> value, Fn1<Result<T>, T> check) {
        return value.map(present -> check.apply(present)
                                         .map(Option::some))
                    .or(() -> Result.success(none()));
    }

    private static <T> Result<T> required(String name, Option<T> value) {
        return value.toResult(cause(name + " required"));
    }

    private static <T> Result<T> present(T body) {
        return option(body).toResult(cause("body required"));
    }
}
