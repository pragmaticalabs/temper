package org.pragmatica.temper.controller.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.protocol.digest.Digests;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// Q-1 (maintainer-required): every route answers 401 without the bearer token, including unknown paths. D3: the read token
/// calls the READ routes and gets 403 on every other route; the full token calls every route.
class RouterAuthTest {
    /// The scope table (D3). Pinned so that marking a write route READ by accident fails here.
    private static final Set<String> READ_ROUTES = Set.of("GET jobs",
                                                          "GET jobs/{id}",
                                                          "GET jobs/{id}/result",
                                                          "GET jobs/{id}/testcases",
                                                          "GET jobs/{id}/manifest",
                                                          "GET jobs/{id}/log",
                                                          "GET hosts",
                                                          "GET maintenance",
                                                          "GET refusals",
                                                          "GET digest",
                                                          "GET gate/{sha}",
                                                          "GET diff",
                                                          "GET known");
    private static final String FORBIDDEN_BODY = "{\"error\":\"forbidden: read-only token\"}";

    private final ApiFixture fixture = new ApiFixture(List.of());

    /// Every served route (from the real list, so a new route is covered) and paths no route serves.
    static Stream<Arguments> everyRoute() {
        return Stream.concat(allRoutes().map(route -> Arguments.of(route.method(), path(route))),
                             Stream.of(Arguments.of(HttpMethod.GET, "/api/v1/no-such-route"),
                                       Arguments.of(HttpMethod.GET, "/"),
                                       Arguments.of(HttpMethod.GET, "/api/v1/"),
                                       Arguments.of(HttpMethod.POST, "/"),
                                       Arguments.of(HttpMethod.GET, "/elsewhere")));
    }

