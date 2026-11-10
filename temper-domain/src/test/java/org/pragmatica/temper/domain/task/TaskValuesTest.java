package org.pragmatica.temper.domain.task;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The task value objects' rules (m3-design §5.4, §5.5, §6.4, §8): every rule accepts and refuses at its edge.
class TaskValuesTest {
    private static final ParkRequest.Blocked BLOCKED = ParkRequest.Blocked.blocked(List.of(), "nothing").unwrap();

    private static ParkRequest.Choice choice(String id) {
        return ParkRequest.Choice.choice(id, "text " + id).unwrap();
    }

    private static ParkRequest.Recommendation recommend(String option, boolean temperDefault) {
        return ParkRequest.Recommendation.recommendation(option, "why", temperDefault).unwrap();
    }

    @Test
    void parkRequest_needsTwoDistinctOptions_andARecommendationOfOne() {
        assertThat(ParkRequest.requested(RulingKind.DESIGN, "Q", List.of(choice("a"), choice("b")), recommend("a", false), BLOCKED).isSuccess())
            .isTrue();
        assertThat(ParkRequest.requested(RulingKind.DESIGN, "Q", List.of(choice("a")), recommend("a", false), BLOCKED).isFailure()).isTrue();
        assertThat(ParkRequest.requested(RulingKind.DESIGN, "Q", List.of(choice("a"), choice("a")), recommend("a", false), BLOCKED).isFailure())
            .isTrue();
        assertThat(ParkRequest.requested(RulingKind.DESIGN, "Q", List.of(choice("a"), choice("b")), recommend("c", false), BLOCKED).isFailure())
            .isTrue();
        assertThat(ParkRequest.requested(RulingKind.DESIGN, " ", List.of(choice("a"), choice("b")), recommend("a", false), BLOCKED).isFailure())
            .isTrue();
    }

    @Test
    void parkRequest_questionUpTo4KiBOfUtf8() {
        var atLimit = "é".repeat(ParkRequest.MAX_QUESTION_BYTES / 2);
        var over = atLimit + "x";

        assertThat(ParkRequest.requested(RulingKind.DESIGN, atLimit, List.of(choice("a"), choice("b")), recommend("a", false), BLOCKED).isSuccess())
            .isTrue();
        assertThat(ParkRequest.requested(RulingKind.DESIGN, over, List.of(choice("a"), choice("b")), recommend("a", false), BLOCKED).isFailure())
            .isTrue();
    }

