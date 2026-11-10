package org.pragmatica.temper.controller.fleet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.ghapp.VirtualSend;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


/// kiln's `GET /api/v1/changes`: loopback, no auth. `epoch` is the one temper holds (none on the
/// first read ever), `after` its cursor; `max` is always 500.
public interface KilnClient {
    int MAX = 500;
    Promise<KilnReply> changes(Option<String> epoch, long after);

    /// Over the JDK client: the whole exchange has `timeout`, and anything but a 200 whose body decodes fails the pull.
    static KilnClient http(URI base, JsonMapper mapper, Duration timeout) {
        var client = HttpClient.newBuilder().connectTimeout(timeout).build();

        return (epoch, after) -> exchange(client, request(base, epoch, after, timeout), timeout).flatMap(body -> mapper.readTree(body)
                                                                                                                       .flatMap(KilnReply::decode)
                                                                                                                       .async());
    }

    private static HttpRequest request(URI base, Option<String> epoch, long after, Duration timeout) {
        return HttpRequest.newBuilder(base.resolve("/api/v1/changes?after=" + after
                                                  + "&max=" + MAX + epoch.map(value -> "&epoch=" + value)
                                                                         .or("")))
                          .timeout(timeout)
                          .GET()
                          .build();
    }

    /// One exchange within `timeout`: the body of a 200, anything else a failure naming the status (kiln's other
    /// routes, the T2 queue's `pr-fresh` read, use it too).
    static Promise<String> exchange(HttpClient client, HttpRequest request, Duration timeout) {
        return exchange(client, request, timeout, KilnClient::body);
    }

    /// The same exchange, the reply judged by `body` (a caller that tells some statuses apart, such as the queue's
    /// `pr-fresh` read, which reads a 404 as a repo kiln does not configure).
    static Promise<String> exchange(HttpClient client,
                                    HttpRequest request,
                                    Duration timeout,
                                    Fn1<Promise<String>, HttpResponse<String>> body) {
        return VirtualSend.send(client,
                                request,
                                HttpResponse.BodyHandlers.ofString(),
                                timeout)
                          .flatMap(body);
    }

    private static Promise<String> body(HttpResponse<String> response) {
        return response.statusCode() == 200
               ? Promise.success(response.body())
               : Causes.cause("kiln answered " + response.statusCode()).promise();
    }
}