    @ParameterizedTest
    @MethodSource("everyRoute")
    void route_answers401_withoutToken(HttpMethod method, String path) {
        var response = fixture.call(method, path, "{}", Map.of());

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.bodyText()).isEqualTo("{\"error\":\"unauthorized\"}");
    }

    @ParameterizedTest
    @MethodSource("everyRoute")
    void route_answers401_wrongToken(HttpMethod method, String path) {
        var response = fixture.call(method, path, "{}", Map.of("Authorization", "Bearer s3cret-tokeN"));

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void route_answers401_nonBearerScheme() {
        var response = fixture.call(HttpMethod.GET, "/api/v1/hosts", "", Map.of("Authorization", "Basic " + ApiFixture.TOKEN));

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /// The scheme is case-insensitive (RFC 7235): `bearer` is accepted, for either token.
    @Test
    void route_acceptsLowerCaseScheme() {
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/hosts", "", Map.of("Authorization", "bearer " + ApiFixture.TOKEN))
                          .status()).isEqualTo(HttpStatus.OK);
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/hosts", "", Map.of("Authorization", "bearer " + ApiFixture.READ_TOKEN))
                          .status()).isEqualTo(HttpStatus.OK);
    }

    /// Whitespace around the token is not part of it.
    @Test
    void route_acceptsWhitespaceAroundTheToken() {
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/hosts", "", Map.of("Authorization", "Bearer   " + ApiFixture.TOKEN + "  \t"))
                          .status()).isEqualTo(HttpStatus.OK);
        assertThat(fixture.call(HttpMethod.GET, "/api/v1/hosts", "", Map.of("Authorization", "Bearer \t" + ApiFixture.READ_TOKEN + " "))
                          .status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void route_answers200_withToken() {
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/hosts", "").status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownRoute_answers404_withToken() {
        assertThat(fixture.authed(HttpMethod.GET, "/api/v1/no-such-route", "").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- D3: the read token ------------------------------------------------------------------------------------
    /// The real route list, so a newly added route is covered without editing this test.
    private static Stream<Route> allRoutes() {
        return new ApiFixture(List.of()).routes.stream();
    }

    private static Stream<Route> readRoutes() {
        return allRoutes().filter(route -> route.scope() == Route.Scope.READ);
    }

    private static Stream<Route> writeRoutes() {
        return allRoutes().filter(route -> route.scope() == Route.Scope.WRITE);
    }

    static Stream<Arguments> everyReadRoute() {
        return readRoutes().map(route -> Arguments.of(named(route), route));
    }

    static Stream<Arguments> everyWriteRoute() {
        return writeRoutes().map(route -> Arguments.of(named(route), route));
    }

    static Stream<Arguments> everyServedRoute() {
        return allRoutes().map(route -> Arguments.of(named(route), route));
    }

    private static String named(Route route) {
        return route.method() + " " + String.join("/", route.pattern());
    }

    /// A concrete path for the route's pattern: every `{parameter}` is bound to `1`.
    private static String path(Route route) {
        return "/api/v1/" + route.pattern()
                                 .stream()
                                 .map(segment -> segment.startsWith("{")
                                                 ? "1"
                                                 : segment)
                                 .collect(Collectors.joining("/"));
    }

    private static void assertPassedTheRouter(Route route, ApiResponse response) {
        assertThat(response.status()).as(named(route))
                                     .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void scopeTable_readRoutes_areExactlyThePinnedGets() {
        assertThat(readRoutes().map(RouterAuthTest::named)
                               .collect(Collectors.toSet())).isEqualTo(READ_ROUTES);
        assertThat(readRoutes()).allSatisfy(route -> assertThat(route.method()).isEqualTo(HttpMethod.GET));
        assertThat(writeRoutes()).as("every route is either READ or WRITE, and there are write routes")
                                 .isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyReadRoute")
    void readToken_passes_everyReadRoute(String name, Route route) {
        assertPassedTheRouter(route, fixture.readAuthed(route.method(), path(route), "{}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWriteRoute")
    void readToken_answers403_onEveryWriteRoute(String name, Route route) {
        var response = fixture.readAuthed(route.method(), path(route), "{}");

        assertThat(response.status()).as(named(route))
                                     .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.bodyText()).isEqualTo(FORBIDDEN_BODY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyServedRoute")
    void fullToken_passes_everyRoute(String name, Route route) {
        assertPassedTheRouter(route, fixture.authed(route.method(), path(route), "{}"));
    }

    /// A GET, but WRITE: `next` creates the subscriber it names. The read token is refused before the handler runs,
    /// so no subscriber appears; the full token, the control, creates it.
    @Test
    void readToken_answers403_onSubscriberNext_andCreatesNoSubscriber() {
        var response = fixture.readAuthed(HttpMethod.GET, "/api/v1/subscribers/reader/next", "");

        assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixture.events.subscribers).doesNotContainKey("reader");

        fixture.authed(HttpMethod.GET, "/api/v1/subscribers/writer/next", "");
        assertThat(fixture.events.subscribers).containsKey("writer");
    }

    @Test
    void readToken_answers404_onUnknownPath() {
        var response = fixture.readAuthed(HttpMethod.GET, "/api/v1/no-such-route", "");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /// Without `readTokenFile` there is no read token: what would be one is just a wrong token.
    @Test
    void readToken_answers401_whenNoReadTokenIsConfigured() {
        Router fullOnly = fixture.router(Option.none());

        assertThat(ApiFixture.call(fullOnly,
                                   HttpMethod.GET,
                                   "/api/v1/hosts",
                                   "",
                                   Map.of("Authorization", "Bearer " + ApiFixture.READ_TOKEN))
                             .status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ApiFixture.call(fullOnly,
                                   HttpMethod.GET,
                                   "/api/v1/hosts",
                                   "",
                                   Map.of("Authorization", "Bearer " + ApiFixture.TOKEN))
                             .status()).isEqualTo(HttpStatus.OK);
    }

    /// The read token's requests (the 403s among them) are logged at INFO with the token's kind, never the token or
    /// its digest; the full token's at DEBUG only.
    @Test
    void accessLog_namesTheTokenKind_neverTheToken() {
        var logged = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(Router.class);
        logged.start();
        logger.addAppender(logged);
        logger.setLevel(Level.DEBUG);
        try {
            fixture.readAuthed(HttpMethod.GET, "/api/v1/hosts", "");
            fixture.readAuthed(HttpMethod.POST, "/api/v1/jobs/1/cancel", "{}");
            fixture.authed(HttpMethod.GET, "/api/v1/hosts", "");
        } finally {
            logger.detachAppender(logged);
            logger.setLevel(null);
        }
        var readDigest = Digests.sha256Hex(ApiFixture.READ_TOKEN).unwrap();
        var fullDigest = Digests.sha256Hex(ApiFixture.TOKEN).unwrap();

        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.INFO)
                               .extracting(ILoggingEvent::getFormattedMessage)
                               .containsExactly("READ token: GET /api/v1/hosts -> 200",
                                                "READ token: POST /api/v1/jobs/1/cancel -> 403");
        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
                               .extracting(ILoggingEvent::getFormattedMessage)
                               .containsExactly("FULL token: GET /api/v1/hosts -> 200");
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                               .noneMatch(line -> line.contains(ApiFixture.READ_TOKEN) || line.contains(ApiFixture.TOKEN)
                                                  || line.contains(readDigest) || line.contains(fullDigest));
    }
}
