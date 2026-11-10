package org.pragmatica.kiln.service.scan;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling 1: a `~/` root is expanded with the trailer's home before `applyPass`; without a usable home it is passed on as
/// is (the store reads it as upsert-only).
class ScanRootsTest {
    private static final List<String> ROOTS = List.of("~/work/co/*", "/srv/co/*", "~/src/pragmatica*");

    @Test
    void withHome_everyTildeRootIsExpanded_andAbsoluteRootsStay() {
        assertThat(ScanRoots.expanded(ROOTS, some("/home/builder")))
            .containsExactly("/home/builder/work/co/*", "/srv/co/*", "/home/builder/src/pragmatica*");
    }

    @Test
    void withoutHome_tildeRootsArePassedOnUnexpanded() {
        assertThat(ScanRoots.expanded(ROOTS, none())).isEqualTo(ROOTS);
    }

    @Test
    void aHomeThatIsNotAnAbsoluteCanonicalPath_isNotUsed() {
        assertThat(ScanRoots.expanded(ROOTS, some("/"))).isEqualTo(ROOTS);
        assertThat(ScanRoots.expanded(ROOTS, some("home/builder"))).isEqualTo(ROOTS);
        assertThat(ScanRoots.expanded(ROOTS, some("/home/builder/"))).isEqualTo(ROOTS);
        assertThat(ScanRoots.expanded(ROOTS, some("/home/../etc"))).isEqualTo(ROOTS);
    }

    @Test
    void onlyATildeSlashPrefixIsExpanded() {
        assertThat(ScanRoots.expanded(List.of("~x/co/*", "/a/~/b"), some("/home/builder"))).containsExactly("~x/co/*", "/a/~/b");
    }
}
