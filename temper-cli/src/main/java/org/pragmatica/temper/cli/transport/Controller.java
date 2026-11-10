package org.pragmatica.temper.cli.transport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.protocol.api.ErrorResponse;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.FleetAlarmsView;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.KnownAddRequest;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.protocol.api.MaintenanceView;
import org.pragmatica.temper.protocol.api.RefusalAddRequest;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.RerunRequest;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SelectorChange;
import org.pragmatica.temper.protocol.api.SubmitRequest;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.SubscriberView;
import org.pragmatica.temper.protocol.api.WsClientMessage.WsAck;
import org.pragmatica.temper.protocol.api.WsServerMessage;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


/// The controller's M0 HTTP routes (m0-design §6.2) as typed calls. Status handling is shared: `401` is
/// [CliError.General#UNAUTHORIZED] (exit 77), `403` (the read token on a write route, D3) is
/// [CliError.General#FORBIDDEN] (exit 77), `400` is [CliError.Rejected], `404` is [CliError.NotFound]
/// except where a route gives it a meaning, and anything else outside the route's contract is
/// [CliError.UnexpectedStatus] (69). Only the log route gives `503` a meaning ([CliError.General#HOST_UNAVAILABLE],
/// exit 5, Q-15); elsewhere a 503 is a transient controller fault that `wait` can ride out. Only adding a refusal or a
/// known-failure entry, and `digest` (a foreign cursor), give `409` a meaning ([CliError.Conflict], exit 2,
/// m1-design §6, m2-design §4.4).
public record Controller(HttpTransport http, JsonMapper json) {
    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int NO_CONTENT = 204;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int UNPROCESSABLE = 422;
    private static final int UNAVAILABLE = 503;

    public static Controller controller(HttpTransport http, JsonMapper json) {
        return new Controller(http, json);
    }

