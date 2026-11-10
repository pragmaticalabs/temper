package org.pragmatica.kiln.service.scan;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.scan.HostScanner.Sized;
import org.pragmatica.kiln.service.store.CheckoutRow;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPgFixture;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFixture.real;
import static org.pragmatica.kiln.service.scan.ScanFixture.repo;
import static org.pragmatica.lang.Option.some;

/// The passes against the real `kiln-scan` (through [LocalScanTransport], `HOME` a `@TempDir`) into Postgres, under the
/// root `~/work/co/*`: an empty hot set gets stub lines only (ruling 5's sentinel is no full-facts request); the next hot
/// pass gives the unscanned rows facts; `size <root> <path>` writes only the size (ruling 4); a checkout removed from disk
/// is deleted by the next complete pass because `~` was expanded with the trailer's home (ruling 1).
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ScanPassesIT {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(120).seconds();
    private static final HostName BIGBOY = HostName.hostName("bigboy").unwrap();

    @TempDir
    Path dir;

    private KilnStore store;

    @BeforeEach
    void store() {
        store = KilnPgFixture.freshStore();
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close().await(WAIT);
        }
    }

    @Test
    void hotSizeAndDeletion_throughTheRealScript() throws IOException {
        var home = Files.createDirectories(dir.resolve("home"));
        var a = repo(home.resolve("work/co/a"));
        var b = repo(home.resolve("work/co/b"));
        var host = ScanHost.scanHost(BIGBOY, List.of("~/work/co/*"), ScanFixture.transport(home));
        var scanner = HostScanner.hostScanner(host, store.checkouts(), store.sources(), ScanFakes::t0, Duration.ofHours(48), () -> {});

        assertThat(hot(scanner)).isEqualTo(new SourceState.Ok(ScanFakes.T0));
        assertThat(rows()).extracting(row -> row.path().value()).containsExactly(real(a), real(b));
        assertThat(rows()).as("an empty hot set asks for stubs only").allSatisfy(row -> assertThat(row.facts().isEmpty()).isTrue());

        hot(scanner);
        assertThat(rows()).as("unscanned rows are hot").allSatisfy(row -> assertThat(row.facts().isPresent()).isTrue());

        var before = rows();
        assertThat(scanner.size().await(WAIT).unwrap().unwrap()).isInstanceOf(Sized.Measured.class);
        var after = rows();
        assertThat(after.getFirst().sizeKb().isPresent()).isTrue();
        assertThat(after.getFirst().sizeAt()).isEqualTo(some(ScanFakes.T0));
        assertThat(after.getFirst()).usingRecursiveComparison().ignoringFields("sizeKb", "sizeAt").isEqualTo(before.getFirst());
        assertThat(after.get(1)).isEqualTo(before.get(1));

        delete(b);
        assertThat(hot(scanner)).isEqualTo(new SourceState.Ok(ScanFakes.T0));
        assertThat(rows()).extracting(row -> row.path().value()).containsExactly(real(a));
    }

    /// Fix round ruling 6: a root whose fixed part vanished (here `~/work/co`, moved away) is a read error, so the pass is
    /// INCOMPLETE and every row under it survives; it was a complete, empty listing that deleted them all.
    @Test
    void aVanishedRoot_deletesNothing() throws IOException {
        var home = Files.createDirectories(dir.resolve("home"));
        var a = repo(home.resolve("work/co/a"));
        var b = repo(home.resolve("work/co/b"));
        var host = ScanHost.scanHost(BIGBOY, List.of("~/work/co/*"), ScanFixture.transport(home));
        var scanner = HostScanner.hostScanner(host, store.checkouts(), store.sources(), ScanFakes::t0, Duration.ofHours(48), () -> {});
        var paths = List.of(real(a), real(b));
        assertThat(hot(scanner)).isEqualTo(new SourceState.Ok(ScanFakes.T0));

        Files.move(home.resolve("work/co"), home.resolve("work/co-moved"));

        assertThat(hot(scanner)).isInstanceOf(SourceState.Incomplete.class);
        assertThat(rows()).extracting(row -> row.path().value()).isEqualTo(paths);
    }

    private static SourceState hot(HostScanner scanner) {
        return scanner.hot().await(WAIT).unwrap().unwrap();
    }

    private List<CheckoutRow> rows() {
        return store.checkouts().rows(BIGBOY).await(WAIT).unwrap();
    }

    private static void delete(Path tree) throws IOException {
        try (Stream<Path> paths = Files.walk(tree)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
