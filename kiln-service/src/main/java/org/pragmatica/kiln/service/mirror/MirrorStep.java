package org.pragmatica.kiln.service.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.flow.SingleFlight;
import org.pragmatica.kiln.service.flow.Steps;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The mirror step (kiln-f1-design §3.1): right after each GitHub cycle and at least every [#PERIOD], `fetch` the
/// mirror, then the behind facts of every open PR's current head against its base, each written with the fetch's time.
/// Single-flight with a deadline ([SingleFlight]): a step while one runs is skipped and counted.
///
/// No failure makes a count read 0 (§3.4): a failed fetch records `Unreachable(reason)` (kept since it began) and writes
/// no behind fact, so the old ones stay with their age; a PR whose behind read fails keeps its old fact, and the step
/// reads `Incomplete(behind failed for N of M open PRs)`. A fetch that succeeded triggers the derivation (`fetched`, the
/// derived rows' `mirror_at`), whatever the behind reads did.
public final class MirrorStep {
    public static final Duration PERIOD = Duration.ofSeconds(60);
    /// A step still running this long fails, and frees the next.
    public static final Duration DEADLINE = Duration.ofMinutes(10);

    private final MirrorGit mirror;
    private final PrStore prs;
    private final SourceStore sources;
    private final Supplier<Instant> clock;
    private final Consumer<Instant> fetched;
    private final Duration deadline;
    private final SingleFlight flight = SingleFlight.singleFlight();
    private final AtomicReference<Option<SourceState>> last = new AtomicReference<>(none());

    private MirrorStep(MirrorGit mirror,
                       PrStore prs,
                       SourceStore sources,
                       Supplier<Instant> clock,
                       Consumer<Instant> fetched,
                       Duration deadline) {
        this.mirror = mirror;
        this.prs = prs;
        this.sources = sources;
        this.clock = clock;
        this.fetched = fetched;
        this.deadline = deadline;
    }

    /// `fetched` runs with the fetch's time after every fetch that succeeded (the derivation's trigger, §3.1).
    public static MirrorStep mirrorStep(MirrorGit mirror,
                                        PrStore prs,
                                        SourceStore sources,
                                        Supplier<Instant> clock,
                                        Consumer<Instant> fetched,
                                        Duration deadline) {
        return new MirrorStep(mirror, prs, sources, clock, fetched, deadline);
    }

    /// One step: the state recorded, or none when a step was still running.
    public Promise<Option<SourceState>> step() {
        return flight.run(this::stepNow, deadline);
    }

    /// The steps skipped while the previous one ran.
    public long skipped() {
        return flight.skipped();
    }

    private Promise<SourceState> stepNow() {
        var at = clock.get();

        return mirror.fetch()
                     .fold(result -> afterFetch(result, at))
                     .flatMap(this::recorded);
    }

    private Promise<SourceState> afterFetch(Result<?> fetch, Instant at) {
        return fetch.fold(cause -> Promise.success(new SourceState.Unreachable(cause.message(), at)),
                          _ -> fetchedAt(at));
    }

    private Promise<SourceState> fetchedAt(Instant at) {
        fetched.accept(at);

        return prs.all()
                  .map(MirrorStep::openOnly)
                  .flatMap(open -> behindAll(open, at));
    }

    private static List<PullRequest> openOnly(List<PullRequest> all) {
        return all.stream()
                  .filter(pr -> pr.state() == PrState.OPEN)
                  .toList();
    }

    /// Each open PR in turn; a failed read is counted, never fatal.
    private Promise<SourceState> behindAll(List<PullRequest> open, Instant at) {
        return Steps.sequence(open, pr -> behindOf(pr, at)).map(read -> judged(read, open.size(), at));
    }

    private Promise<Boolean> behindOf(PullRequest pr, Instant at) {
        return mirror.behind(pr.head(),
                             pr.base())
                     .flatMap(facts -> prs.behind(pr.number(),
                                                  pr.head(),
                                                  pr.base(),
                                                  facts,
                                                  at))
                     .fold(result -> Promise.success(result.isSuccess()));
    }

    private static SourceState judged(List<Boolean> read, int open, Instant at) {
        var failed = read.stream().filter(ok -> !ok).count();

        return failed == 0
               ? new SourceState.Ok(at)
               : new SourceState.Incomplete("behind failed for " + failed + " of " + open + " open PRs", at);
    }

    private Promise<SourceState> recorded(SourceState state) {
        var continued = last.get().map(previous -> SourceState.continued(previous, state)).or(state);

        return sources.record(Source.MIRROR, continued)
                      .map(_ -> remembered(continued));
    }

    private SourceState remembered(SourceState state) {
        last.set(some(state));

        return state;
    }
}
