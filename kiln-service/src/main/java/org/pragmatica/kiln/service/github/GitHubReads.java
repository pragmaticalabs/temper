package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.GitHubError;
import org.pragmatica.kiln.service.store.Cached;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Conditional, budgeted GitHub reads (spec §4.1, K-10, design §8). Each URL is asked with `If-None-Match` and its last
/// ETag; a 200 stores its ETag and body ([EtagStore], `gh_etag`), and a 304 replays the stored body, so a quiet cycle
/// fetches no body at all. Every request first passes [RateBudget#spend], and every answer updates the budget. A 200
/// or 304 is a success; any other status fails as [GitHubError.HttpStatus], so a required list reads `UNKNOWN(<status>)`.
/// The one exception is [#parsedOnce], for a URL asked once: no `If-None-Match`, nothing stored, only a 200 succeeds.
///
/// A page's `Link: rel="next"` is kept beside its body, in the row `<path>` + [#NEXT] (blank: no next page), and
/// replayed with it: a 304 need not repeat the header, and a replayed page without its link would end the paging early.
///
/// Design §2 named `JdkGitHubHttp` as the `gh_etag` writer. WP6 keeps the store here instead, over the [GitHubHttp]
/// port, so the exchange stays one stateless request and the ETag rule is tested without a socket.
public record GitHubReads(GitHubHttp http, EtagStore etags, RateBudget budget, Supplier<Instant> clock) {
    static final int OK = 200;
    static final int NOT_MODIFIED = 304;
    static final String NEXT = "#next";
    static final String GRAPHQL = "/graphql";

    public static GitHubReads gitHubReads(GitHubHttp http,
                                          EtagStore etags,
                                          RateBudget budget,
                                          Supplier<Instant> clock) {
        return new GitHubReads(http, etags, budget, clock);
    }

    /// One page's body.
    public Promise<String> body(String path) {
        return conditional(path).map(GhAnswer::body);
    }

    /// One page's body, parsed; a body the parser refuses fails the read.
    public <T> Promise<T> parsed(String path, Fn1<Result<T>, String> parser) {
        return body(path).flatMap(body -> parser.apply(body)
                                                .async());
    }

    /// One page's body, parsed, asked without `If-None-Match` and never stored: for a URL asked once (a `compare` of
    /// two shas), whose stored body could never be replayed and would only accumulate in `gh_etag`. A 200 is the only
    /// success.
    public <T> Promise<T> parsedOnce(String path, Fn1<Result<T>, String> parser) {
        return unconditional(path).flatMap(answer -> parser.apply(answer.body())
                                                           .async());
    }

    /// A GraphQL query (#102): `POST /graphql` with `json`, counted by [RateBudget#spend] like every request, never
    /// stored. Its rate headers describe GitHub's separate GraphQL bucket, so they are not recorded as the REST count. A
    /// 200 is the only success; the parser judges a 200 that carries `errors[]`.
    public <T> Promise<T> graphql(String json, Fn1<Result<T>, String> parser) {
        return spent().flatMap(_ -> http.post(GRAPHQL, json))
                    .flatMap(GitHubReads::unstored)
                    .flatMap(answer -> parser.apply(answer.body())
                                             .async());
    }

    /// Every page from `path` on, each decoded as it arrives, in read order. A page that fails to decode fails the
    /// read, and nothing more is asked. The next page is asked only while `more` holds for the page just read.
    public <T> Promise<List<T>> pages(String path, Fn1<Result<T>, String> decode, Predicate<T> more) {
        return paged(path, decode, more, List.of());
    }

    /// Every page from `path` on, each asked once as [#parsedOnce] is: no `If-None-Match`, nothing stored in `gh_etag`,
    /// a 200 the only success. For listings that are read until they are final and then never again (#102), whose stored
    /// bodies would only accumulate.
    public <T> Promise<List<T>> pagesOnce(String path, Fn1<Result<T>, String> decode) {
        return pagedOnce(path, decode, List.of());
    }

    private <T> Promise<List<T>> pagedOnce(String path, Fn1<Result<T>, String> decode, List<T> read) {
        return unconditional(path).flatMap(answer -> decode.apply(answer.body())
                                                           .async()
                                                           .flatMap(page -> followingOnce(answer.next(),
                                                                                          decode,
                                                                                          appended(read, page))));
    }

    private <T> Promise<List<T>> followingOnce(Option<String> next, Fn1<Result<T>, String> decode, List<T> read) {
        return next.map(path -> pagedOnce(path, decode, read))
                   .or(Promise.success(read));
    }

    private <T> Promise<List<T>> paged(String path, Fn1<Result<T>, String> decode, Predicate<T> more, List<T> read) {
        return conditional(path).flatMap(answer -> decoded(answer, decode, more, read));
    }

