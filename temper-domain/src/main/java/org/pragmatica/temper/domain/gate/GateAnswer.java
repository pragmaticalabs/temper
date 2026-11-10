package org.pragmatica.temper.domain.gate;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The answer to the merge question `gate <sha>` (m2-design §1.1, design R5). PASSED, FAILED and EMPTY are the one
/// verdict of the newest judge's Gates; CONFLICT is their disagreement (ruling R-M2-5); RUNNING is a newer Gate of
/// that judge that has not finished; NONE is no Gate that decides anything.
public enum GateAnswer {
    PASSED,
    FAILED,
    EMPTY,
    CONFLICT,
    NONE,
    RUNNING;
    /// The verdict a judged state votes for; VOID, ERROR and CANCELLED "describe the environment, not the code" (spec
    /// §2.3) and vote for nothing, nor does a state that is not terminal (ruling R-M2-9).
    public static Option<GateAnswer> vote(JobState state) {
        return switch (state) {
            case PASSED -> some(PASSED);
            case FAILED -> some(FAILED);
            case EMPTY -> some(EMPTY);
            case REFUSED, QUEUED, UNKNOWN, RUNNING, CANCELLING, SUPERSEDED, VOID, ERROR, CANCELLED -> none();
        };
    }
}
