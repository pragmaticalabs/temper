package org.pragmatica.temper.cli.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.protocol.message.BuildVersion;


/// Exchanges one [HttpCall] with the controller, bearer token attached (or the call's lease token, m3-design §6.4),
/// and the call's build version as the `Temper-Version` header when it names one (D-6).
/// Any failure to get an answer
/// (connect refused, connect or request timeout, connection dropped) is [CliError.Unreachable], exit 69.
@FunctionalInterface
public interface HttpTransport {
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /// [P] An answer slower than this counts as unreachable: every M0 route is a DB read or a bounded log tail,
    /// except the event long-poll, which the controller holds for about 30 s. Twice that is the dead-link bound for
    /// `watch` (a half-open connection fails here and is retried).
    Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    Promise<HttpReply> exchange(HttpCall call);

    /// HTTP/1.1 only: the controller speaks nothing else, and an `h2c` upgrade attempt is one more moving part.
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                         .version(HttpClient.Version.HTTP_1_1)
                         .connectTimeout(CONNECT_TIMEOUT)
                         .build();
    }

    /// The JDK `HttpClient` transport against `api` (the `…/api/v1` base).
    static HttpTransport jdkHttpTransport(HttpClient client, URI api, String token) {
        return jdkHttpTransport(client, api, token, REQUEST_TIMEOUT);
    }

    /// The same, with an answer slower than `timeout` counted as unreachable (`alarm-check` waits 10 s).
    static HttpTransport jdkHttpTransport(HttpClient client, URI api, String token, Duration timeout) {
        return call -> exchange(client, api, token, timeout, call);
    }

    private static Promise<HttpReply> exchange(HttpClient client,
                                               URI api,
                                               String token,
                                               Duration timeout,
                                               HttpCall call) {
        return target(api, call).async()
                     .flatMap(uri -> bounded(exchanged(client,
                                                       request(uri, token, timeout, call),
                                                       api),
                                             call,
                                             api));
    }

    /// A call that names its own bound gets it over the whole exchange, body included: the JDK's request timeout ends
    /// at the response headers, so a body that stalls after them would otherwise hold the call (an `events` wait past
    /// its 110 s heartbeat bound). The blocked read is abandoned, not interrupted; the process ends or retries.
    private static Promise<HttpReply> bounded(Promise<HttpReply> exchange, HttpCall call, URI api) {
        return call.timeout()
                   .map(bound -> exchange.timeout(TimeSpan.fromDuration(bound))
                                         .mapError(cause -> unreachableOnTimeout(cause, api)))
                   .or(exchange);
    }

    private static Cause unreachableOnTimeout(Cause cause, URI api) {
        return cause instanceof CoreError.Timeout
               ? CliError.Unreachable.FACTORY.apply(api.toString(), cause)
               : cause;
    }

    private static Result<URI> target(URI api, HttpCall call) {
        return Network.parseURI(api + call.path()).mapError(cause -> CliError.BadSetting.FACTORY.apply("request path",
                                                                                                       call.path(),
                                                                                                       cause));
    }

    private static Promise<HttpReply> exchanged(HttpClient client, HttpRequest request, URI api) {
        return Promise.lift(thrown -> CliError.Unreachable.FACTORY.apply(api.toString(),
                                                                         Causes.fromThrowable(thrown)),
                            () -> client.send(request,
                                              BodyHandlers.ofString()))
                      .map(HttpTransport::reply);
    }

    /// The call's own bound wins over the transport's (`events` long-polls).
    private static HttpRequest request(URI uri, String token, Duration timeout, HttpCall call) {
        return versioned(authorized(uri,
                                    authorization(token, call),
                                    call.timeout().or(timeout)),
                         call).method(call.method(),
                                      body(call))
                        .build();
    }

    /// The CLI's build version as the `Temper-Version` header (D-6), when the call names it.
    private static HttpRequest.Builder versioned(HttpRequest.Builder builder, HttpCall call) {
        return call.version()
                   .map(version -> builder.header(BuildVersion.HEADER, version))
                   .or(builder);
    }

    private static String authorization(String token, HttpCall call) {
        return call.lease()
                   .map(lease -> "Lease " + lease)
                   .or(() -> "Bearer " + token);
    }

    private static HttpRequest.Builder authorized(URI uri, String authorization, Duration timeout) {
        return HttpRequest.newBuilder(uri)
                          .timeout(timeout)
                          .header("Authorization", authorization)
                          .header("Content-Type", "application/json");
    }

    private static BodyPublisher body(HttpCall call) {
        return call.json()
                   .map(BodyPublishers::ofString)
                   .or(BodyPublishers::noBody);
    }

    private static HttpReply reply(HttpResponse<String> response) {
        return HttpReply.httpReply(response.statusCode(),
                                   response.body(),
                                   Option.from(response.headers().firstValue(BuildVersion.HEADER)));
    }
}
