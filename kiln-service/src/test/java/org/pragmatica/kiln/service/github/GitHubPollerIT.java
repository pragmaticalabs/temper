package org.pragmatica.kiln.service.github;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.service.store.BaseView;
import org.pragmatica.kiln.service.store.CheckStore;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.kiln.service.store.PrView;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.RESET;
import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.checkRuns;
import static org.pragmatica.kiln.service.github.GitHubFixtures.open;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quietHead;
import static org.pragmatica.kiln.service.github.GitHubFixtures.run;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.kiln.service.github.GitHubFixtures.unprotected;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// The GitHub loop against a [GitHubFake] and real Postgres (design §3.1, spec §4, §5.6): the backfill and the
/// incremental paging, skipped PRs, a broken page keeping the old rows, the per-open reads, the required lists (stacked
/// bases on demand), cells joined in read order and `UNKNOWN` on a failed read, the base reads keeping their old set,
/// and every source state: OK, INCOMPLETE (skipped), GITHUB-DOWN, RATE-LIMITED (a 403 with nothing left, the reserve,
/// a budget short of the need), each failing state kept since its first cycle, and the stretched period.
@Tag("pg")
class GitHubPollerIT {
    private static final String CHECK_RUNS = ROOT + "commits/" + sha(2).value() + "/check-runs?filter=all&per_page=100";

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

    // pulls

