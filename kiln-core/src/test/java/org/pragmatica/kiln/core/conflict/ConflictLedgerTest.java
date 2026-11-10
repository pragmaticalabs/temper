package org.pragmatica.kiln.core.conflict;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.kiln.core.conflict.ConflictEvent.State;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.assertj.core.api.Assertions.assertThat;


/// temper #100: the events.log line, its replay at restart, the new/cleared transitions and the overlap pair filter.
class ConflictLedgerTest {
    private static final Instant AT = Instant.parse("2026-11-08T10:15:00Z");
    private static final Sha TIP = ok(Sha.sha("2e44f7577bbfce430673a4813836186b499f3552"));
    private static final Sha TIP2 = ok(Sha.sha("96a3eb3e3abec6333f4e628912c011f331e19a7c"));
    private static final PrNumber P2711 = pr(2711);
    private static final PrNumber P2713 = pr(2713);
    private static final PrNumber P2770 = pr(2770);

    private static PrNumber pr(long number) {
        return ok(PrNumber.prNumber(number));
    }

    @Test
    void line_isTabSeparated_hotPathsStarred_andParsesBack() {
        var prediction = Prediction.pair(P2770,
                                         P2711,
                                         List.of("docs/x.md", "aether/src/main/java/org/AetherKey.java"),
                                         TIP);
        var line = ConflictEvent.conflictEvent(AT, State.NEW, prediction).line();

        assertThat(line).isEqualTo("2026-11-08T10:15:00Z\tpair\tnew\t2711+2770\t*aether/src/main/java/org/AetherKey.java,docs/x.md\t" + TIP.value()
                                  + "\t2770>2711");
        assertThat(ConflictEvent.parsed(line).map(ConflictEvent::prediction)).isEqualTo(Option.some(prediction));
    }

    /// PR #108 review F4: the line names every landing order that conflicts, and a six-field line (no orders) still reads.
    @Test
    void line_namesTheConflictingOrders_bothOrOne_andASixFieldLineStillParses() {
        var both = Prediction.pair(P2711, P2770, List.of("f"), TIP, List.of(P2770, P2711));
        var line = ConflictEvent.conflictEvent(AT, State.NEW, both).line();

        assertThat(line).endsWith("\t" + TIP.value() + "\t2711>2770,2770>2711");
        assertThat(ConflictEvent.parsed(line).map(event -> event.prediction()
                                                                .firsts())).isEqualTo(Option.some(List.of(P2711, P2770)));
        assertThat(ConflictEvent.parsed("2026-11-08T10:15:00Z\tpair\tnew\t2711+2770\tf\t" + TIP.value()).map(event -> event.prediction()
                                                                                                                           .firsts())).isEqualTo(Option.some(List.of()));
        assertThat(ConflictEvent.parsed("2026-11-08T10:15:00Z\tpair\tnew\t2711+2770\tf\t" + TIP.value() + "\t2713>2711").isPresent()).as("an order naming a PR outside the pair is not a line of ours")
                  .isFalse();
        assertThat(Prediction.tip(P2713, List.of("f"), TIP).orders()).isEmpty();
    }

    @Test
    void line_hostilePath_cannotSplitTheLineOrAField() {
        var line = ConflictEvent.conflictEvent(AT,
                                               State.NEW,
                                               Prediction.tip(P2713, List.of("a\tb\nc,d"), TIP)).line();

        assertThat(line.split("\t", -1)).hasSize(7);
        assertThat(line).doesNotContain("\n").contains("a?b?c?d");
    }

    @Test
    void parsed_refusesForeignAndTornLines() {
        assertThat(ConflictEvent.parsed("").isPresent()).isFalse();
        assertThat(ConflictEvent.parsed("2026-11-08T10:15:00Z\ttip\tnew\t2713\tx").isPresent()).isFalse();
        assertThat(ConflictEvent.parsed("2026-11-08T10:15:00Z\ttip\tmaybe\t2713\tx\t" + TIP.value()).isPresent()).isFalse();
        assertThat(ConflictEvent.parsed("2026-11-08T10:15:00Z\tbogus\tnew\t2713\tx\t" + TIP.value()).isPresent()).isFalse();
        assertThat(ConflictEvent.parsed("2026-11-08T10:15:00Z\ttip\tnew\t2711+2713\tx\t" + TIP.value()).isPresent()).isFalse();
    }

    @Test
    void hot_marksTheKnownHotFilesByName() {
        assertThat(Prediction.hot("a/b/AetherValue.java")).isTrue();
        assertThat(Prediction.hot("wire-assignment-baseline.txt")).isTrue();
        assertThat(Prediction.hot("a/b/NotAetherValue.java")).isFalse();
        assertThat(Prediction.tip(P2713, List.of("x/OperatorWarningCode.java", "y.txt"), TIP).hotPaths()).containsExactly("x/OperatorWarningCode.java");
    }

    @Test
    void step_newPrediction_emitsNew_once() {
        var current = List.of(Prediction.tip(P2713, List.of("a.java"), TIP));
        var first = Announced.NONE.step(current, _ -> false, TIP, AT);
        var second = first.announced().step(current, _ -> false, TIP, AT);

        assertThat(first.events()).extracting(ConflictEvent::state).containsExactly(State.NEW);
        assertThat(second.events()).isEmpty();
    }

