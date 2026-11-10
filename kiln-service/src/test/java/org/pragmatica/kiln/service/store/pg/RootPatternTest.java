package org.pragmatica.kiln.service.store.pg;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Which stored paths a pass's roots cover (§3.2 roots, §3.4 deletion). `*` matches within one component, literal text
/// is literal (regex metacharacters included), and a `~` root covers nothing (kiln does not know the host's home).
class RootPatternTest {
    @ParameterizedTest(name = "{0} covers {1}: {2}")
    @CsvSource({"/home/builder/work/co/*, /home/builder/work/co/x, true",
                "/home/builder/work/co/*, /home/builder/work/co/x/y, false",
                "/home/builder/work/co/*, /home/builder/work/co, false",
                "/home/builder/work/co/*, /home/builder/work/cox/y, false",
                "/home/builder/work/co/*, /other/work/co/x, false",
                "~/work/co/*, /home/builder/work/co/x, false",
                "~/work/co/*, /Users/me/work/co/x, false",
                "~/work/co/*, /work/co/x, false",
                "~/work/co/*, /srv/co/x, false",
                "~/src/pragmatica*, /Users/me/src/pragmatica, false",
                "/Users/me/src/pragmatica*, /Users/me/src/pragmatica-lite, true",
                "/Users/me/src/pragmatica*, /Users/me/src/other, false",
                "~/src/ticketing, /Users/me/src/ticketing, false",
                "/Users/me/src/ticketing, /Users/me/src/ticketing2, false",
                "/private/tmp/scratch-501/*/*/work/*, /private/tmp/scratch-501/a/b/work/c, true",
                "/private/tmp/scratch-501/*/*/work/*, /private/tmp/scratch-501/a/work/c, false",
                "/w/a.b/*, /w/a.b/x, true",
                "/w/a.b/*, /w/aXb/x, false",
                "/w/(x)+/*, /w/(x)+/y, true",
                "/w/(x)+/*, /w/xx/y, false"})
    void pattern_coversExactlyTheRootsMatches(String root, String path, boolean covered) {
        assertThat(RootPattern.covered(RootPattern.patterns(List.of(root)), path)).isEqualTo(covered);
    }

    @Test
    void covered_isAnyRoot_andNoRootCoversNothing() {
        var roots = RootPattern.patterns(List.of("/a/*", "/b/*"));

        assertThat(RootPattern.covered(roots, "/b/x")).isTrue();
        assertThat(RootPattern.covered(roots, "/c/x")).isFalse();
        assertThat(RootPattern.covered(List.of(), "/a/x")).isFalse();
    }

    /// The deletion set: covered and not listed; a listed path and an uncovered one both stay.
    @Test
    void absent_isCoveredAndUnlisted() {
        var roots = RootPattern.patterns(List.of("/a/*"));

        assertThat(PgCheckoutStore.absent(roots, Set.of("/a/kept"), List.of("/a/kept", "/a/gone", "/b/other"))).containsExactly("/a/gone");
        assertThat(PgCheckoutStore.absentNonGit(roots,
                                                Set.of(new PgCheckoutStore.NonGit("/a/*", "/a/kept")),
                                                List.of(new PgCheckoutStore.NonGit("/a/*", "/a/kept"),
                                                        new PgCheckoutStore.NonGit("/a/*", "/a/gone"),
                                                        new PgCheckoutStore.NonGit("/b/*", "/b/other"))))
            .containsExactly(new PgCheckoutStore.NonGit("/a/*", "/a/gone"));
    }

    @Test
    void pages_isAtLeastOne_andRoundsUp() {
        assertThat(PgViewStore.pages(0)).isEqualTo(1);
        assertThat(PgViewStore.pages(100)).isEqualTo(1);
        assertThat(PgViewStore.pages(101)).isEqualTo(2);
    }
}
