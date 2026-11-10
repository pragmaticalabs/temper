package org.pragmatica.temper.cli.merge;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// One NO reason: the check that produced it and its exact line. Reasons print in check order
/// G1, G2, G3, G4, G5, G8, G9, G10, G6, G7 ([Gate]'s declaration order), each check's lines in production order.
record Reason(Gate check, String line) {
    enum Gate {
        G1,
        G2,
        G3,
        G4,
        G5,
        G8,
        G9,
        G10,
        G6,
        G7
    }

    static final String BUDGET_EXCEEDED = "source-unavailable budget-exceeded";

    static Reason reason(Gate check, String line) {
        return new Reason(check, line);
    }

    /// A server reason; a check label this CLI does not know prints at the G7 position (fail closed: still a NO).
    static Reason fromServer(MergeWire.ServerReason reason) {
        return reason(gateOf(reason.check()), reason.line());
    }

    static Reason budgetExceeded() {
        return reason(Gate.G7, BUDGET_EXCEEDED);
    }

    static Reason gitUnavailable(String step) {
        return reason(Gate.G7, "source-unavailable git " + step);
    }

    private static Gate gateOf(String label) {
        return Option.from(Stream.of(Gate.values()).filter(gate -> gate.name()
                                                                       .equals(label)).findFirst()).or(Gate.G7);
    }

    /// Stable: lines of one check keep their production order.
    static List<Reason> ordered(List<Reason> reasons) {
        return reasons.stream()
                      .sorted(Comparator.comparing(Reason::check))
                      .toList();
    }

    static List<Reason> joined(List<Reason> first, List<Reason> second) {
        return Stream.concat(first.stream(),
                             second.stream())
                     .toList();
    }

    /// The reason's first word (`blocked:<reason word>` in the queue view).
    String word() {
        return line.split(" ", 2) [0];
    }
}
