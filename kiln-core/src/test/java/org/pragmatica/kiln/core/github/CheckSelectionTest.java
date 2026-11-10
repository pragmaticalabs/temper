package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.kiln.core.Fixtures.resource;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// K-11 (spec §4.4): the selection. Of the runs named as the context and from the required app, the latest decides (the
/// greatest `started_at`, a run with none the newest, ties broken by the greatest `id`); a same-named run of another app
/// is ignored and counted as `foreign`; `startup_failure` is shown verbatim. Statuses answer only a check with no app id.
/// Runs and statuses are kept once per `id` before any of it.
class CheckSelectionTest {
    private static final AppId ACTIONS = ok(AppId.appId(15368));
    private static final AppId CODE_RABBIT = ok(AppId.appId(347564));
    private static final RequiredCheck BUILD = new RequiredCheck(ok(CheckContext.checkContext("build")), some(ACTIONS));
    private static final RequiredCheck CI = new RequiredCheck(ok(CheckContext.checkContext("ci")), none());
    private static final Instant T0 = Instant.parse("2026-10-30T01:00:00Z");

    private static CheckRun run(long id, String name, AppId app, String conclusion, Instant startedAt) {
        return new CheckRun(id, name, some(app), "completed", some(conclusion), some(startedAt));
    }

    private static CommitStatus status(long id, String state, Instant updatedAt) {
        return new CommitStatus(id, "ci", state, none(), updatedAt);
    }

    private static Cell only(RequiredCheck check, List<CheckRun> runs, List<CommitStatus> statuses) {
        return CheckCells.cells(new RequiredChecks.Required(List.of(check), "non_admins"), success(runs), success(statuses))
                         .getFirst();
    }

    /// A rerun: the later start decides, whatever the ids and the order GitHub lists them in.
    @Test
    void rerun_theLaterStartDecides() {
        var early = run(10, "build", ACTIONS, "failure", T0);
        var late = run(5, "build", ACTIONS, "success", T0.plusSeconds(60));

        assertThat(only(BUILD, List.of(early, late), List.of()).value()).isEqualTo("success");
        assertThat(only(BUILD, List.of(late, early), List.of()).value()).isEqualTo("success");
        assertThat(only(BUILD,
                        List.of(run(10, "build", ACTIONS, "success", T0), run(5, "build", ACTIONS, "failure", T0.plusSeconds(60))),
                        List.of()).value()).isEqualTo("failure");
    }

    @Test
    void rerun_aTiedStart_isDecidedByTheGreaterId() {
        assertThat(only(BUILD,
                        List.of(run(9, "build", ACTIONS, "success", T0), run(7, "build", ACTIONS, "failure", T0)),
                        List.of()).value()).isEqualTo("success");
        assertThat(only(BUILD,
                        List.of(run(7, "build", ACTIONS, "success", T0), run(9, "build", ACTIONS, "failure", T0)),
                        List.of()).value()).isEqualTo("failure");
    }

    /// A run with no `started_at` has not started yet: it is the newest and decides the cell. The verifier's case: a
    /// queued rerun beside an older success reads `pending`, as GitHub's latest run is.
    @Test
    void rerun_aRunWithNoStart_isTheNewest() {
        var queued = new CheckRun(1, "build", some(ACTIONS), "queued", none(), none());
        var older = run(99, "build", ACTIONS, "success", T0.plusSeconds(3600));

        assertThat(only(BUILD, List.of(older, queued), List.of()).value()).isEqualTo("pending");
        assertThat(only(BUILD, List.of(queued, older), List.of()).value()).isEqualTo("pending");
        assertThat(only(BUILD, List.of(older), List.of()).value()).isEqualTo("success");
    }

    /// Two runs with no start: the greater id decides.
    @Test
    void rerun_twoRunsWithNoStart_areDecidedByTheGreaterId() {
        var queued = new CheckRun(9, "build", some(ACTIONS), "queued", none(), none());
        var failed = new CheckRun(7, "build", some(ACTIONS), "completed", some("failure"), none());

        assertThat(only(BUILD, List.of(queued, failed), List.of()).value()).isEqualTo("pending");
        assertThat(only(BUILD,
                        List.of(new CheckRun(7, "build", some(ACTIONS), "queued", none(), none()),
                                new CheckRun(9, "build", some(ACTIONS), "completed", some("failure"), none())),
                        List.of()).value()).isEqualTo("failure");
    }

