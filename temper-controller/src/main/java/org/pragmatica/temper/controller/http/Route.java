package org.pragmatica.temper.controller.http;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One route: a method, a path pattern below `/api/v1` (`jobs/{id}/result`; `{name}` binds one segment), the scope
/// a token needs to call it ([Access]), whether calling it changes what the controller keeps ([Effect]) and the handler.
public record Route(HttpMethod method,
                    List<String> pattern,
                    Scope scope,
                    Effect effect,
                    Fn1<Promise<ApiResponse>, ApiRequest> handler) {
    /// What a route may do ([Access#permits]). Only the full token calls a WRITE route; the read token calls READ
    /// routes only. WORKER is `claim` (the worker token or the full one, ruling R-M3-15); LEASE a lease verb (the lease
    /// token only); LEASE_OR_WRITE a route both may call, telling them apart by the `Authorization` scheme (`park`, and
    /// `POST jobs`, whose `task` link needs the lease). VERDICT is a merge-gate edit (a verdict, a GO's consume and
    /// outcome, the queue and the control paths) and MAC the Mac liveness post: each only its own
    /// token calls, the full token included in the refusal.
    public enum Scope {
        READ,
        WRITE,
        WORKER,
        LEASE,
        LEASE_OR_WRITE,
        VERDICT,
        MAC
    }

    /// What calling a route does to the controller's state, declared where the route is registered (D-6): the
    /// [VersionGate] refuses a skewed CLI on every WRITES route before it acts. A READ route ([#readRoute]) READS; every
    /// other route WRITES, a GET included (`subscribers/{name}/next` creates its subscriber and skip-acks its cursor).
    public enum Effect {
        READS,
        WRITES
    }

    public Route {
        pattern = List.copyOf(pattern);
    }

    /// A WRITE route that WRITES. WRITE is the default so that a route nobody classified stays out of the read token's
    /// reach, and WRITES so that it stays behind the version gate.
    public static Route route(HttpMethod method, String pattern, Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return new Route(method,
                         List.of(pattern.split("/")),
                         Scope.WRITE,
                         Effect.WRITES,
                         handler);
    }

    /// A route of `scope` other than READ (m3-design §11.1); it WRITES.
    public static Route scopedRoute(HttpMethod method,
                                    String pattern,
                                    Scope scope,
                                    Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return new Route(method,
                         List.of(pattern.split("/")),
                         scope,
                         Effect.WRITES,
                         handler);
    }

    /// A route of `scope` whose [Effect] the registration states, for a route whose method does not say it (a GET that
    /// WRITES).
    public static Route scopedRoute(HttpMethod method,
                                    String pattern,
                                    Scope scope,
                                    Effect effect,
                                    Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return new Route(method,
                         List.of(pattern.split("/")),
                         scope,
                         effect,
                         handler);
    }

    /// A READ route: always a GET, and its handler must change nothing the controller keeps; it READS.
    public static Route readRoute(String pattern, Fn1<Promise<ApiResponse>, ApiRequest> handler) {
        return new Route(HttpMethod.GET,
                         List.of(pattern.split("/")),
                         Scope.READ,
                         Effect.READS,
                         handler);
    }

    /// The bound path parameters when the request matches this route.
    public Option<Map<String, String>> match(ApiRequest request) {
        return request.method() == method && request.segments()
                                                    .size() == pattern.size()
               ? bind(request.segments())
               : none();
    }

    private Option<Map<String, String>> bind(List<String> segments) {
        return IntStream.range(0,
                               pattern.size())
                        .allMatch(index -> isParameter(pattern.get(index)) || pattern.get(index)
                                                                                     .equals(segments.get(index)))
               ? some(parameters(segments))
               : none();
    }

    private Map<String, String> parameters(List<String> segments) {
        return IntStream.range(0,
                               pattern.size())
                        .filter(index -> isParameter(pattern.get(index)))
                        .boxed()
                        .collect(Collectors.toUnmodifiableMap(index -> name(pattern.get(index)),
                                                              segments::get));
    }

    private static boolean isParameter(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    private static String name(String segment) {
        return segment.substring(1, segment.length() - 1);
    }
}
