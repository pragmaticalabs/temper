package org.pragmatica.kiln.service.scan;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.scan.ScanOutcome;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.scan.ScanFakes.RecordingSources;
import org.pragmatica.kiln.service.scan.ScanFakes.RecordingStore;
import org.pragmatica.kiln.service.store.PassKind;
import org.pragmatica.kiln.service.store.PassOutcome;
import org.pragmatica.kiln.service.store.PassRecord;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFakes.T0;
import static org.pragmatica.kiln.service.scan.ScanFakes.listing;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling 6: the Mac's push is the same `applyPass` as a host's, with host `mac` and kind `push`, no reach and no size,
/// and its answer is `{"outcome":…,"rows":N}`.
class MacIngestTest {
    private static final List<String> ROOTS = List.of("~/src/pragmatica*", "/private/tmp/scratch-501/*/*/work/*");

    private final RecordingStore store = new RecordingStore();
    private final RecordingSources sources = new RecordingSources();
    private final AtomicInteger passes = new AtomicInteger();
    private final MacIngest ingest = MacIngest.macIngest(store, sources, ROOTS, () -> T0, passes::incrementAndGet);

    @Test
    void aCompletePush_isAppliedAsTheMacsPushPass_withTildeExpandedByItsHome() {
        var record = ingest.ingest(body(listing(some("/Users/kiln"), "/Users/kiln/src/pragmatica-a"))).await().unwrap();

        assertThat(record).isEqualTo(new PassRecord(PassOutcome.COMPLETE, 1));
        assertThat(store.applied).singleElement().satisfies(applied -> {
            assertThat(applied.host()).isEqualTo(HostName.MAC);
            assertThat(applied.kind()).isEqualTo(PassKind.PUSH);
            assertThat(applied.roots()).containsExactly("/Users/kiln/src/pragmatica*", "/private/tmp/scratch-501/*/*/work/*");
            assertThat(applied.outcome()).isInstanceOf(ScanOutcome.Complete.class);
            assertThat(applied.at()).isEqualTo(T0);
        });
        assertThat(sources.recorded).singleElement().isEqualTo(new ScanFakes.Recorded(Source.MAC, new SourceState.Ok(T0)));
        assertThat(store.calls).containsExactly("applyPass");
        assertThat(passes).hasValue(1);
    }

    @Test
    void aTruncatedPush_isIncomplete_andItsRootsStayUnexpanded() {
        var lines = listing(some("/Users/kiln"), "/Users/kiln/src/pragmatica-a");
        var record = ingest.ingest(body(lines.subList(0, 1))).await().unwrap();

        assertThat(record.outcome()).isEqualTo(PassOutcome.INCOMPLETE);
        assertThat(store.applied.getFirst().roots()).isEqualTo(ROOTS);
        assertThat(sources.last()).isInstanceOf(SourceState.Incomplete.class);
    }

    @Test
    void anEmptyPush_isIncomplete_neverAnEmptyComplete() {
        assertThat(ingest.ingest("").await().unwrap().outcome()).isEqualTo(PassOutcome.INCOMPLETE);
        assertThat(store.applied.getFirst().outcome()).isInstanceOf(ScanOutcome.Incomplete.class);
    }

    @Test
    void aPushWithoutHome_passesTildeRootsOn() {
        ingest.ingest(body(listing(none(), "/private/tmp/scratch-501/a/b/work/c"))).await().unwrap();

        assertThat(store.applied.getFirst().roots()).isEqualTo(ROOTS);
    }

    @Test
    void aPushOver8MiB_isRefused_andWritesNothing() {
        var big = "x".repeat(MacIngest.MAX_BYTES + 1);

        assertThat(ingest.ingest(big).await().isFailure()).isTrue();
        assertThat(ingest.ingest("x".repeat(MacIngest.MAX_BYTES)).await().isSuccess()).isTrue();
        assertThat(store.applied).hasSize(1);
    }

    @Test
    void pushesAreAppliedOneAtATime() throws InterruptedException {
        var gate = Promise.<Unit> promise();
        store.gate = some(() -> gate);
        var lines = body(listing(none(), "/private/tmp/scratch-501/a/b/work/c"));

        var first = ingest.ingest(lines);
        var second = ingest.ingest(lines);

        awaitApplied(1);
        Thread.sleep(300);
        assertThat(store.applied).hasSize(1);
        gate.succeed(Unit.unit());
        first.await().unwrap();
        second.await().unwrap();
        assertThat(store.applied).hasSize(2);
    }

    @Test
    void theAnswer_isOutcomeAndRows() {
        assertThat(MacIngest.answer(new PassRecord(PassOutcome.COMPLETE, 12))).isEqualTo("{\"outcome\":\"COMPLETE\",\"rows\":12}");
        assertThat(MacIngest.answer(new PassRecord(PassOutcome.INCOMPLETE, 0))).isEqualTo("{\"outcome\":\"INCOMPLETE\",\"rows\":0}");
    }

    private void awaitApplied(int count) throws InterruptedException {
        for (var tries = 0; tries < 200 && store.applied.size() < count; tries++) {
            Thread.sleep(25);
        }
    }

    private static String body(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }
}
