package org.pragmatica.temper.controller.api;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.controller.fleet.HighWater;
import org.pragmatica.temper.controller.http.Access;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.controller.t2.FreshToken;
import org.pragmatica.temper.controller.t2.GoRoutes;
import org.pragmatica.temper.controller.t2.T2Routes;
import org.pragmatica.temper.controller.t3.MacRoutes;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.MacStore;
import org.pragmatica.temper.store.api.T2Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The VERDICT and MAC scopes (positive controls T.full, T.read, Q.b). The full token gets 403
/// `forbidden: not permitted for this token` on every VERDICT and MAC route; the read token 403 `forbidden: read-only
/// token`; the verdict token passes the VERDICT routes only and the Mac token the MAC route only. The routes are the
/// real [T2Routes] list beside one READ and one WRITE route, over stores no request here may reach (they fail).
class T2ScopesTest {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final String WORKER = "worker-token";
    private static final String VERDICT = "verdict-token";
    private static final String MAC = "mac-token";
    private static final String NOT_PERMITTED = "{\"error\":\"forbidden: not permitted for this token\"}";
    private static final String READ_ONLY = "{\"error\":\"forbidden: read-only token\"}";
    /// The scope table's WP-A1 and WP-A2 rows, pinned: a route registered with another scope fails here.
    private static final Map<String, Route.Scope> SCOPES = Map.ofEntries(Map.entry("POST fleet/verdicts", Route.Scope.VERDICT),
                                                                         Map.entry("GET fleet/merge-queue", Route.Scope.READ),
                                                                         Map.entry("POST fleet/merge-queue", Route.Scope.VERDICT),
                                                                         Map.entry("POST fleet/merge-queue/remove", Route.Scope.VERDICT),
                                                                         Map.entry("POST fleet/merge-queue/move", Route.Scope.VERDICT),
                                                                         Map.entry("GET fleet/ci-control-paths", Route.Scope.READ),
                                                                         Map.entry("POST fleet/ci-control-paths", Route.Scope.VERDICT),
                                                                         Map.entry("POST fleet/ci-control-paths/remove", Route.Scope.VERDICT),
                                                                         Map.entry("POST fleet/mac", Route.Scope.MAC),
                                                                         Map.entry("GET fleet/pr-fresh", Route.Scope.READ),
                                                                         Map.entry("POST fleet/go", Route.Scope.READ),
                                                                         Map.entry("POST fleet/go/{id}/consume", Route.Scope.VERDICT),
                                                                         Map.entry("POST fleet/go/{id}/outcome", Route.Scope.VERDICT));
    private static final Route READ_ROUTE = Route.readRoute("probe/read", _ -> Promise.success(Responses.noContent()));
    private static final Route WRITE_ROUTE = Route.route(HttpMethod.POST,
                                                         "probe/write",
                                                         _ -> Promise.success(Responses.noContent()));

    @TempDir
    Path state;
    private Router router;
    private List<Route> t2;

