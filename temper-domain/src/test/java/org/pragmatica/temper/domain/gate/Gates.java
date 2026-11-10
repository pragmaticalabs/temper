package org.pragmatica.temper.domain.gate;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Gate candidates for the selection tests.
final class Gates {
    static final Judge X = judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d");
    static final Judge Y = judge("5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80");
    /// A newer build whose commit sorts BEFORE [#X]'s: judges are ordered by candidate id, never by hash.
    static final Judge LOW = judge("0000000000000000000000000000000000000abc");
    static final Counts COUNTS = Counts.counts(12, 11, 1, 0, 0, 12, 3, none(), none(), none());

    private Gates() {}

    static Judge judge(String raw) {
        return Judge.judge(raw).unwrap();
    }

    /// A terminal Gate submitted, granted and finished under `judge`.
    static GateCandidate finished(long id, JobState state, Judge judge) {
        return candidate(id, state, judge, some(judge), some(judge));
    }

    /// A Gate that holds a grant under `judge` and has not finished (RUNNING, UNKNOWN, CANCELLING).
    static GateCandidate granted(long id, JobState state, Judge judge) {
        return candidate(id, state, judge, some(judge), none());
    }

    /// A QUEUED Gate submitted under `judge`, not granted yet.
    static GateCandidate queued(long id, Judge judge) {
        return candidate(id, JobState.QUEUED, judge, none(), none());
    }

    static GateCandidate candidate(long id, JobState state, Judge submit, Option<Judge> grant, Option<Judge> finish) {
        return GateCandidate.gateCandidate(JobId.jobId(id).unwrap(),
                                           state,
                                           submit,
                                           grant.stream().toList(),
                                           finish,
                                           finish.map(_ -> COUNTS),
                                           List.of(),
                                           true);
    }

    /// A terminal Gate whose units were granted under `grants`, in grant order, and finished under `finish`.
    static GateCandidate units(long id, JobState state, Judge submit, List<Judge> grants, Judge finish) {
        return GateCandidate.gateCandidate(JobId.jobId(id).unwrap(), state, submit, grants, some(finish), some(COUNTS), List.of(), true);
    }

    static List<Long> ids(List<GateCandidate> candidates) {
        return candidates.stream().map(candidate -> candidate.job().value()).toList();
    }

    static List<Long> jobIds(List<JobId> jobs) {
        return jobs.stream().map(JobId::value).toList();
    }

    static Option<Long> id(Option<GateCandidate> candidate) {
        return candidate.map(found -> found.job().value());
    }
}
