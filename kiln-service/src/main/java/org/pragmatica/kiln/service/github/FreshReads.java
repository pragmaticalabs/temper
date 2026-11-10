package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;


/// Unconditional GitHub reads for one `GET /api/v1/pr-fresh` (R2-7): over the same [GitHubHttp]
/// port as [GitHubReads], but never through its ETag store. No `If-None-Match` is sent, so a 304 cannot arise and a
/// 200 is the only success; nothing is stored. Every request first passes [RateBudget#spend] and reports its answer to
/// [RateBudget#observed], both in memory. `sent` counts the requests of this call (the reply's `reads`). Once
/// `closed` ([#close], the call has answered), every later read fails with [FreshError.Closed] before it spends or sends.
///
/// The inverse direction (storing what was read) is deliberately absent: a fresh read must not let a later poll cycle
/// replay it, and must not move any cursor the poller's change rules diff against.
public record FreshReads(GitHubHttp http,
                         RateBudget budget,
                         Supplier<Instant> clock,
                         AtomicInteger sent,
                         AtomicBoolean closed) {
    static final int OK = 200;

    public static FreshReads freshReads(GitHubHttp http, RateBudget budget, Supplier<Instant> clock) {
        return new FreshReads(http, budget, clock, new AtomicInteger(), new AtomicBoolean());
    }

    /// Ends this call's reads: every later [#get] fails without spending or sending.
    public Unit close() {
        closed.set(true);

        return unit();
    }

    /// One 200 answer at `path`.
    public Promise<GhAnswer> get(String path) {
        return open(path).flatMap(_ -> spent())
                   .async()
                   .flatMap(_ -> exchanged(path));
    }

    private Result<Unit> open(String path) {
        return closed.get()
               ? FreshError.Closed.FACTORY.apply(path).result()
               : Result.unitResult();
    }

    private Result<Unit> spent() {
        return budget.spend(clock.get())
                     .mapError(FreshError.Budget.FACTORY);
    }

    /// One page at `path`, decoded; `what` names it in a decode failure.
    public <T> Promise<T> parsed(String path, String what, Fn1<Result<T>, String> decode) {
        return get(path).flatMap(answer -> decoded(answer, what, decode));
    }

    /// Every page from `path` on, at most `max`, each decoded, in read order (`Link: rel="next"` followed).
    public <T> Promise<List<T>> pages(String path, String what, Fn1<Result<T>, String> decode, int max) {
        return paged(path, what, decode, max, List.of());
    }

    private <T> Promise<List<T>> paged(String path, String what, Fn1<Result<T>, String> decode, int max, List<T> read) {
        return get(path).flatMap(answer -> page(answer, what, decode, max, read));
    }

    private <T> Promise<List<T>> page(GhAnswer answer,
                                      String what,
                                      Fn1<Result<T>, String> decode,
                                      int max,
                                      List<T> read) {
        return decoded(answer, what, decode).flatMap(page -> following(answer, what, decode, max, appended(read, page)));
    }

    private <T> Promise<List<T>> following(GhAnswer answer,
                                           String what,
                                           Fn1<Result<T>, String> decode,
                                           int max,
                                           List<T> read) {
        return answer.next()
                     .filter(_ -> read.size() < max)
                     .map(next -> paged(next, what, decode, max, read))
                     .or(Promise.success(read));
    }

    private static <T> List<T> appended(List<T> read, T page) {
        return Stream.concat(read.stream(),
                             Stream.of(page))
                     .toList();
    }

    private static <T> Promise<T> decoded(GhAnswer answer, String what, Fn1<Result<T>, String> decode) {
        return decode.apply(answer.body())
                     .mapError(cause -> FreshError.Decode.FACTORY.apply(what, cause))
                     .async();
    }

    private Promise<GhAnswer> exchanged(String path) {
        sent.incrementAndGet();

        return http.get(path,
                        none())
                   .mapError(cause -> FreshError.Unreachable.FACTORY.apply(path, cause))
                   .map(answer -> budget.observed(answer,
                                                  clock.get()))
                   .flatMap(answer -> judged(path, answer));
    }

    private static Promise<GhAnswer> judged(String path, GhAnswer answer) {
        return answer.status() == OK
               ? Promise.success(answer)
               : FreshError.Status.FACTORY.apply(answer.status(),
                                                 path)
                                          .promise();
    }
}
