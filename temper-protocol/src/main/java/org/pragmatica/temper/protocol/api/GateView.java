package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Option.option;


/// `GET /api/v1/gate/{sha}` (m2-design §1.4): the answer to the merge question for one SHA.
///
/// - `sha` is the full SHA the candidates are at, or the asked prefix when no candidate matched it (NONE).
/// - `judge` is the newest judge at the SHA, absent for NONE; every vote carries its own judge.
/// - `job`, `state`, `counts`, `annotations` and `evidence` (`stored`|`missing`) describe the Gate a PASSED, FAILED,
///   EMPTY or RUNNING answer is about; `state` is its state as shown (QUEUED, UNKNOWN, RUNNING or CANCELLING for
///   RUNNING); `counts` and `evidence` are absent while it runs.
/// - `previous` (RUNNING only) is the newest vote of that judge; `conflict` (CONFLICT only) every vote of that judge,
///   newest first; `running` (CONFLICT only) a newer Gate of that judge that has not finished.
/// - `older` are the votes of older judges, `judgeChanged` the Gates judged across a deploy (ruling R-M2-11) and
///   `unjudged` the VOID, ERROR and CANCELLED Gates: history, never the answer. Every list is newest first.
/// - `reruns` are the answering Gate's rerun jobs with their per-class outcomes, oldest first (m2-design §5.3):
///   information only, never the answer (ruling R-M2-10, option (i)); empty without an answering Gate, and when absent.
public record GateView(String sha,
                       Option<String> judge,
                       GateAnswer answer,
                       Option<Long> job,
                       Option<JobState> state,
                       Option<ResultRecordDto.CountsDto> counts,
                       List<String> annotations,
                       Option<String> evidence,
                       Option<Vote> previous,
                       List<Vote> conflict,
                       Option<Long> running,
                       List<Vote> older,
                       List<JudgeChanged> judgeChanged,
                       List<Long> unjudged,
                       List<RerunView> reruns) {
    public GateView {
        annotations = List.copyOf(annotations);
        conflict = List.copyOf(conflict);
        older = List.copyOf(older);
        judgeChanged = List.copyOf(judgeChanged);
        unjudged = List.copyOf(unjudged);
        reruns = option(reruns).map(List::copyOf).or(List.of());
    }

    public static GateView gateView(String sha,
                                    Option<String> judge,
                                    GateAnswer answer,
                                    Option<Long> job,
                                    Option<JobState> state,
                                    Option<ResultRecordDto.CountsDto> counts,
                                    List<String> annotations,
                                    Option<String> evidence,
                                    Option<Vote> previous,
                                    List<Vote> conflict,
                                    Option<Long> running,
                                    List<Vote> older,
                                    List<JudgeChanged> judgeChanged,
                                    List<Long> unjudged,
                                    List<RerunView> reruns) {
        return new GateView(sha,
                            judge,
                            answer,
                            job,
                            state,
                            counts,
                            annotations,
                            evidence,
                            previous,
                            conflict,
                            running,
                            older,
                            judgeChanged,
                            unjudged,
                            reruns);
    }

    /// One judged Gate: its job, its verdict and the judge it counts under.
    public record Vote(long job, JobState verdict, String judge) {
        public static Vote vote(long job, JobState verdict, String judge) {
            return new Vote(job, verdict, judge);
        }
    }

    /// A Gate judged across a deploy: granted under `grantedJudge`, finished under `judge`.
    public record JudgeChanged(long job, JobState verdict, String grantedJudge, String judge) {
        public static JudgeChanged judgeChanged(long job, JobState verdict, String grantedJudge, String judge) {
            return new JudgeChanged(job, verdict, grantedJudge, judge);
        }
    }
}
