package org.pragmatica.kiln.service.scan;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.scan.HostScanner.Sized;
import org.pragmatica.kiln.service.scan.ScanFakes.RecordingSources;
import org.pragmatica.kiln.service.scan.ScanFakes.RecordingStore;
import org.pragmatica.kiln.service.scan.ScanFakes.ScriptedTransport;
import org.pragmatica.kiln.service.scan.ScanTransportError.NoLines;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.kiln.service.scan.ScanFakes.HOST;
import static org.pragmatica.kiln.service.scan.ScanFakes.T0;
import static org.pragmatica.kiln.service.scan.ScanFakes.listing;
import static org.pragmatica.kiln.service.scan.ScanFakes.path;
import static org.pragmatica.kiln.service.scan.ScanFakes.row;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [HostScanner] over fakes: the rulings of kiln WP8 that need no database (each test names its ruling).
class HostScannerTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(20).seconds();
    private static final Duration STEP = Duration.ofMillis(400);
    private static final List<String> ROOTS = List.of("~/work/co/*", "/srv/co/*");

    private final RecordingStore store = new RecordingStore();
    private final RecordingSources sources = new RecordingSources();
    private final ScriptedTransport transport = new ScriptedTransport();
    private final AtomicInteger passes = new AtomicInteger();

    private HostScanner scanner(ScanHost host) {
        return HostScanner.hostScanner(host, store, sources, () -> T0, Duration.ofHours(48), STEP, STEP.multipliedBy(10), passes::incrementAndGet);
    }

    private ScanHost host() {
        return ScanHost.scanHost(HOST, ROOTS, transport);
    }

    private HostScanner scanner() {
        return scanner(host());
    }

    private static <T> T await(Promise<Option<T>> promise) {
        return promise.await(WAIT).unwrap().unwrap();
    }

    // ---- Ruling 1: ~ expanded with the trailer's home ---------------------------------------------------------
    @Test
    void ruling1_completePass_expandsTildeRoots_withTheTrailersHome() {
        transport.thenLines(listing(some("/home/builder"), "/home/builder/work/co/a"));

        assertThat(await(scanner().hot())).isEqualTo(new SourceState.Ok(T0));
        assertThat(store.applied.getFirst().roots()).containsExactly("/home/builder/work/co/*", "/srv/co/*");
    }

    @Test
    void ruling1_completePass_withoutHome_passesTildeRootsOnUnexpanded() {
        transport.thenLines(listing(none(), "/srv/co/a"));

        await(scanner().hot());

        assertThat(store.applied.getFirst().roots()).containsExactly("~/work/co/*", "/srv/co/*");
    }

    @Test
    void ruling1_incompletePass_neverExpands() {
        transport.thenLines(List.of("{\"kind\":\"stub\",\"path\":\"/srv/co/a\",\"activityAt\":1,\"commonDir\":\"/srv/co/a/.git\"}"));

        assertThat(await(scanner().hot())).isInstanceOf(SourceState.Incomplete.class);
        assertThat(store.applied.getFirst().roots()).isEqualTo(ROOTS);
        assertThat(store.applied.getFirst().outcome()).isInstanceOf(ScanOutcome.Incomplete.class);
    }

    // ---- Ruling 3: a transport failure is never a Complete outcome --------------------------------------------
    @Test
    void ruling3_ssh255_recordsUnreachable_andTouchesNoRow() {
        transport.then(() -> NonZero.FACTORY.apply("scan", 255, "ssh: connect to host bigboy port 22: Connection refused").promise());

        var state = await(scanner().hot());

        assertThat(state).isInstanceOf(SourceState.Unreachable.class);
        assertThat(((SourceState.Unreachable) state).reason()).contains("exited 255");
        assertThat(store.applied).isEmpty();
        assertThat(store.failed).singleElement().satisfies(failed -> assertThat(failed.reason()).contains("255"));
        assertThat(sources.last()).isEqualTo(state);
        assertThat(passes).hasValue(0);
    }

    @Test
    void ruling3_nonZeroRc_emptyStream_andTimeout_areEachUnreachable() {
        transport.then(() -> NonZero.FACTORY.apply("scan", 1, "boom").promise())
                 .then(() -> NoLines.FACTORY.apply("scan").promise())
                 .then(Promise::promise)
                 .then(() -> ScanTransportError.TooLarge.FACTORY.apply("scan", ScanExec.MAX_BYTES).promise());
        var scanner = scanner();

        assertThat(await(scanner.hot())).isInstanceOf(SourceState.Unreachable.class);
        assertThat(await(scanner.hot())).isInstanceOf(SourceState.Unreachable.class);
        assertThat(await(scanner.hot())).isInstanceOf(SourceState.Unreachable.class);
        assertThat(await(scanner.hot())).isInstanceOf(SourceState.Unreachable.class);
        assertThat(store.applied).isEmpty();
        assertThat(store.failed).hasSize(4);
    }

    @Test
    void ruling3_aThrowingTransport_isUnreachable() {
        transport.then(() -> {
            throw new IllegalStateException("transport bug");
        });

        assertThat(await(scanner().hot())).isInstanceOf(SourceState.Unreachable.class);
        assertThat(store.applied).isEmpty();
    }

    @Test
    void ruling3_exit74_isAnIncompletePass_withNoRows() {
        transport.then(() -> NonZero.FACTORY.apply("scan", 74, "cannot read /w/co/x").promise());

        assertThat(await(scanner().hot())).isInstanceOf(SourceState.Incomplete.class);
        assertThat(store.failed).isEmpty();
        assertThat(store.applied).singleElement().satisfies(applied -> {
            assertThat(applied.outcome()).isInstanceOf(ScanOutcome.Incomplete.class);
            assertThat(((ScanOutcome.Incomplete) applied.outcome()).rows()).isEmpty();
        });
    }

    @Test
    void ruling3_aFailureThatGoesOn_keepsTheTimeItBegan() {
        var clock = new AtomicReference<>(T0);
        transport.then(() -> NonZero.FACTORY.apply("scan", 255, "down").promise());
        var scanner = HostScanner.hostScanner(host(), store, sources, clock::get, Duration.ofHours(48), STEP, STEP, passes::incrementAndGet);

        await(scanner.hot());
        clock.set(T0.plusSeconds(120));
        var second = (SourceState.Unreachable) await(scanner.hot());

        assertThat(second.since()).isEqualTo(T0);
    }

    @Test
    void ruling3_afterSuccess_aNewFailureBeginsNow() {
        var clock = new AtomicReference<>(T0);
        transport.thenLines(listing(none(), "/srv/co/a"))
                 .then(() -> NonZero.FACTORY.apply("scan", 255, "down").promise());
        var scanner = HostScanner.hostScanner(host(), store, sources, clock::get, Duration.ofHours(48), STEP, STEP, passes::incrementAndGet);

        await(scanner.hot());
        clock.set(T0.plusSeconds(120));

        assertThat(((SourceState.Unreachable) await(scanner.hot())).since()).isEqualTo(T0.plusSeconds(120));
    }

    // ---- Ruling 5 and Q-2: the hot set on stdin; the full pass in chunks of 100 ---------------------------------
    @Test
    void ruling5_hotPass_sendsTheHotSet() {
        store.hot = List.of(path("/srv/co/a"), path("/srv/co/b"));
        transport.thenLines(listing(none(), "/srv/co/a", "/srv/co/b"));

        await(scanner().hot());

        assertThat(transport.calls("scan").getFirst().full()).containsExactly(path("/srv/co/a"), path("/srv/co/b"));
        assertThat(transport.calls("scan").getFirst().words()).isEqualTo(ROOTS);
        assertThat(store.applied.getFirst().kind()).isEqualTo(PassKind.HOT);
        assertThat(passes).hasValue(1);
    }

    @Test
    void ruling5_anEmptyHotSet_asksForStubsOnly_neverForEveryCheckout() {
        transport.thenLines(listing(none(), "/srv/co/a"));

        await(scanner().hot());

        assertThat(transport.calls("scan").getFirst().full()).containsExactly(HostScanner.STUBS_ONLY);
    }

    @Test
    void ruling5_fullPass_sendsChunksOf100_andRecordsKofN() {
        store.rows = IntStream.range(0, 250).mapToObj(index -> row("/srv/co/r%03d".formatted(index))).toList();
        transport.thenLines(listing(none(), "/srv/co/a"));

        assertThat(await(scanner().full())).isEqualTo(new SourceState.Ok(T0));

        var chunks = transport.calls("scan").stream().map(ScanFakes.Call::full).toList();
        assertThat(chunks).extracting(List::size).containsExactly(100, 100, 50);
        assertThat(chunks.get(0).getFirst()).isEqualTo(path("/srv/co/r000"));
        assertThat(chunks.get(1).getFirst()).isEqualTo(path("/srv/co/r100"));
        assertThat(chunks.get(2).getLast()).isEqualTo(path("/srv/co/r249"));
        assertThat(store.progress).extracting(ScanFakes.Progress::k, ScanFakes.Progress::n)
                                  .containsExactly(tuple(1, 3),
                                                   tuple(2, 3),
                                                   tuple(3, 3));
        assertThat(store.applied).extracting(ScanFakes.Applied::kind).containsOnly(PassKind.FULL);
        assertThat(passes).hasValue(3);
    }

    @Test
    void ruling5_fullPass_ofAnExactMultipleOf100_hasNoEmptyChunk() {
        store.rows = IntStream.range(0, 200).mapToObj(index -> row("/srv/co/r%03d".formatted(index))).toList();
        transport.thenLines(listing(none(), "/srv/co/a"));

        await(scanner().full());

        assertThat(transport.calls("scan")).extracting(call -> call.full().size()).containsExactly(100, 100);
        assertThat(store.progress).extracting(ScanFakes.Progress::n).containsOnly(2);
    }

    @Test
    void ruling5_fullPass_withNoKnownCheckout_isOneChunkGivingEveryCheckoutFacts() {
        transport.thenLines(listing(none(), "/srv/co/a"));

        await(scanner().full());

        assertThat(transport.calls("scan")).singleElement().satisfies(call -> assertThat(call.full()).isEmpty());
        assertThat(store.progress).singleElement().satisfies(progress -> assertThat(progress.n()).isEqualTo(1));
    }

    @Test
    void ruling5_fullPass_stopsAtTheFirstChunkThatIsNotComplete() {
        store.rows = IntStream.range(0, 250).mapToObj(index -> row("/srv/co/r%03d".formatted(index))).toList();
        transport.thenLines(listing(none(), "/srv/co/a"))
                 .then(() -> NonZero.FACTORY.apply("scan", 255, "down").promise());

        assertThat(await(scanner().full())).isInstanceOf(SourceState.Unreachable.class);
        assertThat(transport.calls("scan")).hasSize(2);
        assertThat(store.progress).extracting(ScanFakes.Progress::k).containsExactly(1);
        assertThat(store.failed).singleElement().satisfies(failed -> assertThat(failed.kind()).isEqualTo(PassKind.FULL));
    }

    // ---- Ruling 2: one pass per host; single-flight with a deadline --------------------------------------------
    @Test
    void ruling2_hotFullAndSizeOfOneHost_neverOverlap() {
        store.rows = List.of(row("/srv/co/a"));
        transport.then(() -> delayed(listing(none(), "/srv/co/a"), 100));
        var scanner = scanner();

        var hot = scanner.hot();
        var full = scanner.full();
        var size = scanner.size();

        hot.await(WAIT);
        full.await(WAIT);
        size.await(WAIT);
        assertThat(transport.calls).hasSizeGreaterThanOrEqualTo(3);
        assertThat(transport.maxOpen).hasValue(1);
    }

    @Test
    void ruling2_aTickWhileTheLoopRuns_isSkipped_andCounted() {
        var gate = Promise.<List<String>> promise();
        transport.then(() -> gate);
        var scanner = scanner();

        var first = scanner.hot();
        var second = scanner.hot().await(WAIT).unwrap();

        assertThat(second).isEqualTo(none());
        assertThat(scanner.skipped()).containsExactly(1L, 0L, 0L);
        gate.succeed(listing(none(), "/srv/co/a"));
        assertThat(first.await(WAIT).unwrap()).isEqualTo(some(new SourceState.Ok(T0)));
    }

    @Test
    void ruling2_aStepThatNeverSettles_isFailed_andFreesTheHostAndTheLoop() {
        store.gate = some(Promise::promise);
        var scanner = scanner();

        var stuck = scanner.hot().await(WAIT);

        assertThat(stuck.isFailure()).isTrue();
        store.gate = none();
        transport.thenLines(listing(none(), "/srv/co/a"));
        assertThat(await(scanner.hot())).isEqualTo(new SourceState.Ok(T0));
    }

    @Test
    void ruling2_aThrowingStore_failsTheTick_andFreesTheNext() {
        store.gate = some(() -> {
            throw new IllegalStateException("store bug");
        });
        var scanner = scanner();

        assertThat(scanner.hot().await(WAIT).isFailure()).isTrue();
        store.gate = none();
        transport.thenLines(listing(none(), "/srv/co/a"));
        assertThat(await(scanner.hot())).isEqualTo(new SourceState.Ok(T0));
    }

    @Test
    void ruling2_aStepQueuedBehindAnExpiredTick_doesNothing() {
        var blocker = Promise.<Unit> promise();
        var host = host();
        host.exclusive(() -> blocker, Duration.ofSeconds(30));
        var scanner = scanner(host);

        assertThat(scanner.hot().await(WAIT).isFailure()).isTrue();
        blocker.succeed(Unit.unit());
        transport.thenLines(listing(none(), "/srv/co/a"));
        assertThat(await(scanner.hot())).isEqualTo(new SourceState.Ok(T0));
        assertThat(store.calls.stream().filter("hotSet"::equals)).hasSize(1);
    }

    // ---- Ruling 4: size <root> <path>, the root the path is listed under; only size_kb and size_at ---------------
    @Test
    void ruling4_size_picksTheConfiguredRootThePathIsListedUnder() {
        store.rows = List.of(row("/srv/co/a"));
        transport.thenLines(List.of("{\"path\":\"/srv/co/a\",\"kb\":42}"));

        assertThat(await(scanner().size())).isEqualTo(new Sized.Measured(path("/srv/co/a"), 42));
        assertThat(transport.calls("size").getFirst().words()).containsExactly("/srv/co/*", "/srv/co/a");
        assertThat(store.sizes).singleElement().isEqualTo(new ScanFakes.Size(HOST, path("/srv/co/a"), 42, T0));
        assertThat(store.calls).doesNotContain("applyPass", "passFailed", "progress");
    }

    @Test
    void ruling4_aTildeRoot_isMatchedWithTheLastHome_andSentAsConfigured() {
        transport.thenLines(listing(some("/home/builder"), "/home/builder/work/co/a"))
                 .thenLines(List.of("{\"path\":\"/home/builder/work/co/a\",\"kb\":7}"));
        var scanner = scanner();
        store.rows = List.of(row("/home/builder/work/co/a"));

        assertThat(await(scanner.size())).isEqualTo(new Sized.Idle());
        await(scanner.hot());

        assertThat(await(scanner.size())).isEqualTo(new Sized.Measured(path("/home/builder/work/co/a"), 7));
        assertThat(transport.calls("size").getFirst().words()).containsExactly("~/work/co/*", "/home/builder/work/co/a");
    }

    @Test
    void ruling4_nullSizesFirst_thenTheOldest() {
        store.rows = List.of(row("/srv/co/old", some(T0.minusSeconds(60))),
                             row("/srv/co/new", some(T0)),
                             row("/srv/co/never"));
        transport.thenLines(List.of("{\"path\":\"/srv/co/never\",\"kb\":1}"));

        assertThat(await(scanner().size())).isEqualTo(new Sized.Measured(path("/srv/co/never"), 1));

        store.rows = List.of(row("/srv/co/new", some(T0)), row("/srv/co/old", some(T0.minusSeconds(60))));
        transport.thenLines(List.of("{\"path\":\"/srv/co/old\",\"kb\":1}"));
        assertThat(await(scanner().size())).isEqualTo(new Sized.Measured(path("/srv/co/old"), 1));
    }

    @Test
    void ruling4_aRefusedPath_goesToTheBackOfTheQueue_soItCannotStarveTheOthers() {
        var clock = new AtomicReference<>(T0);
        var scanner = HostScanner.hostScanner(host(), store, sources, clock::get, Duration.ofHours(48), STEP, STEP, passes::incrementAndGet);
        store.rows = List.of(row("/srv/co/a"), row("/srv/co/b"), row("/srv/co/c", some(T0.minusSeconds(10))));
        transport.then(() -> NonZero.FACTORY.apply("size", 74, "du failed").promise())
                 .thenLines(List.of("{\"path\":\"/srv/co/b\",\"kb\":3}"))
                 .thenLines(List.of("{\"path\":\"/srv/co/c\",\"kb\":4}"))
                 .thenLines(List.of("{\"path\":\"/srv/co/a\",\"kb\":5}"));

        assertThat(await(scanner.size())).isInstanceOf(Sized.Refused.class);
        clock.set(T0.plusSeconds(5));
        assertThat(await(scanner.size())).isEqualTo(new Sized.Measured(path("/srv/co/b"), 3));
        store.rows = List.of(row("/srv/co/a"), row("/srv/co/b", some(T0.plusSeconds(5))), row("/srv/co/c", some(T0.minusSeconds(10))));
        assertThat(await(scanner.size())).isEqualTo(new Sized.Measured(path("/srv/co/c"), 4));
        store.rows = List.of(row("/srv/co/a"), row("/srv/co/b", some(T0.plusSeconds(5))), row("/srv/co/c", some(T0.plusSeconds(5))));
        assertThat(await(scanner.size())).isEqualTo(new Sized.Measured(path("/srv/co/a"), 5));
    }

    @Test
    void ruling4_aRefusalAfterTheLastSize_ordersThePath_andASizeAfterTheRefusalOrdersItAgain() {
        var clock = new AtomicReference<>(T0);
        var scanner = HostScanner.hostScanner(host(), store, sources, clock::get, Duration.ofHours(48), STEP, STEP, passes::incrementAndGet);
        store.rows = List.of(row("/srv/co/a", some(T0.minusSeconds(100))), row("/srv/co/b", some(T0.minusSeconds(50))));
        transport.then(() -> NonZero.FACTORY.apply("size", 74, "du failed").promise())
                 .thenLines(List.of("{\"path\":\"/srv/co/b\",\"kb\":3}"))
                 .thenLines(List.of("{\"path\":\"/srv/co/d\",\"kb\":4}"));

        assertThat(await(scanner.size())).isInstanceOf(Sized.Refused.class);
        assertThat(await(scanner.size())).as("a now counts as tried at T0").isEqualTo(new Sized.Measured(path("/srv/co/b"), 3));

        store.rows = List.of(row("/srv/co/a", some(T0.plusSeconds(20))), row("/srv/co/d", some(T0.plusSeconds(10))));
        assertThat(await(scanner.size())).as("a was measured after its refusal").isEqualTo(new Sized.Measured(path("/srv/co/d"), 4));
    }

    @Test
    void ruling4_aPathRefusedTwice_staysQueued() {
        store.rows = List.of(row("/srv/co/a"));
        transport.then(() -> NonZero.FACTORY.apply("size", 66, "gone").promise());
        var scanner = scanner();

        assertThat(await(scanner.size())).isInstanceOf(Sized.Refused.class);
        assertThat(await(scanner.size())).isInstanceOf(Sized.Refused.class);
        assertThat(transport.calls("size")).hasSize(2);
    }

    @Test
    void ruling4_equalAges_breakByPath() {
        store.rows = List.of(row("/srv/co/b"), row("/srv/co/a"));
        transport.thenLines(List.of("{\"path\":\"/srv/co/a\",\"kb\":1}"));

        assertThat(await(scanner().size())).isEqualTo(new Sized.Measured(path("/srv/co/a"), 1));
    }

    @Test
    void ruling4_aPathUnderNoConfiguredRoot_isNeverSized() {
        store.rows = List.of(row("/elsewhere/a"));

        assertThat(await(scanner().size())).isEqualTo(new Sized.Idle());
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void ruling4_anAnswerNamingAnotherPath_isRefused_andWritesNothing() {
        store.rows = List.of(row("/srv/co/a"));
        transport.thenLines(List.of("{\"path\":\"/srv/co/b\",\"kb\":42}"));

        assertThat(await(scanner().size())).isInstanceOf(Sized.Refused.class);
        assertThat(store.sizes).isEmpty();
    }

    private static Promise<List<String>> delayed(List<String> lines, long millis) {
        return Promise.promise(TimeSpan.timeSpan(millis).millis(), promise -> promise.succeed(lines));
    }
}