    @Test
    void step_tipMoved_stillConflicting_isNewAtTheNewTip_withoutAClearedLine() {
        var announced = Announced.NONE.step(List.of(Prediction.tip(P2713,
                                                                   List.of("a.java"),
                                                                   TIP)),
                                            _ -> false,
                                            TIP,
                                            AT)
                                      .announced();
        var moved = announced.step(List.of(Prediction.tip(P2713, List.of("a.java"), TIP2)),
                                   _ -> false,
                                   TIP2,
                                   AT);

        assertThat(moved.events()).extracting(event -> event.state() + " " + event.prediction()
                                                                                  .tip()
                                                                                  .value())
                  .containsExactly("NEW " + TIP2.value());
    }

    @Test
    void step_goneAndDetermined_isCleared_atTheCurrentTip() {
        var announced = Announced.NONE.step(List.of(Prediction.tip(P2713,
                                                                   List.of("a.java"),
                                                                   TIP)),
                                            _ -> false,
                                            TIP,
                                            AT)
                                      .announced();
        var step = announced.step(List.of(), _ -> false, TIP2, AT);

        assertThat(step.events()).hasSize(1);
        assertThat(step.events().getFirst().state()).isEqualTo(State.CLEARED);
        assertThat(step.events().getFirst().prediction().tip()).isEqualTo(TIP2);
        assertThat(step.announced().keys()).isEmpty();
    }

    @Test
    void step_goneButUndetermined_keepsItsAnnouncement() {
        var announced = Announced.NONE.step(List.of(Prediction.tip(P2713,
                                                                   List.of("a.java"),
                                                                   TIP)),
                                            _ -> false,
                                            TIP,
                                            AT)
                                      .announced();
        var step = announced.step(List.of(), "tip:2713"::equals, TIP, AT);

        assertThat(step.events()).isEmpty();
        assertThat(step.announced().keys()).containsExactly("tip:2713");
    }

    @Test
    void replayed_restart_emitsNothingForWhatWasAlreadyAnnounced() {
        var lines = List.of(event(State.NEW,
                                  Prediction.tip(P2713, List.of("a.java"), TIP)),
                            event(State.NEW,
                                  Prediction.pair(P2711, P2770, List.of("b.java"), TIP)),
                            event(State.CLEARED,
                                  Prediction.pair(P2711, P2770, List.of("b.java"), TIP)),
                            "torn line");
        var announced = Announced.replayed(lines);
        var restart = announced.step(List.of(Prediction.tip(P2713, List.of("a.java", "c.java"), TIP)),
                                     _ -> false,
                                     TIP,
                                     AT);

        assertThat(announced.keys()).containsExactly("tip:2713");
        assertThat(restart.events()).isEmpty();
    }

    @Test
    void pairs_onlyOverlappingSetsPair_lowerNumberFirst() {
        var pairs = Overlap.pairs(Map.of(P2770,
                                         Changes.edits(Set.of("a.java", "b.java")),
                                         P2711,
                                         Changes.edits(Set.of("b.java")),
                                         P2713,
                                         Changes.edits(Set.of("c.java"))));

        assertThat(pairs).containsExactly(new Overlap.Pair(P2711, P2770));
        assertThat(pairs.getFirst().key()).isEqualTo("pair:2711+2770");
    }

    /// PR #108 review F3: disjoint path sets still meet when one adds a file where the other puts a directory, or adds
    /// into a directory the other moves; new files side by side in one directory, or a move and an edit elsewhere, do not.
    @Test
    void meet_directoryFileAndDirectoryRename_pairDisjointPaths_andNothingElse() {
        var addsA = Changes.changes(List.of(entry('A', "a")));
        var addsAB = Changes.changes(List.of(entry('A', "a/b/c")));
        var movesD = Changes.changes(List.of(new Changes.Entry('R', "d/one", "e/one"),
                                             new Changes.Entry('R', "d/two", "e/two")));
        var addsInD = Changes.changes(List.of(entry('A', "d/sub/new")));
        var addsInE = Changes.changes(List.of(entry('A', "x/new")));
        var editsD = Changes.edits(Set.of("d/three"));
        var addsBeside = Changes.changes(List.of(entry('A', "a/b/d")));

        assertThat(Overlap.meet(addsA, addsAB)).as("file a against directory a/b").isTrue();
        assertThat(Overlap.meet(addsAB, addsA)).isTrue();
        assertThat(Overlap.meet(movesD, addsInD)).as("d moved to e against a new file under d").isTrue();
        assertThat(Overlap.meet(addsInD, movesD)).isTrue();
        assertThat(Overlap.meet(movesD, addsInE)).isFalse();
        assertThat(Overlap.meet(movesD, editsD)).as("an edit is not carried by a directory rename").isFalse();
        assertThat(Overlap.meet(addsAB, addsBeside)).as("two new files in one directory").isFalse();
        assertThat(movesD.paths()).containsExactlyInAnyOrder("d/one", "d/two", "e/one", "e/two");
        assertThat(movesD.movedFrom()).containsExactly("d");
        assertThat(Changes.changes(List.of(new Changes.Entry('R', "src/a/b/X.java", "src/c/b/X.java"))).movedFrom()).containsExactlyInAnyOrder("src/a/b",
                                                                                                                                               "src/a");
        assertThat(addsAB.addedUnder()).containsExactlyInAnyOrder("a/b", "a");
    }

    private static Changes.Entry entry(char status, String path) {
        return new Changes.Entry(status, path, path);
    }

    private static String event(State state, Prediction prediction) {
        return ConflictEvent.conflictEvent(AT, state, prediction).line();
    }
}
