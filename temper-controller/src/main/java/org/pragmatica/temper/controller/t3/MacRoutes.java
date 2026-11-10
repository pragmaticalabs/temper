package org.pragmatica.temper.controller.t3;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.fleet.FleetRoutes;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.MacStore;
import org.pragmatica.temper.store.api.NewMacPost;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.temper.controller.http.Route.scopedRoute;


/// `POST /api/v1/fleet/mac` (MAC scope, WRITES): the Mac's liveness post, behind the fleet
/// stream's `NOT_READY` gate. The body is checked in the contract's order, and the first failure is a 400
/// `{"error":"invalid mac post: <reason>"}`: `too-large`, `json`, `v`, `postedAt`, `processes`, `too-many`, then, over
/// every entry, `pid`, `start`, `label`, and last `duplicate`. Unknown fields are ignored. An accepted post is stored
/// at temper's own clock (F13); the reply is 200 `{"receivedAt","sessions"}`.
public record MacRoutes(Responses responses,
                        JsonMapper mapper,
                        MacStore store,
                        Supplier<Instant> clock,
                        BooleanSupplier ready) {
    static final int MAX_BODY = 65536;
    static final int MAX_PROCESSES = 512;
    /// A start may lead temper's clock by this much (the Mac's clock may run ahead).
    static final long START_SLACK_S = 300;
    static final long MIN_START = 1_000_000_000L;
    static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    record Received(Instant receivedAt, int sessions) {}

    /// The entries of one post as read: every pid, start and label already checked.
    private record Entry(int pid, long start, String label) {}

    public static MacRoutes macRoutes(Responses responses,
                                      JsonMapper mapper,
                                      MacStore store,
                                      Supplier<Instant> clock,
                                      BooleanSupplier ready) {
        return new MacRoutes(responses, mapper, store, clock, ready);
    }

    public List<Route> routes() {
        return List.of(scopedRoute(HttpMethod.POST,
                                   "fleet/mac",
                                   Route.Scope.MAC,
                                   FleetRoutes.gated(responses, ready, this::post)));
    }

    private Promise<ApiResponse> post(ApiRequest request) {
        return parse(request.body(),
                     clock.get()).mapError(ApiError.BadRequest::badRequest)
                    .async()
                    .flatMap(this::stored);
    }

    private Promise<ApiResponse> stored(NewMacPost post) {
        return store.post(post)
                    .map(at -> responses.ok(new Received(at.truncatedTo(ChronoUnit.SECONDS),
                                                         post.processes().size())));
    }

    // ---- validation -----------------------------------------------------------------------------------------
    Result<NewMacPost> parse(byte[] body, Instant now) {
        return body.length > MAX_BODY
               ? invalid("too-large")
               : mapper.readTree(new String(body, StandardCharsets.UTF_8))
                       .mapError(_ -> reason("json"))
                       .flatMap(root -> fields(root, now));
    }

    private static Result<NewMacPost> fields(JsonNode root, Instant now) {
        return version(root).flatMap(_ -> postedAt(root))
                      .flatMap(posted -> processes(root.path("processes"),
                                                   now).map(list -> NewMacPost.newMacPost(now, posted, list)));
    }

    private static Result<Integer> version(JsonNode root) {
        var node = root.path("v");

        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == 1
               ? Result.success(1)
               : invalid("v");
    }

    private static Result<Instant> postedAt(JsonNode root) {
        var node = root.path("postedAt");

        return node.isString()
               ? Result.lift(reason("postedAt"),
                             () -> Instant.parse(node.stringValue()))
               : invalid("postedAt");
    }

    private static Result<List<MacProcess>> processes(JsonNode node, Instant now) {
        return node.isArray()
               ? entries(StreamSupport.stream(node.spliterator(), false).toList(),
                         now)
               : invalid("processes");
    }

    private static Result<List<MacProcess>> entries(List<JsonNode> items, Instant now) {
        return items.size() > MAX_PROCESSES
               ? invalid("too-many")
               : allOf(items, MacRoutes::pid, "pid").flatMap(_ -> allOf(items,
                                                                        item -> start(item,
                                                                                      now.getEpochSecond() + START_SLACK_S),
                                                                        "start"))
                      .flatMap(_ -> allOf(items, MacRoutes::label, "label"))
                      .flatMap(_ -> distinct(read(items)));
    }

    /// The first entry failing `check`, as the reason `name`; the entries' values are read again once all are checked.
    private static Result<List<JsonNode>> allOf(List<JsonNode> items, Fn1<Boolean, JsonNode> check, String name) {
        return items.stream()
                    .allMatch(check::apply)
               ? Result.success(items)
               : invalid(name);
    }

    private static boolean pid(JsonNode item) {
        var node = item.path("pid");

        return node.isIntegralNumber()
               && node.canConvertToInt()
               && node.intValue() >= 1;
    }

    private static boolean start(JsonNode item, long latest) {
        var node = item.path("start");

        return node.isIntegralNumber()
               && node.canConvertToLong()
               && node.longValue() >= MIN_START
               && node.longValue() <= latest;
    }

    private static boolean label(JsonNode item) {
        var node = item.path("label");

        return node.isString() && LABEL.matcher(node.stringValue()).matches();
    }

    private static List<Entry> read(List<JsonNode> items) {
        return items.stream()
                    .map(item -> new Entry(item.path("pid").intValue(),
                                           item.path("start").longValue(),
                                           item.path("label").stringValue()))
                    .toList();
    }

    private static Result<List<MacProcess>> distinct(List<Entry> entries) {
        var seen = new HashSet<String>();

        return entries.stream()
                      .allMatch(entry -> seen.add(entry.pid() + ":" + entry.start()))
               ? Result.success(entries.stream()
                                       .map(entry -> MacProcess.macProcess(entry.pid(),
                                                                           entry.start(),
                                                                           entry.label()))
                                       .toList())
               : invalid("duplicate");
    }

    private static Cause reason(String name) {
        return cause("invalid mac post: " + name);
    }

    private static <T> Result<T> invalid(String name) {
        return reason(name).result();
    }
}
