package org.pragmatica.kiln.service.github;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.open;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quietHead;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.kiln.service.github.GitHubFixtures.requiring;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The WP6 verifier's attacks (kilnv6, a1–a11), kept as regression tests, with the fix round's controls: the skipped
/// set outlives its cycle and a restart, a failed date read keeps the stored date (but never an older head's), every
/// tick settles (a throw, a store that never answers), a secondary limit stops the cycle, a reset far ahead or already
/// past is bounded, and a cycle sends no more than its need.
@Tag("pg")
class GitHubAttackIT {
    private static final Duration SHORT = Duration.ofMillis(300);

    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        quiet(fake, List.of(2L), List.of(sha(2)));
        poller = GitHubFixtures.poller(fake, store, now, none());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    // 1: skipped PRs persist

    /// A1: a skipped PR on a page the incremental read does not re-read stays in the state.
    @Test
    void a1_skippedPr_staysIncomplete_whenItsPageIsNotReRead() {
        skippedOnPageTwo();
        later(60);

        assertThat(await(poller.cycle())).as("skipped #9 is still not in the table")
                                         .isEqualTo(new SourceState.Incomplete(reasonOf9(), T0.plusSeconds(60)));
    }

    /// Control: a later read that decodes #9 takes it out of the set, and the source reads OK.
    @Test
    void a1_skippedPr_leavesTheSet_whenALaterReadDecodesIt() {
        skippedOnPageTwo();
        fake.pages(PULLS, List.of(array(open(9, "good", sha(9), T0.plusSeconds(30)), merged(4, 0))));
        quietHead(fake, 9, sha(9));
        later(60);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0.plusSeconds(60)));
    }

    /// After a start the set is reloaded from the stored `Incomplete` state.
    @Test
    void a1_skippedSet_isReloadedAfterAStart() {
        skippedOnPageTwo();
        var restarted = GitHubFixtures.poller(fake, store, now, none());

        later(60);
        assertThat(await(restarted.cycle())).isEqualTo(new SourceState.Incomplete(reasonOf9(), T0.plusSeconds(60)));
    }

    @Test
    void skippedIn_readsTheSetBack_fromTheReasonItWrote() {
        var reason = "skipped #9: refused; a reason; skipped #12: other";

        assertThat(GitHubPoller.skippedIn(new SourceState.Incomplete(reason, T0))).containsExactly(Map.entry(number(9), "refused; a reason"),
                                                                                                   Map.entry(number(12), "other"));
        assertThat(GitHubPoller.skippedIn(new SourceState.GithubDown("502", T0))).isEmpty();
        assertThat(GitHubPoller.skippedIn(new SourceState.Incomplete("no trailer", T0))).isEmpty();
    }

    // 2: a failed commit-date read

    /// A2: a failed `commits/{sha}` read after a restart keeps the stored head date.
    @Test
    void a2_failedCommitDateRead_keepsTheStoredDate() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        await(poller.cycle());
        assertThat(headDate()).isEqualTo(some(T0));

        fake.answer(ROOT + "commits/" + sha(2).value(), 502, "");
        var restarted = GitHubFixtures.poller(fake, store, now, none());

        later(60);
        assertThat(await(restarted.cycle())).isEqualTo(new SourceState.GithubDown("502", T0.plusSeconds(60)));
        assertThat(headDate()).isEqualTo(some(T0));
    }

    /// Control: a kept date is the current head's only. A new head whose date read fails shows no date, never the old
    /// head's.
    @Test
    void a2_newHeadWithAFailedDateRead_showsNoDate() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        await(poller.cycle());
        quietHead(fake, 2, sha(3));
        fake.answer(ROOT + "commits/" + sha(3).value(), 502, "");
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(3), T0.plusSeconds(30)))));

        later(60);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("502", T0.plusSeconds(60)));
        assertThat(onlyOpen().pr().value().head()).isEqualTo(sha(3));
        assertThat(headDate()).isEqualTo(none());
    }

    // 3: the loop never dies

    /// A6: a tick whose cycle throws releases the single-flight flag.
    @Test
    void a6_tickThatThrows_releasesTheFlag() {
        var thrower = GitHubFixtures.poller(fake, throwingAll(store, new AtomicInteger(1)), now, none());

        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        assertThat(thrower.tick().await(TimeSpan.timeSpan(10).seconds()).isFailure()).isTrue();
        later(120);

        assertThat(await(thrower.tick())).isEqualTo(some(new SourceState.Ok(T0.plusSeconds(120))));
        assertThat(thrower.skippedTicks()).isZero();
    }

    /// A throw at the very start of the cycle, outside any continuation, settles the tick too.
    @Test
    void a6_tickThatThrowsAtItsFirstCall_settlesAndReleasesTheFlag() {
        var thrower = GitHubFixtures.poller(fake, throwingSources(store, new AtomicInteger(1)), now, none());

        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        assertThat(thrower.tick().await(TimeSpan.timeSpan(10).seconds()).isFailure()).isTrue();
        later(120);

        assertThat(await(thrower.tick())).isEqualTo(some(new SourceState.Ok(T0.plusSeconds(120))));
    }

    /// A6b: a store step that throws inside the chain settles the tick and releases the flag.
    @Test
    void a6b_tickWhoseChainThrows_settlesAndReleasesTheFlag() {
        var thrower = GitHubFixtures.poller(fake, throwingBackfill(store, new AtomicInteger(1)), now, none());

        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        thrower.tick().await(TimeSpan.timeSpan(10).seconds());
        later(120);

        assertThat(await(thrower.tick()).isPresent()).isTrue();
    }

    /// A store that never answers: past the deadline the tick reads `GITHUB-DOWN(cycle timed out)` and frees the next;
    /// the late cycle, answered afterwards, sends nothing.
    @Test
    void tick_pastTheDeadline_readsTimedOut_andTheLateCycleSendsNothing() throws InterruptedException {
        var hung = Promise.<List<PullRequest>> promise();
        var hanging = GitHubFixtures.poller(fake, hangingAll(store, hung), now, none(), SHORT);

        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        assertThat(await(hanging.tick())).isEqualTo(some(new SourceState.GithubDown("cycle timed out", T0)));
        assertThat(state()).isEqualTo(new SourceState.GithubDown("cycle timed out", T0));

        hung.succeed(List.of());
        Thread.sleep(SHORT.toMillis());
        assertThat(fake.requests()).as("the late cycle sends nothing").isEmpty();

        later(60);
        assertThat(await(hanging.tick())).isEqualTo(some(new SourceState.Ok(T0.plusSeconds(60))));
        assertThat(hanging.skippedTicks()).isZero();
    }

    /// Control: a cycle that ends within the deadline is not timed out.
    @Test
    void tick_withinTheDeadline_isItsOwnState() {
        var bounded = GitHubFixtures.poller(fake, store, now, none(), Duration.ofSeconds(30));

        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.delay(Duration.ofMillis(50));

        assertThat(await(bounded.tick())).isEqualTo(some(new SourceState.Ok(T0)));
    }

    // 5–7, 9: the rate guard

    /// A3: a secondary rate limit (403, a secondary-limit message, requests remaining) stops the cycle at once.
    @Test
    void a3_secondaryRateLimit_stopsTheCycle() {
        fake.answer(PULLS, 403, "{\"message\":\"You have exceeded a secondary rate limit\"}");
        fake.rate(4000, 5000, T0.plusSeconds(1800), false);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(60), T0));
        assertThat(fake.requests()).hasSize(1);
    }

    /// A `Retry-After` names the wait: nothing is sent until it has passed.
    @Test
    void a3_secondaryRateLimit_sendsNothingUntilItsRetryAfter() {
        fake.answer(PULLS, 403, "{\"message\":\"slow down\"}");
        fake.retryAfter(PULLS, "120");
        fake.rate(4000, 5000, T0.plusSeconds(1800), false);

        assertThat(await(poller.tick())).isEqualTo(some(new SourceState.RateLimited(T0.plusSeconds(120), T0)));
        assertThat(fake.requests()).hasSize(1);
        later(60);
        assertThat(await(poller.tick())).isEqualTo(none());
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(120), T0));
        assertThat(fake.requests()).as("a cycle inside the wait sends nothing").hasSize(1);

        later(120);
        assertThat(await(poller.tick())).isNotEqualTo(none());
        assertThat(fake.requests()).hasSize(2);
    }

    /// R1 (round 3): a `Retry-After` of ~31 years waits an hour at most.
    @Test
    void r1_hugeRetryAfter_pollsAgainWithinTheHour() {
        fake.answer(PULLS, 403, "{\"message\":\"slow down\"}");
        fake.retryAfter(PULLS, "999999999");
        fake.rate(4000, 5000, T0.plusSeconds(1800), false);

        assertThat(await(poller.tick())).isEqualTo(some(new SourceState.RateLimited(T0.plusSeconds(3600), T0)));
        later(3500);
        assertThat(await(poller.tick())).isEqualTo(none());
        later(3600);
        assertThat(await(poller.tick())).isNotEqualTo(none());
        // the wait is over and the rate was last stated an hour ago: a `/rate_limit` sample, then the pulls read (§7.1)
        assertThat(fake.pathsSince(0)).containsExactly(PULLS, "/rate_limit", PULLS);
    }

    /// R2 (round 3): a 403 with requests left and an HTTP-date `Retry-After` (a generic message) stops the cycle, and
    /// waits until that date.
    @Test
    void r2_httpDateRetryAfter_stopsTheCycle() {
        fake.answer(PULLS, 403, "{\"message\":\"Forbidden\"}");
        fake.retryAfter(PULLS, "Fri, 30 Oct 2026 10:02:00 GMT");
        fake.rate(4000, 5000, T0.plusSeconds(1800), false);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(120), T0));
        assertThat(fake.requests()).hasSize(1);
    }

    /// An unreadable `Retry-After` on a 403 with requests left still stops the cycle, for a minute.
    @Test
    void unreadableRetryAfter_stopsTheCycle_forAMinute() {
        fake.answer(PULLS, 403, "{\"message\":\"Forbidden\"}");
        fake.retryAfter(PULLS, "later");
        fake.rate(4000, 5000, T0.plusSeconds(1800), false);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(60), T0));
        assertThat(fake.requests()).hasSize(1);
    }

    /// A4: a reset far in the future is waited for at most an hour; the re-check then sends one request.
    @Test
    void a4_farFutureReset_pollsAgainWithinTheHour_withOneRequest() {
        fake.answer(PULLS, 403, "{\"message\":\"API rate limit exceeded\"}");
        fake.rate(0, 60, T0.plus(Duration.ofDays(3650)), true);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(3600), T0));
        later(3500);
        assertThat(await(poller.tick())).isEqualTo(none());

        later(3600);
        assertThat(await(poller.tick())).isEqualTo(some(new SourceState.RateLimited(T0.plusSeconds(7200), T0)));
        assertThat(fake.requests()).as("the re-check spends one request").hasSize(2);
    }

    /// A5: a 403 with remaining 0 and a reset already past (clock skew) waits a minute, not a tick.
    @Test
    void a5_pastReset_doesNotRunEveryTick() {
        fake.answer(PULLS, 403, "{\"message\":\"API rate limit exceeded\"}");
        fake.rate(0, 60, T0.minusSeconds(60), true);

        assertThat(await(poller.tick())).isEqualTo(some(new SourceState.RateLimited(T0.plusSeconds(60), T0)));
        later(5);
        assertThat(await(poller.tick())).isEqualTo(none());
        assertThat(fake.requests()).hasSize(1);

        later(60);
        assertThat(await(poller.tick())).isNotEqualTo(none());
        assertThat(fake.requests()).as("one re-check a minute later").hasSize(2);
    }

    /// A9: a 429 with remaining 0 reads RATE-LIMITED.
    @Test
    void a9_429WithNothingRemaining_isRateLimited() {
        fake.answer(PULLS, 429, "{\"message\":\"rate\"}");
        fake.rate(0, 60, T0.plusSeconds(1800), true);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(1800), T0));
    }

    // 8: need

    /// B3: after a restart, a cycle with one configured base, one open PR on a stacked base and a head whose date is
    /// not known sends 12 requests, and its need covers them.
    @Test
    void b3_need_coversEveryRequestTheCycleSends() {
        stackedPr();
        await(poller.cycle());
        var restarted = GitHubFixtures.poller(fake, store, now, none());
        var need = restarted.need(openPrs());
        var from = fake.requests().size();

        later(60);
        await(restarted.cycle());

        // 12 reads of the cycle itself, then the team read of a first cycle (the drift reads), in this order
        assertThat(fake.pathsSince(from)).containsExactly(PULLS,
                                                          ROOT + "commits/" + sha(2).value(),
                                                          ROOT + "pulls/2",
                                                          ROOT + "branches/main",
                                                          ROOT + "rules/branches/main",
                                                          ROOT + "branches/feat%2Fx",
                                                          ROOT + "rules/branches/feat%2Fx",
                                                          ROOT + "commits/" + sha(2).value() + "/check-runs?filter=all&per_page=100",
                                                          ROOT + "commits/" + sha(2).value() + "/statuses?per_page=100",
                                                          ROOT + "actions/runs?branch=main&per_page=20",
                                                          ROOT + "actions/runs?event=schedule&per_page=20",
                                                          ROOT + "issues?state=open&creator=app/github-actions",
                                                          "/orgs/pragmaticalabs/teams/temper-reviewers/members?per_page=100");
        assertThat(need).isGreaterThanOrEqualTo(fake.requests().size() - from);
    }

    /// Control: with the head's date known, the need is one less, and still covers what is sent.
    @Test
    void need_coversTheCycle_withTheHeadDateKnown() {
        stackedPr();
        await(poller.cycle());
        var need = poller.need(openPrs());
        var from = fake.requests().size();

        later(60);
        await(poller.cycle());

        assertThat(need).isEqualTo(11);
        assertThat(fake.requests().size() - from).isEqualTo(11);
    }

    // held by the verifier (green before the fix round), kept

    /// A7: equal `updated_at` across a page boundary loses no PR.
    @Test
    void a7_equalUpdatedAtAcrossPageBoundary_losesNoPr() {
        fake.pages(PULLS, List.of(array(merged(4, 0), merged(3, 1))));
        await(poller.cycle());
        fake.pages(PULLS, List.of(array(merged(6, -1), merged(5, 0)), array(merged(7, 0), merged(4, 0)), array(merged(3, 1))));
        later(120);
        await(poller.cycle());
        assertThat(await(store.prs().all())).extracting(row -> row.number().value()).contains(5L, 6L, 7L);
    }

    /// A8: a Link pointing at another host, and a redirect to another host, never carry the bearer there.
    @Test
    void a8_offHostLinkAndRedirect_neverCarryTheToken() throws IOException {
        var other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var seen = new CopyOnWriteArrayList<String>();

        other.createContext("/", exchange -> {
            seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        other.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        other.start();
        try {
            var otherRoot = "http://localhost:" + other.getAddress().getPort();

            assertThat(JdkGitHubHttp.next("<" + otherRoot + "/steal?x=1>; rel=\"next\"")).isEqualTo(some("/steal?x=1"));
            assertThat(JdkGitHubHttp.uri(fake.api(), "//localhost:" + other.getAddress().getPort() + "/x").isFailure()).isTrue();
            assertThat(JdkGitHubHttp.uri(fake.api(), "@localhost:" + other.getAddress().getPort() + "/x").isFailure()).isTrue();

            var redirector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

            redirector.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Location", otherRoot + "/x");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            redirector.start();
            try {
                var via = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(),
                                                      URI.create("http://127.0.0.1:" + redirector.getAddress().getPort()),
                                                      some("tok-SECRET"),
                                                      Duration.ofSeconds(5));

                assertThat(await(via.get(ROOT + "redirect", none())).status()).isEqualTo(302);
            } finally {
                redirector.stop(0);
            }
            assertThat(seen).isEmpty();
        } finally {
            other.stop(0);
        }
    }

    /// A10: a failed statuses read with OK runs: a pinned check is answered by its runs alone; the source is down.
    @Test
    void a10_failedStatusesWithOkRuns_pinnedCellFromRuns() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.answer(ROOT + "commits/" + sha(2).value() + "/statuses?per_page=100", 500, "{}");

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("500", T0));
        assertThat(onlyOpen().cells().stream().map(Observed::value).map(Cell::value).toList()).containsExactly("success");
    }

    /// A11: a failed stacked-base read gives UNKNOWN(status) and no green cell.
    @Test
    void a11_failedStackedBaseRead_isUnknown() {
        fake.pages(PULLS, List.of(array(pr(2, "open", "feat/x", "f", sha(2), T0))));
        fake.answer(ROOT + "branches/feat%2Fx", 502, "");

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("502", T0));
        assertThat(onlyOpen().required().map(Observed::value).map(Object::toString).or("")).contains("502");
        assertThat(onlyOpen().cells()).noneMatch(cell -> cell.value().value().equals("success"));
    }

    private void skippedOnPageTwo() {
        fake.pages(PULLS, List.of(array(merged(4, 0), merged(3, 1)),
                                  array(pr(9, "open", "main", "-bad", sha(9), T0.minus(Duration.ofMinutes(5))))));
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Incomplete(reasonOf9(), T0));
        fake.pages(PULLS, List.of(array(merged(4, 0), merged(3, 1))));
    }

    /// The reason the first cycle wrote for #9, as stored.
    private String reasonOf9() {
        return ((SourceState.Incomplete) state()).reason();
    }

    private void stackedPr() {
        fake.pages(PULLS, List.of(array(pr(2, "open", "feat/x", "f", sha(2), T0))));
        fake.answer(ROOT + "branches/feat%2Fx", requiring("ci"));
        fake.answer(ROOT + "rules/branches/feat%2Fx", "[]");
    }

    private static PrNumber number(long value) {
        return PrNumber.prNumber(value).unwrap();
    }

    private List<PullRequest> openPrs() {
        return await(store.prs().all()).stream().filter(row -> row.state() == PrState.OPEN).toList();
    }

    private SourceState state() {
        return await(store.sources().all()).stream()
                                            .filter(status -> status.source().equals(Source.GITHUB))
                                            .findFirst()
                                            .orElseThrow()
                                            .state();
    }

    private Option<Instant> headDate() {
        return onlyOpen().detail().map(Observed::value).flatMap(OpenDetail::headCommittedAt);
    }

    private void later(long seconds) {
        now.set(T0.plusSeconds(seconds));
    }

    private static String merged(long number, long minutesAgo) {
        return pr(number, "merged", "main", "b" + number, sha(number), T0.minus(Duration.ofMinutes(minutesAgo)));
    }

    private PrView onlyOpen() {
        var open = await(store.views().openPrs());

        assertThat(open).hasSize(1);
        return open.getFirst();
    }

    private static KilnStore throwingAll(KilnStore real, AtomicInteger left) {
        return storeWith(real, "prs", prsWith(real.prs(), "all", left));
    }

    private static KilnStore throwingBackfill(KilnStore real, AtomicInteger left) {
        return storeWith(real, "prs", prsWith(real.prs(), "backfillDone", left));
    }

    private static KilnStore throwingSources(KilnStore real, AtomicInteger left) {
        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                  new Class<?>[] {KilnStore.class},
                                                  (proxy, method, args) -> {
                                                      if (method.getName().equals("sources") && left.getAndDecrement() > 0) {
                                                          throw new IllegalStateException("store broke");
                                                      }
                                                      return invoke(method, real, args);
                                                  });
    }

    /// `prs().all()` answers `hung` once (a promise the test settles), then the real table.
    private static KilnStore hangingAll(KilnStore real, Promise<List<PullRequest>> hung) {
        var once = new AtomicInteger(1);
        var prs = (PrStore) Proxy.newProxyInstance(PrStore.class.getClassLoader(),
                                                   new Class<?>[] {PrStore.class},
                                                   (proxy, method, args) -> method.getName().equals("all") && once.getAndDecrement() > 0
                                                                            ? hung
                                                                            : invoke(method, real.prs(), args));

        return storeWith(real, "prs", prs);
    }

    private static KilnStore storeWith(KilnStore real, String name, Object part) {
        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                  new Class<?>[] {KilnStore.class},
                                                  (proxy, method, args) -> method.getName().equals(name) && (args == null || args.length == 0)
                                                                           ? part
                                                                           : invoke(method, real, args));
    }

    private static PrStore prsWith(PrStore real, String name, AtomicInteger left) {
        return (PrStore) Proxy.newProxyInstance(PrStore.class.getClassLoader(),
                                                new Class<?>[] {PrStore.class},
                                                (proxy, method, args) -> {
                                                    if (method.getName().equals(name) && (args == null || args.length == 0)
                                                        && left.getAndDecrement() > 0) {
                                                        throw new IllegalStateException("store broke");
                                                    }
                                                    return invoke(method, real, args);
                                                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
