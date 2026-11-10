package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.protocol.api.DiffRefusal;
import org.pragmatica.temper.protocol.api.DiffView;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.StoredSide;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `GET /api/v1/diff` (m2-design §3.3) through the router, over the fake results' sides (answered on another thread)
/// and the fake jobs' gate rows: the two forms, the typed refusals, the 404 and the 400s. `--base` goes through the real
/// [GateRoutes#evidence], so `diff --base S` and `gate S` read the same Gate.
class DiffRoutesTest {
    private static final String SHA = ApiFixture.SHA;
    private static final String OTHER_SHA = "0cf5631ff" + "1".repeat(31);
    private static final Judge X = Judge.judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d").unwrap();

    private final ApiFixture fixture = new ApiFixture(List.of());

    @Test
    void twoJobs_areCompared_sideAFirst() {
        side(4690, JobState.PASSED, Recipe.gate(), 2, List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "two", Outcome.PASSED)));
        side(4711, JobState.FAILED, Recipe.gate(), 2, List.of(row("a.A", "one", Outcome.FAILED), row("a.A", "new", Outcome.FAILED)));

        var view = (DiffView) diff("a=4690&b=4711");

        assertThat(view.a()).isEqualTo(DiffView.Side.side(4690, JobState.PASSED, "gate", 2));
        assertThat(view.b()).isEqualTo(DiffView.Side.side(4711, JobState.FAILED, "gate", 2));
        assertThat(view.base()).isEqualTo(none());
        assertThat(view.added()).containsExactly(DiffView.Entry.entry("a.A", "new", KeyOutcome.FAILED));
        assertThat(view.removed()).containsExactly(DiffView.Entry.entry("a.A", "two", KeyOutcome.PASSED));
        assertThat(view.newlyFailing()).containsExactly(DiffView.Entry.entry("a.A", "one", KeyOutcome.FAILED));
        assertThat(view.counts()).isEqualTo(DiffView.Counts.counts(1, 1, 1, 1, 0, 0, 0));
        assertThat(fixture.results.diffReads).containsExactly(List.of(job(4690), job(4711)));
    }

    /// Any recipe pair is compared (m2-design §3.1).
    @Test
    void anyRecipePair_isCompared() {
        side(41, JobState.PASSED, Recipe.moduleTest(List.of("core"), none(), none()).unwrap(), 1, List.of(row("a.A", "one", Outcome.PASSED)));
        side(42, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        var view = (DiffView) diff("a=41&b=42");

        assertThat(view.a()).isEqualTo(DiffView.Side.side(41, JobState.PASSED, "module-test", 1));
        assertThat(view.counts()).isEqualTo(DiffView.Counts.counts(0, 0, 0, 0, 0, 0, 0));
    }

    /// R-M2-13: a VOID, ERROR or CANCELLED side is refused (`side not judged: <verdict>`), its verdict named.
    @Test
    void unjudgedSide_isATypedRefusal_namingItsVerdict() {
        side(41, JobState.VOID, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));
        side(42, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(diff("a=42&b=41")).isEqualTo(DiffRefusal.diffRefusal(DiffObstacle.NOT_JUDGED,
                                                                        some(42L),
                                                                        41,
                                                                        List.of(DiffRefusal.SideState.sideState(DiffOutcome.Side.B, 41, JobState.VOID)),
                                                                        none(),
                                                                        List.of()));
    }

    /// The rows of a rerun unit are not the verdict's (`Verdicts.primaryUnits`): they are neither counted nor compared.
    @Test
    void rerunUnitRows_areLeftOut() {
        var rerunUnit = UnitName.unitName("rerun-1").unwrap();
        var units = List.of(Rows.unit(4711, UnitState.PASSED),
                            UnitRow.unitRow(2, rerunUnit, UnitKind.RERUN, UnitState.FAILED, none(), none(),
                                            List.of(List.of("mvn", "-B", "test")), true, false, 6144, Duration.ofMinutes(30),
                                            none(), none(), none(), none(), none(), none(), none(), none(), none(), none(), true,
                                            none(), none(), none(), none(), 1));

        side(4690, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));
        fixture.results.sides.put(job(4711),
                                  StoredSide.storedSide(jobRow(4711, JobState.PASSED, Recipe.gate(), units, some("PASSED")),
                                                        some(record(4711, JobState.PASSED, 1)),
                                                        List.of(row("a.A", "one", Outcome.PASSED),
                                                                TestcaseRow.testcaseRow(rerunUnit, "a.A", "one", Outcome.FAILED, 0.1, false))));

        var view = (DiffView) diff("a=4690&b=4711");

        assertThat(view.newlyFailing()).as("the rerun unit's FAILED row").isEmpty();
        assertThat(view.b().testcases()).isEqualTo(1);
    }

    /// F16: a side with fewer rows than its record counts is refused, not reported as every test removed.
    @Test
    void prunedSide_isATypedRefusal() {
        side(4690, JobState.PASSED, Recipe.gate(), 2, List.of());
        side(4711, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(diff("a=4690&b=4711")).isEqualTo(DiffRefusal.diffRefusal(DiffObstacle.PRUNED,
                                                                            some(4690L),
                                                                            4711,
                                                                            List.of(DiffRefusal.SideState.sideState(DiffOutcome.Side.A, 4690, JobState.PASSED)),
                                                                            none(),
                                                                            List.of()));
    }

    /// A side without a record is not terminal; its state is the shown one (a QUEUED job whose unit was granted longer
    /// ago than the start bound shows UNKNOWN, R-13 amended).
    @Test
    void sideWithoutARecord_isNotTerminal_withItsShownState() {
        side(4690, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));
        fixture.results.sides.put(job(4711),
                                  StoredSide.storedSide(jobRow(4711,
                                                               JobState.QUEUED,
                                                               Recipe.gate(),
                                                               List.of(Rows.granted(Instant.now().minus(Duration.ofHours(2)), none())),
                                                               none()),
                                                        none(),
                                                        List.of()));

        var refusal = (DiffRefusal) diff("a=4690&b=4711");

        assertThat(refusal.reason()).isEqualTo(DiffObstacle.NOT_TERMINAL);
        assertThat(refusal.sides()).containsExactly(DiffRefusal.SideState.sideState(DiffOutcome.Side.B, 4711, JobState.UNKNOWN));
    }

    @Test
    void unknownJob_is404() {
        side(4690, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(status("a=4690&b=4799")).isEqualTo(404);
        assertThat(status("a=4799&b=4690")).isEqualTo(404);
    }

    @Test
    void query_mustBeExactlyOneForm_withParsableValues() {
        for (var query : List.of("a=1", "a=1&b=2&baseSha=0cf5631", "b=2", "a=x&b=2", "a=0&b=2", "a=1&b=-3", "a=1&baseSha=0cf563",
                                 "a=1&baseSha=xyz5631", "baseSha=0cf5631")) {
            assertThat(status(query)).as(query).isEqualTo(400);
        }
    }

    // ---- --base ------------------------------------------------------------------------------------------------
    /// The base is the Gate `gate <sha>` answers about; the job given is side B. A prefix names the SHA.
    @Test
    void base_isTheAnsweringGate_onSideA() {
        fixture.jobs.gateRows.addAll(List.of(gate(4650, JobState.VOID), gate(4690, JobState.PASSED)));
        side(4690, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));
        side(4711, JobState.FAILED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.FAILED)));

        var view = (DiffView) diff("a=4711&baseSha=0cf5631");

        assertThat(view.base()).isEqualTo(some(4690L));
        assertThat(view.a().job()).isEqualTo(4690);
        assertThat(view.b().job()).isEqualTo(4711);
        assertThat(view.newlyFailing()).hasSize(1);
        assertThat(fixture.results.diffReads).containsExactly(List.of(job(4690), job(4711)));
    }

    /// Q-M2-26 (ruled R-M2-9): a CONFLICT is no base; every vote is listed and no sides are read.
    @Test
    void conflictingBase_isRefused_listingTheVotes_readingNoSides() {
        fixture.jobs.gateRows.addAll(List.of(gate(4690, JobState.PASSED), gate(4711, JobState.FAILED)));
        side(4720, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(diff("a=4720&baseSha=" + SHA)).isEqualTo(DiffRefusal.diffRefusal(DiffObstacle.BASE_CONFLICT,
                                                                                    none(),
                                                                                    4720,
                                                                                    List.of(),
                                                                                    some(SHA),
                                                                                    List.of(GateView.Vote.vote(4711, JobState.FAILED, X.value()),
                                                                                            GateView.Vote.vote(4690, JobState.PASSED, X.value()))));
        assertThat(fixture.results.diffReads).isEmpty();
    }

    @Test
    void noGateAtTheSha_isNoBase() {
        side(4720, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(diff("a=4720&baseSha=0cf5639")).isEqualTo(DiffRefusal.diffRefusal(DiffObstacle.NO_BASE,
                                                                                    none(),
                                                                                    4720,
                                                                                    List.of(),
                                                                                    some("0cf5639"),
                                                                                    List.of()));
    }

    /// An unknown job is a 404 even when the SHA has no base: a typo is a usage error, not a fact about the SHA.
    @Test
    void base_unknownJob_is404_beforeTheBase() {
        assertThat(status("a=4799&baseSha=0cf5639")).isEqualTo(404);
    }

    @Test
    void base_prefixOfGatesAtTwoShas_is400() {
        fixture.jobs.gateRows.addAll(List.of(gate(4690, JobState.PASSED), finishedAt(4712, OTHER_SHA, JobState.FAILED)));
        side(4720, JobState.PASSED, Recipe.gate(), 1, List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(status("a=4720&baseSha=0cf5631")).isEqualTo(400);
    }

    private DiffAnswer diff(String query) {
        var response = fixture.readAuthed(HttpMethod.GET, "/api/v1/diff?" + query, "");

        assertThat(response.status().code()).as(response.bodyText()).isEqualTo(200);
        return TemperJson.mapper().readString(response.bodyText(), DiffAnswer.class).unwrap();
    }

    private int status(String query) {
        return fixture.readAuthed(HttpMethod.GET, "/api/v1/diff?" + query, "").status().code();
    }

    private void side(long id, JobState state, Recipe recipe, int testcases, List<TestcaseRow> rows) {
        fixture.results.sides.put(job(id),
                                  StoredSide.storedSide(jobRow(id, state, recipe, List.of(Rows.unit(id, UnitState.valueOf(state.name()))), some(state.name())),
                                                        some(record(id, state, testcases)),
                                                        rows));
    }

    private static GateRow gate(long id, JobState state) {
        return finishedAt(id, SHA, state);
    }

    private static GateRow finishedAt(long id, String sha, JobState state) {
        var row = JobRow.jobRow(job(id), OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), -20, false, false, false, none(),
                                some(Sha.sha(sha).unwrap()), Recipe.gate(), none(), state, none(), none(), false, none(), none(),
                                none(), some(state.name()), none(), List.of(Rows.unit(id, UnitState.valueOf(state.name()))), List.of());

        return GateRow.gateRow(row, X, List.of(X), some(X), some(record(id, state, 1)));
    }

    private static JobRow jobRow(long id, JobState state, Recipe recipe, List<UnitRow> units, Option<String> verdict) {
        return JobRow.jobRow(job(id), OwnerTag.ownerTag("v2174").unwrap(), Instant.now(), -20, false, false, false, none(),
                             some(Sha.sha(SHA).unwrap()), recipe, none(), state, none(), none(), false, none(), none(), none(),
                             verdict, none(), units, List.of());
    }

    private static ResultRecord record(long id, JobState state, int testcases) {
        return ResultRecord.resultRecord(id,
                                         state.name(),
                                         none(),
                                         none(),
                                         ResultRecord.Source.source(none(), some(SHA)),
                                         ResultRecord.Recipe.Gate.gate(),
                                         List.of(),
                                         Counts.counts(testcases, testcases, 0, 0, 0, testcases, 1, none(), none(), none()),
                                         List.of(),
                                         0,
                                         List.of(),
                                         none(),
                                         ResultRecord.Evidence.evidence(true, none(), none()),
                                         ResultRecord.Timings.timings(none(), none()),
                                         some(X.value()));
    }

    private static TestcaseRow row(String className, String name, Outcome outcome) {
        return TestcaseRow.testcaseRow(UnitName.MAIN, className, name, outcome, 0.1, false);
    }

    private static JobId job(long id) {
        return JobId.jobId(id).unwrap();
    }
}
