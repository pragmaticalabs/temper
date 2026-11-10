package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.state.JobState;


/// A diff that was not computed (m2-design §3.2, §3.3), and why: `reason` is the [DiffObstacle].
///
/// - `a` is side A's job, absent when `--base` named none; `b` is side B's job.
/// - `sides` lists the sides `reason` is about (NOT_JUDGED, PRUNED, NOT_TERMINAL), each with its state as shown: for
///   NOT_JUDGED that is its verdict (`side not judged: <verdict>`, R-M2-13).
/// - `sha` is the SHA a `--base` resolved to (NO_BASE, BASE_CONFLICT; the asked prefix when no Gate is there);
///   `conflict` every vote of a BASE_CONFLICT, newest first.
public record DiffRefusal(DiffObstacle reason,
                          Option<Long> a,
                          long b,
                          List<SideState> sides,
                          Option<String> sha,
                          List<GateView.Vote> conflict) implements DiffAnswer {
    public DiffRefusal {
        sides = List.copyOf(sides);
        conflict = List.copyOf(conflict);
    }

    public static DiffRefusal diffRefusal(DiffObstacle reason,
                                          Option<Long> a,
                                          long b,
                                          List<SideState> sides,
                                          Option<String> sha,
                                          List<GateView.Vote> conflict) {
        return new DiffRefusal(reason, a, b, sides, sha, conflict);
    }

    /// One side the refusal is about: A or B, its job and its state as shown.
    public record SideState(DiffOutcome.Side side, long job, JobState state) {
        public static SideState sideState(DiffOutcome.Side side, long job, JobState state) {
            return new SideState(side, job, state);
        }
    }
}