    private <T> Promise<List<T>> decoded(GhAnswer answer,
                                         Fn1<Result<T>, String> decode,
                                         Predicate<T> more,
                                         List<T> read) {
        return decode.apply(answer.body())
                     .async()
                     .flatMap(page -> following(answer.next(),
                                                page,
                                                decode,
                                                more,
                                                appended(read, page)));
    }

    private <T> Promise<List<T>> following(Option<String> next,
                                           T page,
                                           Fn1<Result<T>, String> decode,
                                           Predicate<T> more,
                                           List<T> read) {
        return next.filter(_ -> more.test(page))
                   .map(path -> paged(path, decode, more, read))
                   .or(Promise.success(read));
    }

    private static <T> List<T> appended(List<T> read, T page) {
        return Stream.concat(read.stream(),
                             Stream.of(page))
                     .toList();
    }

    /// Counts before the ETag lookup, so a failed lookup counts a request that was never sent. Accepted: it errs toward
    /// under-spending, never over.
    private Promise<GhAnswer> conditional(String path) {
        return spent().flatMap(_ -> etags.cached(path))
                    .flatMap(cached -> exchanged(path, cached));
    }

    /// A request the credential refuses is never sent, so it is not counted: the credential is asked first.
    private Promise<Unit> spent() {
        return http.ready()
                   .flatMap(_ -> budget.spend(clock.get())
                                       .async());
    }

    private Promise<GhAnswer> unconditional(String path) {
        return spent().flatMap(_ -> http.get(path,
                                             Option.none()))
                    .map(answer -> budget.observed(answer,
                                                   clock.get()))
                    .flatMap(GitHubReads::unstored);
    }

    private static Promise<GhAnswer> unstored(GhAnswer answer) {
        return answer.status() == OK
               ? Promise.success(answer)
               : GitHubError.HttpStatus.FACTORY.apply(answer.status()).promise();
    }

    private Promise<GhAnswer> exchanged(String path, Option<Cached> cached) {
        return http.get(path,
                        cached.map(Cached::etag))
                   .map(answer -> budget.observed(answer,
                                                  clock.get()))
                   .flatMap(answer -> judged(path, answer, cached));
    }

    private Promise<GhAnswer> judged(String path, GhAnswer answer, Option<Cached> cached) {
        return switch (answer.status()) {
            case OK -> stored(path, answer);
            case NOT_MODIFIED -> replayed(path, answer, cached);
            default -> GitHubError.HttpStatus.FACTORY.apply(answer.status()).promise();
        };
    }

    /// A 200 without an ETag is not stored: there is nothing to ask the next one with.
    private Promise<GhAnswer> stored(String path, GhAnswer answer) {
        return answer.etag()
                     .map(etag -> storedWith(path, etag, answer))
                     .or(Promise.success(answer));
    }

    private Promise<GhAnswer> storedWith(String path, String etag, GhAnswer answer) {
        var at = clock.get();

        return etags.store(path,
                           etag,
                           answer.body(),
                           at)
                    .flatMap(_ -> etags.store(path + NEXT,
                                              etag,
                                              answer.next().or(""),
                                              at))
                    .map(_ -> answer);
    }

    /// A 304 answers only an `If-None-Match`, which is sent only with a stored body; one without it is GitHub's error,
    /// shown as its status.
    private Promise<GhAnswer> replayed(String path, GhAnswer answer, Option<Cached> cached) {
        return cached.map(found -> replayedWith(path,
                                                answer,
                                                found.body()))
                     .or(GitHubError.HttpStatus.FACTORY.apply(NOT_MODIFIED).promise());
    }

    private Promise<GhAnswer> replayedWith(String path, GhAnswer answer, String body) {
        return answer.next()
                     .map(_ -> Promise.success(withBody(answer,
                                                        body,
                                                        answer.next())))
                     .or(() -> withStoredNext(path, answer, body));
    }

    private Promise<GhAnswer> withStoredNext(String path, GhAnswer answer, String body) {
        return etags.cached(path + NEXT)
                    .map(link -> withBody(answer,
                                          body,
                                          storedNext(link)));
    }

    private static Option<String> storedNext(Option<Cached> link) {
        return link.map(Cached::body)
                   .filter(next -> !next.isBlank());
    }

    private static GhAnswer withBody(GhAnswer answer, String body, Option<String> next) {
        return GhAnswer.ghAnswer(answer.status(),
                                 answer.etag(),
                                 body,
                                 answer.remaining(),
                                 answer.limit(),
                                 answer.reset(),
                                 next,
                                 answer.retryAfter());
    }
}