    /// c6: pagination lists one run on two pages. It is counted once, and the later page's copy (the latest read) decides.
    @Test
    void paginationDuplicate_countsARunOnce_andTheLastCopyDecides() {
        var foreign = run(2, "build", CODE_RABBIT, "success", T0);

        assertThat(only(BUILD, List.of(run(1, "build", ACTIONS, "success", T0), foreign, foreign), List.of()).foreign()).isEqualTo(1);
        assertThat(only(BUILD, List.of(foreign), List.of()).foreign()).isEqualTo(1);

        var queued = new CheckRun(1, "build", some(ACTIONS), "queued", none(), some(T0));
        var done = run(1, "build", ACTIONS, "failure", T0);

        assertThat(only(BUILD, List.of(queued, done), List.of()).value()).isEqualTo("failure");
        assertThat(only(BUILD, List.of(done, queued), List.of()).value()).isEqualTo("pending");
    }

    /// A status listed on two pages is kept once, the later page's copy, as runs are.
    @Test
    void paginationDuplicate_keepsTheLastCopyOfAStatus() {
        assertThat(only(CI, List.of(), List.of(status(5, "failure", T0.plusSeconds(1)), status(5, "success", T0))).value()).isEqualTo("success");
        assertThat(only(CI, List.of(), List.of(status(5, "success", T0), status(5, "failure", T0.plusSeconds(1)))).value()).isEqualTo("failure");
    }

    @Test
    void foreignApp_isIgnored_andCounted() {
        var cell = only(BUILD,
                        List.of(run(1, "build", ACTIONS, "failure", T0),
                                run(2, "build", CODE_RABBIT, "success", T0.plusSeconds(60)),
                                new CheckRun(3, "build", none(), "completed", some("success"), some(T0.plusSeconds(90)))),
                        List.of());

        assertThat(cell.value()).isEqualTo("failure");
        assertThat(cell.foreign()).isEqualTo(2);
        assertThat(cell.shown()).containsExactly("failure");
    }

    @Test
    void foreignApp_onlyForeignRuns_readMissing() {
        var cell = only(BUILD, List.of(run(2, "build", CODE_RABBIT, "success", T0)), List.of());

        assertThat(cell.value()).isEqualTo("missing");
        assertThat(cell.foreign()).isEqualTo(1);
        assertThat(cell.isSuccess()).isFalse();
    }

    /// No app id required: any app's run answers, and none is foreign.
    @Test
    void noAppId_anyAppsRunAnswers() {
        var cell = only(CI, List.of(run(2, "ci", CODE_RABBIT, "success", T0)), List.of());

        assertThat(cell.value()).isEqualTo("success");
        assertThat(cell.foreign()).isZero();
    }

    @Test
    void startupFailure_isShownVerbatim_notSuccess() {
        var cell = only(BUILD, List.of(run(1, "build", ACTIONS, "startup_failure", T0)), List.of());

        assertThat(cell.value()).isEqualTo("startup_failure");
        assertThat(cell.isSuccess()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "neutral", "skipped", "cancelled", "timed_out", "action_required", "stale",
                            "SUCCESS", "a-new-conclusion"})
    void everyOtherConclusion_isShownVerbatim_notSuccess(String conclusion) {
        var cell = only(BUILD, List.of(run(1, "build", ACTIONS, conclusion, T0)), List.of());

        assertThat(cell.value()).isEqualTo(conclusion);
        assertThat(cell.isSuccess()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "in_progress", "waiting", "requested", "pending"})
    void aWaitingRun_isPending(String status) {
        var cell = only(BUILD, List.of(new CheckRun(1, "build", some(ACTIONS), status, none(), some(T0))), List.of());

        assertThat(cell.value()).isEqualTo("pending");
    }

    /// A waiting run that already names a conclusion is still waiting: the status decides.
    @Test
    void aWaitingRun_withAConclusion_isPending_notSuccess() {
        var cell = only(BUILD, List.of(new CheckRun(1, "build", some(ACTIONS), "in_progress", some("success"), some(T0))), List.of());

        assertThat(cell.value()).isEqualTo("pending");
    }

    @Test
    void anUnnamedStatusOrAMissingConclusion_isShownVerbatim() {
        assertThat(only(BUILD, List.of(new CheckRun(1, "build", some(ACTIONS), "paused", none(), some(T0))), List.of()).value()).isEqualTo("paused");
        assertThat(only(BUILD, List.of(new CheckRun(1, "build", some(ACTIONS), "completed", none(), some(T0))), List.of()).value()).isEqualTo("completed");
    }

    @Test
    void noRunNamedAsTheContext_isMissing() {
        var cell = only(BUILD, List.of(run(1, "build-and-test", ACTIONS, "success", T0)), List.of());

        assertThat(cell.value()).isEqualTo("missing");
        assertThat(cell.shown()).isEmpty();
        assertThat(cell.foreign()).isZero();
    }

