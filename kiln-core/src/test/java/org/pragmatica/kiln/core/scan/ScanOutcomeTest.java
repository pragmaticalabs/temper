package org.pragmatica.kiln.core.scan;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.scan.ScanLine.NonGitLine;
import org.pragmatica.kiln.core.scan.ScanLine.StubLine;
import org.pragmatica.kiln.core.scan.ScanOutcome.Complete;
import org.pragmatica.kiln.core.scan.ScanOutcome.Incomplete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §5.2: a missing or mismatched trailer marks the scan incomplete, and an incomplete scan never makes a checkout
/// read as gone (K-4 builds on this). `count` counts checkout and stub lines; non-git lines are not counted.
class ScanOutcomeTest {
    private static final String CHECKOUT = ScanLineTest.CHECKOUT;
    private static final String STUB = "{\"kind\":\"stub\",\"path\":\"/w/co/b\",\"activityAt\":5,\"commonDir\":\"/c\"}";
    private static final String NON_GIT = "{\"kind\":\"nonGit\",\"root\":\"~/work/co/*\",\"path\":\"/w/co/c\"}";

    private static final String STUB_AT_A = STUB.replace("/w/co/b", "/w/co/a");
    private static final String NON_GIT_AT_A = NON_GIT.replace("/w/co/c", "/w/co/a");

    private static String trailer(int count, int nonGit) {
        return "{\"end\":true,\"count\":" + count + ",\"nonGit\":" + nonGit + ",\"nonGitBytes\":null}";
    }

    private static Incomplete incomplete(List<String> lines) {
        return (Incomplete) ScanOutcome.of(lines);
    }

    @Test
    void of_isComplete_whenTheLastLineIsATrailerCountingTheCheckoutAndStubLines() {
        var outcome = ScanOutcome.of(List.of(CHECKOUT, NON_GIT, STUB, trailer(2, 1)));

        assertThat(outcome).isInstanceOf(Complete.class);
        assertThat(((Complete) outcome).nonGit()).isEqualTo(1);
        assertThat(((Complete) outcome).rows()).hasSize(3)
                                               .noneMatch(ScanLine.Trailer.class::isInstance);
        assertThat(((Complete) outcome).rows()
                                       .get(1)).isInstanceOf(NonGitLine.class);
    }

    /// A root with nothing under it is a complete, empty listing: only the trailer vouches for it.
    @Test
    void of_isComplete_forAnEmptyListingWithItsTrailer() {
        assertThat(ScanOutcome.of(List.of(trailer(0, 0)))).isEqualTo(new Complete(List.of(), 0, none()));
    }

    /// `Complete` carries the trailer's `home`, so a `~` root can be expanded with the scanned host's own home; a
    /// trailer without one (an older script) gives none.
    @Test
    void of_exposesTheTrailersHome() {
        var withHome = trailer(1, 0).replace("}", ",\"home\":\"/home/builder\"}");

        assertThat(((Complete) ScanOutcome.of(List.of(STUB, withHome))).home()).isEqualTo(some("/home/builder"));
        assertThat(((Complete) ScanOutcome.of(List.of(STUB, trailer(1, 0)))).home()).isEqualTo(none());
    }

    @Test
    void of_isIncomplete_withoutATrailer_andKeepsTheRows() {
        var outcome = incomplete(List.of(CHECKOUT, STUB));

        assertThat(outcome.rows()).hasSize(2);
        assertThat(outcome.reason()).isEqualTo("No trailer as the last line");
    }

    /// An empty stream is not an empty listing (K-5 maps transport failures before this; this is the last guard).
    @Test
    void of_isIncomplete_forNoLinesAtAll() {
        assertThat(ScanOutcome.of(List.of())).isEqualTo(new Incomplete(List.of(), ScanError.General.NO_TRAILER.message()));
    }

    @Test
    void of_isIncomplete_whenTheTrailerIsNotTheLastLine() {
        var outcome = incomplete(List.of(CHECKOUT, trailer(2, 0), STUB));

        assertThat(outcome.rows()).hasSize(2);
        assertThat(outcome.reason()).isEqualTo(ScanError.General.NO_TRAILER.message());
    }

