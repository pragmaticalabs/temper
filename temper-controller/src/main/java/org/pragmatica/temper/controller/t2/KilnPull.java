package org.pragmatica.temper.controller.t2;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.fleet.KilnClient;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.some;


/// kiln's `GET /api/v1/pr-fresh?repo=&pr=` without `sha` (R1 only, only `pull` answered), which the
/// queue's `add` reads for the PR's base when the body names none. The endpoint is WP-B's; only `pull.state`
/// and `pull.base` are read, by key, so the rest of the reply is ignored.
public interface KilnPull {
    Duration TIMEOUT = Duration.ofSeconds(35);
    Promise<PullState> pull(String repo, long pr);

    /// kiln answered 404: the repo is not one of its `[[repos]]`.
    enum Failure implements Cause {
        REPO_NOT_CONFIGURED("kiln: repo not configured");
        private final String message;
        Failure(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The PR's state (`open|closed|merged`) and base as kiln's R1 read them.
    record PullState(String state, String base) {
        public static PullState pullState(String state, String base) {
            return new PullState(state, base);
        }

        public boolean open() {
            return state.equals("open");
        }
    }

    /// Over the JDK client, loopback like kiln's other route ([KilnClient]): a 404 is [Failure#REPO_NOT_CONFIGURED];
    /// anything else but a 200 whose `pull` decodes fails the read.
    static KilnPull http(URI base, JsonMapper mapper, Duration timeout) {
        var client = HttpClient.newBuilder().connectTimeout(timeout).build();

        return (repo, pr) -> KilnClient.exchange(client, request(base, repo, pr, timeout), timeout, KilnPull::body).flatMap(body -> mapper.readTree(body)
                                                                                                                                          .flatMap(KilnPull::decoded)
                                                                                                                                          .async());
    }

    private static HttpRequest request(URI base, String repo, long pr, Duration timeout) {
        return HttpRequest.newBuilder(base.resolve("/api/v1/pr-fresh?repo=" + URLEncoder.encode(repo,
                                                                                                StandardCharsets.UTF_8)
                                                  + "&pr=" + pr))
                          .timeout(timeout)
                          .GET()
                          .build();
    }

    private static Promise<String> body(HttpResponse<String> response) {
        return switch (response.statusCode()) {
            case 200 -> Promise.success(response.body());
            case 404 -> Failure.REPO_NOT_CONFIGURED.promise();
            default -> Causes.cause("kiln answered " + response.statusCode()).promise();
        };
    }

    private static Result<PullState> decoded(JsonNode reply) {
        return Option.all(text(reply.path("pull"),
                               "state"),
                          text(reply.path("pull"),
                               "base"))
                     .map(PullState::pullState)
                     .toResult(Causes.cause("kiln pr-fresh: no pull.state or pull.base"));
    }

    private static Option<String> text(JsonNode node, String key) {
        return some(node.path(key)).filter(JsonNode::isString)
                   .map(JsonNode::stringValue);
    }
}
