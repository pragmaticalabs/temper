package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.store.api.VerdictRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [T2Checks]: G9 (G9.a-h), G6's server part (G6.a, b, j) and the print order.
class T2ChecksTest {
    private static final List<String> SEEDED = List.of(".github/**", "scripts/changelog-check.sh", "tools/**", ".mvn/**", "pom.xml");
    private static final String TAIL = " (needs an explicit maintainer ruling)";

    /// G9.a-e: each seeded glob matches its path; `sub/pom.xml` (unanchored) and a `.github` sibling match nothing.
    @Test
    void g9ae_seededPaths_areCiControlChanged_subPomIsNot() {
        assertThat(List.of(".github/x", "scripts/changelog-check.sh", "tools/x", ".mvn/x", "pom.xml"))
            .allSatisfy(path -> assertThat(T2Checks.g9(1, 1, List.of(path), SEEDED))
                .containsExactly(T2Checks.Reason.reason("G9", "ci-control-changed " + path + TAIL)));
        assertThat(T2Checks.g9(4, 4, List.of("sub/pom.xml", ".githubx/y", "src/tools/x", "scripts/changelog-check.sh.bak"), SEEDED)).isEmpty();
    }

    /// `**` spans segments (zero or more); `*` stays within one.
    @Test
    void matches_doubleStarSpansSegments_singleStarDoesNot() {
        assertThat(T2Checks.matches(".github/**", ".github/workflows/ci.yml")).isTrue();
        assertThat(T2Checks.matches("a/**/b.txt", "a/b.txt")).isTrue();
        assertThat(T2Checks.matches("a/**/b.txt", "a/x/y/b.txt")).isTrue();
        assertThat(T2Checks.matches("a/*.yml", "a/ci.yml")).isTrue();
        assertThat(T2Checks.matches("a/*.yml", "a/x/ci.yml")).isFalse();
        assertThat(T2Checks.matches("a.b", "axb")).isFalse();
        assertThat(T2Checks.matches("a/**/b.txt", "a/x/c.txt")).isFalse();
        assertThat(T2Checks.matches("a/b/c", "a/b")).isFalse();
    }

    /// G9.f: a rename out of `.github/` lists the old path, which matches.
    @Test
    void g9f_renameOutOfGithub_isCiControlChanged() {
        assertThat(T2Checks.g9(1, 1, List.of("docs/ci.yml", ".github/workflows/ci.yml"), SEEDED))
            .containsExactly(T2Checks.Reason.reason("G9", "ci-control-changed .github/workflows/ci.yml" + TAIL));
    }

    /// G9.g: fewer files read than the PR changed.
    @Test
    void g9g_shortRead_isFilesReadIncomplete() {
        assertThat(T2Checks.g9(2, 3, List.of("a", "b"), SEEDED))
            .containsExactly(T2Checks.Reason.reason("G9", "files-read-incomplete 2/3"));
    }

    /// G9.h: a repo without globs fails closed.
    @Test
    void g9h_repoWithoutGlobs_isCiControlUnconfigured() {
        assertThat(T2Checks.g9(1, 1, List.of("README.md"), List.of()))
            .containsExactly(T2Checks.Reason.reason("G9", "ci-control-unconfigured"));
    }

    /// Matches are sorted and distinct; past 20, one line counts the rest.
    @Test
    void g9_manyMatches_sorted_capAt20_thenMore() {
        var paths = IntStream.rangeClosed(1, 23).mapToObj(index -> String.format("tools/f%02d", 24 - index)).toList();

        var reasons = T2Checks.g9(23, 23, paths, SEEDED);

        assertThat(reasons).hasSize(21);
        assertThat(reasons.getFirst().line()).isEqualTo("ci-control-changed tools/f01" + TAIL);
        assertThat(reasons.get(19).line()).isEqualTo("ci-control-changed tools/f20" + TAIL);
        assertThat(reasons.getLast().line()).isEqualTo("ci-control-changed … 3 more");
    }

    /// Exactly 20 matches: 20 lines, no count line.
    @Test
    void g9_exactly20Matches_noMoreLine() {
        var paths = IntStream.rangeClosed(1, 20).mapToObj(index -> String.format("tools/f%02d", index)).toList();

        assertThat(T2Checks.g9(20, 20, paths, SEEDED)).hasSize(20)
                                                      .noneMatch(reason -> reason.line().contains("more"));
    }

    /// G6.a: no row for the head (a row for the previous SHA is not this head's, so the store answers none).
    @Test
    void g6a_noVerdict_isVerdictMissing() {
        assertThat(T2Checks.g6(none(), "main")).containsExactly(T2Checks.Reason.reason("G6", "verdict-missing"));
    }

    /// G6.b: the latest row decides; a FIX (or REJECT) is that line, an ACCEPT none.
    @Test
    void g6b_fixOrReject_isThatVerdict_acceptIsNothing() {
        assertThat(T2Checks.g6(some(verdict("FIX", "main")), "main")).containsExactly(T2Checks.Reason.reason("G6", "verdict-FIX"));
        assertThat(T2Checks.g6(some(verdict("REJECT", "main")), "main")).containsExactly(T2Checks.Reason.reason("G6", "verdict-REJECT"));
        assertThat(T2Checks.g6(some(verdict("ACCEPT", "main")), "main")).isEmpty();
    }

    /// G6.j: a verdict registered at another base than the PR's.
    @Test
    void g6j_baseMismatch_isVerdictBaseMismatch() {
        assertThat(T2Checks.g6(some(verdict("ACCEPT", "dev")), "main"))
            .containsExactly(T2Checks.Reason.reason("G6", "verdict-base-mismatch dev"));
    }

    /// G1, G2, G3, G4, G5, G8, G9, G10, G6, G7; a check's lines keep their order; a check the gate does not name sorts
    /// last.
    @Test
    void ordered_isThePrintOrder() {
        var reasons = List.of(reason("GX", "unnamed"), reason("G6", "verdict-missing"), reason("G10", "required-changed"), reason("G9", "x"),
                              reason("G4", "check-red a 1"), reason("G2", "not-open closed"), reason("G4", "check-red b 2"), reason("G1", "first"));

        assertThat(T2Checks.ordered(reasons)).extracting(T2Checks.Reason::line)
                                             .containsExactly("first", "not-open closed", "check-red a 1", "check-red b 2", "x", "required-changed",
                                                              "verdict-missing", "unnamed");
    }

    private static T2Checks.Reason reason(String check, String line) {
        return T2Checks.Reason.reason(check, line);
    }

    private static VerdictRow verdict(String verdict, String base) {
        return VerdictRow.verdictRow(5, verdict, "agent", Instant.EPOCH, base, "/r.md", "d".repeat(64), none());
    }

    @Test
    void vt6_globMatchesTheWholePath_notAPrefix() {
        assertThat(T2Checks.matches(".github", ".github/workflows/ci.yml")).isFalse();
        assertThat(T2Checks.matches("tools/*", "tools/a/b")).isFalse();
        assertThat(T2Checks.matches("tools/*", "tools/a")).isTrue();
    }
}
