package org.pragmatica.kiln.service.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.ghapp.VirtualSend;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// [GitHubHttp] over the JDK `HttpClient` (based on temper's `GitHubPulls`): one exchange per call with a whole-exchange
/// deadline, a body cap, `Authorization: Bearer` only when the [Credential] has a token (none: anonymous, Q-7), and
/// `If-None-Match` when the caller gives an ETag. Every status comes back as an answer; [GitHubReads] judges it. The
/// token travels only in its header: it is in no URI and no failure message, and the client is a lambda with no
/// `toString` of its own. A path is resolved against the API root and must stay on its host, so a `Link` from GitHub
/// cannot send the token anywhere else.
public sealed interface JdkGitHubHttp {
    URI API = URI.create("https://api.github.com");
    Duration TIMEOUT = Duration.ofSeconds(20);
    /// 100 PRs a page, each body up to 65,536 characters, fits; anything larger fails the exchange.
    long MAX_BODY = 16L * 1024 * 1024;
    Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    Cause OFF_HOST = Causes.cause("not one absolute path on the API host");

    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                         .version(HttpClient.Version.HTTP_1_1)
                         .connectTimeout(TIMEOUT)
                         .followRedirects(HttpClient.Redirect.NEVER)
                         .build();
    }

    static GitHubHttp jdkGitHubHttp(HttpClient client, URI api, Option<String> token, Duration timeout) {
        return jdkGitHubHttp(client, api, Credential.of(token), timeout);
    }

    /// Every request asks `credential` for its bearer first: with the App, the current installation token.
    static GitHubHttp jdkGitHubHttp(HttpClient client, URI api, Credential credential, Duration timeout) {
        return new GitHubHttp() {
            @Override
            public Promise<GhAnswer> get(String path, Option<String> etag) {
                return JdkGitHubHttp.get(client, api, credential, timeout, path, etag);
            }

            @Override
            public Promise<GhAnswer> post(String path, String json) {
                return JdkGitHubHttp.post(client, api, credential, timeout, path, json);
            }

            @Override
            public Promise<Unit> ready() {
                return credential.bearer()
                                 .mapToUnit();
            }

            @Override
            public Credential.Mode mode() {
                return credential.mode();
            }
        };
    }

    private static Promise<GhAnswer> get(HttpClient client,
                                         URI api,
                                         Credential credential,
                                         Duration timeout,
                                         String path,
                                         Option<String> etag) {
        return uri(api, path).async()
                  .flatMap(uri -> credential.bearer()
                                            .flatMap(token -> exchange(client,
                                                                       request(uri, token, etag, timeout),
                                                                       timeout)))
                  .map(JdkGitHubHttp::answer);
    }

    /// A GraphQL query (#102): the same credential, deadline, body cap and host rule as a GET; no `If-None-Match`. Only a
    /// read is sent ([GraphQlRead#checked]: the path `/graphql`, no mutation or subscription); anything else is refused
    /// before the credential is asked.
    private static Promise<GhAnswer> post(HttpClient client,
                                          URI api,
                                          Credential credential,
                                          Duration timeout,
                                          String path,
                                          String json) {
        return GraphQlRead.checked(path, json)
                          .flatMap(_ -> uri(api, path))
                          .async()
                          .flatMap(uri -> credential.bearer()
                                                    .flatMap(token -> exchange(client,
                                                                               posted(uri, token, json, timeout),
                                                                               timeout)))
                          .map(JdkGitHubHttp::answer);
    }

    private static HttpRequest posted(URI uri, Option<String> token, String json, Duration timeout) {
        var builder = HttpRequest.newBuilder(uri)
                                 .timeout(timeout)
                                 .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

        builder.header("Accept", "application/vnd.github+json")
               .header("Content-Type", "application/json")
               .header("User-Agent", "kiln");

        return withHeader(builder, "Authorization", token.map(value -> "Bearer " + value)).build();
    }

    /// The API root followed by `path`, which must be one absolute path (`/repos/...`) on the root's host.
    static Result<URI> uri(URI api, String path) {
        return Network.parseURI(api.toString() + path)
                      .mapError(origin -> GitHubReadError.BadPath.FACTORY.apply(path, origin))
                      .filter(GitHubReadError.BadPath.FACTORY.apply(path, OFF_HOST),
                              uri -> path.startsWith("/")
                                     && !path.startsWith("//")
                                     && api.getHost()
                                           .equals(uri.getHost()));
    }

    /// `HttpRequest.timeout` bounds only the headers, so the whole exchange, body included, has its own deadline; on it
    /// the exchange is cancelled.
    private static Promise<HttpResponse<String>> exchange(HttpClient client, HttpRequest request, Duration timeout) {
        return VirtualSend.send(client, request, JdkGitHubHttp::body, timeout).mapError(GitHubReadError.Unreachable.FACTORY);
    }

    /// Read through the [#MAX_BODY] limit; running over it fails the exchange, declared length or not.
    private static HttpResponse.BodySubscriber<String> body(HttpResponse.ResponseInfo info) {
        return HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
                                                     MAX_BODY);
    }

    private static HttpRequest request(URI uri, Option<String> token, Option<String> etag, Duration timeout) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();

        builder.header("Accept", "application/vnd.github+json")
               .header("X-GitHub-Api-Version", "2022-11-28")
               .header("User-Agent", "kiln");

        return withHeader(withHeader(builder, "Authorization", token.map(value -> "Bearer " + value)),
                          "If-None-Match",
                          etag).build();
    }

    private static HttpRequest.Builder withHeader(HttpRequest.Builder builder, String name, Option<String> value) {
        return value.map(present -> builder.header(name, present))
                    .or(builder);
    }

    private static GhAnswer answer(HttpResponse<String> response) {
        var headers = response.headers();

        return GhAnswer.ghAnswer(response.statusCode(),
                                 header(headers, "ETag"),
                                 option(response.body()).or(""),
                                 number(headers, "X-RateLimit-Remaining"),
                                 number(headers, "X-RateLimit-Limit"),
                                 number(headers, "X-RateLimit-Reset").map(Instant::ofEpochSecond),
                                 header(headers, "Link").flatMap(JdkGitHubHttp::next),
                                 header(headers, "Retry-After").map(JdkGitHubHttp::retryAfter));
    }

    private static Option<String> header(HttpHeaders headers, String name) {
        return Option.from(headers.firstValue(name));
    }

    /// FER: a rate header that is not a number reads as absent; the budget then stays as last known.
    private static Option<Integer> number(HttpHeaders headers, String name) {
        return header(headers, name).flatMap(JdkGitHubHttp::integer);
    }

    /// `Retry-After` as seconds, else as an RFC 1123 date. FER: a value that is neither still says a `Retry-After` was
    /// sent, so it degrades to [GhAnswer.RetryAfter.Unreadable], which the budget waits [RateBudget#FLOOR] for.
    static GhAnswer.RetryAfter retryAfter(String raw) {
        return Number.parseLong(raw.strip())
                     .<GhAnswer.RetryAfter> map(seconds -> new GhAnswer.RetryAfter.Delay(Duration.ofSeconds(seconds)))
                     .or(() -> retryAt(raw.strip()));
    }

    private static GhAnswer.RetryAfter retryAt(String raw) {
        return Result.lift(Causes::fromThrowable,
                           () -> Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw)))
                     .<GhAnswer.RetryAfter> map(GhAnswer.RetryAfter.At::new)
                     .or(new GhAnswer.RetryAfter.Unreadable());
    }

    private static Option<Integer> integer(String raw) {
        return Number.parseInt(raw.strip()).option();
    }

    /// The `rel="next"` target as a path and query on the API root: GitHub names an absolute URL; only its path and
    /// query are kept, so a next page is always asked of the configured API host.
    static Option<String> next(String link) {
        var matcher = NEXT.matcher(link);

        return matcher.find()
               ? Network.parseURI(matcher.group(1))
                        .option()
                        .map(JdkGitHubHttp::pathAndQuery)
               : none();
    }

    private static String pathAndQuery(URI uri) {
        var path = uri.getRawPath();

        return option(uri.getRawQuery()).map(query -> path + "?" + query)
                     .or(path);
    }

    record unused() implements JdkGitHubHttp {}
}
