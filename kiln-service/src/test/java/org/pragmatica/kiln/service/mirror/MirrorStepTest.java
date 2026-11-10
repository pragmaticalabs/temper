package org.pragmatica.kiln.service.mirror;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [MirrorStep] (kiln-f1-design §3.1, WP9): a fetch, then the behind facts of every open PR's head, written with the
/// fetch's time; the derivation triggered after every fetch that succeeded (open item 11). No failure makes a count read
/// 0 (§3.4): a failed fetch writes no fact and records `Unreachable`, kept since it began; a failed behind read leaves
/// that PR's fact and reads `Incomplete`.
class MirrorStepTest {
    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();
    private static final BranchName MAIN = BranchName.branchName("main").unwrap();

    record Written(long pr, Sha head, BranchName base, BehindFacts facts, Instant at) {}

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final List<Written> written = new CopyOnWriteArrayList<>();
    private final List<SourceState> recorded = new CopyOnWriteArrayList<>();
    private final List<Instant> fetched = new CopyOnWriteArrayList<>();
    private volatile boolean fetchFails;
    private volatile Set<Sha> behindFails = Set.of();
    private volatile List<PullRequest> prs = List.of(pr(1, PrState.OPEN), pr(2, PrState.CLOSED), pr(3, PrState.OPEN));

    private static Sha sha(long n) {
        return Sha.sha(String.format("%040x", n)).unwrap();
    }

    private static PullRequest pr(long number, PrState state) {
        return new PullRequest(PrNumber.prNumber(number).unwrap(), "pr", state, false, MAIN, BranchName.branchName("b" + number).unwrap(),
                               none(), sha(number), none(), none(), T0, List.of(), List.of(), List.of());
    }

    private final MirrorGit mirror = new MirrorGit() {
        @Override
        public Promise<Unit> fetch() {
            return fetchFails ? Causes.cause("fetch failed: 128").promise() : Promise.unitPromise();
        }

        @Override
        public Promise<BehindFacts> behind(Sha head, BranchName base) {
            return behindFails.contains(head)
                   ? Causes.cause("rev-list failed").promise()
                   : Promise.success(new BehindFacts(some((int) Long.parseLong(head.value(), 16)), some(false)));
        }

        @Override
        public Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Ancestry> ancestry(Sha head, List<BranchName> bases) {
            throw new UnsupportedOperationException();
        }
    };

    private final PrStore store = (PrStore) Proxy.newProxyInstance(PrStore.class.getClassLoader(), new Class<?>[] {PrStore.class}, (_, method, args) -> switch (method.getName()) {
        case "all" -> Promise.success(prs);
        case "behind" -> {
            written.add(new Written(((PrNumber) args[0]).value(), (Sha) args[1], (BranchName) args[2], (BehindFacts) args[3], (Instant) args[4]));
            yield Promise.unitPromise();
        }
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final SourceStore sources = (SourceStore) Proxy.newProxyInstance(SourceStore.class.getClassLoader(), new Class<?>[] {SourceStore.class}, (_, method, args) -> {
        assertThat(method.getName()).isEqualTo("record");
        assertThat(args[0]).isEqualTo(Source.MIRROR);
        recorded.add((SourceState) args[1]);
        return Promise.unitPromise();
    });

    private final MirrorStep step = MirrorStep.mirrorStep(mirror, store, sources, now::get, fetched::add, Duration.ofSeconds(5));

    private Option<SourceState> stepped() {
        return step.step().await(WAIT).unwrap();
    }

    @Test
    void step_writesTheBehindFactsOfEveryOpenHead_atTheFetchTime_andTriggersTheDerivation() {
        assertThat(stepped()).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(written).containsExactly(new Written(1, sha(1), MAIN, new BehindFacts(some(1), some(false)), T0),
                                            new Written(3, sha(3), MAIN, new BehindFacts(some(3), some(false)), T0));
        assertThat(fetched).containsExactly(T0);
        assertThat(recorded).containsExactly(new SourceState.Ok(T0));
    }

    @Test
    void aFailedFetch_writesNothing_triggersNothing_andKeepsItsStart() {
        fetchFails = true;
        stepped();
        now.set(T0.plusSeconds(60));
        stepped();

        assertThat(written).isEmpty();
        assertThat(fetched).isEmpty();
        assertThat(recorded).containsExactly(new SourceState.Unreachable("fetch failed: 128", T0),
                                             new SourceState.Unreachable("fetch failed: 128", T0));
    }

    @Test
    void aFailedBehindRead_keepsThatPrsFact_andReadsIncomplete() {
        behindFails = Set.of(sha(1));

        assertThat(stepped()).isEqualTo(some(new SourceState.Incomplete("behind failed for 1 of 2 open PRs", T0)));
        assertThat(written).extracting(Written::pr).containsExactly(3L);
        assertThat(fetched).containsExactly(T0);
    }

    @Test
    void noOpenPr_isOk() {
        prs = List.of(pr(2, PrState.MERGED));

        assertThat(stepped()).isEqualTo(some(new SourceState.Ok(T0)));
        assertThat(written).isEmpty();
    }

    @Test
    void aStepWhileOneRuns_isSkippedAndCounted() {
        var gate = Promise.<Unit> promise();
        var slow = MirrorStep.mirrorStep(new MirrorGit() {
            @Override
            public Promise<Unit> fetch() {
                return gate;
            }

            @Override
            public Promise<BehindFacts> behind(Sha head, BranchName base) {
                return mirror.behind(head, base);
            }

            @Override
            public Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Promise<Ancestry> ancestry(Sha head, List<BranchName> bases) {
                throw new UnsupportedOperationException();
            }
        }, store, sources, now::get, fetched::add, Duration.ofSeconds(5));
        var first = slow.step();

        assertThat(slow.step().await(WAIT).unwrap()).isEqualTo(none());
        assertThat(slow.skipped()).isEqualTo(1);
        gate.succeed(Unit.unit());
        assertThat(first.await(WAIT).unwrap()).isEqualTo(some(new SourceState.Ok(T0)));
    }
}
