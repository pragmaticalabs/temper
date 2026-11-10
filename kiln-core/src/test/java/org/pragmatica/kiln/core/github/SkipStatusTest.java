package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// K-7 (spec §4.4): a commit status `success` whose description matches `(?i)skip` reads `success*`, which is not
/// success. Only `success` counts as success, anywhere.
class SkipStatusTest {
    private static final RequiredCheck CI = new RequiredCheck(ok(CheckContext.checkContext("ci")), none());

    private static Cell cell(Option<String> description, List<CheckRun> runs) {
        var status = new CommitStatus(1, "ci", "success", description, Instant.parse("2026-10-30T01:00:00Z"));
        return CheckCells.cells(new RequiredChecks.Required(List.of(CI), "non_admins"), success(runs), success(List.of(status)))
                         .getFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skipped", "Skipped", "SKIP", "build skipped by path filter", "Skipping: docs only"})
    void status_successWithASkipDescription_isSuccessStar_notSuccess(String description) {
        var cell = cell(some(description), List.of());

        assertThat(cell.value()).isEqualTo("success*");
        assertThat(cell.shown()).containsExactly("success*");
        assertThat(cell.isSuccess()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"All checks passed", "Build succeeded", ""})
    void status_successWithAnyOtherDescription_isSuccess(String description) {
        var cell = cell(some(description), List.of());

        assertThat(cell.value()).isEqualTo("success");
        assertThat(cell.isSuccess()).isTrue();
    }

    @Test
    void status_successWithNoDescription_isSuccess() {
        assertThat(cell(none(), List.of()).isSuccess()).isTrue();
    }

    /// A successful run beside a skipped status: the cell shows both and is not success.
    @Test
    void status_skipped_besideASuccessfulRun_isNotSuccess() {
        var run = new CheckRun(5, "ci", none(), "completed", some("success"), some(Instant.parse("2026-10-30T01:00:00Z")));
        var cell = cell(some("skipped"), List.of(run));

        assertThat(cell.shown()).containsExactly("success", "success*");
        assertThat(cell.value()).isEqualTo("success*");
        assertThat(cell.isSuccess()).isFalse();
    }

    /// A check run concluded `skipped` is shown verbatim, also not success.
    @Test
    void run_concludedSkipped_isShownVerbatim_notSuccess() {
        var run = new CheckRun(5, "ci", none(), "completed", some("skipped"), some(Instant.parse("2026-10-30T01:00:00Z")));
        var cell = CheckCells.cells(new RequiredChecks.Required(List.of(CI), "non_admins"), success(List.of(run)), success(List.of()))
                             .getFirst();

        assertThat(cell.value()).isEqualTo("skipped");
        assertThat(cell.isSuccess()).isFalse();
    }
}