    @Test
    void parkRequest_byAWorkerOrTheCto_neverTemperRaised() {
        assertThat(ParkRequest.requested(RulingKind.STUCK_LEASE, "Q", List.of(choice("a"), choice("b")), recommend("a", false), BLOCKED)
                              .isFailure()).isTrue();
        assertThat(ParkRequest.requested(RulingKind.DESIGN, "Q", List.of(choice("a"), choice("b")), recommend("a", true), BLOCKED).isFailure())
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"round-cap", "stuck-lease", "worker-failures", "ci-absent", "ci-cancelled", "foreign-push", "audit-missed"})
    void rulingKind_temperRaised(String wire) {
        assertThat(RulingKind.rulingKind(wire).unwrap().isTemperRaised()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"design", "schema-unexplained", "surface-conflict", "merge-conflict", "budget"})
    void rulingKind_raisedByOthers(String wire) {
        assertThat(RulingKind.rulingKind(wire).unwrap().isTemperRaised()).isFalse();
    }

    @Test
    void rulingDecision_optionText_andExtraRoundsInRange() {
        assertThat(RulingDecision.rulingDecision("a", "go", none(), some(1)).isSuccess()).isTrue();
        assertThat(RulingDecision.rulingDecision("a", "go", some("f".repeat(40)), some(RulingDecision.MAX_EXTRA_ROUNDS)).isSuccess()).isTrue();
        assertThat(RulingDecision.rulingDecision("a", "go", none(), some(0)).isFailure()).isTrue();
        assertThat(RulingDecision.rulingDecision("a", "go", none(), some(RulingDecision.MAX_EXTRA_ROUNDS + 1)).isFailure()).isTrue();
        assertThat(RulingDecision.rulingDecision("a b", "go", none(), none()).isFailure()).isTrue();
        assertThat(RulingDecision.rulingDecision("a", " ", none(), none()).isFailure()).isTrue();
        assertThat(RulingDecision.rulingDecision("a", "go", some("abc"), none()).isFailure()).isTrue();
    }

    @Test
    void releaseReason_token_budgetIsNotAFailure() {
        assertThat(ReleaseReason.releaseReason("budget").unwrap().isBudget()).isTrue();
        assertThat(ReleaseReason.releaseReason("no-evidence").unwrap().isBudget()).isFalse();
        assertThat(ReleaseReason.releaseReason("No Evidence").isFailure()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"main,true", "master,true", "release-1.0,true", "release,true", "archive/old,true", "agent/42,false", "mainline,false"})
    void protectedRefs_namesAndPrefixes(String branch, boolean isProtected) {
        assertThat(ProtectedRefs.DEFAULT.isProtected(Ref.ref(branch).unwrap())).isEqualTo(isProtected);
    }

    @Test
    void newTask_fieldsAsTheTableChecksThem() {
        var brief = TaskBrief.taskBrief("briefs/a.md", none(), false, Map.of()).unwrap();

        assertThat(task("ok-1", brief, 0, 3, List.of(1L)).isSuccess()).isTrue();
        assertThat(task("Bad", brief, 0, 3, List.of()).isFailure()).isTrue();
        assertThat(task("x".repeat(41), brief, 0, 3, List.of()).isFailure()).isTrue();
        assertThat(task("ok-2", brief, 1001, 3, List.of()).isFailure()).isTrue();
        assertThat(task("ok-3", brief, -1000, 3, List.of()).isSuccess()).isTrue();
        assertThat(task("ok-4", brief, 0, 0, List.of()).isFailure()).isTrue();
        assertThat(task("ok-5", brief, 0, NewTask.MAX_ROUND_CAP + 1, List.of()).isFailure()).isTrue();
        assertThat(task("ok-6", brief, 0, 3, List.of(0L)).isFailure()).isTrue();
    }

    @Test
    void taskBrief_pathPresent_shaFull() {
        assertThat(TaskBrief.taskBrief(" ", none(), false, Map.of()).isFailure()).isTrue();
        assertThat(TaskBrief.taskBrief("b.md", some("abc"), false, Map.of()).isFailure()).isTrue();
        assertThat(TaskBrief.taskBrief("b.md", some("a".repeat(40)), true, Map.of()).unwrap().design()).isTrue();
    }

    /// Review #3: a job named twice is waited for once.
    @Test
    void waiting_namesEachJobOnce() {
        var seven = JobId.jobId(7).unwrap();
        var eight = JobId.jobId(8).unwrap();

        assertThat(Waiting.waiting(List.of(seven, seven, eight, seven), false).jobs()).containsExactly(seven, eight);
    }

    @Test
    void dispatchRank_fixCiGate_beforeVerify_beforeDesignCode() {
        assertThat(List.of(Stage.FIX, Stage.CI, Stage.GATE).stream().map(DispatchOrder::rank).distinct().toList()).containsExactly(0);
        assertThat(DispatchOrder.rank(Stage.VERIFY)).isEqualTo(1);
        assertThat(DispatchOrder.rank(Stage.DESIGN)).isEqualTo(2);
        assertThat(DispatchOrder.rank(Stage.CODE)).isEqualTo(2);
    }

    private static Result<NewTask> task(String slug, TaskBrief brief, int priority, int roundCap, List<Long> after) {
        return NewTask.newTask(slug, "T", List.of(1), brief, "a/b", "main", "agent/x", none(), false, priority, after, roundCap, "cto");
    }
}
