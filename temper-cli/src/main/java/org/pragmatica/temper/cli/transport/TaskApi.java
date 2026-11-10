package org.pragmatica.temper.cli.transport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.protocol.api.ClaimRequest;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.ErrorResponse;
import org.pragmatica.temper.protocol.api.LeaseCall;
import org.pragmatica.temper.protocol.api.ParkCall;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.SubmitRequest;
import org.pragmatica.temper.protocol.api.SubmitResponse;
import org.pragmatica.temper.protocol.api.TaskCreateRequest;
import org.pragmatica.temper.protocol.api.TaskDecision;
import org.pragmatica.temper.protocol.api.TaskEventDto;
import org.pragmatica.temper.protocol.api.TaskView;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


/// The task routes (m3-design §11.1) as typed calls. Status handling, per route kind (§11.2):
/// - a lease-token call (heartbeat, wait, release, a park under a lease, a linked submit): `409` is
///   [CliError.LeaseLost] (exit 12);
/// - a claim: `204` is none (exit 8), `409` is [CliError.NotReady] (9);
/// - the maintainer's commands: `409` is [CliError.NotReady] (9);
/// - every route: `422` is [CliError.TaskRefused] (2), `400` [CliError.Rejected] and `404` [CliError.NotFound] (64),
///   `401`/`403` exit 77, anything else outside the contract [CliError.UnexpectedStatus] (69).
public record TaskApi(HttpTransport http, JsonMapper json) {
    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int NO_CONTENT = 204;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int UNPROCESSABLE = 422;

    /// How a route's `409` reads.
    enum Conflict {
        LEASE,
        STATE
    }

    public static TaskApi taskApi(HttpTransport http, JsonMapper json) {
        return new TaskApi(http, json);
    }

    public Promise<TaskView> create(TaskCreateRequest request) {
        return posted("/tasks", request, none(), CREATED, TaskView.class, Conflict.STATE);
    }

    public Promise<List<TaskView>> list(Option<String> stage, Option<String> status) {
        var path = "/tasks" + query(List.of(pair("stage", stage), pair("status", status)));

        return http.exchange(HttpCall.httpCall("GET",
                                               path,
                                               none()))
                   .flatMap(reply -> expected(path, reply, OK, TaskView[].class, Conflict.STATE).map(TaskApi::listOf)
                                             .async());
    }

    public Promise<List<TaskView>> next(Option<String> stage) {
        var path = "/tasks/next" + query(List.of(pair("stage", stage)));

        return http.exchange(HttpCall.httpCall("GET",
                                               path,
                                               none()))
                   .flatMap(reply -> expected(path, reply, OK, TaskView[].class, Conflict.STATE).map(TaskApi::listOf)
                                             .async());
    }

    public Promise<TaskView> show(long task) {
        var path = taskPath(task);

        return http.exchange(HttpCall.httpCall("GET",
                                               path,
                                               none()))
                   .flatMap(reply -> expected(path, reply, OK, TaskView.class, Conflict.STATE).async());
    }

    public Promise<List<TaskEventDto>> events(long task) {
        var path = taskPath(task) + "/events";

        return http.exchange(HttpCall.httpCall("GET",
                                               path,
                                               none()))
                   .flatMap(reply -> expected(path, reply, OK, TaskEventDto[].class, Conflict.STATE).map(TaskApi::listOf)
                                             .async());
    }