    /// Two listings run together: the last line is a trailer, but an earlier one is too.
    @Test
    void of_isIncomplete_withATrailerBeforeTheLastLine() {
        var outcome = incomplete(List.of(STUB, trailer(1, 0), STUB, trailer(1, 0)));

        assertThat(outcome.rows()).hasSize(2);
        assertThat(outcome.reason()).isEqualTo("A trailer before the last line");
    }

    @Test
    void of_isIncomplete_whenTheCountIsOffByOne() {
        assertThat(incomplete(List.of(CHECKOUT, STUB, trailer(3, 0))).reason())
            .isEqualTo("Trailer count 3, 2 checkout and stub lines");
        assertThat(incomplete(List.of(CHECKOUT, STUB, trailer(1, 0))).reason())
            .isEqualTo("Trailer count 1, 2 checkout and stub lines");
        assertThat(incomplete(List.of(CHECKOUT, STUB, trailer(1, 0))).rows()).hasSize(2);
    }

    /// The non-git list is replaced only by a Complete scan: a trailer whose `nonGit` differs from the non-git lines
    /// listed, either way, leaves the scan Incomplete.
    @Test
    void of_isIncomplete_whenNonGitDiffersFromTheNonGitLines() {
        assertThat(incomplete(List.of(STUB, NON_GIT, trailer(1, 2))).reason())
            .isEqualTo("Trailer nonGit 2, 1 non-git lines");
        assertThat(incomplete(List.of(STUB, NON_GIT, NON_GIT_AT_A, trailer(1, 1))).reason())
            .isEqualTo("Trailer nonGit 1, 2 non-git lines");
        assertThat(incomplete(List.of(STUB, trailer(1, 1))).rows()).hasSize(1);
        assertThat(ScanOutcome.of(List.of(STUB, NON_GIT, NON_GIT_AT_A, trailer(1, 2)))).isEqualTo(
            new Complete(List.of(ScanLine.decode(STUB).unwrap(), ScanLine.decode(NON_GIT).unwrap(),
                                 ScanLine.decode(NON_GIT_AT_A).unwrap()), 2, none()));
    }

    /// A path listed twice is Incomplete whatever lists it: checkout and checkout, checkout and stub, stub and stub.
    /// With one path doubled and another omitted, the count still matches, so only this check stops K-4 deleting the
    /// omitted one. The reason names the first doubled path; the rows are kept.
    @Test
    void of_isIncomplete_whenAPathIsListedTwice() {
        var twice = incomplete(List.of(CHECKOUT, CHECKOUT, trailer(2, 0)));

        assertThat(twice.reason()).isEqualTo("Scan lists path /w/co/a twice");
        assertThat(twice.rows()).hasSize(2);
        assertThat(incomplete(List.of(CHECKOUT, STUB_AT_A, trailer(2, 0))).reason())
            .isEqualTo("Scan lists path /w/co/a twice");
        assertThat(incomplete(List.of(STUB, NON_GIT, STUB, trailer(2, 1))).reason())
            .isEqualTo("Scan lists path /w/co/b twice");
        assertThat(incomplete(List.of(STUB, CHECKOUT, STUB, STUB_AT_A, trailer(4, 0))).reason())
            .isEqualTo("Scan lists path /w/co/b twice");
    }

    /// A directory cannot be both a checkout and non-git, nor non-git twice: a non-git line sharing a checkout's,
    /// a stub's or another non-git line's path is a duplicate too.
    @Test
    void of_isIncomplete_whenANonGitLineSharesAPath() {
        assertThat(incomplete(List.of(CHECKOUT, NON_GIT_AT_A, trailer(1, 1))).reason())
            .isEqualTo("Scan lists path /w/co/a twice");
        assertThat(incomplete(List.of(NON_GIT_AT_A, STUB_AT_A, trailer(1, 1))).reason())
            .isEqualTo("Scan lists path /w/co/a twice");
        assertThat(incomplete(List.of(STUB, NON_GIT, NON_GIT, trailer(1, 2))).reason())
            .isEqualTo("Scan lists path /w/co/c twice");
        assertThat(ScanOutcome.of(List.of(CHECKOUT, NON_GIT, trailer(1, 1)))).isInstanceOf(Complete.class);
        assertThat(ScanOutcome.of(List.of(NON_GIT_AT_A, NON_GIT, trailer(0, 2)))).isInstanceOf(Complete.class);
    }