    /// Every call of a store fails: a request that passes the router reaches at most a handler's 400 or a 500.
    @SuppressWarnings("unchecked")
    private static <T> T failing(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),
                                          new Class<?>[] {type},
                                          (_, method, _) -> method.getReturnType() == Promise.class
                                                            ? Causes.cause("store not reachable here").promise()
                                                            : null);
    }

    @BeforeEach
    void setUp() {
        var responses = Responses.responses(TemperJson.mapper());
        var writer = FleetWriter.fleetWriter(failing(FleetStore.class), HighWater.highWater(state), Wake.wake(), Instant::now);
        t2 = Stream.of(T2Routes.t2Routes(responses, TemperJson.mapper(), failing(T2Store.class), writer, none(), Instant::now, () -> true)
                                   .routes()
                                   .stream(),
                           GoRoutes.goRoutes(responses, TemperJson.mapper(), failing(T2Store.class), writer, none(),
                                             FreshToken.freshToken(new SecureRandom()), Instant::now, () -> true)
                                   .routes()
                                   .stream(),
                           MacRoutes.macRoutes(responses, TemperJson.mapper(), failing(MacStore.class), Instant::now, () -> true)
                                    .routes()
                                    .stream())
                   .flatMap(routes -> routes)
                   .toList();
        router = Router.router(Credentials.credentials(token(FULL), some(token(READ)), some(token(WORKER)), some(token(VERDICT)), some(token(MAC))),
                               Stream.concat(t2.stream(), Stream.of(READ_ROUTE, WRITE_ROUTE)).toList(),
                               responses);
    }

    private static BearerToken token(String raw) {
        return BearerToken.bearerToken(raw).unwrap();
    }

    private ApiResponse call(Router via, String token, Route route) {
        return ApiFixture.call(via, route.method(), "/api/v1/" + String.join("/", route.pattern()), "{}",
                               Map.of("Authorization", "Bearer " + token));
    }

    private ApiResponse call(String token, Route route) {
        return call(router, token, route);
    }

    private static String named(Route route) {
        return route.method() + " " + String.join("/", route.pattern());
    }

    private List<Route> scoped(Route.Scope scope) {
        return t2.stream().filter(route -> route.scope() == scope).toList();
    }

    @Test
    void scopeTable_t2Routes_areThePinnedScopes() {
        assertThat(t2.stream().collect(Collectors.toMap(T2ScopesTest::named, Route::scope))).isEqualTo(SCOPES);
        assertThat(t2).filteredOn(route -> route.method() != HttpMethod.GET)
                      .allSatisfy(route -> assertThat(route.effect()).as(named(route)).isEqualTo(Route.Effect.WRITES));
    }

    /// T.full, Q.b: the full token calls every route but the VERDICT and MAC ones (and the lease verbs).
    @Test
    void tFull_fullToken_isRefused403_onEveryVerdictAndMacRoute() {
        var refused = Stream.concat(scoped(Route.Scope.VERDICT).stream(), scoped(Route.Scope.MAC).stream()).toList();

        assertThat(refused).extracting(T2ScopesTest::named)
                           .contains("POST fleet/verdicts", "POST fleet/merge-queue", "POST fleet/merge-queue/remove",
                                     "POST fleet/merge-queue/move", "POST fleet/mac", "POST fleet/go/{id}/consume",
                                     "POST fleet/go/{id}/outcome");
        assertThat(refused).allSatisfy(route -> {
            var reply = call(FULL, route);
            assertThat(reply.status()).as(named(route)).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(reply.bodyText()).as(named(route)).isEqualTo(NOT_PERMITTED);
        });
        assertThat(call(FULL, READ_ROUTE).status()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(FULL, WRITE_ROUTE).status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /// T.read: the read token on a VERDICT route keeps its own 403 text; the verdict token on a READ route (a T2 one and
    /// another) and on a WRITE route is refused as not permitted.
    @Test
    void tRead_readTokenOnVerdictRoutes_andVerdictTokenOnReadOrWriteRoutes_are403() {
        assertThat(scoped(Route.Scope.VERDICT)).allSatisfy(route -> {
            var reply = call(READ, route);
            assertThat(reply.status()).as(named(route)).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(reply.bodyText()).as(named(route)).isEqualTo(READ_ONLY);
        });
        assertThat(Stream.concat(scoped(Route.Scope.READ).stream(), Stream.of(READ_ROUTE, WRITE_ROUTE)).toList())
            .hasSize(6)
            .allSatisfy(route -> {
                var reply = call(VERDICT, route);
                assertThat(reply.status()).as(named(route)).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(reply.bodyText()).as(named(route)).isEqualTo(NOT_PERMITTED);
            });
    }

    /// The verdict token passes every VERDICT route (the handler answers: here the body `{}` is a 400, and the GO id
    /// `{id}` names no GO, 404) and the Mac token
    /// the MAC route (the body `{}` is a 400); each is refused the other's routes.
    @Test
    void verdictAndMacTokens_passOnlyTheirOwnScope() {
        assertThat(scoped(Route.Scope.VERDICT)).allSatisfy(route -> {
            assertThat(call(VERDICT, route).status()).as(named(route))
                                                     .isEqualTo(named(route).contains("{id}") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST);
            assertThat(call(MAC, route).status()).as(named(route)).isEqualTo(HttpStatus.FORBIDDEN);
        });
        assertThat(scoped(Route.Scope.MAC)).singleElement().satisfies(route -> {
            assertThat(call(MAC, route).status()).as("the body {} is no mac post").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(call(VERDICT, route).status()).isEqualTo(HttpStatus.FORBIDDEN);
        });
        assertThat(call(MAC, READ_ROUTE).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(WORKER, scoped(Route.Scope.VERDICT).getFirst()).status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /// No verdict or Mac token configured: what would be one is an unknown token (401), and every VERDICT and MAC route
    /// refuses every configured caller (403).
    @Test
    void withoutVerdictOrMacToken_everyVerdictAndMacRoute_refusesEveryCaller() {
        var fullOnly = Router.router(Credentials.credentials(token(FULL), some(token(READ)), some(token(WORKER))),
                                     t2,
                                     Responses.responses(TemperJson.mapper()));

        assertThat(Stream.concat(scoped(Route.Scope.VERDICT).stream(), scoped(Route.Scope.MAC).stream()).toList())
            .allSatisfy(route -> {
                assertThat(call(fullOnly, VERDICT, route).status()).as(named(route)).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(call(fullOnly, MAC, route).status()).as(named(route)).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(Stream.of(FULL, READ, WORKER).map(caller -> call(fullOnly, caller, route).status()))
                    .as(named(route))
                    .containsOnly(HttpStatus.FORBIDDEN);
            });
    }

    static Stream<Arguments> everyAccess() {
        return Stream.of(Access.values()).map(Arguments::of);
    }

    /// The whole permits table, pinned: FULL = everything but LEASE, VERDICT and MAC; VERDICT and MAC only
    /// themselves; READ, WORKER, LEASE as before.
    @ParameterizedTest
    @MethodSource("everyAccess")
    void permits_isThePinnedTable(Access access) {
        var expected = switch (access) {
            case FULL -> EnumSet.of(Route.Scope.READ, Route.Scope.WRITE, Route.Scope.WORKER, Route.Scope.LEASE_OR_WRITE);
            case READ -> EnumSet.of(Route.Scope.READ);
            case WORKER -> EnumSet.of(Route.Scope.READ, Route.Scope.WORKER);
            case LEASE -> EnumSet.of(Route.Scope.LEASE, Route.Scope.LEASE_OR_WRITE);
            case VERDICT -> EnumSet.of(Route.Scope.VERDICT);
            case MAC -> EnumSet.of(Route.Scope.MAC);
        };

        assertThat(Stream.of(Route.Scope.values()).filter(access::permits).collect(Collectors.toSet()))
            .isEqualTo(Set.copyOf(expected));
    }

    /// Every bearer token is classified as its own kind (each compared, no short-circuit by order).
    @Test
    void credentials_classifyEachToken_asItsKind() {
        var credentials = Credentials.credentials(token(FULL), some(token(READ)), some(token(WORKER)), some(token(VERDICT)), some(token(MAC)));

        assertThat(Stream.of(FULL, READ, WORKER, VERDICT, MAC, "other")
                         .map(raw -> credentials.classify(Headers.fromSingleValueMap(Map.of("Authorization", "Bearer " + raw))))
                         .toList())
            .containsExactly(some(Access.FULL), some(Access.READ), some(Access.WORKER), some(Access.VERDICT), some(Access.MAC),
                             Option.<Access> none());
    }
}