    /// No app id and both a run and a status: both shown, success only if both are.
    @Test
    void noAppId_runAndStatus_bothShown_successOnlyIfBoth() {
        var good = run(1, "ci", ACTIONS, "success", T0);

        assertThat(only(CI, List.of(good), List.of(status(1, "success", T0))).shown()).containsExactly("success", "success");
        assertThat(only(CI, List.of(good), List.of(status(1, "success", T0))).isSuccess()).isTrue();
        assertThat(only(CI, List.of(good), List.of(status(1, "failure", T0))).value()).isEqualTo("failure");
        assertThat(only(CI, List.of(run(1, "ci", ACTIONS, "cancelled", T0)), List.of(status(1, "success", T0))).value()).isEqualTo("cancelled");
        assertThat(only(CI, List.of(), List.of(status(1, "pending", T0))).value()).isEqualTo("pending");
        assertThat(only(CI, List.of(), List.of(status(1, "error", T0))).value()).isEqualTo("error");
    }

    @Test
    void status_theLatestUpdateDecides_tiesByTheGreaterId() {
        assertThat(only(CI, List.of(), List.of(status(9, "failure", T0), status(2, "success", T0.plusSeconds(1)))).value()).isEqualTo("success");
        assertThat(only(CI, List.of(), List.of(status(9, "success", T0), status(2, "failure", T0.plusSeconds(1)))).value()).isEqualTo("failure");
        assertThat(only(CI, List.of(), List.of(status(9, "success", T0), status(2, "failure", T0))).value()).isEqualTo("success");
        assertThat(only(CI, List.of(), List.of(status(2, "success", T0), status(9, "failure", T0))).value()).isEqualTo("failure");
    }

    @Test
    void status_ofAnotherContext_isNotShown() {
        var other = new CommitStatus(1, "lint", "failure", none(), T0);

        assertThat(only(CI, List.of(run(1, "ci", ACTIONS, "success", T0)), List.of(other)).shown()).containsExactly("success");
    }

    /// A pinned check is answered by its app's runs alone: a status of the same context is not read.
    @Test
    void pinnedCheck_readsNoStatus() {
        var status = new CommitStatus(1, "build", "failure", none(), T0);
        var cell = CheckCells.cells(new RequiredChecks.Required(List.of(BUILD), "non_admins"),
                                    success(List.of(run(1, "build", ACTIONS, "success", T0))),
                                    success(List.of(status)))
                             .getFirst();

        assertThat(cell.shown()).containsExactly("success");
        assertThat(cell.isSuccess()).isTrue();
    }

    /// A failed runs read makes every cell UNKNOWN; a failed statuses read only the cells that read statuses.
    @Test
    void aFailedRead_givesUnknownCells_neverTheOldOnes() {
        var required = new RequiredChecks.Required(List.of(BUILD, CI), "non_admins");
        Result<List<CheckRun>> runs = success(List.of(run(1, "build", ACTIONS, "success", T0), run(2, "ci", ACTIONS, "success", T0)));

        assertThat(CheckCells.cells(required, cause("HTTP 502").result(), success(List.of()))).extracting(Cell::value)
                                                                                         .containsExactly("UNKNOWN", "UNKNOWN");
        assertThat(CheckCells.cells(required, runs, cause("HTTP 502").result())).extracting(Cell::value)
                                                                            .containsExactly("success", "UNKNOWN");
        assertThat(CheckCells.cells(required, runs, cause("HTTP 502").result())
                             .get(1)
                             .isSuccess()).isFalse();
    }

    @Test
    void cells_followTheRequiredOrder_andGiveNoneForAnUnrequiredList() {
        var required = new RequiredChecks.Required(List.of(CI, BUILD), "non_admins");
        Result<List<CommitStatus>> statuses = success(List.of());

        assertThat(CheckCells.cells(required, success(List.of()), statuses)).extracting(Cell::check)
                                                                          .containsExactly(CI, BUILD);
        assertThat(CheckCells.cells(new RequiredChecks.NoneRequired("off", false), success(List.of()), statuses)).isEmpty();
        assertThat(CheckCells.cells(new RequiredChecks.Unknown("403"), success(List.of()), statuses)).isEmpty();
    }

    /// The live rc5 list against the live runs at #2504's head: nine success cells, CodeRabbit never among them.
    @Test
    void live_rc5ListAtPr2504_readsNineSuccessCells() {
        var required = RequiredChecks.resolve(BranchRead.decode(resource("github/branch-release-1.0.0-rc5.json")),
                                              success(List.of()),
                                              true);
        var cells = CheckCells.cells(required,
                                     CheckRun.decode(resource("github/check-runs-pr2504.json")),
                                     success(List.<CommitStatus>of()));

        assertThat(cells).hasSize(9)
                         .allMatch(Cell::isSuccess)
                         .allMatch(cell -> cell.foreign() == 0)
                         .noneMatch(cell -> cell.check()
                                                .context()
                                                .value()
                                                .equals("CodeRabbit"));
        assertThat(cells).extracting(Cell::shown)
                         .allMatch(shown -> shown.equals(List.of("success")));
    }
}
