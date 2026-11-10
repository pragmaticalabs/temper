package org.pragmatica.kiln.core.summary;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §7: every observed change of a PR's state or a required-check cell. A change needs two observations: a first
/// sight, or a value gone from the newer snapshot, is none.
class ChangesTest {
    private static final Instant AT = Instant.parse("2026-10-30T03:00:00Z");
    private static final PrNumber ONE = ok(PrNumber.prNumber(1));
    private static final PrNumber TWO = ok(PrNumber.prNumber(2));

    private static PrSnapshot states(Map<PrNumber, PrState> states) {
        return new PrSnapshot(states, Map.of(), Map.of());
    }

    private static PrSnapshot cells(Map<PrNumber, Map<String, String>> cells) {
        return new PrSnapshot(Map.of(), cells, Map.of());
    }

    private static PrSnapshot.Head head(long n, String base) {
        return new PrSnapshot.Head(sha(n), ok(BranchName.branchName(base)));
    }

    private static Sha sha(long n) {
        return ok(Sha.sha(String.format("%040x", n)));
    }

    private static PrSnapshot heads(Map<PrNumber, PrState> states, Map<PrNumber, PrSnapshot.Head> heads) {
        return new PrSnapshot(states, Map.of(), heads);
    }

    @Test
    void between_findsAStateChange() {
        assertThat(Changes.between(states(Map.of(ONE, PrState.OPEN)), states(Map.of(ONE, PrState.MERGED)), AT)).containsExactly(new Change(AT,
                                                                                                                                         ONE,
                                                                                                                                         ChangeKind.PR_STATE,
                                                                                                                                         "#1",
                                                                                                                                         "open",
                                                                                                                                         "merged",
                                                                                                                                         AT));
    }

    @Test
    void between_findsACellChange() {
        assertThat(Changes.between(cells(Map.of(ONE, Map.of("build", "pending", "lint", "success"))),
                                   cells(Map.of(ONE, Map.of("build", "success", "lint", "success"))),
                                   AT)).containsExactly(new Change(AT, ONE, ChangeKind.CHECK_CELL, "build", "pending", "success", AT));
    }

    @Test
    void between_findsNothing_whenNothingChanged() {
        var snapshot = new PrSnapshot(Map.of(ONE, PrState.OPEN), Map.of(ONE, Map.of("build", "success")), Map.of(ONE, head(1, "main")));

        assertThat(Changes.between(snapshot, snapshot, AT)).isEmpty();
    }

    @Test
    void between_findsNothing_onAFirstSightOrAGoneValue() {
        assertThat(Changes.between(states(Map.of()), states(Map.of(ONE, PrState.OPEN)), AT)).isEmpty();
        assertThat(Changes.between(states(Map.of(ONE, PrState.OPEN)), states(Map.of()), AT)).isEmpty();
        assertThat(Changes.between(cells(Map.of()), cells(Map.of(ONE, Map.of("build", "pending"))), AT)).isEmpty();
        assertThat(Changes.between(cells(Map.of(ONE, Map.of("lint", "success"))), cells(Map.of(ONE, Map.of("build", "pending"))), AT)).isEmpty();
        assertThat(Changes.between(cells(Map.of(ONE, Map.of("build", "pending"))), cells(Map.of(ONE, Map.of())), AT)).isEmpty();
    }

    @Test
    void between_sortsByPr_thenKind_thenSubject() {
        var before = new PrSnapshot(Map.of(ONE, PrState.OPEN, TWO, PrState.OPEN),
                                    Map.of(ONE, Map.of("b", "pending", "a", "pending"), TWO, Map.of("a", "pending")),
                                    Map.of(ONE, head(1, "main")));
        var after = new PrSnapshot(Map.of(ONE, PrState.CLOSED, TWO, PrState.MERGED),
                                   Map.of(ONE, Map.of("b", "failure", "a", "success"), TWO, Map.of("a", "UNKNOWN")),
                                   Map.of(ONE, head(2, "main")));

        assertThat(Changes.between(before, after, AT)).extracting(change -> change.pr() + " " + change.kind() + " " + change.subject() + " "
                                                                            + change.before() + "->" + change.after())
                                                      .containsExactly("#1 CHECK_CELL a pending->success",
                                                                       "#1 CHECK_CELL b pending->failure",
                                                                       "#1 PR_STATE #1 open->closed",
                                                                       "#2 CHECK_CELL a pending->UNKNOWN",
                                                                       "#2 PR_STATE #2 open->merged");
    }

    /// An open PR whose head SHA changed gives exactly one `HEAD_MOVED`, before/after the old and
    /// new SHA, subject the base it targets now.
    @Test
    void between_findsAHeadMove_ofAnOpenPr() {
        var before = heads(Map.of(ONE, PrState.OPEN), Map.of(ONE, head(1, "main")));
        var after = heads(Map.of(ONE, PrState.OPEN), Map.of(ONE, head(2, "release-1")));

        assertThat(Changes.between(before, after, AT)).containsExactly(new Change(AT,
                                                                                  ONE,
                                                                                  ChangeKind.HEAD_MOVED,
                                                                                  "release-1",
                                                                                  sha(1).value(),
                                                                                  sha(2).value(),
                                                                                  AT));
    }

