package org.pragmatica.temper.cli.transport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.unitResult;


/// The fleet routes `events` uses, over the version-checked [HttpTransport]. Each call carries its
/// own answer bound ([HttpCall#withTimeout]): a long-poll is held up to its `wait`, and the caller sizes the bound to the
/// time it has left.
///
/// Statuses: `200` the reply's lines ([FleetLine], [FleetReply]); `401` and `403` exit 77; `400` [CliError.Rejected]
/// (64); `404` [CliError.NotFound] (64: the subscriber does not exist, `NO_SUBSCRIBER`, or the route does not, a
/// controller older than T1); `409` from `events/after` [CliError.EpochChanged] (7), from an ack
/// [CliError.AckEpochChanged] (an epoch boundary `--follow` polls again after, lead ruling F2), from `subscribers` [CliError.Conflict] (2: the name exists); anything else [CliError.UnexpectedStatus]. A `200` whose lines do not decode
/// is [CliError.BadPayload].
public record FleetApi(HttpTransport http, JsonMapper json) {
    public static final String HEARTBEAT_PATH = "/fleet/heartbeat";
    public static final String AFTER_PATH = "/fleet/events/after";
    public static final String EVENTS_PATH = "/fleet/events";
    public static final String SUBSCRIBERS_PATH = "/fleet/subscribers";
    /// The route's `max` upper bound: `--follow` takes as much as one reply carries.
    public static final int MAX_EVENTS = 500;
    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int NO_CONTENT = 204;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int CLIENT_ERRORS = 400;
    private static final int SERVER_ERRORS = 500;
    private static final String UNKNOWN_EPOCH = "an epoch it did not name";

    /// A stateless wait (`GET /fleet/events/after`): the first event after `after` whose kind is one of `until`, whose
    /// `match` pairs (`k=v`) all hold, and that is not a catch-up row unless `includeCatchUp`.
    public record AfterQuery(FleetPosition after, List<String> until, List<String> match, boolean includeCatchUp) {
        public static AfterQuery afterQuery(FleetPosition after,
                                            List<String> until,
                                            List<String> match,
                                            boolean includeCatchUp) {
            return new AfterQuery(after, until, match, includeCatchUp);
        }
    }

    /// `POST /fleet/subscribers/<name>/ack`'s body: the seq, and the epoch it was read in (lead ruling F2).
    public record AckBody(String epoch, long seq) {}

    /// `POST /fleet/subscribers`'s body.
    public record CreateBody(String name, String start, String bindTo) {}

    public static FleetApi fleetApi(HttpTransport http, JsonMapper json) {
        return new FleetApi(http, json);
    }

    /// `GET /fleet/heartbeat`: the heartbeat line alone.
    public Promise<FleetLine.Heartbeat> heartbeat(Duration timeout) {
        return http.exchange(get(HEARTBEAT_PATH, timeout))
                   .flatMap(reply -> heartbeatOf(reply).async());
    }

    /// `GET /fleet/events/after`, held up to `wait` seconds when nothing matches.
    public Promise<FleetReply> after(AfterQuery query, long wait, Duration timeout) {
        var path = AFTER_PATH + afterQueryText(query, wait);

        return http.exchange(get(path, timeout))
                   .flatMap(reply -> afterAnswer(path,
                                                 query.after(),
                                                 reply).async());
    }

    /// `GET /fleet/events` for `subscriber`: the events after its cursor, then the heartbeat; `text` asks for the text
    /// form of the event lines.
    public Promise<FleetReply> events(String subscriber, boolean text, long wait, Duration timeout) {
        var path = EVENTS_PATH
                 + "?subscriber=" + urlEncoded(subscriber)
                 + "&max=" + MAX_EVENTS
                 + "&wait=" + wait
                 + "&format=" + (text
                                 ? "text"
                                 : "ndjson");

        return http.exchange(get(path, timeout))
                   .flatMap(reply -> eventsAnswer(path, text, reply).async());
    }

    /// `POST /fleet/subscribers/<name>/ack {"epoch","seq"}`: the cursor moves to at least `seq` while the controller is
    /// still in `epoch` (the epoch of the reply that delivered the line, lead ruling F2); `409` when it is not.
    public Promise<Unit> ack(String subscriber, String epoch, long seq, Duration timeout) {
        var path = subscriberPath(subscriber) + "/ack";

        return body(new AckBody(epoch, seq),
                    "ack seq",
                    Long.toString(seq)).async()
                   .flatMap(body -> http.exchange(post(path, body, timeout)))
                   .flatMap(reply -> acknowledged(path, epoch, reply).async());
    }

    /// `POST /fleet/subscribers {"name","start","bindTo"}`: `201` created.
    public Promise<Unit> create(String name, String start, String bindTo, Duration timeout) {
        return body(new CreateBody(name, start, bindTo),
                    "subscriber name",
                    name).async()
                   .flatMap(body -> http.exchange(post(SUBSCRIBERS_PATH, body, timeout)))
                   .flatMap(reply -> created(reply).async());
    }

    /// The failures no retry of a fleet route changes: the token (401, 403), a closed stdout, a refused or unknown
    /// request (400, 404, any other 4xx), a changed epoch, an existing name, another controller build (D-6). Everything
    /// else (unreachable, a 5xx, an answer that does not decode or lacks its heartbeat) may pass on the next try.
    public static boolean isFinal(Cause cause) {
        return switch (cause) {
            case CliError.General.UNAUTHORIZED, CliError.General.FORBIDDEN, CliError.General.OUTPUT_CLOSED -> true;
            case CliError.Rejected _, CliError.NotFound _, CliError.Conflict _ -> true;
            case CliError.VersionSkew _, CliError.EpochChanged _ -> true;
            case CliError.UnexpectedStatus status -> isClientError(status.status());
            default -> false;
        };
    }

