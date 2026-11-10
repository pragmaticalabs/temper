package org.pragmatica.temper.controller.git;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.ghapp.GitHubAppAuth;
import org.pragmatica.temper.ghapp.VirtualSend;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOriginError;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.controller.task.PullMerges;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


/// [PullOrigins] over the GitHub REST API: `GET /repos/{owner}/{repo}/pulls/{N}`. Same-repo iff `head.repo` is present and its `full_name` equals
/// `base.repo.full_name`; a null `head.repo` (a deleted fork) is a fork. Every other outcome (a non-github origin, the
/// whole exchange, body included, not done within `timeout`, a body over [#MAX_BODY], any status but 200, the rate
/// limit, an unreadable body) is a [PullOriginError], which the submit turns into a refusal: fail closed.
///
/// A judged origin is kept in `known` for this controller process (a restart asks again): a PR's head repository never changes (a deleted
/// fork stays a fork), so each PR costs one call ever. Failures are not kept; the next submit asks again.
///
/// Authentication: with a [GitHubAppAuth], every request carries `Authorization: Bearer`
/// with the controller App's installation token, and without one (`[github] anonymous = true`) it carries none. When the
/// token cannot be had ([org.pragmatica.temper.ghapp.AppAuthError.CredentialUnavailable]) nothing is sent and the call
/// fails like any other failure (the submit is refused, T15 cannot close): no fallback to an unauthenticated read. An
/// authenticated client never follows a redirect, so a `Location` or `Link` off `api.github.com` cannot carry the token;
/// the 3xx answer is a [PullOriginError.Status] failure.
public record GitHubPulls(HttpClient client,
                          URI api,
                          String origin,
                          Duration timeout,
                          Map<PullNumber, PullOrigin> known,
                          Option<GitHubAppAuth> auth) implements PullOrigins, PullMerges {
    private static final Logger log = LoggerFactory.getLogger(GitHubPulls.class);
    public static final URI API = URI.create("https://api.github.com");
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int OK = 200;
    /// A pull-request answer is a few KiB; anything past this is not one.
    public static final long MAX_BODY = 1024 * 1024;
    /// GitHub's payload has many more fields than these; unknown ones are ignored (Jackson 3's default).
    private static final JsonMapper JSON = JsonMapper.jsonMapper().withPragmaticaTypes().build();
    private static final Result<PullOrigin> DELETED_FORK = success(PullOrigin.FORK);

    /// The production judge, as `Main` installs it.
    public static GitHubPulls gitHubPulls(String origin) {
        return gitHubPulls(API, origin);
    }

    /// The production client (it follows a renamed repository's 301) against `api`, unauthenticated.
    public static GitHubPulls gitHubPulls(URI api, String origin) {
        return gitHubPulls(HttpClient.newBuilder()
                                     .connectTimeout(TIMEOUT)
                                     .followRedirects(HttpClient.Redirect.NORMAL)
                                     .build(),
                           api,
                           origin,
                           TIMEOUT);
    }

    public static GitHubPulls gitHubPulls(HttpClient client, URI api, String origin, Duration timeout) {
        return new GitHubPulls(client, api, origin, timeout, new ConcurrentHashMap<>(), Option.none());
    }

    /// The production judge with the controller App's token: the public API, [#TIMEOUT].
    public static GitHubPulls gitHubPulls(String origin, GitHubAppAuth auth) {
        return gitHubPulls(API, origin, TIMEOUT, auth);
    }

    /// An authenticated judge against `api`. Its client never follows a redirect, so the token goes only to `api`.
    public static GitHubPulls gitHubPulls(URI api, String origin, Duration timeout, GitHubAppAuth auth) {
        return new GitHubPulls(HttpClient.newBuilder()
                                         .connectTimeout(timeout)
                                         .followRedirects(HttpClient.Redirect.NEVER)
                                         .build(),
                               api,
                               origin,
                               timeout,
                               new ConcurrentHashMap<>(),
                               Option.some(auth));
    }

    @Override
    public Option<PullOrigin> known(PullNumber pull) {
        return Option.option(known.get(pull));
    }

    @Override
    public Promise<PullOrigin> origin(PullNumber pull) {
        return known(pull).map(Promise::success)
                    .or(() -> asked(pull));
    }

    /// T15's guard (m3-design §10.5): the same `GET …/pulls/{N}`, its `merged` flag. Never kept: a PR
    /// becomes merged. Every failure of [#origin]'s kinds fails the answer (fail closed), and an absent flag is not merged.
    @Override
    public Promise<Boolean> isMerged(long pr) {
        var pull = new PullNumber(pr);

        return GitHubRepo.gitHubRepo(origin)
                         .async()
                         .flatMap(repo -> answer(repo, pull))
                         .flatMap(response -> mergedOf(response).async());
    }

    static Result<Boolean> mergedOf(HttpResponse<Option<String>> response) {
        return response.statusCode() == OK
               ? response.body()
                         .toResult(PullOriginError.General.OVERSIZED)
                         .flatMap(GitHubPulls::mergedFlag)
               : PullOriginError.Status.FACTORY.apply(response.statusCode()).result();
    }

    private static Result<Boolean> mergedFlag(String body) {
        return JSON.readString(body, MergedDto.class)
                   .mapError(PullOriginError.Unparseable.FACTORY)
                   .map(MergedDto::isMerged);
    }

    record MergedDto(Option<Boolean> merged) {
        boolean isMerged() {
            return merged.or(false);
        }
    }

    private Promise<PullOrigin> asked(PullNumber pull) {
        return GitHubRepo.gitHubRepo(origin)
                         .async()
                         .flatMap(repo -> answer(repo, pull))
                         .flatMap(response -> judged(response).async())
                         .withSuccess(judged -> known.put(pull, judged))
                         .onFailure(cause -> log.warn("origin of pull request {} unverified: {}",
                                                      pull,
                                                      chain(cause)));
    }

    /// The failure and every cause under it, for the log only: the refusal detail carries the top message.
    private static String chain(Cause cause) {
        return cause.stream()
                    .map(Cause::message)
                    .collect(Collectors.joining(" <- "));
    }

    /// `HttpRequest.timeout` bounds only the headers, so the whole exchange, body included, has its own deadline; on
    /// it the exchange is cancelled. The body is read per [#body].
    private Promise<HttpResponse<Option<String>>> answer(GitHubRepo repo, PullNumber pull) {
        return request(repo, pull).flatMap(this::exchanged);
    }

    private Promise<HttpResponse<Option<String>>> exchanged(HttpRequest request) {
        return VirtualSend.send(client, request, GitHubPulls::body, timeout).mapError(PullOriginError.CallFailed.FACTORY);
    }

    /// A body whose declared length is over [#MAX_BODY] is not read: none, a per-PR [PullOriginError.General#OVERSIZED].
    /// One without a declared length is read through the [#MAX_BODY] limit, and running over it fails the exchange.
    /// So an over-cap body is per-PR when its length is declared and API-wide when chunked; only GitHub itself can produce
    /// either, because PR text is capped at 65,536 characters.
    private static HttpResponse.BodySubscriber<Option<String>> body(HttpResponse.ResponseInfo info) {
        return info.headers()
                   .firstValueAsLong("Content-Length")
                   .orElse(0) > MAX_BODY
               ? HttpResponse.BodySubscribers.replacing(Option.none())
               : HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8),
                                                                                            MAX_BODY),
                                                      Option::some);
    }

    /// The request, with the App's token when there is an auth; a token that cannot be had fails here, before anything is sent.
    private Promise<HttpRequest> request(GitHubRepo repo, PullNumber pull) {
        var builder = HttpRequest.newBuilder(api.resolve("/repos/" + repo.fullName() + "/pulls/" + pull.value()))
                                 .timeout(timeout)
                                 .header("Accept", "application/vnd.github+json")
                                 .header("User-Agent", "temper")
                                 .GET();

        return auth.map(source -> source.token()
                                        .mapError(PullOriginError.CallFailed.FACTORY)
                                        .map(token -> builder.header("Authorization",
                                                                     "Bearer " + token.value())
                                                             .build()))
                   .or(() -> Promise.success(builder.build()));
    }

    static Result<PullOrigin> judged(HttpResponse<Option<String>> response) {
        return response.statusCode() == OK
               ? response.body()
                         .toResult(PullOriginError.General.OVERSIZED)
                         .flatMap(GitHubPulls::parsed)
               : PullOriginError.Status.FACTORY.apply(response.statusCode()).result();
    }

    private static Result<PullOrigin> parsed(String body) {
        return JSON.readString(body, PullDto.class)
                   .mapError(PullOriginError.Unparseable.FACTORY)
                   .flatMap(PullDto::origin);
    }

    record PullDto(Option<SideDto> head, Option<SideDto> base) {
        Result<PullOrigin> origin() {
            return base.flatMap(SideDto::repo)
                       .flatMap(RepoDto::fullName)
                       .toResult(PullOriginError.General.INCOMPLETE)
                       .flatMap(this::headAgainst);
        }

        private Result<PullOrigin> headAgainst(String baseName) {
            return head.toResult(PullOriginError.General.INCOMPLETE)
                       .flatMap(side -> side.against(baseName));
        }
    }

    record SideDto(Option<RepoDto> repo) {
        Result<PullOrigin> against(String baseName) {
            return repo.map(present -> present.against(baseName))
                       .or(DELETED_FORK);
        }
    }

    record RepoDto(@JsonProperty("full_name") Option<String> fullName) {
        Result<PullOrigin> against(String baseName) {
            return fullName.toResult(PullOriginError.General.INCOMPLETE)
                           .map(name -> PullOrigin.pullOrigin(name, baseName));
        }
    }
}
