package org.pragmatica.temper.controller.http;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.message.BuildVersion;
import org.pragmatica.temper.protocol.message.Handshake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


/// D-6, M1: the one filter in front of every mutating route. [HttpGlue] passes every request through [#admitted]
/// BEFORE the [Router], so the check precedes authentication and every handler: a refused request changes nothing.
///
/// A request is mutating when it matches a route registered as [Route.Effect#WRITES] (every route but the READ routes,
/// `GET subscribers/{name}/next` included). A `Temper-Version` header that is present but outside
/// [Handshake#VERSION_ALPHABET] is [ApiError.MalformedVersion], 412. Otherwise the header is compared with the
/// controller's own version ([BuildVersion#compare]):
///
/// - SAME: admitted.
/// - SKEWED (both judged and different): [ApiError.VersionSkew], 412, naming both versions.
/// - UNJUDGED (either side `+unjudged`): admitted, with a warning in the log.
/// - PREDATES (no header, or a version without `+`): admitted, with [#PREDATES_WARNING] in the log (G1/G2, transition
///   rules; END CONDITION: when the rollback set no longer holds any build older than D-6, this becomes the 412 refusal;
///   the deploy runbook).
///
/// A request that matches no WRITES route is admitted unchecked: a READ route changes nothing and the CLI checks its
/// answer's header; a request no route matches reaches no handler.
public record VersionGate(String version, List<Route> routes) {
    private static final Logger log = LoggerFactory.getLogger(VersionGate.class);
    /// The mirror of the CLI's transition warning (G1/G2), exactly as the maintainer ruled it.
    public static final String PREDATES_WARNING = "CLI predates build identity; skew not checked";

    public VersionGate {
        routes = List.copyOf(routes);
    }

    public static VersionGate versionGate(String version, List<Route> routes) {
        return new VersionGate(version, routes);
    }

    /// `request`, unless it is a mutating request of a skewed CLI or one whose header is no version.
    public Result<ApiRequest> admitted(ApiRequest request) {
        return writes(request)
               ? checked(request)
               : success(request);
    }

    private boolean writes(ApiRequest request) {
        return routes.stream()
                     .filter(route -> route.effect() == Route.Effect.WRITES)
                     .anyMatch(route -> route.match(request)
                                             .isPresent());
    }

    private Result<ApiRequest> checked(ApiRequest request) {
        return checked(request,
                       request.headers().get(BuildVersion.HEADER));
    }

    /// A present header that is no version is refused; the absent header stays PREDATES (G1).
    private Result<ApiRequest> checked(ApiRequest request, Option<String> cliVersion) {
        return cliVersion.filter(VersionGate::malformed)
                         .map(value -> refused(request,
                                               ApiError.MalformedVersion.FACTORY.apply(value)))
                         .or(() -> compared(request, cliVersion));
    }

    private static boolean malformed(String value) {
        return ! Handshake.VERSION_ALPHABET.matcher(value).matches();
    }

    private Result<ApiRequest> compared(ApiRequest request, Option<String> cliVersion) {
        return switch (BuildVersion.compare(version, cliVersion)) {
            case SAME -> success(request);
            case PREDATES -> success(warned(request, PREDATES_WARNING));
            case UNJUDGED -> success(warned(request,
                                            "CLI version " + cliVersion.or("") + " differs from controller version " + version + " (an unjudged build cannot be compared); skew not checked"));
            case SKEWED -> refused(request,
                                   ApiError.VersionSkew.FACTORY.apply(cliVersion.or(""), version));
        };
    }

    private static ApiRequest warned(ApiRequest request, String warning) {
        log.warn("{} {}: {}", request.method(), request.path(), warning);

        return request;
    }

    private static Result<ApiRequest> refused(ApiRequest request, ApiError refusal) {
        log.warn("{} {} refused: {}", request.method(), request.path(), refusal.message());

        return refusal.result();
    }
}
