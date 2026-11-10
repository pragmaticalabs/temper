package org.pragmatica.kiln.service.http;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// kiln's router (kiln-f1-design §1.2, based on temper's `Router.java:98-103`): no credentials and no version gate, since
/// the server is reachable only on the loopback address (§4, Q-1). An unknown path is 404, a known path with another
/// method 405. A route's failure degrades forward into its status: an [ApiError]'s own, else 500, logged with its
/// message first; the client always gets an answer.
public record Router(List<Route> routes) {
    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public Router {
        routes = List.copyOf(routes);
    }

    public static Router router(List<Route> routes) {
        return new Router(routes);
    }

    public Promise<ApiResponse> handle(ApiRequest request) {
        return matching(request).map(route -> invoke(route, request))
                       .or(() -> Promise.success(unmatched(request)));
    }

    private Option<Route> matching(ApiRequest request) {
        return Option.from(routes.stream().filter(route -> route.matches(request)).findFirst());
    }

    private ApiResponse unmatched(ApiRequest request) {
        return routes.stream()
                     .anyMatch(route -> route.path()
                                             .equals(request.path()))
               ? ApiResponse.failure(405, "method not allowed")
               : ApiResponse.failure(404, "not found");
    }

    /// FER: a failure becomes its status; a synchronous throw is a failure too.
    private static Promise<ApiResponse> invoke(Route route, ApiRequest request) {
        return Result.lift(Causes::fromThrowable,
                           () -> route.handler()
                                      .apply(request))
                     .async()
                     .flatMap(answer -> answer)
                     .recover(cause -> failure(request, cause));
    }

    /// A refused request is an [ApiError], alone or gathered with others (`Result.all` composes the failures of several
    /// parameters into one cause, whose message lists them all).
    static ApiResponse failure(ApiRequest request, Cause cause) {
        return refusal(cause).map(refused -> ApiResponse.failure(refused.status(),
                                                                 cause.message()))
                      .or(() -> serverSide(request, cause));
    }

    private static Option<ApiError> refusal(Cause cause) {
        return Option.from(cause.stream().filter(ApiError.class::isInstance).map(ApiError.class::cast).findFirst());
    }

    private static ApiResponse serverSide(ApiRequest request, Cause cause) {
        logged(request, cause);

        return ApiResponse.failure(500, "kiln could not answer: " + cause.message());
    }

    private static Unit logged(ApiRequest request, Cause cause) {
        log.warn("{} {} failed: {}", request.method(), request.path(), cause.message());

        return unit();
    }
}