    @Test
    void cycle_backfillsToTheEnd_thenPagesOnlyWhileAPageReachesTheMark() {
        fake.pages(PULLS, List.of(array(merged(4, 0), merged(3, 1)), array(merged(2, 2), merged(1, 3))));
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0));
        assertThat(await(store.prs().all())).hasSize(4);
        assertThat(await(store.prs().highWater())).isEqualTo(some(T0));

        var from = fake.requests().size();

        later(60);
        await(poller.cycle());
        assertThat(fake.pathsSince(from)).contains(PULLS).doesNotContain(PULLS + "&page=2");

        fake.pages(PULLS, List.of(array(merged(6, -2), merged(5, -1)), array(merged(4, 0), merged(3, 1))));
        from = fake.requests().size();
        later(120);
        await(poller.cycle());
        assertThat(fake.pathsSince(from)).contains(PULLS, PULLS + "&page=2");
        assertThat(await(store.prs().all())).hasSize(6);
        assertThat(await(store.prs().highWater())).isEqualTo(some(T0.plusSeconds(120)));
    }

    @Test
    void cycle_showsASkippedPr_andStillAdvancesTheMark_overAPageOfSkippedPrs() {
        assertThat(poller.skippedNotes()).isEmpty();
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        await(poller.cycle());
        assertThat(poller.skippedNotes()).isEmpty();
        fake.pages(PULLS, List.of(array(pr(9, "open", "main", "-bad", sha(9), T0.plusSeconds(300)))));
        later(60);

        var state = await(poller.cycle());

        assertThat(state).isInstanceOfSatisfying(SourceState.Incomplete.class,
                                                 incomplete -> assertThat(incomplete.reason()).startsWith("skipped #9: "));
        assertThat(await(store.prs().highWater())).isEqualTo(some(T0.plusSeconds(300)));
        assertThat(numbers()).containsExactly(1L);
        // WP9 open item 2: the sources view reads the skipped PR, observed at the cycle that recorded it
        assertThat(poller.skippedNotes()).singleElement()
                                         .satisfies(note -> {
                                             assertThat(note.value().number().value()).isEqualTo(9);
                                             assertThat(note.value().reason()).isNotBlank();
                                             assertThat(note.observedAt()).isEqualTo(T0.plusSeconds(60));
                                         });
    }

    @Test
    void cycle_keepsTheOldRowsAndTheMark_whenAPageIsBroken() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0), merged(1, 1))));
        await(poller.cycle());
        var before = await(store.prs().all());

        fake.pages(PULLS, List.of(array("{\"number\":3,\"state\":\"open\"}", merged(1, 1))));
        later(60);

        assertThat(await(poller.cycle())).isInstanceOf(SourceState.GithubDown.class);
        assertThat(await(store.prs().all())).isEqualTo(before);
        assertThat(await(store.prs().highWater())).isEqualTo(some(T0));
        assertThat(openCount()).as("no failure makes the open count read 0").isEqualTo(1);
    }

    // per open PR

    @Test
    void cycle_storesTheOpenDetail_andReadsTheHeadDateOncePerSha() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        await(poller.cycle());
        later(60);
        await(poller.cycle());

        assertThat(fake.requests()).filteredOn(request -> request.path().equals(ROOT + "commits/" + sha(2).value())).hasSize(1);
        assertThat(onlyOpen().detail().map(Observed::value)).isEqualTo(some(new OpenDetail(some(true), some("clean"), some(T0))));
    }

    @Test
    void cycle_joinsCheckPagesInReadOrder_soTheLaterCopyOfARunDecides() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.pages(CHECK_RUNS,
                   List.of(checkRuns(run(5, "ci", "in_progress", null, T0)), checkRuns(run(5, "ci", "completed", "success", T0))));

        await(poller.cycle());

        assertThat(cellValues()).containsExactly("success");
    }

    @Test
    void cycle_givesUnknownCellsAtTheNewHead_whenItsCheckReadFails() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        await(poller.cycle());
        assertThat(cellValues()).containsExactly("success");

        quietHead(fake, 2, sha(22));
        fake.answer(ROOT + "commits/" + sha(22).value() + "/check-runs?filter=all&per_page=100", 500, "{}");
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(22), T0.plusSeconds(30)))));
        later(60);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("500", T0.plusSeconds(60)));
        assertThat(onlyOpen().pr().value().head()).isEqualTo(sha(22));
        assertThat(cellValues()).containsExactly("UNKNOWN");
    }

    @Test
    void cycle_writesAnUnknownList_whenTheBranchReadIsRefused() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.answer(ROOT + "branches/main", 403, "{\"message\":\"Forbidden\"}");

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("403", T0));
        assertThat(onlyOpen().required().map(Observed::value)).isEqualTo(some(new RequiredChecks.Unknown("403")));
        assertThat(cellValues()).isEmpty();
    }

    @Test
    void cycle_readsAStackedBaseOnDemand() {
        fake.pages(PULLS, List.of(array(pr(2, "open", "feat/x", "f", sha(2), T0))));
        fake.answer(ROOT + "branches/feat%2Fx", unprotected());
        fake.answer(ROOT + "rules/branches/feat%2Fx", "[]");

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0));
        assertThat(onlyOpen().required().map(Observed::value)).isEqualTo(some(new RequiredChecks.NoneRequired(RequiredChecks.DISABLED,
                                                                                                             true)));
    }

    // base reads

    @Test
    void cycle_keepsTheOldBaseRuns_whenTheirReadFails() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        await(poller.cycle());
        fake.answer(ROOT + "actions/runs?branch=main&per_page=20", 502, "");
        later(60);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("502", T0.plusSeconds(60)));
        assertThat(await(store.views().bases()).runs()).extracting(BaseView.Run::name, BaseView.Run::observedAt)
                                                       .containsExactlyInAnyOrder(tuple("CI", T0),
                                                                                  tuple("nightly",
                                                                                                                      T0.plusSeconds(60)));
    }

    // source states and the budget

    @Test
    void cycle_staysDown_sinceItsFirstFailure() {
        fake.answer(PULLS, 500, "{}");
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("500", T0));
        later(60);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("500", T0));
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        later(120);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0.plusSeconds(120)));
    }

    @Test
    void cycle_isRateLimited_onA403WithNothingRemaining_andWaitsForTheReset() {
        fake.answer(PULLS, 403, "{\"message\":\"API rate limit exceeded\"}");
        fake.rate(0, 60, RESET, true);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(RESET, T0));
        assertThat(fake.requests()).as("the reserve stops every later read").hasSize(1);

        later(60);
        assertThat(await(poller.tick())).isEqualTo(none());
        assertThat(fake.requests()).hasSize(1);
    }

    /// Design §8: the reserve is 10, written as a literal so the test does not move with the constant.
    @Test
    void cycle_neverSpendsTheReserve() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.rate(13, 5000, RESET, false);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(RESET, T0));
        assertThat(fake.requests()).hasSize(3);
    }

    @Test
    void cycle_sendsNothing_whenTheBudgetDoesNotCoverItsNeed() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.rate(10 + GitHubPoller.need(1, 0, 0, 0) * 2 - 1, 5000, RESET, false);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0));
        var sent = fake.requests().size();

        later(60);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(RESET, T0.plusSeconds(60)));
        assertThat(fake.requests()).hasSize(sent);
    }

    @Test
    void tick_waitsTheStretchedPeriod() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.rate(50, 60, T0.plusSeconds(3600), true);

        assertThat(await(poller.tick())).isEqualTo(some(new SourceState.Ok(T0)));
        var stretched = Duration.ofSeconds(Math.ceilDiv(3600L * (GitHubPoller.need(1, 0, 0, 0) + 1), 50)); // limit 60 - reserve 10; the first cycle also reads the team (§7.4)

        assertThat(poller.period()).isEqualTo(stretched);
        var sent = fake.requests().size();

        later(60);
        assertThat(await(poller.tick())).isEqualTo(none());
        assertThat(fake.requests()).hasSize(sent);
        later(stretched.toSeconds());
        assertThat(await(poller.tick()).isPresent()).isTrue();
        assertThat(fake.requests().size()).isGreaterThan(sent);
    }

    /// WP5 precondition: one `cells` writer per PR. A tick while a cycle runs sends nothing, is skipped and counted.
    /// Probe (WP6 state file): drop the guard in `guarded` → the overlapping tick runs a second cycle and this goes red.
    @Test
    void tick_isSkippedAndCounted_whileACycleRuns() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.delay(Duration.ofMillis(300));

        var first = poller.tick();
        var overlapping = await(poller.tick());

        assertThat(overlapping).isEqualTo(none());
        assertThat(poller.skippedTicks()).isEqualTo(1);
        assertThat(await(first)).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(fake.requests()).filteredOn(request -> request.path().equals(PULLS)).hasSize(1);
        assertThat(cellValues()).containsExactly("success");
    }

    /// Single writer (review F9): `change` rows are inserted only by the cycle's PR upsert and cell
    /// writes, and concurrent triggers serialize: [#TICKERS] ticks at once run one cycle, every other tick is skipped,
    /// and no two change writes are ever in flight together, so kiln's change ids commit in id order. Cited at
    /// `PgChangeStore.insert`. Probe: the guard in `GitHubPoller.guarded` dropped → [#TICKERS] cycles run and this goes red.
    @Test
    void concurrentTicks_runOneCycle_soChangeWritesNeverOverlap() throws Exception {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.delay(Duration.ofMillis(200));
        var writes = new Writes();
        var tracked = GitHubFixtures.poller(fake, writes.tracking(store), now, none());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(TICKERS);

        var ticks = IntStream.range(0, TICKERS).mapToObj(_ -> submitTick(pool, start, tracked)).toList();
        start.countDown();
        var results = ticks.stream().map(GitHubPollerIT::settled).toList();
        pool.shutdownNow();

        assertThat(results).filteredOn(Option::isPresent).containsExactly(some(new SourceState.Ok(T0)));
        assertThat(tracked.skippedTicks()).isEqualTo(TICKERS - 1);
        assertThat(fake.requests()).filteredOn(request -> request.path().equals(PULLS)).hasSize(1);
        assertThat(writes.started.get()).as("one upsert and one cells write").isEqualTo(2);
        assertThat(writes.peak.get()).as("change writes in flight at once").isEqualTo(1);
    }

    private static final int TICKERS = 8;

    /// Lead ruling (single writer): a cycle past its deadline while its PR upsert (a change write) is held keeps the guard,
    /// so a trigger then is skipped and starts no cycle; once the write settles, a trigger runs the next cycle. Probe: the
    /// guard freed when the tick settles at the deadline → the second trigger runs a cycle while the write is held, and
    /// this goes red.
    @Test
    void timedOutCycle_holdsTheGuard_untilItsWriteSettles() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        Promise<Unit> gate = Promise.promise();
        var held = GitHubFixtures.poller(fake, gated(store, gate), now, none(), Duration.ofSeconds(2));

        var first = await(held.tick());
        now.set(T0.plusSeconds(3_600));
        var during = await(held.tick());

        assertThat(first).isEqualTo(some(new SourceState.GithubDown(GitHubPoller.TIMED_OUT, T0)));
        assertThat(during).isEqualTo(none());
        assertThat(held.skippedTicks()).isEqualTo(1);
        assertThat(fake.requests()).filteredOn(request -> request.path().equals(PULLS)).hasSize(1);

        gate.succeed(unit());

        assertThat(firstRun(held).isPresent()).as("a cycle runs once the held write settled").isTrue();
        assertThat(fake.requests()).filteredOn(request -> request.path().equals(PULLS)).hasSize(2);
    }

    /// The store with every PR upsert waiting for `gate`.
    private static KilnStore gated(KilnStore store, Promise<Unit> gate) {
        return Writes.proxy(KilnStore.class, (_, method, args) -> switch (method.getName()) {
            case "prs" -> Writes.proxy(PrStore.class, (_, inner, innerArgs) -> inner.getName().equals("upsert")
                                                                              ? upsertAfter(gate, store.prs(), innerArgs)
                                                                              : inner.invoke(store.prs(), innerArgs));
            default -> method.invoke(store, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static Promise<List<Change>> upsertAfter(Promise<Unit> gate, PrStore prs, Object[] args) {
        return gate.flatMap(_ -> prs.upsert((List<PullRequest>) args[0], (Map<PrNumber, String>) args[1], (Instant) args[2]));
    }

    /// The first tick, of up to 100 taken 100 ms apart, that ran a cycle.
    private static Option<SourceState> firstRun(GitHubPoller poller) {
        return IntStream.range(0, 100)
                        .mapToObj(_ -> pausedTick(poller))
                        .filter(Option::isPresent)
                        .findFirst()
                        .orElse(none());
    }

    private static Option<SourceState> pausedTick(GitHubPoller poller) {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        return await(poller.tick());
    }

    private static Future<Promise<Option<SourceState>>> submitTick(ExecutorService pool, CountDownLatch start, GitHubPoller poller) {
        return pool.submit(() -> tickAfter(start, poller));
    }

    private static Promise<Option<SourceState>> tickAfter(CountDownLatch start, GitHubPoller poller) throws InterruptedException {
        start.await();
        return poller.tick();
    }

    private static Option<SourceState> settled(Future<Promise<Option<SourceState>>> tick) {
        try {
            return await(tick.get());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /// The store with its change writers (`prs().upsert`, `checks().cells`) counted: how many started, and the most in
    /// flight at once.
    private static final class Writes {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();

        KilnStore tracking(KilnStore store) {
            return proxy(KilnStore.class, (_, method, args) -> switch (method.getName()) {
                case "prs" -> proxy(PrStore.class, (_, inner, innerArgs) -> tracked(inner, store.prs(), innerArgs, "upsert"));
                case "checks" -> proxy(CheckStore.class, (_, inner, innerArgs) -> tracked(inner, store.checks(), innerArgs, "cells"));
                default -> method.invoke(store, args);
            });
        }

        private Object tracked(Method method, Object target, Object[] args, String write) throws Throwable {
            if (!method.getName().equals(write)) {
                return method.invoke(target, args);
            }
            started.incrementAndGet();
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return ((Promise<?>) method.invoke(target, args)).withResult(_ -> inFlight.decrementAndGet());
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }

    /// The need of a cycle: 1 pulls page + 3 per configured base + 2 per stacked base + 3 per open PR + 1 per new head
    /// + 2 (scheduled runs, issues).
    @Test
    void need_countsPagesBasesStackedBasesOpenPrsAndNewHeads() {
        assertThat(GitHubPoller.need(1, 0, 0, 0)).isEqualTo(6);
        assertThat(GitHubPoller.need(2, 0, 0, 0)).isEqualTo(9);
        assertThat(GitHubPoller.need(1, 0, 2, 0)).isEqualTo(12);
        assertThat(GitHubPoller.need(1, 0, 2, 2)).isEqualTo(14);
        assertThat(GitHubPoller.need(1, 1, 1, 1)).as("a stacked base and a new head").isEqualTo(12);
        assertThat(GitHubPoller.need(2, 3, 5, 4)).isEqualTo(1 + 6 + 6 + 15 + 4 + 2);
    }

    /// Two open PRs on one base need 12 requests: 22 remaining (12 above the reserve of 10) runs the cycle, 21 sends
    /// nothing and reads RATE-LIMITED.
    @Test
    void cycle_coversExactlyItsNeed_withTwoOpenPrs() {
        quietHead(fake, 3, sha(3));
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0), open(3, "other", sha(3), T0))));
        await(poller.cycle());
        var sent = fake.requests().size();

        poller.reads().budget().observed(new GhAnswer(200, none(), "", some(21), some(5000), some(RESET), none(), none()), now.get());
        later(60);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(RESET, T0.plusSeconds(60)));
        assertThat(fake.requests()).hasSize(sent);

        poller.reads().budget().observed(new GhAnswer(200, none(), "", some(22), some(5000), some(RESET), none(), none()), now.get());
        later(120);
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0.plusSeconds(120)));
        assertThat(fake.requests().size()).isGreaterThan(sent);
    }

    /// A 403 or 429 that leaves nothing remaining is the rate limit even as the cycle's last read (nothing is refused
    /// after it); with requests left it is GITHUB-DOWN.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {403, 429})
    void cycle_isRateLimited_onTheLastReadAnswering403Or429WithNothingRemaining(int status) {
        var issues = ROOT + "issues?state=open&creator=app/github-actions";

        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.rate(1000, 5000, RESET, false);
        fake.answer(issues, status, "{\"message\":\"limit\"}");
        fake.exhaustOn(issues);

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(RESET, T0));
        assertThat(fake.requests().getLast().path()).isEqualTo(issues);
    }

    @Test
    void cycle_isDown_onA403WithRequestsLeft() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.rate(1000, 5000, RESET, false);
        fake.answer(ROOT + "issues?state=open&creator=app/github-actions", 403, "{}");

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.GithubDown("403", T0));
    }

    /// A 429 is always a rate limit (round 3 ruling): with requests left and no `Retry-After` it waits a minute.
    @Test
    void cycle_isRateLimited_onA429WithRequestsLeft() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.rate(1000, 5000, RESET, false);
        fake.answer(ROOT + "issues?state=open&creator=app/github-actions", 429, "{}");

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.RateLimited(T0.plusSeconds(60), T0));
    }

    /// An unpinned check reads the statuses too, every page in order: the failure on page 2 decides.
    @Test
    void cycle_readsEveryStatusPage_forAnUnpinnedCheck() {
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.answer(ROOT + "branches/main", GitHubFixtures.requiringUnpinned("ci"));
        fake.pages(ROOT + "commits/" + sha(2).value() + "/statuses?per_page=100",
                   List.of(array(GitHubFixtures.status(1, "other", "success")), array(GitHubFixtures.status(2, "ci", "failure"))));

        await(poller.cycle());

        assertThat(cellValues()).containsExactly("failure");
    }

    @Test
    void cycle_storesTheBotIssues() {
        fake.pages(PULLS, List.of(array(merged(1, 0))));
        fake.answer(ROOT + "issues?state=open&creator=app/github-actions", array(GitHubFixtures.issue(7, "nightly failed")));

        await(poller.cycle());

        assertThat(await(store.views().bases()).issues()).extracting(BaseView.Issue::number, BaseView.Issue::title, BaseView.Issue::observedAt)
                                                         .containsExactly(tuple(7L, "nightly failed", T0));
    }

    private void later(long seconds) {
        now.set(T0.plusSeconds(seconds));
    }

    /// `#n` merged, updated `minutesAgo` minutes before T0 (negative: after).
    private static String merged(long number, long minutesAgo) {
        return pr(number, "merged", "main", "b" + number, sha(number), T0.minus(Duration.ofMinutes(minutesAgo)));
    }

    private List<Long> numbers() {
        return await(store.prs().all()).stream().map(pr -> pr.number().value()).toList();
    }

    private long openCount() {
        return await(store.views().openPrs()).size();
    }

    private PrView onlyOpen() {
        var open = await(store.views().openPrs());

        assertThat(open).hasSize(1);
        return open.getFirst();
    }

    private List<String> cellValues() {
        return onlyOpen().cells().stream().map(Observed::value).map(Cell::value).toList();
    }
}
