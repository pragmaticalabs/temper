package org.pragmatica.temper.domain.diff;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// The answer of `temper diff` (m2-design §3.2): the [TestDiff] of two complete sides, or the obstacle that keeps them
/// from being compared, never sets computed over partial or half-deleted rows.
public sealed interface DiffOutcome {
    /// Both sides complete: the exact set algebra over their rows.
    record Compared(TestDiff diff) implements DiffOutcome {
        public static Compared compared(TestDiff diff) {
            return new Compared(diff);
        }
    }

    /// Not compared: `obstacle`, on `sides` (empty for the `--base` obstacles, which concern no side).
    record Refused(DiffObstacle obstacle, List<Side> sides) implements DiffOutcome {
        public Refused {
            sides = List.copyOf(sides);
        }

        public static Refused refused(DiffObstacle obstacle, List<Side> sides) {
            return new Refused(obstacle, sides);
        }
    }

    /// Side A is the first job (or the base Gate), side B the second (or the job compared with the base).
    enum Side {
        A,
        B
    }

    /// The first obstacle in [DiffObstacle] order that either side has, naming every side that has it; otherwise the
    /// diff of the two sides' primary rows.
    static DiffOutcome decide(DiffSide a, DiffSide b) {
        return firstObstacle(a, b).map(obstacle -> blocked(obstacle, a, b))
                            .or(() -> Compared.compared(TestDiff.compare(a.primaryRows(),
                                                                         b.primaryRows())));
    }

    private static Option<DiffObstacle> firstObstacle(DiffSide a, DiffSide b) {
        return Option.from(Stream.concat(a.obstacle().stream(),
                                         b.obstacle().stream()).min(Comparator.naturalOrder()));
    }

    private static DiffOutcome blocked(DiffObstacle obstacle, DiffSide a, DiffSide b) {
        return Refused.refused(obstacle,
                               Stream.concat(named(Side.A, a, obstacle), named(Side.B, b, obstacle)).toList());
    }

    /// `label` when `side` has `obstacle`.
    private static Stream<Side> named(Side label, DiffSide side, DiffObstacle obstacle) {
        return side.obstacle()
                   .filter(obstacle::equals)
                   .map(_ -> label)
                   .stream();
    }
}
