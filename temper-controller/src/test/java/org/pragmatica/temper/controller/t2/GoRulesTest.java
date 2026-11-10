package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.GoBasis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [GoRules], the match rules: R3-3.b-d (`checks-changed`), GO.d-e (`base-moved`), the `fleetSeq` bound, and the
/// first match of a scan (R3-3.e's creation scan).
class GoRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final String SHA = "a".repeat(40);
    private static final String OTHER = "b".repeat(40);
    private static final String TIP = "c".repeat(40);
    private static final String NEW_TIP = "d".repeat(40);
    private static final GoBasis GO = GoBasis.goBasis("o/r", 7, SHA, "main", TIP, 10);

    /// R3-3.b: a check concluded at the GO's head after its fleet seq.
    @Test
    void r33b_checkConcludedAtTheHead_afterFleetSeq_isChecksChanged() {
        assertThat(GoRules.reason(GO, row(11, "check-concluded", check(7, some(SHA))))).isEqualTo(some("checks-changed"));
        assertThat(GoRules.reason(GO, row(11, "checks-settled", check(7, some(SHA))))).isEqualTo(some("checks-changed"));
    }

    /// R3-3.c: `check-cell` carries no head, so any cell change of the PR invalidates.
    @Test
    void r33c_checkCellWithoutHead_isChecksChanged() {
        assertThat(GoRules.reason(GO, row(11, "check-cell", check(7, none())))).isEqualTo(some("checks-changed"));
        assertThat(GoRules.reason(GO, row(11, "check-cell", "{\"repo\":\"o/r\",\"pr\":7,\"head\":null}"))).isEqualTo(some("checks-changed"));
    }

    /// R3-3.d: another head, another PR or repo, or a seq at or below the GO's fleet seq leaves it valid.
    @Test
    void r33d_otherHead_otherPr_orSeqAtFleetSeq_staysValid() {
        assertThat(GoRules.reason(GO, row(11, "check-concluded", check(7, some(OTHER))))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(11, "check-cell", check(8, none())))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(11, "check-cell", "{\"repo\":\"o/x\",\"pr\":7}"))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(10, "check-concluded", check(7, some(SHA))))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(9, "check-cell", check(7, none())))).isEqualTo(none());
    }

    /// GO.d: a base move whose tree changed, at the GO's repo and base, to a tip the GO did not judge.
    @Test
    void goD_baseMovedTreeChanged_isBaseMoved() {
        assertThat(GoRules.reason(GO, row(11, "base-moved", moved("main", true, some(NEW_TIP))))).isEqualTo(some("base-moved"));
        assertThat(GoRules.reason(GO, row(11, "base-moved", moved("main", true, none())))).isEqualTo(some("base-moved"));
    }

    /// GO.e: an empty move (treeChanged=false), a move to the GO's own tip, another base, or a seq at the fleet seq
    /// leaves the GO valid.
    @Test
    void goE_emptyMove_orMoveToTheGosTip_staysValid() {
        assertThat(GoRules.reason(GO, row(11, "base-moved", moved("main", false, some(NEW_TIP))))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(11, "base-moved", moved("main", true, some(TIP))))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(11, "base-moved", moved("dev", true, some(NEW_TIP))))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(11, "base-moved", "{\"repo\":\"o/r\",\"base\":\"main\",\"treeChanged\":\"true\",\"after\":\"" + NEW_TIP + "\"}"))).isEqualTo(none());
        assertThat(GoRules.reason(GO, row(10, "base-moved", moved("main", true, some(NEW_TIP))))).isEqualTo(none());
    }

    /// The detector's own kinds and every other kind match no rule.
    @Test
    void reason_otherKinds_matchNothing() {
        assertThat(List.of("go-invalidated", "merged", "ungated-merge", "queue-advanced", "gap", "pr-state", "verdict-registered"))
            .allSatisfy(kind -> assertThat(GoRules.reason(GO, row(11, kind, check(7, some(SHA))))).isEqualTo(none()));
    }

    /// R3-3.e (rule half): the creation scan answers the first matching row's reason, in seq order.
    @Test
    void first_answersTheFirstMatchInSeqOrder() {
        var rows = List.of(row(11, "check-concluded", check(7, some(OTHER))),
                           row(12, "base-moved", moved("main", true, some(NEW_TIP))),
                           row(13, "check-cell", check(7, none())));

        assertThat(GoRules.first(GO, rows)).isEqualTo(some("base-moved"));
        assertThat(GoRules.first(GO, rows.subList(0, 1))).isEqualTo(none());
    }

    private static String check(long pr, Option<String> head) {
        return "{\"repo\":\"o/r\",\"pr\":" + pr + head.map(sha -> ",\"head\":\"" + sha + "\"").or("") + "}";
    }

    private static String moved(String base, boolean treeChanged, Option<String> after) {
        return "{\"base\":\"" + base + "\",\"treeChanged\":" + treeChanged + ",\"repo\":\"o/r\"" + after.map(tip -> ",\"after\":\"" + tip + "\"").or("") + "}";
    }

    private static FleetEventRow row(long seq, String kind, String payload) {
        return FleetEventRow.fleetEventRow("E", seq, NOW, NOW, kind, "s", false, "kiln", payload);
    }
}