    /// `claim <id>` (`task` present) or `claim --stage S`: none when nothing is ready (`204`).
    public Promise<Option<ClaimResponse>> claim(Option<Long> task, Option<String> stage, Option<String> worker) {
        var path = task.map(id -> taskPath(id) + "/claim")
                       .or(() -> "/tasks/claim" + query(List.of(pair("stage", stage))));

        return json.writeAsString(new ClaimRequest(worker))
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause))
                   .async()
                   .flatMap(body -> http.exchange(HttpCall.httpCall("POST",
                                                                    path,
                                                                    some(body))))
                   .flatMap(reply -> claimed(path, reply).async());
    }

    public Promise<Unit> heartbeat(long task, long epoch, String lease) {
        return leaseCall(taskPath(task) + "/heartbeat", new LeaseCall.Heartbeat(epoch), lease);
    }

    public Promise<Unit> waitOn(long task, long epoch, List<Long> jobs, boolean ci, String lease) {
        return leaseCall(taskPath(task) + "/wait", new LeaseCall.Wait(epoch, some(jobs), some(ci)), lease);
    }

    public Promise<Unit> release(long task, long epoch, String reason, String lease) {
        return leaseCall(taskPath(task) + "/release", new LeaseCall.Release(epoch, reason), lease);
    }

    /// Under the lease when one is given (the holder parks), else with the bearer token (the maintainer).
    public Promise<TaskView> park(long task, ParkCall call, Option<String> lease) {
        return posted(taskPath(task) + "/park",
                      call,
                      lease,
                      OK,
                      TaskView.class,
                      lease.map(_ -> Conflict.LEASE).or(Conflict.STATE));
    }

    public Promise<TaskView> rule(long task, TaskDecision.Rule rule) {
        return posted(taskPath(task) + "/rule", rule, none(), OK, TaskView.class, Conflict.STATE);
    }

    public Promise<TaskView> close(long task, TaskDecision.Close close) {
        return posted(taskPath(task) + "/close", close, none(), OK, TaskView.class, Conflict.STATE);
    }

    public Promise<TaskView> bounce(long task, TaskDecision.Bounce bounce) {
        return posted(taskPath(task) + "/bounce", bounce, none(), OK, TaskView.class, Conflict.STATE);
    }

    /// `submit --task`: the submit under the task's lease; a `409` is the lease lost (exit 12).
    public Promise<SubmitAnswer> submit(SubmitRequest request, String lease) {
        var path = "/jobs";

        return json.writeAsString(request)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply("submit request", cause))
                   .async()
                   .flatMap(body -> http.exchange(HttpCall.httpCall("POST",
                                                                    path,
                                                                    some(body)).withLease(lease)))
                   .flatMap(reply -> submitAnswer(path, reply).async());
    }

    // ---- shared ------------------------------------------------------------------------------------------------------
    private Promise<Unit> leaseCall(String path, LeaseCall body, String lease) {
        return json.writeAsString(body)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause))
                   .async()
                   .flatMap(text -> http.exchange(HttpCall.httpCall("POST",
                                                                    path,
                                                                    some(text)).withLease(lease)))
                   .flatMap(reply -> noContent(path, reply).async());
    }

    private <T> Promise<T> posted(String path,
                                  Object body,
                                  Option<String> lease,
                                  int expected,
                                  Class<T> type,
                                  Conflict conflict) {
        return json.writeAsString(body)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause))
                   .async()
                   .flatMap(text -> http.exchange(call(path, text, lease)))
                   .flatMap(reply -> expected(path, reply, expected, type, conflict).async());
    }

    private static HttpCall call(String path, String body, Option<String> lease) {
        var call = HttpCall.httpCall("POST", path, some(body));

        return lease.map(call::withLease)
                    .or(call);
    }

    private Result<Option<ClaimResponse>> claimed(String path, HttpReply reply) {
        return reply.status() == NO_CONTENT
               ? success(none())
               : expected(path, reply, OK, ClaimResponse.class, Conflict.STATE).map(Option::some);
    }

    private Result<Unit> noContent(String path, HttpReply reply) {
        return reply.status() == NO_CONTENT
               ? unitResult()
               : failure(path, reply, Conflict.LEASE);
    }

    private Result<SubmitAnswer> submitAnswer(String path, HttpReply reply) {
        return switch (reply.status()) {
            case OK -> decode(path, reply, SubmitResponse.class).map(SubmitAnswer.Accepted::new);
            case UNPROCESSABLE -> decode(path, reply, RefusedResponse.class).map(SubmitAnswer.Refused::new);
            default -> failure(path, reply, Conflict.LEASE);
        };
    }

    private <T> Result<T> expected(String path, HttpReply reply, int status, Class<T> type, Conflict conflict) {
        return reply.status() == status
               ? decode(path, reply, type)
               : failure(path, reply, conflict);
    }

    private <T> Result<T> decode(String path, HttpReply reply, Class<T> type) {
        return json.readString(reply.body(),
                               type)
                   .mapError(cause -> CliError.BadPayload.FACTORY.apply(path, cause));
    }

    private <T> Result<T> failure(String path, HttpReply reply, Conflict conflict) {
        return failureCause(path, reply, conflict).result();
    }

    private Cause failureCause(String path, HttpReply reply, Conflict conflict) {
        return switch (reply.status()) {
            case UNAUTHORIZED -> CliError.General.UNAUTHORIZED;
            case FORBIDDEN -> CliError.General.FORBIDDEN;
            case BAD_REQUEST -> CliError.Rejected.FACTORY.apply(errorText(reply));
            case NOT_FOUND -> CliError.NotFound.FACTORY.apply(path);
            case CONFLICT -> conflicted(conflict, errorText(reply));
            case UNPROCESSABLE -> CliError.TaskRefused.FACTORY.apply(errorText(reply));
            default -> CliError.UnexpectedStatus.FACTORY.apply(reply.status(), path);
        };
    }

    private static Cause conflicted(Conflict conflict, String error) {
        return switch (conflict) {
            case LEASE -> CliError.LeaseLost.FACTORY.apply(error);
            case STATE -> CliError.NotReady.FACTORY.apply(error);
        };
    }

    /// The `ErrorResponse.error` text; a body that is not one is shown raw (FER: the raw text is the better diagnosis,
    /// and the failure is reported either way).
    private String errorText(HttpReply reply) {
        return json.readString(reply.body(),
                               ErrorResponse.class)
                   .map(ErrorResponse::error)
                   .or(reply.body());
    }

    private static <T> List<T> listOf(T[] items) {
        return List.of(items);
    }

    private static String taskPath(long task) {
        return "/tasks/" + task;
    }

    private static Option<String> pair(String name, Option<String> value) {
        return value.map(present -> name + "=" + URLEncoder.encode(present, StandardCharsets.UTF_8));
    }

    private static String query(List<Option<String>> pairs) {
        var present = pairs.stream().flatMap(Option::stream).toList();

        return present.isEmpty()
               ? ""
               : "?" + String.join("&", present);
    }
}
