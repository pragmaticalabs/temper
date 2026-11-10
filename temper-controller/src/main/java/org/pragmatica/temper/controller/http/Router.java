package org.pragmatica.temper.controller.http;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Tuple;
import org.pragmatica.lang.Tuple.Tuple2;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.flow.CauseChain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import static org.pragmatica.lang.Unit.unit;


/// temper's own router (m0-design §6.2). The bearer token is checked BEFORE route matching, so every route, and
/// every unknown path, answers 401 without a valid one (Q-1). An authenticated request to an unknown path is 404; the
/// read token on a WRITE route is 403 and its handler never runs (D3). A matched route's failure becomes its
/// `ErrorResponse`.
///
/// Every authenticated request is logged with the kind of token, never the token: at INFO for the read, worker, verdict
/// and Mac tokens (which covers their 403s; a verdict or a merge is rare and worth the line), at DEBUG for the full token, whose clients (the T1 harness polls `jobs` for hours) would
/// flood the journal at INFO, and for a lease token, whose heartbeats would as well.
public record Router(Credentials credentials, List<Route> routes, Responses responses) {
    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public Router {
        routes = List.copyOf(routes);
    }

    public static Router router(Credentials credentials, List<Route> routes, Responses responses) {
        return new Router(credentials, routes, responses);
    }

    public Promise<ApiResponse> handle(ApiRequest request) {
        return credentials.classify(request.headers())
                          .map(access -> dispatch(access, request))
                          .or(this::unauthorized);
    }

    private Promise<ApiResponse> unauthorized() {
        return Promise.success(responses.failure(ApiError.General.UNAUTHORIZED));
    }

    /// `withSuccess`, not `onSuccess`: the line is written before the response is handed on, in chain order.
    private Promise<ApiResponse> dispatch(Access access, ApiRequest request) {
        return matching(request).map(found -> permitted(access,
                                                        found.first(),
                                                        request.withParams(found.last())))
                       .or(this::notFound)
                       .withSuccess(response -> logAccess(access, request, response));
    }

    private Promise<ApiResponse> permitted(Access access, Route route, ApiRequest request) {
        return access.permits(route.scope())
               ? invoke(route, request)
               : Promise.success(responses.failure(forbidden(access)));
    }

    /// The read token keeps its own text (D3); any other credential is refused as not permitted.
    private static ApiError forbidden(Access access) {
        return access == Access.READ
               ? ApiError.General.FORBIDDEN
               : ApiError.General.NOT_PERMITTED;
    }

    private static Unit logAccess(Access access, ApiRequest request, ApiResponse response) {
        log.atLevel(levelOf(access))
           .log("{} token: {} {} -> {}",
                access,
                request.method(),
                request.path(),
                response.status().code());

        return unit();
    }

    private static Level levelOf(Access access) {
        return switch (access) {
            case READ, WORKER, VERDICT, MAC -> Level.INFO;
            case FULL, LEASE -> Level.DEBUG;
        };
    }

    private Promise<ApiResponse> notFound() {
        return Promise.success(responses.failure(ApiError.General.NOT_FOUND));
    }

    /// FER: a route's failure degrades forward into its HTTP error response (an [ApiError]'s status, else 500); the
    /// client always gets an answer. A failure answered with 5xx is logged with its whole cause chain first, since
    /// the response shows only the top message.
    private Promise<ApiResponse> invoke(Route route, ApiRequest request) {
        return route.handler()
                    .apply(request)
                    .onFailure(Router::logServerSide)
                    .recover(responses::failure);
    }

    private static Unit logServerSide(Cause cause) {
        return Responses.status(cause).code() >= 500
               ? logged(cause)
               : unit();
    }

    private static Unit logged(Cause cause) {
        log.warn("request failed: {}", CauseChain.render(cause));

        return unit();
    }

    private Option<Tuple2<Route, Map<String, String>>> matching(ApiRequest request) {
        return Option.from(routes.stream().flatMap(route -> bound(route, request)).findFirst());
    }

    private static Stream<Tuple2<Route, Map<String, String>>> bound(Route route, ApiRequest request) {
        return route.match(request)
                    .map(params -> Tuple.tuple(route, params))
                    .stream();
    }
}