    private static boolean isClientError(int status) {
        return status >= CLIENT_ERRORS && status < SERVER_ERRORS;
    }

    private Result<FleetLine.Heartbeat> heartbeatOf(HttpReply reply) {
        return reply.status() == OK
               ? lines(HEARTBEAT_PATH, reply, FleetLine::ndjson).flatMap(FleetApi::beatOf)
               : failure(HEARTBEAT_PATH, reply);
    }

    private static Result<FleetLine.Heartbeat> beatOf(FleetReply reply) {
        return reply.heartbeat()
                    .toResult(missingHeartbeat(HEARTBEAT_PATH));
    }

    /// The failure of a reply that ended without its heartbeat line.
    public static Cause missingHeartbeat(String path) {
        return CliError.BadPayload.FACTORY.apply(path, Causes.cause("the reply ends without a heartbeat line"));
    }

    private Result<FleetReply> afterAnswer(String path, FleetPosition after, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> lines(path, reply, FleetLine::ndjson);
            case CONFLICT -> CliError.EpochChanged.FACTORY.apply(after.epoch(), currentEpoch(reply)).result();
            default -> failure(path, reply);
        };
    }

    private Result<FleetReply> eventsAnswer(String path, boolean text, HttpReply reply) {
        return reply.status() == OK
               ? lines(path,
                       reply,
                       text
                       ? FleetLine::text
                       : FleetLine::ndjson)
               : failure(path, reply);
    }

    /// `200` or `204`: the contract names no status for an ack; `409` the epoch it was read in is no longer the
    /// controller's (lead ruling F2), the cursor unchanged.
    private Result<Unit> acknowledged(String path, String epoch, HttpReply reply) {
        return switch (reply.status()) {
            case OK, NO_CONTENT -> unitResult();
            case CONFLICT -> CliError.AckEpochChanged.FACTORY.apply(epoch).result();
            default -> failure(path, reply);
        };
    }

    private Result<Unit> created(HttpReply reply) {
        return switch (reply.status()) {
            case CREATED -> unitResult();
            case CONFLICT -> CliError.Conflict.FACTORY.apply(errorText(reply)).result();
            default -> failure(SUBSCRIBERS_PATH, reply);
        };
    }

    private Result<FleetReply> lines(String path, HttpReply reply, Fn2<Result<FleetLine>, JsonMapper, String> parser) {
        return Result.allOf(reply.body().lines().filter(Verify.Is::notBlank).map(line -> parser.apply(json, line)))
                     .map(FleetReply::fleetReply)
                     .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause));
    }

    /// The `epoch` the 409's body names; a body without one still exits 7, naming none.
    private String currentEpoch(HttpReply reply) {
        return field(reply, "epoch").or(UNKNOWN_EPOCH);
    }

    private <T> Result<T> failure(String path, HttpReply reply) {
        return failureCause(path, reply).result();
    }

    private Cause failureCause(String path, HttpReply reply) {
        return switch (reply.status()) {
            case UNAUTHORIZED -> CliError.General.UNAUTHORIZED;
            case FORBIDDEN -> CliError.General.FORBIDDEN;
            case BAD_REQUEST -> CliError.Rejected.FACTORY.apply(errorText(reply));
            case NOT_FOUND -> CliError.NotFound.FACTORY.apply(path + " (" + errorText(reply) + ")");
            default -> CliError.UnexpectedStatus.FACTORY.apply(reply.status(), path);
        };
    }

    /// The body's `error` text; a body without one is shown raw (FER: the raw text is the diagnosis, and the status is
    /// reported either way).
    private String errorText(HttpReply reply) {
        return field(reply, "error").or(reply.body().strip());
    }

    /// A top-level text field of a JSON body; none for a body that is not JSON or lacks it (the decode failure is the
    /// absence itself: callers fall back to the raw body or a fixed text).
    private Option<String> field(HttpReply reply, String name) {
        return json.readTree(reply.body())
                   .option()
                   .flatMap(node -> textField(node, name));
    }

    private static Option<String> textField(JsonNode node, String name) {
        return Option.from(node.path(name).stringValueOpt());
    }

    private Result<String> body(Object value, String setting, String raw) {
        return json.writeAsString(value)
                   .mapError(cause -> CliError.BadSetting.FACTORY.apply(setting, raw, cause));
    }

    private static String afterQueryText(AfterQuery query, long wait) {
        return "?after=" + urlEncoded(query.after().text())
             + "&includeCatchUp=" + (query.includeCatchUp()
                                     ? "1"
                                     : "0") + matchText(query.match())
             + "&until=" + urlEncoded(String.join(",", query.until()))
             + "&wait=" + wait;
    }

    private static String matchText(List<String> match) {
        return match.isEmpty()
               ? ""
               : "&match=" + urlEncoded(String.join(",", match));
    }

    private static String subscriberPath(String subscriber) {
        return SUBSCRIBERS_PATH + "/" + urlEncoded(subscriber).replace("+", "%20");
    }

    private static String urlEncoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpCall get(String path, Duration timeout) {
        return HttpCall.httpCall("GET", path, none()).withTimeout(timeout);
    }

    private static HttpCall post(String path, String body, Duration timeout) {
        return HttpCall.httpCall("POST", path, some(body)).withTimeout(timeout);
    }
}
