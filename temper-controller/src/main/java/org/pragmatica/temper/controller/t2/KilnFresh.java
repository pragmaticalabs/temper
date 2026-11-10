package org.pragmatica.temper.controller.t2;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.fleet.KilnClient;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.some;


/// kiln's `GET /api/v1/pr-fresh?repo=&pr=&sha=`, which `GET fleet/pr-fresh` proxies, with
/// the 35 s timeout. A sibling of [KilnClient] over the same base URI; the reply is handed on as JSON, read by key.
///
/// - 404: [KilnPull.Failure#REPO_NOT_CONFIGURED].
/// - Anything else fails the read; [#detail] is the G7 reason's tail (`source-unavailable kiln <detail>`): kiln's own
///   `detail` for its 503, `status <n>` for another status or a 200 that is not a JSON object ([Unavailable]),
///   `unreachable` when no answer came (connection refused, timeout; [Unreachable]), `bad-payload` for an
///   undecodable 200 ([BadPayload]).
public interface KilnFresh {
    Promise<JsonNode> fresh(String repo, long pr, Option<String> sha);

    /// kiln answered, but not usefully; `detail` names how, on one line.
    record Unavailable(String detail, String message) implements Cause {
        public static final Fn1<Unavailable, String> FACTORY = Causes.forOneValue("kiln pr-fresh unavailable: %s",
                                                                                  Unavailable::new);
    }

    /// No answer came from kiln.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreachable(Cause origin, String message) implements Cause, Cause.Wrapped {
        public static final Fn1<Unreachable, Cause> FACTORY = Causes.forOneValue("kiln pr-fresh unreachable",
                                                                                 Unreachable::new);
    }

    /// kiln's 200 is not JSON.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadPayload(Cause origin, String message) implements Cause, Cause.Wrapped {
        public static final Fn1<BadPayload, Cause> FACTORY = Causes.forOneValue("kiln pr-fresh: undecodable reply",
                                                                                BadPayload::new);
    }

    /// A reason value never contains a control character (never a newline).
    Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]+");

    static KilnFresh http(URI base, JsonMapper mapper, Duration timeout) {
        var client = HttpClient.newBuilder().connectTimeout(timeout).build();

        return (repo, pr, sha) -> KilnClient.exchange(client,
                                                      request(base, repo, pr, sha, timeout),
                                                      timeout,
                                                      response -> body(mapper, response))
                                            .mapError(KilnFresh::unreachable)
                                            .flatMap(body -> decoded(mapper, body));
    }

    private static HttpRequest request(URI base, String repo, long pr, Option<String> sha, Duration timeout) {
        return HttpRequest.newBuilder(base.resolve("/api/v1/pr-fresh?repo=" + URLEncoder.encode(repo,
                                                                                                StandardCharsets.UTF_8)
                                                  + "&pr=" + pr + sha.map(value -> "&sha=" + value)
                                                                     .or("")))
                          .timeout(timeout)
                          .GET()
                          .build();
    }

    private static Promise<String> body(JsonMapper mapper, HttpResponse<String> response) {
        return switch (response.statusCode()) {
            case 200 -> Promise.success(response.body());
            case 404 -> KilnPull.Failure.REPO_NOT_CONFIGURED.promise();
            case 503 -> Unavailable.FACTORY.apply(detail(mapper, response.body())).promise();
            default -> Unavailable.FACTORY.apply("status " + response.statusCode()).promise();
        };
    }

    /// The G7 reason's tail for a failed read (none for [KilnPull.Failure#REPO_NOT_CONFIGURED], which is a 404).
    static String detail(Cause cause) {
        return switch (cause) {
            case Unavailable unavailable -> unavailable.detail();
            case BadPayload _ -> "bad-payload";
            default -> "unreachable";
        };
    }

    /// kiln's 503 `detail` (`github <status> <path>`, `rate-budget`, `deadline`, `decode <what>`), on one line. FER: a
    /// 503 body without one still reads as unavailable, as `status 503`.
    private static String detail(JsonMapper mapper, String body) {
        return mapper.readTree(body)
                     .option()
                     .flatMap(node -> some(node.path("detail")).filter(JsonNode::isString)
                                          .map(JsonNode::stringValue))
                     .map(text -> CONTROL.matcher(text)
                                         .replaceAll(" ")
                                         .strip())
                     .filter(text -> !text.isEmpty())
                     .or("status 503");
    }

    /// A failure that is not one of kiln's answers is a transport failure: no answer came.
    private static Cause unreachable(Cause cause) {
        return switch (cause) {
            case KilnPull.Failure _, Unavailable _ -> cause;
            default -> Unreachable.FACTORY.apply(cause);
        };
    }

    private static Promise<JsonNode> decoded(JsonMapper mapper, String body) {
        return mapper.readTree(body)
                     .mapError(BadPayload.FACTORY)
                     .filter(Unavailable.FACTORY.apply("status 200 not an object"),
                             JsonNode::isObject)
                     .async();
    }
}
