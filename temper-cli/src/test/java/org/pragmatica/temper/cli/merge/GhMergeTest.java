package org.pragmatica.temper.cli.merge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.assertj.core.api.Assertions.assertThat;

/// The production `GhMerge` against a scripted runner: the exact argv, gh's exit and the
/// first stderr line, and a gh that cannot start reported as exit -1 (never a failed promise).
class GhMergeTest {
    private static final String SHA = "1370ac30dd0254eb748b461235e8980b3adf2be5";

    private static ProcessRunner answering(List<Command> seen, Promise<Completed> answer) {
        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                seen.add(command);
                return answer;
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return Causes.cause("not used").result();
            }
        };
    }

    @Test
    void gh_runsSquashMergeWithHeadPrecondition_andReportsFirstStderrLine() {
        var seen = new CopyOnWriteArrayList<Command>();
        var gh = GhMerge.gh(answering(seen,
                                      Promise.success(Completed.completed("gh", 1, new byte[0],
                                                                          "first line\nsecond".getBytes(StandardCharsets.UTF_8), false))));

        var exit = gh.merge("acme/widget", 7, SHA).await().unwrap();

        assertThat(exit).isEqualTo(GhMerge.GhExit.ghExit(1, "first line"));
        assertThat(exit.isMerged()).isFalse();
        assertThat(seen.getFirst().argv()).containsExactly("gh", "pr", "merge", "7", "--repo", "acme/widget", "--squash",
                                                           "--match-head-commit", SHA);
    }

    @Test
    void gh_isMerged_onExitZero() {
        var gh = GhMerge.gh(answering(new CopyOnWriteArrayList<>(),
                                      Promise.success(Completed.completed("gh", 0, new byte[0], new byte[0], false))));

        assertThat(gh.merge("acme/widget", 7, SHA).await().unwrap().isMerged()).isTrue();
    }

    @Test
    void gh_isExitMinusOne_whenItCannotStart() {
        var gh = GhMerge.gh(answering(new CopyOnWriteArrayList<>(),
                                      ExecError.StartFailed.FACTORY.apply("gh", Causes.cause("no such file")).promise()));

        var exit = gh.merge("acme/widget", 7, SHA).await().unwrap();

        assertThat(exit.code()).isEqualTo(-1);
        assertThat(exit.firstStderrLine()).startsWith("Failed to start gh");
    }

    /// Ruling 11-04 item 1: a gh that did not finish is `timedOut` (the merge may have happened), not exit -1.
    @Test
    void gh_isTimedOut_whenItDoesNotFinish() {
        var gh = GhMerge.gh(answering(new CopyOnWriteArrayList<>(),
                                      ExecError.TimedOut.FACTORY.apply("gh", GhMerge.TIMEOUT).promise()));

        var exit = gh.merge("acme/widget", 7, SHA).await().unwrap();

        assertThat(exit.timedOutAfter().stream()).containsExactly(120L);
        assertThat(exit.isMerged()).isFalse();
    }

    @Test
    void production_usesGithubAndTheContractBudget() {
        var ports = MergePorts.production();

        assertThat(ports.url("acme/widget")).isEqualTo("https://github.com/acme/widget.git");
        assertThat(ports.budget()).isEqualTo(MergePorts.BUDGET);
        assertThat(ports.call()).isEqualTo(java.time.Duration.ofSeconds(35));
        assertThat(ports.gh()).isNotNull();
    }
}
