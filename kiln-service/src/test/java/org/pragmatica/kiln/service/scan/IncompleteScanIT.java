package org.pragmatica.kiln.service.scan;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.scan.ScanFakes.ScriptedTransport;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPgFixture;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFakes.HOST;
import static org.pragmatica.kiln.service.scan.ScanFakes.T0;
import static org.pragmatica.kiln.service.scan.ScanFakes.listing;
import static org.pragmatica.lang.Option.none;

/// K-4 (kiln-f1-design §7.2): a scan that cannot vouch for what it did not list deletes nothing. A [ScanTransport] fake
/// answers a complete listing of three checkouts, then the same scan truncated (no trailer, one row), then one with
/// `count` off by one (two rows, `count` 3); each later pass goes through [HostScanner] into Postgres, and every row
/// stays. The control: a complete listing of one row does delete the other two.
///
/// Mutation (recorded in the WP8 state file): remove the trailer check in `ScanOutcome.of` (a listing without a trailer
/// reads Complete): the truncated listing deletes `b` and `c`, and [#aTruncatedListing_deletesNothing] goes red. Remove
/// the count check: [#aListingWithItsCountOffByOne_deletesNothing] goes red.
class IncompleteScanIT {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();
    private static final List<String> ROOTS = List.of("/w/co/*");
    private static final List<String> COMPLETE = listing(none(), "/w/co/a", "/w/co/b", "/w/co/c");

    private final ScriptedTransport transport = new ScriptedTransport();
    private KilnStore store;
    private HostScanner scanner;

    @BeforeEach
    void scanner() {
        store = KilnPgFixture.freshStore();
        scanner = HostScanner.hostScanner(ScanHost.scanHost(HOST, ROOTS, transport),
                                          store.checkouts(),
                                          store.sources(),
                                          () -> T0,
                                          Duration.ofHours(48),
                                          () -> {});
        transport.thenLines(COMPLETE);
        assertThat(hot()).isEqualTo(new SourceState.Ok(T0));
        assertThat(paths()).containsExactly("/w/co/a", "/w/co/b", "/w/co/c");
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close().await(WAIT);
        }
    }

    @Test
    void aTruncatedListing_deletesNothing() {
        transport.thenLines(COMPLETE.subList(0, 1));

        assertThat(hot()).isInstanceOf(SourceState.Incomplete.class);
        assertThat(paths()).containsExactly("/w/co/a", "/w/co/b", "/w/co/c");
    }

    @Test
    void aListingWithItsCountOffByOne_deletesNothing() {
        transport.thenLines(List.of(COMPLETE.get(0), COMPLETE.get(1), COMPLETE.getLast()));

        assertThat(hot()).isInstanceOf(SourceState.Incomplete.class);
        assertThat(paths()).containsExactly("/w/co/a", "/w/co/b", "/w/co/c");
    }

    @Test
    void control_aCompleteListing_deletesTheAbsentRows() {
        transport.thenLines(listing(none(), "/w/co/a"));

        assertThat(hot()).isEqualTo(new SourceState.Ok(T0));
        assertThat(paths()).containsExactly("/w/co/a");
    }

    private SourceState hot() {
        return await(scanner.hot()).unwrap();
    }

    private List<String> paths() {
        return await(store.checkouts().rows(HOST)).stream().map(row -> row.path().value()).toList();
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await(WAIT).unwrap();
    }
}