    /// `POST /jobs`: `200` accepted (or deduplicated), `422` refused.
    public Promise<SubmitAnswer> submit(SubmitRequest request) {
        return json.writeAsString(request)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply("submit request", cause))
                   .async()
                   .flatMap(body -> http.exchange(post("/jobs",
                                                       some(body))))
                   .flatMap(reply -> submitAnswer("/jobs", reply).async());
    }

    /// `POST /jobs/{id}/rerun` (m2-design §5.5): `200` accepted (or deduplicated), `422` refused, `404` no such job.
    public Promise<SubmitAnswer> rerun(long job, RerunRequest request) {
        var path = jobPath(job) + "/rerun";

        return json.writeAsString(request)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply("rerun request", cause))
                   .async()
                   .flatMap(body -> http.exchange(post(path,
                                                       some(body))))
                   .flatMap(reply -> submitAnswer(path, reply).async());
    }

    /// `GET /jobs/{id}`.
    public Promise<JobView> job(long job) {
        var path = jobPath(job);

        return http.exchange(get(path))
                   .flatMap(reply -> expect(path, reply, JobView.class).async());
    }

    /// `GET /jobs/{id}/result`: `404` means "not terminal yet" (spec §7), so it is `none()`; whether the job
    /// exists at all is for the caller to ask through [#job].
    public Promise<Option<ResultRecordDto>> result(long job) {
        var path = jobPath(job) + "/result";

        return http.exchange(get(path))
                   .flatMap(reply -> optional(path, reply).async());
    }

    /// `POST /jobs/{id}/cancel`.
    public Promise<CancelResponse> cancel(long job) {
        var path = jobPath(job) + "/cancel";

        return http.exchange(post(path,
                                  none()))
                   .flatMap(reply -> expect(path, reply, CancelResponse.class).async());
    }

    /// `GET /jobs/{id}/log?unit=&tail=`: raw text; `503` when the host link is down.
    public Promise<String> log(long job, Option<String> unit, Option<Integer> tail) {
        var path = jobPath(job) + "/log" + query(Map.of("unit", unit, "tail", tail.map(String::valueOf)));

        return http.exchange(get(path))
                   .flatMap(reply -> text(path, reply).async());
    }

    /// `GET /subscribers/{name}/next?terminal=`: `200` the event (or gap) after the subscriber's cursor, `204`
    /// (`none()`) when nothing arrived within the controller's poll window (about 30 s, under [HttpTransport]'s
    /// request timeout). It does not move the cursor: asking again without [#ack] answers the same message. The
    /// controller creates an unknown subscriber here, so a `404` means the route itself is missing (a controller
    /// without long-poll): [CliError.UnexpectedStatus], not [CliError.NotFound].
    public Promise<Option<WsServerMessage>> next(String subscriber, boolean terminal) {
        var path = subscriberPath(subscriber) + "/next?terminal=" + terminal;

        return http.exchange(get(path))
                   .flatMap(reply -> pending(path, reply).async());
    }

    /// `POST /subscribers/{name}/ack` with `{"ack":{"seq":N}}`: `204`; the cursor becomes `max(cursor, seq)`. A `404`
    /// (the subscriber vanished since its `next`) is [CliError.NotFound]. An encoding failure is labelled
    /// [CliError.BadPayload] as in [#submit]; a record of one `long` cannot practically fail to encode.
    public Promise<Unit> ack(String subscriber, long seq) {
        var path = subscriberPath(subscriber) + "/ack";

        return json.writeAsString(new WsAck(seq))
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause))
                   .async()
                   .flatMap(body -> http.exchange(post(path,
                                                       some(body))))
                   .flatMap(reply -> acknowledged(path, reply).async());
    }

    /// `PUT /subscribers/{name}/selectors` with `{add, remove}` in wire form: `200` the subscriber after the change.
    /// A `404` (an added job does not exist) is [CliError.NotFound] and a `400` (a bad name or selector)
    /// [CliError.Rejected], both exit 64. An encoding failure is labelled as in [#submit].
    public Promise<SubscriberView> selectors(String subscriber, List<String> add, List<String> remove) {
        var path = subscriberPath(subscriber) + "/selectors";

        return json.writeAsString(SelectorChange.selectorChange(add, remove))
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause))
                   .async()
                   .flatMap(body -> http.exchange(put(path,
                                                      some(body))))
                   .flatMap(reply -> expect(path, reply, SubscriberView.class).async());
    }

    /// `GET /jobs?sha=&ref=&limit=&before=`: one page of the jobs at a SHA or a requested ref, newest first, older than
    /// `before` when given.
    public Promise<List<JobView>> jobs(Option<String> sha, Option<String> ref, int limit, Option<Long> before) {
        var path = "/jobs" + query(Map.of("sha",
                                          sha,
                                          "ref",
                                          ref,
                                          "limit",
                                          Option.some(String.valueOf(limit)),
                                          "before",
                                          before.map(String::valueOf)));

        return http.exchange(get(path))
                   .flatMap(reply -> expect(path, reply, JobView[].class).map(Controller::jobsOf)
                                           .async());
    }

    /// `GET /refusals?all=`: the active refusals, or every one with `all`.
    public Promise<List<RefusalDto>> refusals(boolean all) {
        var path = "/refusals?all=" + all;

        return http.exchange(get(path))
                   .flatMap(reply -> listed(path, reply).async());
    }

    /// `POST /refusals`: `201` the stored refusal; `409` the code exists ([CliError.Conflict]); `400` the code or
    /// matcher does not parse ([CliError.Rejected]). An encoding failure is labelled as in [#submit].
    public Promise<RefusalDto> newRefusal(RefusalAddRequest request) {
        var path = "/refusals";

        return json.writeAsString(request)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply("refusal add request", cause))
                   .async()
                   .flatMap(body -> http.exchange(post(path,
                                                       some(body))))
                   .flatMap(reply -> added(path, reply, RefusalDto.class).async());
    }

    /// `POST /refusals/{code}/expire`: `200` the refusal with its `expiredAt`; `404` the code is unknown.
    public Promise<RefusalDto> expireRefusal(String code) {
        var path = "/refusals/" + segment(code) + "/expire";

        return http.exchange(post(path,
                                  none()))
                   .flatMap(reply -> expect(path, reply, RefusalDto.class).async());
    }

    /// `GET /known?all=` (m2-design §4.4): the active known-failure entries, or every one with `all`.
    public Promise<List<KnownDto>> known(boolean all) {
        var path = "/known?all=" + all;

        return http.exchange(get(path))
                   .flatMap(reply -> knownList(path, reply).async());
    }

    /// `POST /known`: `201` the stored entry; `409` an entry for that test is active ([CliError.Conflict]); `400` a
    /// field does not parse ([CliError.Rejected]). An encoding failure is labelled as in [#submit].
    public Promise<KnownDto> newKnown(KnownAddRequest request) {
        var path = "/known";

        return json.writeAsString(request)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply("known add request", cause))
                   .async()
                   .flatMap(body -> http.exchange(post(path,
                                                       some(body))))
                   .flatMap(reply -> added(path, reply, KnownDto.class).async());
    }

    /// `POST /known/{id}/expire`: `200` the entry with its `expiredAt`; `404` the id is unknown.
    public Promise<KnownDto> expireKnown(long id) {
        var path = "/known/" + id + "/expire";

        return http.exchange(post(path,
                                  none()))
                   .flatMap(reply -> expect(path, reply, KnownDto.class).async());
    }

    /// `GET /hosts`: every configured worker host with its link state.
    public Promise<List<HostView>> hosts() {
        var path = "/hosts";

        return http.exchange(get(path))
                   .flatMap(reply -> hostList(path, reply).async());
    }

    /// `GET /fleet/alarms`: every expected host with its current alarms.
    public Promise<FleetAlarmsView> fleetAlarms() {
        var path = "/fleet/alarms";

        return http.exchange(get(path))
                   .flatMap(reply -> expect(path, reply, FleetAlarmsView.class).async());
    }

    /// `GET /maintenance`: the latest `pg_dump` run and when the latest good one ended.
    public Promise<MaintenanceView> maintenance() {
        var path = "/maintenance";

        return http.exchange(get(path))
                   .flatMap(reply -> expect(path, reply, MaintenanceView.class).async());
    }

    /// `GET /digest?since=&max=`: the events after the cursor `since` (none: from the head of the event log), at most
    /// `max` (none: the controller's default), and what stands now. A `400` (a cursor ahead of the controller's event
    /// log) is [CliError.Rejected]; a `409` (`CURSOR_FOREIGN`: the cursor is not from this event log) is
    /// [CliError.Conflict].
    public Promise<DigestView> digest(Option<String> since, Option<Integer> max) {
        var path = "/digest" + query(Map.of("since", since, "max", max.map(String::valueOf)));

        return http.exchange(get(path))
                   .flatMap(reply -> digested(path, reply).async());
    }

    /// `GET /gate/{sha}` (m2-design §1.4): the answer for a SHA or a prefix of 7 to 40 hex. A `400` (a SHA that is not
    /// hex, or a prefix of Gates at several SHAs, which the error names) is [CliError.Rejected], exit 64.
    public Promise<GateView> gate(ShaPrefix sha) {
        var path = "/gate/" + sha.value();

        return http.exchange(get(path))
                   .flatMap(reply -> expect(path, reply, GateView.class).async());
    }

    /// `GET /diff?a=&b=` (m2-design §3.3): side A is job `a`, side B job `b`; the diff, or a typed refusal (pruned, not
    /// terminal, refused). A `404` (an unknown job) is [CliError.NotFound], a `400` [CliError.Rejected], both exit 64.
    public Promise<DiffAnswer> diff(long a, long b) {
        return diffed(query(Map.of("a", some(String.valueOf(a)), "b", some(String.valueOf(b)))));
    }

    /// `GET /diff?a=&baseSha=` (m2-design §3.3): side B is `job`, side A the Gate `gate <sha>` answers about; a NONE or
    /// CONFLICT answer is a typed refusal. Statuses as [#diff(long, long)]; a prefix of Gates at two SHAs is the `400`.
    public Promise<DiffAnswer> diffBase(long job, ShaPrefix sha) {
        return diffed(query(Map.of("a", some(String.valueOf(job)), "baseSha", some(sha.value()))));
    }

    private Promise<DiffAnswer> diffed(String query) {
        var path = "/diff" + query;

        return http.exchange(get(path))
                   .flatMap(reply -> expect(path, reply, DiffAnswer.class).async());
    }

    /// The name as one path segment: form encoding turns a space into `+`, which a path reads literally.
    private static String subscriberPath(String subscriber) {
        return "/subscribers/" + segment(subscriber);
    }

    private static String segment(String value) {
        return urlEncoded(value).replace("+", "%20");
    }

    private static String jobPath(long job) {
        return "/jobs/" + job;
    }

    private static String query(Map<String, Option<String>> parameters) {
        return joined(parameters.entrySet()
                                .stream()
                                .sorted(Map.Entry.comparingByKey())
                                .flatMap(Controller::pair)
                                .toList());
    }

    /// `name=value` for a present parameter, nothing for an absent one.
    private static Stream<String> pair(Map.Entry<String, Option<String>> parameter) {
        var name = parameter.getKey();

        return parameter.getValue()
                        .map(value -> name + "=" + urlEncoded(value))
                        .stream();
    }

    private static String joined(List<String> pairs) {
        return pairs.isEmpty()
               ? ""
               : "?" + String.join("&", pairs);
    }

    private static HttpCall get(String path) {
        return HttpCall.httpCall("GET", path, none());
    }

    private static HttpCall post(String path, Option<String> body) {
        return HttpCall.httpCall("POST", path, body);
    }

    private static HttpCall put(String path, Option<String> body) {
        return HttpCall.httpCall("PUT", path, body);
    }

    private static String urlEncoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Result<SubmitAnswer> submitAnswer(String path, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> decode(path, reply, SubmitResponse.class).map(SubmitAnswer.Accepted::new);
            case UNPROCESSABLE -> decode(path, reply, RefusedResponse.class).map(SubmitAnswer.Refused::new);
            default -> failure(path, reply);
        };
    }

    private Result<List<RefusalDto>> listed(String path, HttpReply reply) {
        return expect(path, reply, RefusalDto[].class).map(Controller::listOf);
    }

    private static List<RefusalDto> listOf(RefusalDto[] refusals) {
        return List.of(refusals);
    }

    private Result<List<KnownDto>> knownList(String path, HttpReply reply) {
        return expect(path, reply, KnownDto[].class).map(Controller::knownOf);
    }

    private static List<KnownDto> knownOf(KnownDto[] entries) {
        return List.of(entries);
    }

    private Result<List<HostView>> hostList(String path, HttpReply reply) {
        return expect(path, reply, HostView[].class).map(Controller::hostsOf);
    }

    private static List<JobView> jobsOf(JobView[] jobs) {
        return List.of(jobs);
    }

    private static List<HostView> hostsOf(HostView[] hosts) {
        return List.of(hosts);
    }

    private Result<DigestView> digested(String path, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> decode(path, reply, DigestView.class);
            case CONFLICT -> CliError.Conflict.FACTORY.apply(errorText(reply)).result();
            default -> failure(path, reply);
        };
    }

    /// `201` the stored row; `409` is [CliError.Conflict].
    private <T> Result<T> added(String path, HttpReply reply, Class<T> type) {
        return switch (reply.status()) {
            case CREATED -> decode(path, reply, type);
            case CONFLICT -> CliError.Conflict.FACTORY.apply(errorText(reply)).result();
            default -> failure(path, reply);
        };
    }

    private Result<Option<ResultRecordDto>> optional(String path, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> decode(path, reply, ResultRecordDto.class).map(Option::some);
            case NOT_FOUND -> success(none());
            default -> failure(path, reply);
        };
    }

    private Result<Option<WsServerMessage>> pending(String path, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> decode(path, reply, WsServerMessage.class).map(Option::some);
            case NO_CONTENT -> success(none());
            case NOT_FOUND -> CliError.UnexpectedStatus.FACTORY.apply(NOT_FOUND, path).result();
            default -> failure(path, reply);
        };
    }

    private Result<Unit> acknowledged(String path, HttpReply reply) {
        return reply.status() == NO_CONTENT
               ? unitResult()
               : failure(path, reply);
    }

    private Result<String> text(String path, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> success(reply.body());
            case UNAVAILABLE -> CliError.General.HOST_UNAVAILABLE.result();
            default -> failure(path, reply);
        };
    }

    private <T> Result<T> expect(String path, HttpReply reply, Class<T> type) {
        return reply.status() == OK
               ? decode(path, reply, type)
               : failure(path, reply);
    }

    private <T> Result<T> decode(String path, HttpReply reply, Class<T> type) {
        return json.readString(reply.body(),
                               type)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause));
    }

    private <T> Result<T> failure(String path, HttpReply reply) {
        return failureCause(path, reply).result();
    }

    private Cause failureCause(String path, HttpReply reply) {
        return switch (reply.status()) {
            case UNAUTHORIZED -> CliError.General.UNAUTHORIZED;
            case FORBIDDEN -> CliError.General.FORBIDDEN;
            case BAD_REQUEST -> CliError.Rejected.FACTORY.apply(errorText(reply));
            case NOT_FOUND -> CliError.NotFound.FACTORY.apply(path);
            default -> CliError.UnexpectedStatus.FACTORY.apply(reply.status(), path);
        };
    }

    /// The `ErrorResponse.error` text. A body that is not an `ErrorResponse` is shown raw: the decode failure is
    /// dropped on purpose (FER: the raw text is the more useful diagnosis, and the 400 is reported either way).
    /// The controller's error text on one line: a composite rejection lists each reason, joined by "; ".
    private String errorText(HttpReply reply) {
        return json.readString(reply.body(),
                               ErrorResponse.class)
                   .map(ErrorResponse::error)
                   .or(reply.body())
                   .lines()
                   .map(String::strip)
                   .filter(line -> !line.isEmpty())
                   .collect(Collectors.joining("; "));
    }
}
