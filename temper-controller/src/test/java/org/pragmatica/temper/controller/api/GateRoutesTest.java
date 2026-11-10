package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.RerunRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `GET /api/v1/gate/{sha}` (m2-design §1.4) through the router over the fake store's gate rows: the view carries
/// the selection's answer and history, every candidate's state is the shown one, a prefix resolves to the one SHA its
/// Gates are at, and a SHA without Gates is a NONE answer, not a 404.
class GateRoutesTest {
    private static final String SHA = ApiFixture.SHA;
    private static final String OTHER_SHA = "0cf5631ff" + "1".repeat(31);
    private static final Judge X = Judge.judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d").unwrap();
    private static final Judge Y = Judge.judge("5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80").unwrap();
    private static final Counts COUNTS = Counts.counts(21048, 21040, 1, 0, 7, 21048, 600, none(), none(), none());

    private final ApiFixture fixture = new ApiFixture(List.of());

    @Test
    void getGate_failed_carriesJobJudgeCountsAnnotationsAndEvidence() {
        fixture.jobs.gateRows.add(finished(4711, SHA, JobState.FAILED, X, List.of("KNOWN(#1679)", "evidence=missing"), false));

        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.FAILED);
        assertThat(view.sha()).isEqualTo(SHA);
        assertThat(view.job()).isEqualTo(some(4711L));
        assertThat(view.judge()).isEqualTo(some(X.value()));
        assertThat(view.state()).isEqualTo(some(JobState.FAILED));
        assertThat(view.counts().map(counts -> counts.testcases())).isEqualTo(some(21048));
        assertThat(view.annotations()).containsExactly("KNOWN(#1679)", "evidence=missing");
        assertThat(view.evidence()).isEqualTo(some("missing"));
    }

    /// m2-design §5.3: the answering Gate's reruns are listed with their per-class outcomes, information only; another
    /// Gate's reruns are not, and the answer stays the Gate's.
    @Test
    void getGate_listsTheAnsweringGatesReruns_only() {
        var answering = finished(4711, SHA, JobState.FAILED, X, List.of(), true);
        var older = finished(4650, SHA, JobState.FAILED, Y, List.of(), true);

        fixture.jobs.gateRows.addAll(List.of(withReruns(older, List.of(RerunRow.rerunRow(JobId.jobId(4655).unwrap(), JobState.PASSED, List.of()))),
                                             withReruns(answering,
                                                        List.of(RerunRow.rerunRow(JobId.jobId(4740).unwrap(),
                                                                                  JobState.PASSED,
                                                                                  List.of("rerun-of=4711", "rerun(org.x.FooTest)=PASSED", "evidence=missing")),
                                                                RerunRow.rerunRow(JobId.jobId(4745).unwrap(), JobState.RUNNING, List.of())))));

        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.FAILED);
        assertThat(view.job()).isEqualTo(some(4711L));
        assertThat(view.reruns()).containsExactly(RerunView.rerunView(4740, JobState.PASSED, List.of("rerun(org.x.FooTest)=PASSED")),
                                                  RerunView.rerunView(4745, JobState.RUNNING, List.of()));
    }

    private static GateRow withReruns(GateRow row, List<RerunRow> reruns) {
        var job = row.job();

        return GateRow.gateRow(JobRow.jobRow(job.id(), job.submittedBy(), job.submittedAt(), job.priority(), job.keep(), job.fresh(),
                                             job.unpushed(), job.ref(), job.sha(), job.recipe(), job.dedupKey(), job.state(),
                                             job.refusalCode(), job.refusalDetail(), job.superseded(), job.supersededBy(),
                                             job.cancelRequestedAt(), job.finishedAt(), job.verdict(), job.reason(), job.units(), reruns),
                               row.submitJudge(),
                               row.grantJudges(),
                               row.resultJudge(),
                               row.record());
    }

    /// R-M2-5 through the route: both votes listed, no job answers.
    @Test
    void getGate_disagreement_isConflict_listingEveryVote_andTheNewerRunningGate() {
        fixture.jobs.gateRows.addAll(List.of(finished(4690, SHA, JobState.PASSED, X, List.of(), true),
                                             finished(4711, SHA, JobState.FAILED, X, List.of(), true),
                                             running(4720, SHA, UnitState.RUNNING, JobState.RUNNING, X)));

        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.CONFLICT);
        assertThat(view.job()).isEqualTo(none());
        assertThat(view.conflict()).containsExactly(GateView.Vote.vote(4711, JobState.FAILED, X.value()),
                                                    GateView.Vote.vote(4690, JobState.PASSED, X.value()));
        assertThat(view.running()).isEqualTo(some(4720L));
    }

    /// R-M2-10 and R-M2-11 through the route: an older judge's vote is history, a Gate judged across a deploy too.
    @Test
    void getGate_newerJudge_answers_olderAndJudgeChangedAreListed() {
        fixture.jobs.gateRows.addAll(List.of(finished(4650, SHA, JobState.FAILED, X, List.of(), true),
                                             acrossDeploy(4700, SHA, JobState.FAILED, X, Y),
                                             finished(4760, SHA, JobState.PASSED, Y, List.of(), true)));

        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.PASSED);
        assertThat(view.job()).isEqualTo(some(4760L));
        assertThat(view.judge()).isEqualTo(some(Y.value()));
        assertThat(view.older()).containsExactly(GateView.Vote.vote(4650, JobState.FAILED, X.value()));
        assertThat(view.judgeChanged()).containsExactly(GateView.JudgeChanged.judgeChanged(4700, JobState.FAILED, X.value(), Y.value()));
    }

    /// The shown state (R-13, amended): a QUEUED Gate whose unit was granted longer ago than the start bound shows
    /// UNKNOWN, and answers RUNNING with the vote it would otherwise be.
    @Test
    void getGate_runningAnswer_showsTheDerivedState() {
        var granted = Rows.granted(Instant.now().minus(Duration.ofHours(2)), none());

        fixture.jobs.gateRows.addAll(List.of(finished(4711, SHA, JobState.FAILED, X, List.of(), true),
                                             GateRow.gateRow(job(4720, SHA, JobState.QUEUED, List.of(granted), none()),
                                                             X,
                                                             List.of(X),
                                                             none(),
                                                             none())));

        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.RUNNING);
        assertThat(view.state()).isEqualTo(some(JobState.UNKNOWN));
        assertThat(view.previous()).isEqualTo(some(GateView.Vote.vote(4711, JobState.FAILED, X.value())));
        assertThat(view.counts()).isEqualTo(none());
        assertThat(view.evidence()).isEqualTo(none());
    }

    @Test
    void getGate_withoutGates_isNone_notA404() {
        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.NONE);
        assertThat(view.sha()).isEqualTo(SHA);
        assertThat(view.judge()).isEqualTo(none());
    }

    /// A VOID never answers (R-M2-9): NONE, the VOID listed.
    @Test
    void getGate_voidOnly_isNone_listingTheUnjudged() {
        fixture.jobs.gateRows.add(finished(4702, SHA, JobState.VOID, X, List.of(), true));

        var view = gate(SHA);

        assertThat(view.answer()).isEqualTo(GateAnswer.NONE);
        assertThat(view.unjudged()).containsExactly(4702L);
    }

    @Test
    void getGate_prefix_resolvesToTheFullSha_andTheUppercaseFormToo() {
        fixture.jobs.gateRows.add(finished(4711, SHA, JobState.PASSED, X, List.of(), true));

        assertThat(gate("0cf5631").sha()).isEqualTo(SHA);
        assertThat(gate("0CF5631").answer()).isEqualTo(GateAnswer.PASSED);
        assertThat(gate("0cf5639").answer()).as("no Gate starts with it").isEqualTo(GateAnswer.NONE);
        assertThat(gate("0cf5639").sha()).as("the asked prefix").isEqualTo("0cf5639");
    }

    /// m2-design §1.4: a prefix of Gates at two SHAs is a 400 naming both, never a guess.
    @Test
    void getGate_ambiguousPrefix_is400_namingBothShas() {
        fixture.jobs.gateRows.addAll(List.of(finished(4711, SHA, JobState.PASSED, X, List.of(), true),
                                             finished(4712, OTHER_SHA, JobState.FAILED, X, List.of(), true)));

        var response = fixture.readAuthed(HttpMethod.GET, "/api/v1/gate/0cf5631", "");

        assertThat(response.status().code()).isEqualTo(400);
        assertThat(response.bodyText()).contains(SHA).contains(OTHER_SHA);
        assertThat(gate(SHA).answer()).as("the full SHA is not ambiguous").isEqualTo(GateAnswer.PASSED);
    }

    @Test
    void getGate_notASha_is400() {
        for (var raw : List.of("0cf563", "xyz5631", "0cf5631" + "0".repeat(34))) {
            assertThat(fixture.readAuthed(HttpMethod.GET, "/api/v1/gate/" + raw, "").status().code()).as(raw).isEqualTo(400);
        }
    }

    private GateView gate(String sha) {
        var response = fixture.readAuthed(HttpMethod.GET, "/api/v1/gate/" + sha, "");

        assertThat(response.status().code()).as(response.bodyText()).isEqualTo(200);
        return TemperJson.mapper().readString(response.bodyText(), GateView.class).unwrap();
    }

    private static GateRow finished(long id, String sha, JobState state, Judge judge, List<String> annotations, boolean stored) {
        return GateRow.gateRow(job(id, sha, state, List.of(Rows.unit(id, UnitState.valueOf(state.name()))), some(state.name())),
                               judge,
                               List.of(judge),
                               some(judge),
                               some(record(id, sha, state, annotations, stored, judge)));
    }

    /// Granted under `granted`, finished under `finished` (R-M2-11).
    private static GateRow acrossDeploy(long id, String sha, JobState state, Judge granted, Judge finished) {
        return GateRow.gateRow(job(id, sha, state, List.of(Rows.unit(id, UnitState.valueOf(state.name()))), some(state.name())),
                               granted,
                               List.of(granted),
                               some(finished),
                               some(record(id, sha, state, List.of(Judge.changedAnnotation(granted)), true, finished)));
    }

    private static GateRow running(long id, String sha, UnitState unit, JobState state, Judge judge) {
        return GateRow.gateRow(job(id, sha, state, List.of(Rows.unit(id, unit)), none()), judge, List.of(judge), none(), none());
    }

    private static JobRow job(long id, String sha, JobState state, List<UnitRow> units, Option<String> verdict) {
        return JobRow.jobRow(JobId.jobId(id).unwrap(), OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), -20, false, false,
                             false, none(), some(Sha.sha(sha).unwrap()), Recipe.gate(), none(), state, none(), none(), false,
                             none(), none(), none(), verdict, none(), units, List.of());
    }

    private static ResultRecord record(long id, String sha, JobState state, List<String> annotations, boolean stored, Judge judge) {
        return ResultRecord.resultRecord(id,
                                         state.name(),
                                         none(),
                                         none(),
                                         ResultRecord.Source.source(none(), some(sha)),
                                         ResultRecord.Recipe.Gate.gate(),
                                         annotations,
                                         COUNTS,
                                         List.of(),
                                         0,
                                         List.of(),
                                         none(),
                                         ResultRecord.Evidence.evidence(stored, none(), none()),
                                         ResultRecord.Timings.timings(none(), none()),
                                         some(judge.value()));
    }
}