    @Test
    void of_isComplete_whenEveryListedPathIsDistinct() {
        assertThat(ScanOutcome.of(List.of(CHECKOUT, STUB, trailer(2, 0)))).isInstanceOf(Complete.class);
        assertThat(ScanOutcome.of(List.of(STUB_AT_A, STUB, trailer(2, 0)))).isInstanceOf(Complete.class);
    }

    /// A line holding a key twice does not decode, so the scan is Incomplete.
    @Test
    void of_isIncomplete_onALineWithADuplicateKey() {
        assertThat(incomplete(List.of(STUB, "{\"end\":true,\"count\":5,\"count\":1,\"nonGit\":0}")).reason())
            .isEqualTo("Scan line 2: Scan line is not JSON");
        assertThat(ScanOutcome.of(List.of(STUB, "{\"end\":true,\"count\":1,\"nonGit\":0}"))).isInstanceOf(Complete.class);
    }

    /// Non-git lines are not in `count`: a trailer that counted them would not match.
    @Test
    void of_isIncomplete_whenTheCountIncludesNonGitLines() {
        assertThat(ScanOutcome.of(List.of(STUB, NON_GIT, trailer(2, 1)))).isInstanceOf(Incomplete.class);
        assertThat(ScanOutcome.of(List.of(STUB, NON_GIT, trailer(1, 1)))).isInstanceOf(Complete.class);
    }

    /// One undecodable line spoils the scan even with a matching trailer; the reason names the line, and only the rows
    /// before it are kept (decoding stops there; the STUB after it was kept before the CI OOM ruling).
    @Test
    void of_isIncomplete_onAnUndecodableLine_andKeepsTheRowsBeforeIt() {
        var outcome = incomplete(List.of(STUB, "{\"kind\":\"stub\",\"path\":\"/w/co/d\"}", NON_GIT, trailer(1, 1)));

        assertThat(outcome.rows()).containsExactly(ScanLine.decode(STUB)
                                                           .unwrap());
        assertThat(outcome.rows()
                          .getFirst()).isInstanceOf(StubLine.class);
        assertThat(outcome.reason()).isEqualTo("Scan line 2: Scan line field missing or invalid: activityAt");
    }

    @Test
    void of_isIncomplete_onAnUndecodableTrailer() {
        assertThat(incomplete(List.of(STUB, "{\"end\":true,\"count\":1}")).reason())
            .isEqualTo("Scan line 2: Scan line field missing or invalid: nonGit");
    }

    /// A blank line is no line the script writes: it spoils the scan like any other undecodable line.
    @Test
    void of_isIncomplete_onABlankLine() {
        assertThat(ScanOutcome.of(List.of(STUB, trailer(1, 0), ""))).isInstanceOf(Incomplete.class);
    }

    /// The work is bounded by the good rows, not the bad lines: decoding stops at the first line that fails. Bad lines
    /// first, then good rows and a matching trailer: no row is kept, and the reason is line 1's. Decoding every line would
    /// keep the stub and the non-git line after them.
    @Test
    void of_stopsDecodingAtTheFirstBadLine() {
        var badFirst = incomplete(List.of("", "not json", "{}", STUB, NON_GIT, trailer(1, 1)));
        var badLast = incomplete(List.of(STUB, NON_GIT, "", STUB_AT_A, trailer(2, 1)));

        assertThat(badFirst.rows()).isEmpty();
        assertThat(badFirst.reason()).startsWith("Scan line 1: ");
        assertThat(badLast.rows()).containsExactly(ScanLine.decode(STUB).unwrap(), ScanLine.decode(NON_GIT).unwrap());
        assertThat(badLast.reason()).startsWith("Scan line 3: ");
    }
}
