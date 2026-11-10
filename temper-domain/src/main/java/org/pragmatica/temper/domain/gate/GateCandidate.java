package org.pragmatica.temper.domain.gate;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;


/// One Gate at the asked SHA (m2-design §1.1): a pushed, non-superseded, non-REFUSED job of recipe `gate`. `state` is
/// the state as shown (the UNKNOWN overlay applied). `submitJudge` is the judge of the controller that accepted it,
/// `grantJudges` those of the controllers that granted its units, in grant order, empty until a grant, and `finishJudge`
/// that of the controller that wrote its result, absent until it is terminal (§1.7, ruling R-M2-11). `counts`,
/// `annotations` and `evidenceStored` come from its result record, empty until there is one.
public record GateCandidate(JobId job,
                            JobState state,
                            Judge submitJudge,
                            List<Judge> grantJudges,
                            Option<Judge> finishJudge,
                            Option<Counts> counts,
                            List<String> annotations,
                            boolean evidenceStored) {
    public GateCandidate {
        grantJudges = List.copyOf(grantJudges);
        annotations = List.copyOf(annotations);
    }

    public static GateCandidate gateCandidate(JobId job,
                                              JobState state,
                                              Judge submitJudge,
                                              List<Judge> grantJudges,
                                              Option<Judge> finishJudge,
                                              Option<Counts> counts,
                                              List<String> annotations,
                                              boolean evidenceStored) {
        return new GateCandidate(job, state, submitJudge, grantJudges, finishJudge, counts, annotations, evidenceStored);
    }

    /// The judge it counts under: the finishing controller's once terminal, the one that granted its first unit while it
    /// runs, the submitting one's while it waits. A job queued under one build and granted and finished under another
    /// counts under the second (ruling R-M2-11).
    public Judge judge() {
        return finishJudge.orElse(this::firstGrantJudge)
                          .or(submitJudge);
    }

    /// The grant judge that differs from the judge it counts under, when one does: the first such grant.
    public Option<Judge> changedGrantJudge() {
        return Option.from(grantJudges.stream().filter(granted -> !granted.equals(judge())).findFirst());
    }

    /// The verdict it votes for: PASSED, FAILED or EMPTY.
    public Option<GateAnswer> vote() {
        return GateAnswer.vote(state);
    }

    public boolean isJudged() {
        return vote().isPresent();
    }

    public boolean isRunning() {
        return ! state.isTerminal();
    }

    /// VOID, ERROR or CANCELLED: never an answer, listed as `unjudged` (ruling R-M2-9).
    public boolean isUnjudged() {
        return state.isTerminal() && !isJudged();
    }

    /// Judged across a deploy (ruling R-M2-11): a unit of it was granted under another judge than the one that finished
    /// it. Excluded from the answer and from the CONFLICT set, listed as `judge-changed`; the way out is `--fresh`. The
    /// submit judge does not count: a job that only waited in the queue across a deploy ran under the new build.
    public boolean isJudgeChanged() {
        return isJudged() && changedGrantJudge().isPresent();
    }

    /// A candidate that may decide the newest judge and the answer: judged within one build, or still running.
    public boolean isDecisive() {
        return isVote() || isRunning();
    }

    /// A vote that counts: judged within one build.
    public boolean isVote() {
        return isJudged() && !isJudgeChanged();
    }

    private Option<Judge> firstGrantJudge() {
        return Option.from(grantJudges.stream().findFirst());
    }
}
