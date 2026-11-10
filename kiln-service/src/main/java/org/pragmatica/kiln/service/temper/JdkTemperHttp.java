package org.pragmatica.kiln.service.temper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.core.temper.HostsRead;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.ghapp.VirtualSend;

import static org.pragmatica.lang.Option.option;


/// [TemperHttp] over the JDK `HttpClient` (based on temper-cli's `HttpTransport`): `Authorization: Bearer <read token>`
/// (spec §3, `[temper] readTokenFile`), a whole-exchange deadline and a body cap. A 200 body is the answer; any other
/// status, and any failed exchange, is a [TemperReadError] (`TEMPER-DOWN`). The gate body is decoded only by kiln's
/// own lenient [GateRead#decode], never by temper's classes (spec §4.5). The token is in no URI, no failure message and
/// no [#toString].
public record JdkTemperHttp(HttpClient client, URI api, String token, Duration timeout) implements TemperHttp {
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    public static final long MAX_BODY = 4L * 1024 * 1024;
    static final int OK = 200;

    /// `api` is temper's root (`http://host:port`); the `/api/v1/...` paths are appended to it.
    public static JdkTemperHttp jdkTemperHttp(HttpClient client, URI api, String token, Duration timeout) {
        return new JdkTemperHttp(client, api, token, timeout);
    }

    @Override
    public Promise<String> digestText() {
        return get("/api/v1/digest?format=text&max=10");
    }

    @Override
    public Promise<GateRead> gate(Sha head) {
        return get("/api/v1/gate/" + head.value()).map(JdkTemperHttp::lenient);
    }

    @Override
    public Promise<HostsRead> fleetAlarms() {
        return get("/api/v1/fleet/alarms").map(HostsRead::decode);
    }

    /// kiln's own lenient read (spec §4.5), never temper's classes.
    private static GateRead lenient(String body) {
        return GateRead.decode(body);
    }

    @Override
    public String toString() {
        return "JdkTemperHttp[api=" + api + ", token=<redacted>, timeout=" + timeout + "]";
    }

    private Promise<String> get(String path) {
        return exchange(HttpRequest.newBuilder(api.resolve(root() + path))
                                   .timeout(timeout)
                                   .header("Authorization", "Bearer " + token)
                                   .header("Accept", "application/json, text/plain")
                                   .GET()
                                   .build()).flatMap(JdkTemperHttp::judged);
    }

    private String root() {
        var raw = api.toString();

        return raw.endsWith("/")
               ? raw.substring(0, raw.length() - 1)
               : raw;
    }

    private static Promise<String> judged(HttpResponse<String> response) {
        return response.statusCode() == OK
               ? Promise.success(option(response.body()).or(""))
               : TemperReadError.HttpStatus.FACTORY.apply(response.statusCode()).promise();
    }

    /// `HttpRequest.timeout` bounds only the headers, so the whole exchange, body included, has its own deadline; on it
    /// the exchange is cancelled.
    private Promise<HttpResponse<String>> exchange(HttpRequest request) {
        return VirtualSend.send(client, request, JdkTemperHttp::body, timeout).mapError(TemperReadError.Unreachable.FACTORY);
    }

    private static HttpResponse.BodySubscriber<String> body(HttpResponse.ResponseInfo info) {
        return HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
                                                     MAX_BODY);
    }
}
