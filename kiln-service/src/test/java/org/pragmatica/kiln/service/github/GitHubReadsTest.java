package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.GitHubError;
import org.pragmatica.kiln.service.store.Cached;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [GitHubReads] and [RateBudget] over a scripted [GitHubHttp] and an in-memory [EtagStore]: the ETag rule (store on
/// 200, ask with it, replay body and next link on 304), statuses as failures, the reserve, paging in read order, the
/// cycle's cover and its stretched period.
class GitHubReadsTest {
    private static final Instant RESET = T0.plusSeconds(600);

    record Sent(String path, Option<String> etag) {}

    private final Queue<GhAnswer> script = new ArrayDeque<>();
    private final List<Sent> sent = new ArrayList<>();
    private final Map<String, Cached> stored = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final RateBudget budget = RateBudget.rateBudget(1_000_000, 10);
    private final GitHubReads reads = GitHubReads.gitHubReads(this::get, etags(), budget, now::get);

    @Test
    void body_storesA200_asksWithItsEtag_andReplaysBodyAndNextOnA304() {
        script.add(answer(200, some("\"e1\""), "[1]", some("/p?page=2")));
        script.add(answer(304, none(), "", none()));

        assertThat(await(reads.body("/p"))).isEqualTo("[1]");
        assertThat(await(reads.pages("/p", body -> Result.success(body), _ -> false))).containsExactly("[1]");

        assertThat(sent).containsExactly(new Sent("/p", none()), new Sent("/p", some("\"e1\"")));
        assertThat(stored.get("/p" + GitHubReads.NEXT).body()).isEqualTo("/p?page=2");
    }

    @Test
    void pages_followTheReplayedNext_whenA304CarriesNoLink() {
        script.add(answer(200, some("\"e1\""), "a", some("/p?page=2")));
        script.add(answer(200, some("\"e2\""), "b", none()));
        script.add(answer(304, none(), "", none()));
        script.add(answer(304, none(), "", none()));

        assertThat(await(reads.pages("/p", Result::success, _ -> true))).containsExactly("a", "b");
        assertThat(await(reads.pages("/p", Result::success, _ -> true))).containsExactly("a", "b");
        assertThat(sent).extracting(Sent::path).containsExactly("/p", "/p?page=2", "/p", "/p?page=2");
    }

    /// A 304 that does carry a `Link` uses it, not the stored one.
    @Test
    void body_replaysA304sOwnNextLink_whenItCarriesOne() {
        script.add(answer(200, some("\"e1\""), "a", some("/p?page=2")));
        script.add(answer(304, none(), "", some("/p?page=9")));
        script.add(answer(200, none(), "z", none()));

        assertThat(await(reads.body("/p"))).isEqualTo("a");
        assertThat(await(reads.pages("/p", Result::success, _ -> true))).containsExactly("a", "z");
        assertThat(sent).extracting(Sent::path).containsExactly("/p", "/p", "/p?page=9");
    }

    /// The reserve is 10 (design §8): a cycle of `need` fits only with `need + 10` remaining.
    @Test
    void covers_boundaries() {
        budget.observed(new GhAnswer(200, none(), "", some(12), some(5000), some(RESET), none(), none()), T0);
        assertThat(budget.covers(12, T0)).as("exactly need remaining").isFalse();
        budget.observed(new GhAnswer(200, none(), "", some(22), some(5000), some(RESET), none(), none()), T0);
        assertThat(budget.covers(12, T0)).as("need + reserve").isTrue();
        budget.observed(new GhAnswer(200, none(), "", some(21), some(5000), some(RESET), none(), none()), T0);
        assertThat(budget.covers(12, T0)).as("one below").isFalse();
        assertThat(budget.exhausted()).isFalse();
        budget.observed(new GhAnswer(200, none(), "", some(1), some(5000), some(RESET), none(), none()), T0);
        assertThat(budget.exhausted()).as("one left is not exhausted").isFalse();
        budget.observed(new GhAnswer(429, none(), "", some(0), some(5000), some(RESET), none(), none()), T0);
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.covers(0, T0)).as("exhausted covers nothing").isFalse();
    }

    /// A limit of 0 reported (never by GitHub) leaves no effective budget (limit - reserve <= 0): no cycle until the
    /// window passes, an hour.
    @Test
    void period_waitsTheWindow_forAZeroLimit() {
        budget.observed(new GhAnswer(200, none(), "", some(5), some(0), some(RESET), none(), none()), T0);

        assertThat(budget.period(39, GitHubPoller.PERIOD)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void pages_stopAtABrokenPage_andFailTheRead() {
        script.add(answer(200, none(), "bad", some("/p?page=2")));

        var read = reads.pages("/p", body -> body.equals("bad") ? GitHubError.BadField.FACTORY.apply("$").<String> result() : Result.success(body),
                               _ -> true).await();

        assertThat(read.isFailure()).isTrue();
        assertThat(sent).hasSize(1);
    }

    @Test
    void body_failsWithTheStatus_onAnythingBut200Or304() {
        script.add(answer(502, none(), "", none()));
        script.add(answer(304, none(), "", none()));

        assertThat(failure(reads.body("/p"))).isEqualTo(GitHubError.HttpStatus.FACTORY.apply(502));
        assertThat(failure(reads.body("/q"))).as("a 304 with nothing stored").isEqualTo(GitHubError.HttpStatus.FACTORY.apply(304));
    }

    @Test
    void spend_refusesAtTheReserve_untilTheReset() {
        script.add(rated(11));
        script.add(rated(10));
        script.add(rated(4000));

        await(reads.body("/a"));
        await(reads.body("/b"));
        assertThat(failure(reads.body("/c"))).isEqualTo(GitHubReadError.BudgetSpent.FACTORY.apply(RESET));
        assertThat(sent).hasSize(2);

        now.set(RESET);
        await(reads.body("/c"));
        assertThat(sent).hasSize(3);
    }

    @Test
    void covers_andPeriod_followTheReportedRate() {
        assertThat(budget.covers(1000, T0)).as("nothing known yet").isTrue();
        assertThat(budget.period(39, GitHubPoller.PERIOD)).isEqualTo(GitHubPoller.PERIOD);

        budget.observed(new GhAnswer(200, none(), "", some(50), some(60), some(RESET), none(), none()), T0);

        assertThat(budget.covers(40, T0)).isTrue();
        assertThat(budget.covers(41, T0)).isFalse();
        assertThat(budget.covers(41, RESET)).as("after the reset").isTrue();
        assertThat(budget.period(39, GitHubPoller.PERIOD)).isEqualTo(Duration.ofSeconds(2808));
        assertThat(budget.exhausted()).isFalse();

        budget.observed(new GhAnswer(200, none(), "", some(5000), some(5000), some(RESET), none(), none()), T0);
        assertThat(budget.period(39, GitHubPoller.PERIOD)).isEqualTo(GitHubPoller.PERIOD);

        budget.observed(new GhAnswer(403, none(), "", some(0), none(), none(), none(), none()), T0);
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.until(T0, 1)).isEqualTo(RESET);
    }

    /// A URL asked once (`parsedOnce`) costs kiln a request like any other: counted toward the cap, refused at it.
    @Test
    void parsedOnce_isCountedTowardKilnsSpend_andRefusedAtTheCap() {
        var capped = RateBudget.rateBudget(2, 10);
        var once = GitHubReads.gitHubReads(this::get, etags(), capped, now::get);

        capped.observed(rated(4000), T0);
        script.add(rated(4000));
        script.add(rated(4000));

        String first = await(once.parsedOnce("/a", Result::success));
        String second = await(once.parsedOnce("/b", Result::success));

        assertThat(List.of(first, second)).containsExactly("{}", "{}");
        assertThat(capped.spent(T0)).as("two unconditional reads counted").isEqualTo(2);
        assertThat(failure(once.parsedOnce("/c", Result::success))).isEqualTo(GitHubReadError.BudgetSpent.FACTORY.apply(RESET));
        assertThat(sent).as("the third was never sent").extracting(Sent::path).containsExactly("/a", "/b");
    }

    private Promise<GhAnswer> get(String path, Option<String> etag) {
        sent.add(new Sent(path, etag));
        return Promise.success(script.remove());
    }

    private EtagStore etags() {
        return new EtagStore() {
            @Override
            public Promise<Option<Cached>> cached(String url) {
                return Promise.success(Option.option(stored.get(url)));
            }

            @Override
            public Promise<Unit> store(String url, String etag, String body, Instant at) {
                stored.put(url, new Cached(etag, body, at));
                return Promise.unitPromise();
            }
        };
    }

    private static GhAnswer answer(int status, Option<String> etag, String body, Option<String> next) {
        return new GhAnswer(status, etag, body, none(), none(), none(), next, none());
    }

    private static GhAnswer rated(int remaining) {
        return new GhAnswer(200, none(), "{}", some(remaining), some(5000), some(RESET), none(), none());
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await().unwrap();
    }

    private static <T> Object failure(Promise<T> promise) {
        return promise.await().fold(cause -> cause, value -> "success: " + value);
    }
}
