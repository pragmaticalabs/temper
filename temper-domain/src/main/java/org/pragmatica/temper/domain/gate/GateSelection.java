package org.pragmatica.temper.domain.gate;

import java.util.Comparator;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The rules of [GateEvidence] (m2-design §1.1; rulings R-M2-5, R-M2-9, R-M2-10, R-M2-11), one Condition per method:
/// the newest judge first, then CONFLICT before RUNNING before the verdict.
sealed interface GateSelection {
    Comparator<GateCandidate> NEWEST_FIRST = Comparator.comparingLong((GateCandidate candidate) -> candidate.job()
                                                                                                            .value()).reversed();

    static GateEvidence select(List<GateCandidate> candidates) {
        var newestFirst = candidates.stream().sorted(NEWEST_FIRST).toList();

        return newestJudge(newestFirst).map(judge -> judgedBy(judge, newestFirst))
                          .or(() -> nothingDecides(newestFirst));
    }

    /// What every answer lists besides itself: the other judges' votes, the Gates judged across a deploy, the unjudged.
    record History(List<GateCandidate> older, List<GateCandidate> judgeChanged, List<JobId> unjudged) {
        static History history(List<GateCandidate> older, List<GateCandidate> judgeChanged, List<JobId> unjudged) {
            return new History(older, judgeChanged, unjudged);
        }
    }

    /// The candidates of the newest judge, newest first, and their votes.
    record Ballot(Judge judge, List<GateCandidate> candidates, List<GateCandidate> votes) {
        static Ballot ballot(Judge judge, List<GateCandidate> candidates, List<GateCandidate> votes) {
            return new Ballot(judge, candidates, votes);
        }
    }

    private static Option<Judge> newestJudge(List<GateCandidate> newestFirst) {
        return Option.from(newestFirst.stream().filter(GateCandidate::isDecisive).findFirst()).map(GateCandidate::judge);
    }

    private static GateEvidence judgedBy(Judge judge, List<GateCandidate> newestFirst) {
        var candidates = newestFirst.stream()
                                    .filter(candidate -> candidate.isDecisive() && candidate.judge()
                                                                                            .equals(judge))
                                    .toList();
        var ballot = Ballot.ballot(judge,
                                   candidates,
                                   candidates.stream().filter(GateCandidate::isVote).toList());
        var history = history(some(judge), newestFirst);

        return isConflict(ballot)
               ? conflict(ballot, history)
               : agreed(ballot, history);
    }

    private static boolean isConflict(Ballot ballot) {
        return ballot.votes()
                     .stream()
                     .map(GateCandidate::vote)
                     .distinct()
                     .count() > 1;
    }

    /// One verdict at most: the newest candidate decides. A vote is the verdict; no vote (it is still running) is
    /// RUNNING.
    private static GateEvidence agreed(Ballot ballot, History history) {
        var newest = ballot.candidates().getFirst();

        return newest.vote()
                     .map(vote -> verdict(ballot, newest, vote, history))
                     .or(() -> running(ballot, history));
    }

    private static GateEvidence conflict(Ballot ballot, History history) {
        return GateEvidence.gateEvidence(some(ballot.judge()),
                                         GateAnswer.CONFLICT,
                                         none(),
                                         none(),
                                         ballot.votes(),
                                         newerRunning(ballot),
                                         history.older(),
                                         history.judgeChanged(),
                                         history.unjudged());
    }

    private static GateEvidence running(Ballot ballot, History history) {
        return GateEvidence.gateEvidence(some(ballot.judge()),
                                         GateAnswer.RUNNING,
                                         some(ballot.candidates().getFirst()),
                                         Option.from(ballot.votes().stream().findFirst()),
                                         List.of(),
                                         none(),
                                         history.older(),
                                         history.judgeChanged(),
                                         history.unjudged());
    }

    /// The newest candidate of the judge and its vote, the one verdict of the ballot.
    private static GateEvidence verdict(Ballot ballot, GateCandidate newest, GateAnswer vote, History history) {
        return GateEvidence.gateEvidence(some(ballot.judge()),
                                         vote,
                                         some(newest),
                                         none(),
                                         List.of(),
                                         none(),
                                         history.older(),
                                         history.judgeChanged(),
                                         history.unjudged());
    }

    private static GateEvidence nothingDecides(List<GateCandidate> newestFirst) {
        var history = history(none(), newestFirst);

        return GateEvidence.gateEvidence(none(),
                                         GateAnswer.NONE,
                                         none(),
                                         none(),
                                         List.of(),
                                         none(),
                                         history.older(),
                                         history.judgeChanged(),
                                         history.unjudged());
    }

    /// The newest running candidate of the judge, when it is newer than every vote: its result can only add a vote.
    private static Option<JobId> newerRunning(Ballot ballot) {
        var newestVote = ballot.votes().getFirst().job().value();

        return Option.from(ballot.candidates()
                                 .stream()
                                 .filter(GateCandidate::isRunning)
                                 .filter(candidate -> candidate.job()
                                                               .value() > newestVote)
                                 .findFirst()).map(GateCandidate::job);
    }

    private static History history(Option<Judge> judge, List<GateCandidate> newestFirst) {
        return History.history(newestFirst.stream()
                                          .filter(candidate -> candidate.isVote() && judge.filter(candidate.judge()::equals)
                                                                                          .isEmpty())
                                          .toList(),
                               newestFirst.stream().filter(GateCandidate::isJudgeChanged).toList(),
                               newestFirst.stream().filter(GateCandidate::isUnjudged).map(GateCandidate::job).toList());
    }

    record unused() implements GateSelection {}
}