    /// Same head (also retargeted), a first sighting, and a head change seen with the PR no longer open give none.
    @Test
    void between_findsNoHeadMove_forTheSameHead_aFirstSighting_orAPrNotOpen() {
        var stored = heads(Map.of(ONE, PrState.OPEN), Map.of(ONE, head(1, "main")));

        assertThat(Changes.between(stored, heads(Map.of(ONE, PrState.OPEN), Map.of(ONE, head(1, "release-1"))), AT)).isEmpty();
        assertThat(Changes.between(heads(Map.of(), Map.of()), stored, AT)).isEmpty();
        assertThat(Changes.between(stored, heads(Map.of(ONE, PrState.CLOSED), Map.of(ONE, head(2, "main"))), AT))
            .extracting(Change::kind)
            .containsExactly(ChangeKind.PR_STATE);
        assertThat(Changes.between(stored, heads(Map.of(ONE, PrState.MERGED), Map.of(ONE, head(2, "main"))), AT))
            .extracting(Change::kind)
            .containsExactly(ChangeKind.PR_STATE);
    }

    /// The rule answers `recordedAt` = `observedAt`; the store's stamp replaces only `recordedAt`.
    @Test
    void recorded_replacesOnlyTheRecordedAt() {
        var change = Changes.between(states(Map.of(ONE, PrState.OPEN)), states(Map.of(ONE, PrState.MERGED)), AT)
                            .getFirst();
        var later = AT.plusSeconds(30);

        assertThat(change.recordedAt()).isEqualTo(AT);
        assertThat(change.recorded(later)).isEqualTo(new Change(AT, ONE, ChangeKind.PR_STATE, "#1", "open", "merged", later));
    }

    /// Two cells of one context under different apps (ruling 1) are keyed apart by [RequiredCheck#key] and change apart.
    @Test
    void between_keepsTwoCellsOfOneContext_underDifferentApps_apart() {
        var actions = new RequiredCheck(ok(CheckContext.checkContext("ci")), some(ok(AppId.appId(15368))));
        var other = new RequiredCheck(ok(CheckContext.checkContext("ci")), some(ok(AppId.appId(999))));
        var unpinned = new RequiredCheck(ok(CheckContext.checkContext("ci")), none());
        var before = cells(Map.of(ONE, keyed(List.of(actions, other, unpinned), List.of("pending", "pending", "pending"))));
        var after = cells(Map.of(ONE, keyed(List.of(actions, other, unpinned), List.of("success", "failure", "pending"))));

        assertThat(Changes.between(before, after, AT)).containsExactly(new Change(AT, ONE, ChangeKind.CHECK_CELL, "ci\u001F15368", "pending", "success", AT),
                                                                        new Change(AT, ONE, ChangeKind.CHECK_CELL, "ci\u001F999", "pending", "failure", AT));
    }

    @Test
    void key_isTheContext_withTheAppWhenPinned() {
        assertThat(new RequiredCheck(ok(CheckContext.checkContext("ci")), none()).key()).isEqualTo("ci");
        assertThat(new RequiredCheck(ok(CheckContext.checkContext("build-and-test")), some(ok(AppId.appId(15368)))).key()).isEqualTo("build-and-test\u001F15368");
    }

    /// The key is injective: an unpinned context spelled like a pinned label keeps its own key (and its own cell), since
    /// no context can hold the separator.
    @Test
    void key_neverCollides_forAnUnpinnedContextSpelledLikeAPinnedOne() {
        var unpinned = new RequiredCheck(ok(CheckContext.checkContext("ci@15368")), none());
        var pinned = new RequiredCheck(ok(CheckContext.checkContext("ci")), some(ok(AppId.appId(15368))));
        var before = cells(Map.of(ONE, keyed(List.of(unpinned, pinned), List.of("pending", "pending"))));
        var after = cells(Map.of(ONE, keyed(List.of(unpinned, pinned), List.of("failure", "success"))));

        assertThat(unpinned.key()).isNotEqualTo(pinned.key());
        assertThat(Changes.between(before, after, AT)).hasSize(2);
        assertThat(CheckContext.checkContext("ci" + RequiredCheck.SEPARATOR + "15368")
                               .isFailure()).isTrue();
    }

    /// The label is the display form, the same for a check and for its key.
    @Test
    void label_showsThePinnedAppAfterAnAt() {
        var pinned = new RequiredCheck(ok(CheckContext.checkContext("ci")), some(ok(AppId.appId(15368))));
        var unpinned = new RequiredCheck(ok(CheckContext.checkContext("ci")), none());

        assertThat(pinned.label()).isEqualTo("ci@15368");
        assertThat(RequiredCheck.label(pinned.key())).isEqualTo(pinned.label());
        assertThat(unpinned.label()).isEqualTo("ci");
        assertThat(RequiredCheck.label(unpinned.key())).isEqualTo("ci");
    }

    /// Cells by key; a key collision keeps the last value, so a key that merged two cells would lose a change.
    private static Map<String, String> keyed(List<RequiredCheck> checks, List<String> values) {
        return IntStream.range(0, checks.size())
                        .boxed()
                        .collect(Collectors.toMap(i -> checks.get(i).key(), values::get, (_, last) -> last));
    }
}
