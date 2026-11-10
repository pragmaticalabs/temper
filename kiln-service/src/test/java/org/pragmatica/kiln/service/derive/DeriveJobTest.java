package org.pragmatica.kiln.service.derive;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Preservation;
import org.pragmatica.kiln.core.checkout.Reach;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.mirror.MirrorGit;
import org.pragmatica.kiln.service.scan.ScanFakes;
import org.pragmatica.kiln.service.scan.ScanFakes.RecordingStore;
import org.pragmatica.kiln.service.scan.ScanFakes.ScriptedTransport;
import org.pragmatica.kiln.service.scan.ScanHost;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.DerivedRow;
import org.pragmatica.kiln.service.store.PrStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.kiln.service.scan.ScanFakes.HOST;
import static org.pragmatica.kiln.service.scan.ScanFakes.T0;
import static org.pragmatica.kiln.service.scan.ScanFakes.factsRow;
import static org.pragmatica.kiln.service.scan.ScanFakes.path;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling 7: [DeriveJob] gathers reach (`reach` for the tips the mirror lacks, through the host's serial) and ancestry,
/// derives, and writes `checkout_derived` with the input times; its runs coalesce.
class DeriveJobTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(20).seconds();
    private static final Sha A = sha('a');
    private static final Sha B = sha('b');
    private static final Sha C = sha('c');
    private static final List<BranchName> BASES = List.of(branch("main"));
    private static final Instant NOW = T0.plusSeconds(3600);

    private final RecordingStore store = new RecordingStore();
    private final FakeMirror mirror = new FakeMirror();
    private final FakePrs prs = new FakePrs();
    private final ScriptedTransport transport = new ScriptedTransport();
    private final ScanHost host = ScanHost.scanHost(HOST, List.of("/w/co/*"), transport);

    private DeriveJob job() {
        return DeriveJob.deriveJob(store, prs, mirror, List.of(host), "pragmaticalabs/pragmatica", BASES, Duration.ofHours(48),
                                   Duration.ofSeconds(5), Duration.ofSeconds(10), () -> NOW);
    }

    @Test
    void reachOf_mirrorFirst_thenTheHostsAnswer_andNoAnswerIsUnknown() {
        var tips = Set.of(A, B, C, sha('d'));
        var reach = DeriveJob.reachOf(tips, Map.of(A, true, B, false, C, false, sha('d'), false), Map.of(B, true, C, false));

        assertThat(reach).isEqualTo(Map.of(A, Reach.MIRROR, B, Reach.HOST_BARE, C, Reach.NOWHERE, sha('d'), Reach.UNKNOWN));
    }

    @Test
    void aTipOnlyInTheHostsBare_isHostBareOnly_andOnlyTheTipsTheMirrorLacksAreAsked() {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A), tip("feature", B)), T0));
        transport.thenLines(List.of("{\"sha\":\"" + B.value() + "\",\"ref\":\"refs/heads/feature\"}", "{\"end\":true,\"count\":1}"));

        job().derive().await(WAIT).unwrap();

        assertThat(transport.calls("reach")).singleElement().satisfies(call -> assertThat(call.words()).containsExactly(B.value()));
        assertThat(only().derived().preservation()).isInstanceOf(Preservation.HostBareOnly.class);
    }

    @Test
    void aTipInNoBare_isUnpushed() {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A), tip("feature", B)), T0));
        transport.thenLines(List.of("{\"sha\":\"" + B.value() + "\",\"ref\":null}", "{\"end\":true,\"count\":1}"));

        job().derive().await(WAIT).unwrap();

        assertThat(only().derived().preservation()).isEqualTo(new Preservation.Unpreserved(List.of("unpushed:feature")));
    }

    @Test
    void aHostThatFailsReach_leavesItsTipsUnknown_neverNowhereOrPreserved() {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A), tip("feature", B)), T0));
        transport.then(() -> Causes.cause("ssh down").promise());

        job().derive().await(WAIT).unwrap();

        assertThat(only().derived().preservation()).isEqualTo(new Preservation.Unpreserved(List.of("unpushed:feature")));
        assertThat(transport.calls("reach")).hasSize(1);
    }

    @Test
    void reach_mapsTheHostsAnswer_andAFailedOrGarbledAnswerIsUnknown_neverNowhere() {
        mirror.inMirror = Set.of(A);
        var target = new DeriveJob.Target(HOST, some(host));
        transport.thenLines(List.of("{\"sha\":\"" + B.value() + "\",\"ref\":\"refs/heads/x\"}",
                                    "{\"sha\":\"" + C.value() + "\",\"ref\":null}",
                                    "{\"end\":true,\"count\":2}"))
                 .then(() -> Causes.cause("ssh down").promise())
                 .thenLines(List.of("{\"sha\":\"" + B.value() + "\",\"ref\":null}"));
        var job = job();

        assertThat(job.reach(target, Set.of(A, B, C)).await(WAIT).unwrap())
            .isEqualTo(Map.of(A, Reach.MIRROR, B, Reach.HOST_BARE, C, Reach.NOWHERE));
        assertThat(job.reach(target, Set.of(A, B, C)).await(WAIT).unwrap())
            .isEqualTo(Map.of(A, Reach.MIRROR, B, Reach.UNKNOWN, C, Reach.UNKNOWN));
        assertThat(job.reach(target, Set.of(A, B, C)).await(WAIT).unwrap())
            .isEqualTo(Map.of(A, Reach.MIRROR, B, Reach.UNKNOWN, C, Reach.UNKNOWN));
        assertThat(job.reach(new DeriveJob.Target(HostName.MAC, none()), Set.of(A, B)).await(WAIT).unwrap())
            .isEqualTo(Map.of(A, Reach.MIRROR, B, Reach.UNKNOWN));
        assertThat(transport.calls("reach")).extracting(ScanFakes.Call::words).containsOnly(List.of(B.value(), C.value()));
    }

    @Test
    void aGarbledReachAnswer_isNoAnswer() {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A), tip("feature", B)), T0));
        transport.thenLines(List.of("{\"sha\":\"" + B.value() + "\",\"ref\":\"refs/heads/feature\"}"));

        job().derive().await(WAIT).unwrap();

        assertThat(only().derived().preservation()).isInstanceOf(Preservation.Unpreserved.class);
    }

    @Test
    void allTipsInTheMirror_isPreserved_withNoReachCall() {
        mirror.inMirror = Set.of(A, B);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A), tip("feature", B)), T0));

        job().derive().await(WAIT).unwrap();

        assertThat(transport.calls).isEmpty();
        assertThat(only().derived().preservation()).isInstanceOf(Preservation.Preserved.class);
    }

    @Test
    void theMacsRows_haveNoReach_soTheirTipsTheMirrorLacksAreUnknown() {
        mirror.inMirror = Set.of(A);
        var macStore = new RecordingStore() {
            @Override
            public Promise<List<CheckoutRow>> rows(HostName name) {
                return Promise.success(name.equals(HostName.MAC)
                                       ? List.of(factsRow(facts("/Users/k/x", A, tip("main", A), tip("feature", B)), T0))
                                       : List.of());
            }
        };
        DeriveJob.deriveJob(macStore, prs, mirror, List.of(host), "pragmaticalabs/pragmatica", BASES, Duration.ofHours(48),
                            Duration.ofSeconds(5), Duration.ofSeconds(10), () -> NOW)
                 .derive().await(WAIT).unwrap();

        assertThat(transport.calls).isEmpty();
        assertThat(macStore.derivedCalls).extracting(ScanFakes.Derived::host).containsExactly(HOST, HostName.MAC);
        assertThat(macStore.derivedCalls.get(1).rows().getFirst().derived().preservation()).isInstanceOf(Preservation.Unpreserved.class);
    }

    @Test
    void reach_asksAtMost200ShasACall() {
        var tips = IntStream.range(0, 450).mapToObj(index -> Sha.sha("%040x".formatted(index + 1)).unwrap()).toList();
        mirror.inMirror = Set.of();
        store.rows = List.of(factsRow(facts("/w/co/x", tips.getFirst(), tips.stream().map(sha -> tip("b" + sha.value().substring(30), sha)).toArray(BranchTip[]::new)), T0));
        transport.then(() -> Causes.cause("no answer").promise());

        job().derive().await(WAIT).unwrap();

        assertThat(transport.calls("reach")).extracting(call -> call.words().size()).containsExactly(200, 200, 50);
        assertThat(transport.calls("reach").getFirst().words())
            .isEqualTo(tips.stream().map(Sha::value).sorted().limit(200).toList());
        assertThat(transport.calls("reach").stream().flatMap(call -> call.words().stream()).collect(Collectors.toSet()))
            .hasSize(450);
    }

    @Test
    void reach_ofAnExactMultipleOf200_hasNoEmptyBatch() {
        var tips = IntStream.range(0, 400).mapToObj(index -> Sha.sha("%040x".formatted(index + 1)).unwrap()).collect(Collectors.toSet());

        assertThat(DeriveJob.batches(tips)).extracting(Set::size).containsExactly(200, 200);
        assertThat(DeriveJob.batches(Set.of())).isEmpty();
    }

    @Test
    void theDefaultFactory_derives() {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A)), T0));

        DeriveJob.deriveJob(store, prs, mirror, List.of(host), "pragmaticalabs/pragmatica", BASES, Duration.ofHours(48), () -> NOW)
                 .derive().await(WAIT).unwrap();

        assertThat(only().derived().preservation()).isInstanceOf(Preservation.Preserved.class);
    }

    @Test
    void reach_waitsForTheHostsSerial() throws InterruptedException {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A), tip("feature", B)), T0));
        transport.thenLines(List.of("{\"sha\":\"" + B.value() + "\",\"ref\":null}", "{\"end\":true,\"count\":1}"));
        var blocker = Promise.<Unit> promise();
        host.exclusive(() -> blocker, Duration.ofSeconds(30));

        var run = job().derive();
        Thread.sleep(300);

        assertThat(transport.calls).isEmpty();
        blocker.succeed(Unit.unit());
        run.await(WAIT).unwrap();
        assertThat(transport.calls("reach")).hasSize(1);
    }

    @Test
    void theDerivedRow_carriesTheInputTimes() {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A)), T0), ScanFakes.row("/w/co/stub"));
        var job = job();

        job.derive().await(WAIT).unwrap();
        var before = store.derived.getFirst();
        assertThat(before).extracting(DerivedRow::mirrorAt, DerivedRow::prsAt).containsOnly(tuple(none(), none()));

        job.mirrorFetched(T0.plusSeconds(10)).await(WAIT).unwrap();
        job.prsUpserted(T0.plusSeconds(20)).await(WAIT).unwrap();
        var after = store.derived.getLast();

        assertThat(after.getFirst()).satisfies(row -> {
            assertThat(row.path()).isEqualTo(path("/w/co/x"));
            assertThat(row.factsAt()).isEqualTo(some(T0));
            assertThat(row.mirrorAt()).isEqualTo(some(T0.plusSeconds(10)));
            assertThat(row.prsAt()).isEqualTo(some(T0.plusSeconds(20)));
            assertThat(row.derivedAt()).isEqualTo(NOW);
        });
        assertThat(after.get(1)).satisfies(row -> {
            assertThat(row.derived().status()).isEqualTo(CheckoutStatus.UNSCANNED);
            assertThat(row.factsAt()).isEqualTo(none());
        });
    }

    @Test
    void ancestry_isAskedOncePerDistinctHead_withTheBases() {
        mirror.inMirror = Set.of(A, B);
        mirror.ancestry = new Ancestry(true, true, Set.of(), Set.of());
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A)), T0),
                             factsRow(facts("/w/co/y", A, tip("main", A)), T0),
                             factsRow(facts("/w/co/z", B, tip("other", B)), T0));

        job().derive().await(WAIT).unwrap();

        assertThat(mirror.asked).containsExactlyInAnyOrder(A, B);
        assertThat(mirror.bases).containsOnly(BASES);
        assertThat(store.derived.getFirst()).allSatisfy(row -> assertThat(row.derived().atBase()).isEqualTo(some(true)));
    }

    @Test
    void aMirrorFailure_failsTheRun_andWritesNothing() {
        mirror.fail = true;
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A)), T0));

        assertThat(job().derive().await(WAIT).isFailure()).isTrue();
        assertThat(store.derived).isEmpty();
    }

    @Test
    void triggers_coalesce_intoOneMoreRun() {
        var gate = Promise.<Unit> promise();
        prs.gate = some(gate);
        var job = job();

        var first = job.trigger();
        var second = job.trigger();
        var third = job.trigger();
        assertThat(prs.reads).hasValue(1);
        assertThat(second.isResolved()).as("a coalesced trigger waits for the run that covers it").isFalse();

        gate.succeed(Unit.unit());
        first.await(WAIT).unwrap();
        second.await(WAIT).unwrap();
        third.await(WAIT).unwrap();

        assertThat(prs.reads).hasValue(2);
        job.trigger().await(WAIT).unwrap();
        assertThat(prs.reads).hasValue(3);
    }

    /// Fix round ruling 3: a run past its deadline whose store read settles late writes nothing, so it can never put
    /// older derived rows over a newer run's.
    @Test
    void aRunPastItsDeadline_dropsItsLateWrite() throws InterruptedException {
        mirror.inMirror = Set.of(A);
        store.rows = List.of(factsRow(facts("/w/co/x", A, tip("main", A)), T0));
        store.gate = some(() -> Promise.promise(TimeSpan.timeSpan(800).millis(), late -> late.succeed(Unit.unit())));
        var job = DeriveJob.deriveJob(store, prs, mirror, List.of(host), "pragmaticalabs/pragmatica", BASES, Duration.ofHours(48),
                                      Duration.ofSeconds(5), Duration.ofMillis(300), () -> NOW);

        job.trigger().await(WAIT).unwrap();
        Thread.sleep(1500);

        assertThat(store.calls).as("the late rows read went on").contains("rows");
        assertThat(store.derivedCalls).as("and its write was dropped").isEmpty();
        store.gate = none();
        job.trigger().await(WAIT).unwrap();
        assertThat(store.derived).as("control: a run in time writes").hasSize(1);
    }

    @Test
    void aFailedOrStuckRun_neverFailsTheTrigger_andFreesTheNext() {
        prs.gate = some(Promise.promise());
        var job = DeriveJob.deriveJob(store, prs, mirror, List.of(host), "pragmaticalabs/pragmatica", BASES, Duration.ofHours(48),
                                      Duration.ofSeconds(5), Duration.ofMillis(300), () -> NOW);

        assertThat(job.trigger().await(WAIT).isSuccess()).isTrue();
        prs.gate = none();
        job.trigger().await(WAIT).unwrap();
        assertThat(store.derived).isNotEmpty();
    }

    private DerivedRow only() {
        assertThat(store.derived).hasSize(1);
        return store.derived.getFirst().getFirst();
    }

    private static CheckoutFacts facts(String path, Sha head, BranchTip... branches) {
        return new CheckoutFacts(path(path), some(head), some(branches[0].name()), none(), List.of(branches), 0, 0, none(),
                                 T0, List.of(), false, path + "/.git", 0, none());
    }

    private static BranchTip tip(String name, Sha sha) {
        return new BranchTip(branch(name), sha);
    }

    private static Sha sha(char c) {
        return Sha.sha(String.valueOf(c).repeat(40)).unwrap();
    }

    private static BranchName branch(String name) {
        return BranchName.branchName(name).unwrap();
    }

    static final class FakeMirror implements MirrorGit {
        volatile Set<Sha> inMirror = Set.of();
        volatile Ancestry ancestry = new Ancestry(true, false, Set.of(), Set.of());
        volatile boolean fail;
        final List<Sha> asked = new CopyOnWriteArrayList<>();
        final List<List<BranchName>> bases = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Unit> fetch() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<BehindFacts> behind(Sha head, BranchName base) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas) {
            return fail
                   ? Causes.cause("mirror broken").promise()
                   : Promise.success(shas.stream().collect(Collectors.toMap(sha -> sha, inMirror::contains)));
        }

        @Override
        public Promise<Ancestry> ancestry(Sha head, List<BranchName> bases) {
            asked.add(head);
            this.bases.add(bases);
            return Promise.success(ancestry);
        }
    }

    static final class FakePrs implements PrStore {
        final AtomicInteger reads = new AtomicInteger();
        volatile Option<Promise<Unit>> gate = none();

        @Override
        public Promise<List<PullRequest>> all() {
            reads.incrementAndGet();
            return gate.map(g -> g.map(_ -> List.<PullRequest> of())).or(() -> Promise.success(List.of()));
        }

        @Override
        public Promise<List<Change>> upsert(List<PullRequest> prs, Map<PrNumber, String> mergedBy, Instant observedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> openDetail(PrNumber pr, OpenDetail detail, Instant observedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> behind(PrNumber pr, Sha head, BranchName base, BehindFacts facts, Instant fetchedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Option<Instant>> highWater() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> highWater(Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Option<Instant>> backfillDone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Promise<Unit> backfillDone(Instant at) {
            throw new UnsupportedOperationException();
        }
    }
}
