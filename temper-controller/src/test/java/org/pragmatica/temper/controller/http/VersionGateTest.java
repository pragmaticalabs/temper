package org.pragmatica.temper.controller.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Promise;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// D-6, M1: the controller's gate in front of every route registered as WRITES; the transition rules' log warning
/// pinned (G1/G2); a present header that is no version refused.
class VersionGateTest {
    private static final String OWN = "0.1.0-SNAPSHOT+111111111111";
    private static final String OTHER = "0.1.0-SNAPSHOT+222222222222";
    private static final String EXPIRE = "refusals/R-9/expire";
    private static final String NEXT = "subscribers/cto/next";
    private static final List<HttpMethod> WRITE_METHODS = List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH);

    private final VersionGate gate = VersionGate.versionGate(OWN, routes());

    private static List<Route> routes() {
        var routes = new ArrayList<Route>();
        WRITE_METHODS.forEach(method -> routes.add(Route.route(method, "refusals/{code}/expire", VersionGateTest::handler)));
        routes.add(Route.scopedRoute(HttpMethod.GET, "subscribers/{name}/next", Route.Scope.WRITE, Route.Effect.WRITES, VersionGateTest::handler));
        routes.add(Route.readRoute("refusals", VersionGateTest::handler));
        return routes;
    }

    private static Promise<ApiResponse> handler(ApiRequest request) {
        return Promise.success(Responses.noContent());
    }

    private static ApiRequest request(HttpMethod method, String path, Map<String, String> headers) {
        return ApiRequest.apiRequest(method,
                                     List.of(path.split("/")),
                                     Map.of(),
                                     QueryParams.empty(),
                                     Headers.fromSingleValueMap(headers),
                                     new byte[0]);
    }

    private void refusedAsSkew(HttpMethod method, String path) {
        gate.admitted(request(method, path, Map.of("Temper-Version", OTHER)))
            .onSuccess(admitted -> fail(method + " " + path + " admitted: " + admitted))
            .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(ApiError.VersionSkew.class, skew -> {
                           assertThat(skew.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
                           assertThat(skew.message()).contains(OTHER, OWN);
                       }));
    }

    @Test
    void admitted_skewedWrite_isVersionSkew412_namingBothVersions_forEveryMutatingMethod() {
        WRITE_METHODS.forEach(method -> refusedAsSkew(method, EXPIRE));
    }

    /// A GET registered as WRITES (`subscribers/{name}/next` creates and skip-acks its subscriber) is gated too.
    @Test
    void admitted_skewedGetOfAWritesRoute_isVersionSkew412() {
        refusedAsSkew(HttpMethod.GET, NEXT);
    }

    @Test
    void admitted_sameBuild_isAdmitted_headerNameCaseIgnored() {
        assertThat(gate.admitted(request(HttpMethod.POST, EXPIRE, Map.of("temper-version", OWN)))
                       .isSuccess()).isTrue();
    }

    /// Reads keep the reply-side check: a skewed GET of a READ route passes the gate.
    @Test
    void admitted_skewedReadOfAReadRoute_isAdmitted() {
        assertThat(gate.admitted(request(HttpMethod.GET, "refusals", Map.of("Temper-Version", OTHER)))
                       .isSuccess()).isTrue();
    }

    /// A request no route matches reaches no handler (the router answers 401 or 404).
    @Test
    void admitted_skewedRequestOfNoRoute_isAdmitted() {
        assertThat(gate.admitted(request(HttpMethod.POST, "no/such/route", Map.of("Temper-Version", OTHER)))
                       .isSuccess()).isTrue();
    }

    @Test
    void admitted_unjudgedCli_isAdmitted() {
        assertThat(gate.admitted(request(HttpMethod.POST, EXPIRE, Map.of("Temper-Version", "0.1.0-SNAPSHOT+unjudged")))
                       .isSuccess()).isTrue();
    }

    /// A header that is present but no version is refused with 412 naming it, never read as an old CLI (PREDATES).
    @Test
    void admitted_malformedHeader_isMalformedVersion412_namingTheValue() {
        for (var value : List.of("", "   ", "0".repeat(41), "0.1.0+abc def", "0.1.0;rm", "0.1.0+ü", "0.1.0_x")) {
            gate.admitted(request(HttpMethod.POST, EXPIRE, Map.of("Temper-Version", value)))
                .onSuccess(admitted -> fail("'" + value + "' admitted: " + admitted))
                .onFailure(cause -> assertThat(cause).as("'" + value + "'")
                                                     .isInstanceOfSatisfying(ApiError.MalformedVersion.class, malformed -> {
                                                         assertThat(malformed.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
                                                         assertThat(malformed.message()).contains("'" + value + "'", "is not a build version");
                                                     }));
        }
    }

    /// The longest well-formed value (40 characters) is a version, compared as usual.
    @Test
    void admitted_fortyCharacterVersion_isCompared_notMalformed() {
        var forty = "0.1.0-SNAPSHOT+" + "a".repeat(25);

        gate.admitted(request(HttpMethod.POST, EXPIRE, Map.of("Temper-Version", forty)))
            .onSuccess(admitted -> fail("admitted: " + admitted))
            .onFailure(cause -> assertThat(cause).isInstanceOf(ApiError.VersionSkew.class));
    }

    /// G1, transition rule: a CLI without the header is admitted with exactly the ruled warning. The flip to a
    /// refusal (the deploy runbook end condition) changes this test.
    @Test
    void admitted_writeWithoutHeader_isAdmitted_withThePredatesWarning() {
        var warnings = logged(() -> assertThat(gate.admitted(request(HttpMethod.POST, EXPIRE, Map.of()))
                                                   .isSuccess()).isTrue());

        assertThat(warnings).containsExactly("POST /api/v1/refusals/R-9/expire: CLI predates build identity; skew not checked");
    }

    /// G2, transition rule: a CLI version without `+` is admitted with the same warning.
    @Test
    void admitted_plusLessCliVersion_isAdmitted_withThePredatesWarning() {
        var warnings = logged(() -> assertThat(gate.admitted(request(HttpMethod.POST, EXPIRE, Map.of("Temper-Version", "0.1.0-SNAPSHOT")))
                                                   .isSuccess()).isTrue());

        assertThat(warnings).containsExactly("POST /api/v1/refusals/R-9/expire: CLI predates build identity; skew not checked");
    }

    private static List<String> logged(Executable action) {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(VersionGate.class);
        appender.start();
        logger.addAppender(appender);
        try {
            action.execute();
        } catch (Throwable thrown) {
            throw new AssertionError(thrown);
        } finally {
            logger.detachAppender(appender);
        }
        var messages = new ArrayList<String>();
        appender.list.forEach(event -> messages.add(event.getFormattedMessage()));
        return messages;
    }
}
