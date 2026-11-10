package org.pragmatica.temper.controller.review;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.GitHubReviewer;
import org.pragmatica.temper.ghapp.GitHubAppAuth;
import org.pragmatica.temper.ghapp.VirtualSend;

import com.fasterxml.jackson.annotation.JsonProperty;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// [ReviewGitHub] over the GitHub REST API. Reads carry the controller App's installation token (the one of
/// [org.pragmatica.temper.ghapp.GitHubAppAuth], narrowed to `pull_requests: read` where it is built, C.5), or no
/// `Authorization` with `[github] anonymous = true`. The post carries the reviewer's PAT, read from its file inside
/// every [#post] call and held in no field: a rotated PAT is in use at the next review (R.10). No request, header or
/// token is logged or put in an error; a failure says a status, or GitHub's own message up to its first line.
///
/// No redirect is followed, so no credential leaves `api`; a `Link` is never fetched (the review list is paged by
/// `page=`).
public record HttpReviewGitHub(HttpClient client,
                               URI api,
                               Duration readTimeout,
                               Duration postTimeout,
                               Option<GitHubAppAuth> app,
                               GitHubReviewer reviewer) implements ReviewGitHub {
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /// §5.3 step 8: the POST has 30 s.
    public static final Duration POST_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_BODY = 1024 * 1024;
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 10;
    private static final JsonMapper JSON = JsonMapper.jsonMapper().withPragmaticaTypes().build();
    private static final int OK = 200;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int UNPROCESSABLE = 422;
    private static final int TOO_MANY = 429;
    private static final long FALLBACK_WAIT_S = 60;

    public static HttpReviewGitHub httpReviewGitHub(URI api, Option<GitHubAppAuth> app, GitHubReviewer reviewer) {
        return httpReviewGitHub(api, READ_TIMEOUT, POST_TIMEOUT, app, reviewer);
    }

    public static HttpReviewGitHub httpReviewGitHub(URI api,
                                                    Duration readTimeout,
                                                    Duration postTimeout,
                                                    Option<GitHubAppAuth> app,
                                                    GitHubReviewer reviewer) {
        return new HttpReviewGitHub(HttpClient.newBuilder()
                                              .connectTimeout(readTimeout)
                                              .followRedirects(HttpClient.Redirect.NEVER)
                                              .build(),
                                    api,
                                    readTimeout,
                                    postTimeout,
                                    app,
                                    reviewer);
    }

    @Override
    public String reviewerLogin() {
        return reviewer.login();
    }

    // ---- reads (controller App) ----------------------------------------------------------------------------------
    @Override
    public Promise<PullView> pull(String repo, long pr) {
        return read(repo, "pulls/" + pr).flatMap(body -> JSON.readString(body, PullDto.class)
                                                             .mapError(_ -> ReviewError.ReadFailed.readFailed("unreadable pull request"))
                                                             .flatMap(PullDto::view)
                                                             .async());
    }

    @Override
    public Promise<List<ReviewView>> reviews(String repo, long pr) {
        return page(repo, pr, 1, List.of());
    }

    private Promise<List<ReviewView>> page(String repo, long pr, int number, List<ReviewView> seen) {
        return read(repo, "pulls/" + pr + "/reviews?per_page=" + PAGE + "&page=" + number).flatMap(body -> JSON.readString(body,
                                                                                                                           ReviewDto[].class)
                                                                                                               .mapError(_ -> ReviewError.ReadFailed.readFailed("unreadable review list"))
                                                                                                               .async())
                   .flatMap(dtos -> dtos.length == PAGE && number < MAX_PAGES
                                    ? page(repo,
                                           pr,
                                           number + 1,
                                           joined(seen, dtos))
                                    : Promise.success(joined(seen, dtos)));
    }

    private static List<ReviewView> joined(List<ReviewView> seen, ReviewDto[] dtos) {
        return Stream.concat(seen.stream(),
                             Stream.of(dtos).map(ReviewDto::view))
                     .toList();
    }

    private Promise<String> read(String repo, String path) {
        return exchange(readTimeout,
                        builder -> builder.uri(url(repo, path))
                                          .GET(),
                        authorization()).mapError(cause -> ReviewError.ReadFailed.readFailed("GitHub read failed: " + cause.message()))
                       .flatMap(reply -> reply.status() == OK
                                         ? Promise.success(reply.body())
                                         : ReviewError.ReadFailed.readFailed("GitHub read answered " + reply.status()).<String> promise());
    }

    /// The App's token, or none (anonymous); a token that cannot be had fails before anything is sent.
    private Promise<Option<String>> authorization() {
        return app.map(auth -> auth.token()
                                   .map(token -> some("Bearer " + token.value())))
                  .or(() -> Promise.success(none()));
    }

    // ---- the post (reviewer) ----------------------------------------------------------------------------------------
    @Override
    public Promise<Posted> post(String repo, long pr, String sha, String event, String body) {
        return pat().async()
                  .flatMap(token -> posted(repo, pr, sha, event, body, token));
    }

    /// The PAT, freshly read; never kept.
    private Result<String> pat() {
        return Result.lift(_ -> ReviewError.Credential.credential("reviewer token file unreadable"),
                           () -> Files.readString(reviewer.tokenFile(),
                                                  StandardCharsets.UTF_8)
                                      .strip())
                     .flatMap(token -> token.isEmpty()
                                       ? ReviewError.Credential.credential("reviewer token file is empty").<String> result()
                                       : Result.success(token));
    }

    private Promise<Posted> posted(String repo, long pr, String sha, String event, String body, String token) {
        return JSON.writeAsString(Map.of("commit_id", sha, "event", event, "body", body))
                   .mapError(cause -> ReviewError.Unknown.unknown("review body not encodable"))
                   .async()
                   .flatMap(json -> exchange(postTimeout,
                                             builder -> builder.uri(url(repo, "pulls/" + pr + "/reviews"))
                                                               .header("Content-Type", "application/json")
                                                               .POST(HttpRequest.BodyPublishers.ofString(json)),
                                             Promise.success(some("Bearer " + token))).mapError(cause -> ReviewError.Unknown.unknown("review post: " + cause.message())))
                   .flatMap(HttpReviewGitHub::answered);
    }

    private static Promise<Posted> answered(Reply reply) {
        return switch (reply.status()) {
            case OK -> JSON.readString(reply.body(), IdDto.class).mapError(_ -> ReviewError.Unknown.unknown("review reply unreadable")).map(dto -> new Posted(dto.id(),
                                                                                                                                                              reply.remaining())).async();
            case UNPROCESSABLE -> ReviewError.Rejected.rejected(firstLine(reply)).promise();
            case UNAUTHORIZED, FORBIDDEN, TOO_MANY -> limited(reply)
                                                      ? ReviewError.RateLimited.rateLimited(until(reply)).promise()
                                                      : ReviewError.Credential.credential("GitHub answered " + reply.status()).promise();
            default -> ReviewError.Unknown.unknown("GitHub answered " + reply.status()).promise();
        };
    }

    /// 429; or 403 with no calls left, a `retry-after`, or a rate-limit message (the secondary limit).
    private static boolean limited(Reply reply) {
        return reply.status() == TOO_MANY || (reply.status() == FORBIDDEN && (reply.remaining()
                                                                                   .filter(left -> left == 0)
                                                                                   .isPresent() || reply.header("retry-after")
                                                                                                        .isPresent() || firstLine(reply).toLowerCase()
                                                                                                                                 .contains("rate limit")));
    }

    private static Instant until(Reply reply) {
        return reply.header("retry-after")
                    .flatMap(HttpReviewGitHub::seconds)
                    .map(wait -> Instant.now().plusSeconds(wait))
                    .or(() -> reply.header("x-ratelimit-reset")
                                   .flatMap(HttpReviewGitHub::seconds)
                                   .map(Instant::ofEpochSecond)
                                   .or(() -> Instant.now().plusSeconds(FALLBACK_WAIT_S)));
    }

    private static Option<Long> seconds(String raw) {
        return Result.lift(_ -> Causes.cause("not a number"),
                           () -> Long.parseLong(raw.strip()))
                     .option();
    }

    /// GitHub's `message` up to its first line, bounded; a fixed text with the status when the body is no GitHub JSON
    /// message (an HTML error page from a proxy is never echoed).
    static String firstLine(Reply reply) {
        var line = JSON.readString(reply.body(),
                                   MessageDto.class)
                       .option()
                       .flatMap(MessageDto::message)
                       .map(text -> text.strip()
                                        .lines()
                                        .findFirst()
                                        .orElse(""))
                       .or("non-JSON reply, status " + reply.status());

        return line.length() > 200
               ? line.substring(0, 200)
               : line;
    }

    // ---- the exchange -------------------------------------------------------------------------------------------------
    private URI url(String repo, String path) {
        return api.resolve("/repos/" + repo + "/" + path);
    }

    /// The whole exchange, body included, within `timeout`; then cancelled.
    private Promise<Reply> exchange(Duration timeout,
                                    java.util.function.UnaryOperator<HttpRequest.Builder> shape,
                                    Promise<Option<String>> authorization) {
        return authorization.flatMap(token -> {
            var builder = shape.apply(HttpRequest.newBuilder()
                                                 .timeout(timeout)
                                                 .header("Accept", "application/vnd.github+json")
                                                 .header("X-GitHub-Api-Version", "2022-11-28")
                                                 .header("User-Agent", "temper"));

            token.onPresent(value -> builder.header("Authorization", value));

            return VirtualSend.send(client,
                                    builder.build(),
                                    HttpReviewGitHub::body,
                                    timeout)
                              .map(Reply::reply);
        });
    }

    private static HttpResponse.BodySubscriber<String> body(HttpResponse.ResponseInfo info) {
        return HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
                                                     MAX_BODY);
    }

    record Reply(int status, String body, Map<String, List<String>> headers) {
        static Reply reply(HttpResponse<String> response) {
            return new Reply(response.statusCode(),
                             response.body(),
                             response.headers().map());
        }

        Option<String> header(String name) {
            return headers.entrySet()
                          .stream()
                          .filter(entry -> entry.getKey()
                                                .equalsIgnoreCase(name))
                          .flatMap(entry -> entry.getValue()
                                                 .stream())
                          .findFirst()
                          .map(Option::some)
                          .orElseGet(Option::none);
        }

        Option<Integer> remaining() {
            return header("x-ratelimit-remaining").flatMap(raw -> Result.lift(_ -> Causes.cause("not a number"),
                                                                              () -> Integer.parseInt(raw.strip()))
                                                                        .option());
        }
    }

    record PullDto(Option<String> state, Option<SideDto> head, Option<SideDto> base) {
        Result<PullView> view() {
            return Result.all(state.toResult(incomplete()),
                              head.flatMap(SideDto::sha).toResult(incomplete()),
                              base.flatMap(SideDto::ref).toResult(incomplete()),
                              base.flatMap(SideDto::sha).toResult(incomplete()))
                         .map(PullView::new);
        }

        private static org.pragmatica.lang.Cause incomplete() {
            return ReviewError.ReadFailed.readFailed("pull request answer incomplete");
        }
    }

    record SideDto(Option<String> sha, Option<String> ref) {}

    record ReviewDto(long id, Option<UserDto> user, String state, @JsonProperty("commit_id") Option<String> commitId) {
        ReviewView view() {
            return new ReviewView(id, user.flatMap(UserDto::login), state, commitId);
        }
    }

    record UserDto(Option<String> login) {}

    record IdDto(long id) {}

    record MessageDto(Option<String> message) {}
}
