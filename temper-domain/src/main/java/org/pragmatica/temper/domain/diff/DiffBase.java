package org.pragmatica.temper.domain.diff;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.gate.GateCandidate;
import org.pragmatica.temper.domain.gate.GateEvidence;


/// Side A of `temper diff <job> --base <sha>` (m2-design §3.1): the Gate that `gate <sha>` answers about, read from the
/// same [GateEvidence], so the two verbs never disagree about what "the gate at S" is.
///
/// | `gate` answer | base |
/// |---|---|
/// | PASSED, FAILED, EMPTY | the answering Gate |
/// | RUNNING | its `previous`, the same judge's newest vote [G]; none without one |
/// | NONE | none (exit 8) |
/// | CONFLICT | none: [Conflicting], every vote listed (exit 8; ruling R-M2-9 on Q-M2-26). Picking either job would present the latest, which R-M2-5 forbids |
public sealed interface DiffBase {
    /// The Gate to compare with.
    record Found(GateCandidate gate) implements DiffBase {
        public static Found found(GateCandidate gate) {
            return new Found(gate);
        }
    }

    /// No Gate at the SHA decides anything.
    record Missing() implements DiffBase {
        public static Missing missing() {
            return new Missing();
        }
    }

    /// The newest judge's Gates disagree: `votes` as `gate` lists them, newest first.
    record Conflicting(List<GateCandidate> votes) implements DiffBase {
        public Conflicting {
            votes = List.copyOf(votes);
        }

        public static Conflicting conflicting(List<GateCandidate> votes) {
            return new Conflicting(votes);
        }
    }

    static DiffBase choose(GateEvidence evidence) {
        return switch (evidence.answer()) {
            case PASSED, FAILED, EMPTY -> foundOrMissing(evidence.answering());
            case RUNNING -> foundOrMissing(evidence.previous());
            case CONFLICT -> Conflicting.conflicting(evidence.conflict());
            case NONE -> Missing.missing();
        };
    }

    private static DiffBase foundOrMissing(Option<GateCandidate> gate) {
        return gate.<DiffBase> map(Found::found)
                   .or(Missing::missing);
    }
}
