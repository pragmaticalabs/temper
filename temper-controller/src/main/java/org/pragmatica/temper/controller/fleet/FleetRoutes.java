package org.pragmatica.temper.controller.fleet;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.events.EventFeed;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.protocol.api.FleetEventLine;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.digest.Digests;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetAcked;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.FleetSubscriber;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.http.Route.readRoute;
import static org.pragmatica.temper.controller.http.Route.route;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;


/// The fleet stream's routes. Every events reply ends with the heartbeat line.
///
/// - `POST fleet/subscribers {"name","start":"head"|"seq:<n>","bindTo":"caller"|"read"}` (WRITE): 201; 409
///   `SUBSCRIBER_EXISTS`; 400 `START_AHEAD` for a `seq:<n>` above the head. The subscriber is bound to a token id, the first 16 hex digits of its SHA-256 (R2-f): the
///   CALLING token's by default (`caller`), or the configured read token's (`read`, so a reader can poll and ack a
///   subscriber only the full token can create; 400 when no read token is configured).
/// - `POST fleet/subscribers/{name}/reset {"start"}` (WRITE): the explicit cursor reset; 400 `START_AHEAD` for a
///   `seq:<n>` above the head, the cursor unchanged.
/// - `GET fleet/events?subscriber&max=1..500&wait=0..55&format=ndjson|text` (READ; it records `last_poll_at`, so it is
///   registered as writing): the events after the cursor, long-polling up to `wait` s when there are none; 404
///   `NO_SUBSCRIBER`, 403 for another token. A retention gap is one `gap` line (`source=controller`, `subject=fleet`,
///   `seq` = the floor), whose ack moves the cursor past it.
/// - `POST fleet/subscribers/{name}/ack {"epoch","seq"}` (READ, NOTE 4): `cursor = GREATEST(cursor, seq)`; 403 for another
///   token; 409 `EPOCH_MISMATCH` when `epoch` (the epoch the line was read in) is not the current one (lead ruling F2: an
///   old epoch's seq names nothing in the new one), and 400 `ACK_AHEAD` for a seq above the head, the cursor
///   unchanged either way; 400 without an `epoch`.
/// - `GET fleet/events/after?after=<epoch>:<seq>&until=<k,..>&match=<k=v,..>&includeCatchUp=0|1&wait=0..55` (READ,
///   stateless): the first matching event after `seq`, then the heartbeat; or, when `wait` expires without one
///   (a wake without a match does not end the wait), the heartbeat alone. Another epoch, or
///   (same epoch) a `seq` above the head (R3-4: a restore the file missed), is 409 `{"error":"EPOCH_CHANGED","epoch"}`.
///   Events retention deleted are passed over (they are older than 30 days).
/// - `GET fleet/heartbeat` (READ): the heartbeat alone. `GET fleet/heartbeat/history?since=<ISO>` (READ): the
///   self-recorded minute ticks, `{"ticks":[{"at":"<ISO>"},…]}`, ascending. The heartbeat is always
///   one JSON line, with `format=text` too.
///
/// Until the controller's start has completed (the first self-check included), every one of them answers 503
/// `NOT_READY` ([#gated]).
public record FleetRoutes(Responses responses,
                          JsonMapper mapper,
                          FleetStore store,
                          Heartbeats heartbeats,
                          Wake wake,
                          Supplier<Instant> clock,
                          Option<String> readTokenId,
                          BooleanSupplier ready) {
    public static final int MAX = 500;
    public static final int MAX_WAIT_S = 55;
    private static final int TOKEN_ID_HEX = 16;

    record CreateBody(String name, Option<String> start, Option<String> bindTo) {}

    record Named(String name, Option<Long> start) {}

    record Bound(String name, Option<Long> start, Option<String> tokenId) {}

    record Acked(String name, Ack body) {}

    record Poll(String name, int max, int waitS, FleetLines.Format format) {}

    record StartBody(Option<String> start) {}

    record AckBody(Option<String> epoch, long seq) {}

    /// An ack body that names its epoch.
    record Ack(String epoch, long seq) {}

    record Created(String name, long cursor) {}

    record EpochChanged(String error, String epoch) {}

    record Tick(Instant at) {}

    record History(List<Tick> ticks) {}

    /// The stateless wait's query.
    record AfterQuery(String epoch,
                      long seq,
                      Set<String> until,
                      Map<String, String> match,
                      boolean catchUp,
                      int waitS) {}

    public static FleetRoutes fleetRoutes(Responses responses,
                                          JsonMapper mapper,
                                          FleetStore store,
                                          Heartbeats heartbeats,
                                          Wake wake,
                                          Supplier<Instant> clock,
                                          Option<String> readTokenId,
                                          BooleanSupplier ready) {
        return new FleetRoutes(responses, mapper, store, heartbeats, wake, clock, readTokenId, ready);
    }

    public List<Route> routes() {
        return List.of(route(HttpMethod.POST, "fleet/subscribers", gated(this::create)),
                       route(HttpMethod.POST, "fleet/subscribers/{name}/reset", gated(this::reset)),
                       scopedRoute(HttpMethod.POST, "fleet/subscribers/{name}/ack", Route.Scope.READ, gated(this::ack)),
                       scopedRoute(HttpMethod.GET,
                                   "fleet/events",
                                   Route.Scope.READ,
                                   Route.Effect.WRITES,
                                   gated(this::events)),
                       readRoute("fleet/events/after", gated(this::after)),
                       readRoute("fleet/heartbeat", gated(this::heartbeat)),
                       readRoute("fleet/heartbeat/history", gated(this::history)));
    }

    /// FIX-6: every fleet route answers 503 `NOT_READY` until the controller's start has completed, the first
    /// self-check included, so no heartbeat says ok=false with nothing failed. `GET hosts` is not gated: the loopback
    /// probe calls it during that check.
    private Fn1<Promise<ApiResponse>, ApiRequest> gated(Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return gated(responses, ready, handler);
    }

    /// The same gate for the routes beside the stream's (the T2 routes).
    public static Fn1<Promise<ApiResponse>, ApiRequest> gated(Responses responses,
                                                              BooleanSupplier ready,
                                                              Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return request -> served(responses, ready, handler, request);
    }

    private static Promise<ApiResponse> served(Responses responses,
                                               BooleanSupplier ready,
                                               Fn1<Promise<ApiResponse>, ApiRequest> handler,
                                               ApiRequest request) {
        return ready.getAsBoolean()
               ? handler.apply(request)
               : Promise.success(responses.failure(ApiError.General.NOT_READY));
    }

    /// The id a subscriber is bound to: the first 16 hex digits of the presented bearer token's SHA-256.
    static String tokenId(ApiRequest request) {
        return BearerToken.presented(request.headers())
                          .flatMap(token -> Digests.sha256Hex(token).option())
                          .map(hex -> hex.substring(0, TOKEN_ID_HEX))
                          .or("");
    }

    // ---- subscribers -----------------------------------------------------------------------------------------
    private Promise<ApiResponse> create(ApiRequest request) {
        return mapper.readBytes(request.body(),
                                CreateBody.class)
                     .flatMap(FleetRoutes::present)
                     .flatMap(body -> Result.all(EventFeed.subscriberName(body.name()),
                                                 start(body.start()),
                                                 bound(body.bindTo()))
                                            .map(Bound::new))
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(bound -> created(bound.name(),
                                               bound.start(),
                                               bound.tokenId().or(() -> tokenId(request))));
    }

    /// The token id `bindTo` names: none for the caller's (the default), the read token's id for `read`.
    private Result<Option<String>> bound(Option<String> bindTo) {
        return bindTo.filter(value -> !value.equals("caller"))
                     .map(this::readBound)
                     .or(() -> Result.success(none()));
    }

    private Result<Option<String>> readBound(String bindTo) {
        return bindTo.equals("read")
               ? readTokenId.toResult(Causes.cause("bindTo read: no read token is configured"))
                            .map(Option::some)
               : invalid("bindTo must be caller or read");
    }

    private Promise<ApiResponse> created(String name, Option<Long> start, String tokenId) {
        return startable(start).flatMap(_ -> store.create(name, start, tokenId))
                        .flatMap(made -> made
                                         ? existing(name)
                                         : ApiError.General.SUBSCRIBER_EXISTS.<FleetSubscriber> promise())
                        .map(row -> responses.json(HttpStatus.CREATED,
                                                   new Created(row.name(),
                                                               row.cursor())))
                        .recover(responses::failure);
    }

    /// A start above the head would skip the seqs written up to it; it is refused (400 `START_AHEAD`) by one
    /// head read, as the ack is.
    private Promise<Unit> startable(Option<Long> start) {
        return start.map(this::notAhead)
                    .or(Promise::unitPromise);
    }

    private Promise<Unit> notAhead(long seq) {
        return store.head()
                    .flatMap(head -> seq > head.seq()
                                     ? ApiError.General.START_AHEAD.<Unit> promise()
                                     : Promise.unitPromise());
    }

    private Promise<FleetSubscriber> resetTo(Named named) {
        return store.reset(named.name(),
                           named.start())
                    .flatMap(done -> done
                                     ? existing(named.name())
                                     : ApiError.General.NO_SUBSCRIBER.<FleetSubscriber> promise());
    }

    private Promise<ApiResponse> reset(ApiRequest request) {
        return Result.all(EventFeed.subscriberName(request.param("name").or("")),
                          mapper.readBytes(request.body(),
                                           StartBody.class)
                                .flatMap(FleetRoutes::present)
                                .flatMap(body -> start(body.start())))
                     .map(Named::new)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(named -> startable(named.start()).flatMap(_ -> resetTo(named)))
                     .map(row -> responses.ok(new Created(row.name(),
                                                          row.cursor())))
                     .recover(responses::failure);
    }

    private Promise<ApiResponse> ack(ApiRequest request) {
        return Result.all(EventFeed.subscriberName(request.param("name").or("")),
                          mapper.readBytes(request.body(),
                                           AckBody.class)
                                .flatMap(FleetRoutes::present)
                                .flatMap(FleetRoutes::ackOf))
                     .map(Acked::new)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(acked -> owned(acked.name(),
                                             tokenId(request)).flatMap(_ -> acknowledged(acked)))
                     .map(_ -> Responses.noContent())
                     .recover(responses::failure);
    }

    private static Result<Ack> ackOf(AckBody body) {
        return body.epoch()
                   .filter(Verify.Is::notBlank)
                   .toResult(Causes.cause("epoch required"))
                   .map(epoch -> new Ack(epoch,
                                         body.seq()));
    }

    /// Lead ruling F2: an ack read in another epoch than the current one names a seq of a log that is gone (a restore
    /// between the read and the ack); it is refused with 409 and the cursor stays, so the reader polls again and gets
    /// the new epoch's rows from the cursor. An ack above the head would skip seqs not yet written; refused, the
    /// cursor stays. The checks and the update are one statement ([FleetStore#ack]): no rotation lands between them.
    private Promise<Unit> acknowledged(Acked acked) {
        return store.ack(acked.name(),
                         acked.body().epoch(),
                         acked.body().seq())
                    .flatMap(found -> found.moved()
                                      ? Promise.unitPromise()
                                      : refusal(found,
                                                acked.body()).<Unit> promise());
    }

    /// Why the statement moved nothing: the epoch first (a seq of another epoch is not comparable with this head), then
    /// the head; with both in order, the subscriber was gone by then.
    private static ApiError refusal(FleetAcked found, Ack ack) {
        return ! found.epoch()
                      .equals(ack.epoch())
               ? ApiError.General.EPOCH_MISMATCH
               : ack.seq() > found.head()
                 ? ApiError.General.ACK_AHEAD
                 : ApiError.General.NO_SUBSCRIBER;
    }

    private Promise<FleetSubscriber> existing(String name) {
        return store.subscriber(name)
                    .flatMap(row -> row.async(ApiError.General.NO_SUBSCRIBER));
    }

    /// The subscriber when it exists and is bound to this token: 404, then 403.
    private Promise<FleetSubscriber> owned(String name, String tokenId) {
        return existing(name).flatMap(row -> row.tokenId()
                                                .equals(tokenId)
                                             ? Promise.success(row)
                                             : ApiError.General.SUBSCRIBER_TOKEN.<FleetSubscriber> promise());
    }

    // ---- events ----------------------------------------------------------------------------------------------
    private Promise<ApiResponse> events(ApiRequest request) {
        return Result.all(EventFeed.subscriberName(request.queryParam("subscriber").or("")),
                          bounded(request, "max", 1, MAX, MAX),
                          bounded(request, "wait", 0, MAX_WAIT_S, 0),
                          format(request))
                     .map(Poll::new)
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(poll -> owned(poll.name(),
                                            tokenId(request)).flatMap(row -> delivered(row,
                                                                                       poll.max(),
                                                                                       poll.waitS(),
                                                                                       poll.format())))
                     .recover(responses::failure);
    }

    /// The wake promise is taken BEFORE looking, so an event committed between the look and the wait wakes the poll.
    /// The epoch is read BEFORE the rows too (verifier F7): [#reply] serves them only when the heartbeat's epoch is
    /// still that one.
    private Promise<ApiResponse> delivered(FleetSubscriber row, int max, int waitS, FleetLines.Format format) {
        var signal = wake.next();

        return store.head()
                    .flatMap(head -> deliveredIn(head.epoch(),
                                                 signal,
                                                 row,
                                                 max,
                                                 waitS,
                                                 format));
    }

    private Promise<ApiResponse> deliveredIn(String readIn,
                                             Promise<Unit> signal,
                                             FleetSubscriber row,
                                             int max,
                                             int waitS,
                                             FleetLines.Format format) {
        return store.pending(row.cursor(),
                             max)
                    .flatMap(pending -> isEmpty(pending) && waitS > 0
                                        ? afterWait(signal, row, max, waitS)
                                        : Promise.success(pending))
                    .flatMap(pending -> store.polled(row.name(),
                                                     clock.get())
                                             .map(_ -> pending))
                    .flatMap(pending -> reply(lines(pending),
                                              format,
                                              readIn));
    }

    /// Design-out: the wait's timeout is the end of the long-poll, not a failure; either outcome looks once more.
    private Promise<FleetPending> afterWait(Promise<Unit> signal, FleetSubscriber row, int max, int waitS) {
        return signal.timeout(TimeSpan.timeSpan(waitS).seconds())
                     .fold(_ -> store.pending(row.cursor(),
                                              max));
    }

    private static boolean isEmpty(FleetPending pending) {
        return pending instanceof FleetPending.Events events && events.rows()
                                                                      .isEmpty();
    }

    private List<FleetEventRow> lines(FleetPending pending) {
        return switch (pending) {
            case FleetPending.Events events -> events.rows();
            case FleetPending.Gap gap -> List.of(gapRow(gap));
        };
    }

    /// The epoch is filled in by [#reply] (the gap is the current epoch's).
    private FleetEventRow gapRow(FleetPending.Gap gap) {
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("from", gap.from());
        payload.put("to", gap.to());

        return FleetEventRow.fleetEventRow("",
                                           gap.to(),
                                           clock.get(),
                                           clock.get(),
                                           "gap",
                                           "fleet",
                                           false,
                                           "controller",
                                           payload.toString());
    }

    // ---- stateless wait --------------------------------------------------------------------------------------
    private Promise<ApiResponse> after(ApiRequest request) {
        return afterQuery(request).mapError(ApiError.BadRequest::badRequest)
                         .async()
                         .flatMap(query -> store.head()
                                                .flatMap(head -> head.epoch()
                                                                     .equals(query.epoch()) && query.seq() <= head.seq()
                                                                 ? waited(query)
                                                                 : Promise.success(responses.json(HttpStatus.CONFLICT,
                                                                                                  new EpochChanged("EPOCH_CHANGED",
                                                                                                                   head.epoch())))))
                         .recover(responses::failure);
    }

    private Promise<ApiResponse> waited(AfterQuery query) {
        return awaited(query,
                       query.seq(),
                       System.nanoTime() + TimeUnit.SECONDS.toNanos(query.waitS())).flatMap(found -> reply(found.found()
                                                                                                                .stream()
                                                                                                                .toList(),
                                                                                                           FleetLines.Format.NDJSON,
                                                                                                           query.epoch()));
    }

    /// Answers only on a match, or (the heartbeat alone) once `wait` expires; a wake that brings no match waits
    /// on for what is left. The wake promise is taken BEFORE looking, as in [#delivered].
    private Promise<Scan> awaited(AfterQuery query, long after, long deadlineNanos) {
        var signal = wake.next();

        return scan(query, after).flatMap(found -> matchedOrWaited(query, found, signal, deadlineNanos));
    }

    private Promise<Scan> matchedOrWaited(AfterQuery query, Scan found, Promise<Unit> signal, long deadlineNanos) {
        var left = deadlineNanos - System.nanoTime();

        return found.found()
                    .isPresent() || left <= 0
               ? Promise.success(found)
               : signal.timeout(TimeSpan.timeSpan(left).nanos())
                       .fold(_ -> awaited(query,
                                          found.scanned(),
                                          deadlineNanos));
    }

    /// The first match after `after`, or none and the highest seq read.
    record Scan(Option<FleetEventRow> found, long scanned) {}

    private Promise<Scan> scan(AfterQuery query, long after) {
        return store.pending(after, MAX)
                    .flatMap(pending -> switch (pending) {
            case FleetPending.Gap gap -> scan(query,
                                              gap.to());
            case FleetPending.Events events -> matchIn(query,
                                                       after,
                                                       events.rows());
        });
    }

    private Promise<Scan> matchIn(AfterQuery query, long after, List<FleetEventRow> rows) {
        var found = Option.from(rows.stream().filter(row -> matches(mapper, query, row)).findFirst());
        var scanned = rows.isEmpty()
                      ? after
                      : rows.getLast().seq();

        return found.isPresent() || rows.size() < MAX
               ? Promise.success(new Scan(found, scanned))
               : scan(query, scanned);
    }

    /// `catchUp` rows only when asked (R2-6.3); a kind in `until` (empty: any); every `match` key equal, `subject`
    /// addressing the subject and any other key a top-level payload field (a string by its text, else its JSON).
    static boolean matches(JsonMapper mapper, AfterQuery query, FleetEventRow row) {
        return (query.catchUp() || !row.catchUp())
               && (query.until()
                        .isEmpty() || query.until()
                                           .contains(row.kind()))
               && mapper.readTree(row.payload())
                        .map(payload -> query.match()
                                             .entrySet()
                                             .stream()
                                             .allMatch(entry -> field(row,
                                                                      payload,
                                                                      entry.getKey()).filter(entry.getValue()::equals)
                                                                     .isPresent()))
                        .or(false);
    }

    private static Option<String> field(FleetEventRow row, JsonNode payload, String key) {
        return "subject".equals(key)
               ? some(row.subject())
               : some(payload.path(key)).filter(node -> !node.isMissingNode())
                     .map(FleetLines::scalar);
    }

    static Result<AfterQuery> afterQuery(ApiRequest request) {
        return Result.all(position(request.queryParam("after").or("")),
                          Result.success(csv(request.queryParam("until").or(""))),
                          pairs(request.queryParam("match").or("")),
                          flag(request, "includeCatchUp"),
                          bounded(request, "wait", 0, MAX_WAIT_S, 0))
                     .map((position, until, match, catchUp, waitS) -> new AfterQuery(position.epoch(),
                                                                                     position.seq(),
                                                                                     until,
                                                                                     match,
                                                                                     catchUp,
                                                                                     waitS));
    }

    record Position(String epoch, long seq) {}

    private static Result<Position> position(String raw) {
        var colon = raw.lastIndexOf(':');

        return colon > 0
               ? number(raw.substring(colon + 1), "after").map(seq -> new Position(raw.substring(0, colon), seq))
               : invalid("after must be <epoch>:<seq>");
    }

    private static Set<String> csv(String raw) {
        return Stream.of(raw.split(","))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .collect(Collectors.toUnmodifiableSet());
    }

    private static Result<Map<String, String>> pairs(String raw) {
        return Result.allOf(csv(raw).stream().map(FleetRoutes::pair).toList()).map(entries -> entries.stream()
                                                                                                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                                                                                           Map.Entry::getValue)));
    }

    private static Result<Map.Entry<String, String>> pair(String raw) {
        var equals = raw.indexOf('=');

        return equals > 0
               ? Result.success(Map.entry(raw.substring(0, equals), raw.substring(equals + 1)))
               : invalid("match must be k=v,...");
    }

    // ---- heartbeat -------------------------------------------------------------------------------------------
    private Promise<ApiResponse> heartbeat(ApiRequest request) {
        return heartbeats.now()
                         .map(responses::ok)
                         .recover(responses::failure);
    }

    private Promise<ApiResponse> history(ApiRequest request) {
        return Result.lift(Causes::fromThrowable,
                           () -> Instant.parse(request.queryParam("since").or("")))
                     .mapError(ApiError.BadRequest::badRequest)
                     .async()
                     .flatMap(store::ticks)
                     .map(ticks -> responses.ok(new History(ticks.stream().map(Tick::new).toList())))
                     .recover(responses::failure);
    }

    // ---- rendering -------------------------------------------------------------------------------------------
    /// The rows' lines, then the heartbeat; a row without an epoch (a synthetic gap) carries the current one.
    /// The rows were read in `readIn` (read before them); when the heartbeat, read after them, names another epoch, a
    /// rotation fell between the two reads and the rows may belong to the old log: the reply is the heartbeat alone and
    /// the reader asks again (verifier F7; design-out: no row is ever served under an epoch it was not read in).
    private Promise<ApiResponse> reply(List<FleetEventRow> rows, FleetLines.Format format, String readIn) {
        return heartbeats.now()
                         .flatMap(heartbeat -> body(readIn(rows, readIn, heartbeat),
                                                    heartbeat,
                                                    format).async())
                         .map(text -> ApiResponse.apiResponse(HttpStatus.OK,
                                                              CommonContentType.TEXT_PLAIN,
                                                              text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<FleetEventRow> readIn(List<FleetEventRow> rows, String readIn, HeartbeatLine heartbeat) {
        return heartbeat.epoch()
                        .equals(readIn)
               ? rows
               : List.of();
    }

    private Result<String> body(List<FleetEventRow> rows, HeartbeatLine heartbeat, FleetLines.Format format) {
        return Result.allOf(rows.stream().map(row -> eventLine(withEpoch(row, heartbeat.epoch()), format)).toList()).flatMap(lines -> mapper.writeAsString(heartbeat)
                                                                                                                                            .map(last -> Stream.concat(lines.stream(),
                                                                                                                                                                       Stream.of(last))
                                                                                                                                                               .collect(Collectors.joining("\n",
                                                                                                                                                                                           "",
                                                                                                                                                                                           "\n"))));
    }

    private Result<String> eventLine(FleetEventRow row, FleetLines.Format format) {
        return FleetLines.line(mapper, row).flatMap(line -> FleetLines.render(mapper, format, line));
    }

    private static FleetEventRow withEpoch(FleetEventRow row, String epoch) {
        return row.epoch()
                  .isEmpty()
               ? FleetEventRow.fleetEventRow(epoch,
                                             row.seq(),
                                             row.at(),
                                             row.observedAt(),
                                             row.kind(),
                                             row.subject(),
                                             row.catchUp(),
                                             row.source(),
                                             row.payload())
               : row;
    }

    // ---- parameters ------------------------------------------------------------------------------------------
    private static Result<Option<Long>> start(Option<String> raw) {
        return raw.filter(value -> !value.equals("head"))
                  .map(FleetRoutes::seqStart)
                  .or(() -> Result.success(none()));
    }

    private static Result<Option<Long>> seqStart(String raw) {
        return raw.startsWith("seq:")
               ? number(raw.substring(4),
                        "start").filter(Causes.forOneValue("start seq must be >= 0: %s"),
                                        seq -> seq >= 0)
                       .map(Option::some)
               : invalid("start must be head or seq:<n>");
    }

    private static Result<Integer> bounded(ApiRequest request, String name, int min, int max, int fallback) {
        return request.queryParam(name)
                      .map(raw -> number(raw, name).map(Long::intValue)
                                        .filter(Causes.forOneValue(name + " out of range: %s"),
                                                value -> value >= min && value <= max))
                      .or(() -> Result.success(fallback));
    }

    private static Result<Boolean> flag(ApiRequest request, String name) {
        return request.queryParam(name)
                      .map(raw -> switch (raw) {
            case "1", "true" -> Result.success(true);
            case "0", "false" -> Result.success(false);
            default -> FleetRoutes.<Boolean> invalid(name + " must be 0 or 1");
        })
                      .or(() -> Result.success(false));
    }

    private static Result<FleetLines.Format> format(ApiRequest request) {
        return switch (request.queryParam("format")
                              .or("ndjson")) {
            case "ndjson" -> Result.success(FleetLines.Format.NDJSON);
            case "text" -> Result.success(FleetLines.Format.TEXT);
            default -> invalid("format must be ndjson or text");
        };
    }

    private static Result<Long> number(String raw, String name) {
        return Result.lift(cause -> Causes.cause("Invalid " + name + ": " + raw, some(Causes.fromThrowable(cause))),
                           () -> Long.parseLong(raw));
    }

    private static <T> Result<T> present(T body) {
        return Option.option(body).toResult(Causes.cause("body required"));
    }

    private static <T> Result<T> invalid(String text) {
        return Causes.cause(text).result();
    }
}
