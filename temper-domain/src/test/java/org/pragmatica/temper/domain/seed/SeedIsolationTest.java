package org.pragmatica.temper.domain.seed;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.Fixtures.failure;
import static org.pragmatica.temper.domain.Fixtures.ok;

/// A13 (pure part): any file with more than one link fails seed isolation.
class SeedIsolationTest {
    @Test
    void verify_accepts_whenEveryFileHasOneLink() {
        var files = List.of(FileLinks.fileLinks("org/a/a.jar", 1), FileLinks.fileLinks("org/a/a.pom", 1));

        assertThat(ok(SeedIsolation.verify(files))).isEqualTo(Unit.unit());
        assertThat(ok(SeedIsolation.verify(List.of()))).isEqualTo(Unit.unit());
    }

    @Test
    void verify_failsHardlinked_whenAnyFileHasMoreLinks() {
        var files = List.of(FileLinks.fileLinks("org/a/a.jar", 1), FileLinks.fileLinks("org/a/a.pom", 2));

        assertThat(failure(SeedIsolation.verify(files))).isInstanceOfSatisfying(SeedError.Hardlinked.class, hardlinked -> {
            assertThat(hardlinked.count()).isEqualTo(1);
            assertThat(hardlinked.firstPaths()).containsExactly("org/a/a.pom");
        });
    }

    @Test
    void verify_reportsCountAndFirstFivePaths() {
        var files = IntStream.range(0, 7)
                             .mapToObj(index -> FileLinks.fileLinks("f" + index, 2))
                             .toList();

        assertThat(failure(SeedIsolation.verify(files))).isInstanceOfSatisfying(SeedError.Hardlinked.class, hardlinked -> {
            assertThat(hardlinked.count()).isEqualTo(7);
            assertThat(hardlinked.firstPaths()).containsExactly("f0", "f1", "f2", "f3", "f4");
        });
    }
}
