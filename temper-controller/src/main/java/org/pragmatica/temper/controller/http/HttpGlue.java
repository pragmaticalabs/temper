package org.pragmatica.temper.controller.http;

import java.util.function.BiConsumer;

import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.protocol.message.BuildVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The one adapter between rc4 `http-server` and the [Router]: it builds the [ApiRequest] and writes the
/// [ApiResponse] when the route's promise resolves. The `void` handler signature is rc4's callback contract.
///
/// Every request passes the [VersionGate] first, ahead of the router's authentication (D-6, M1): a mutating request of a
/// skewed CLI is answered 412 and reaches no route.
///
/// Every response this adapter writes, the 412, a 401, a 404 and a 500 included, carries the controller's build
/// version in the [BuildVersion#HEADER] header (D-6), which the CLI compares with its own before it reads the body. The
/// header is set on the writer before anything else runs, so rc4's own 500 for a handler that throws carries it too.
/// Transition gap (L3): replies Netty writes or drops itself, outside this adapter, carry none: the aggregator's 413
/// for a body over [org.pragmatica.temper.controller.Controller#MAX_CONTENT_LENGTH], a 400 of a request that does not
/// decode, and rc4's `exceptionCaught`, which closes the connection without a reply; rc4 1.0.0-rc4 exposes no hook for
/// its pipeline. The CLI reads a header-less answer as PREDATES (a warning, the deploy runbook).
@Contract
public record HttpGlue(Router router, VersionGate gate) implements BiConsumer<HttpRequest, ResponseWriter> {
    private static final Logger log = LoggerFactory.getLogger(HttpGlue.class);

    public static HttpGlue httpGlue(Router router, VersionGate gate) {
        return new HttpGlue(router, gate);
    }

    @Override
    public void accept(HttpRequest request, ResponseWriter writer) {
        respond(request,
                writer.header(BuildVersion.HEADER, gate.version()));
    }

    private void respond(HttpRequest request, ResponseWriter writer) {
        // May run late: writing the response is the terminal step; nothing runs after it.
        answer(ApiRequest.apiRequest(request)).onSuccess(response -> write(writer, response))
              .onFailure(cause -> failed(writer, request, cause));
    }

    /// The gate's refusal is answered as is; an admitted request goes to the router.
    private Promise<ApiResponse> answer(ApiRequest request) {
        return gate.admitted(request)
                   .fold(refusal -> Promise.success(router.responses().failure(refusal)),
                         router::handle);
    }

    private static void write(ResponseWriter writer, ApiResponse response) {
        logServerError(response);
        writer.write(response.status(), response.body(), response.contentType());
    }

    private static void logServerError(ApiResponse response) {
        if (response.status().code() >= 500) {
            log.warn("{} {}",
                     response.status().code(),
                     response.bodyText());
        }
    }

    private static void failed(ResponseWriter writer, HttpRequest request, Cause cause) {
        log.error("{} {} failed: {}", request.method(), request.path(), cause.message());
        writer.error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }
}
