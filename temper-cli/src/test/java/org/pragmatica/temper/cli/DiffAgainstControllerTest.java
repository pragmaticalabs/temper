package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `diff <jobA> <jobB>` and `diff <job> --base <sha>` (m2-design §3.4) as whole CLI invocations against the fake
/// controller: the exact output (sections first, counts line last), the query each form sends, `--json`, and the
/// usage errors that never reach the controller. Exit codes per row are `ExitCodeContractTest`'s.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DiffAgainstControllerTest {
    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    @Test
    void twoJobs_printSectionsThenTheCountsLine_andSendAThenB() {
        controller.onJson("GET /diff",
                          Fixtures.diffView(List.of(Fixtures.test("org.x.FooTest", "newCase", KeyOutcome.FAILED),
                                                    Fixtures.test("org.x.FooTest", "plain", KeyOutcome.PASSED)),
                                            List.of(Fixtures.test("org.x.BarTest", "flips", KeyOutcome.FAILED)),
                                            none()));

        var result = cli.run("diff", "4690", "4711");

        assertThat(result.exit()).as(result.err()).isEqualTo(1);
        assertThat(result.out()).containsExactly("added",
                                                 "  org.x.FooTest#newCase [failed]",
                                                 "  org.x.FooTest#plain [passed]",
                                                 "removed",
                                                 "  org.x.GoneTest#old",
                                                 "newly-failing",
                                                 "  org.x.BarTest#flips",
                                                 "newly-passing",
                                                 "diff 4690 4711 a=PASSED b=FAILED added=2 added-failing=1 removed=1 newly-failing=1 newly-passing=0 skip-changed=1 duplicates=0");
        assertThat(controller.received()).extracting(FakeController.Received::target)
                                         .containsExactly("/api/v1/diff?a=4690&b=4711");
    }

    /// The base form sends the job as `a` and the SHA as `baseSha` (m2-design §3.3), and the line names the base.
    @Test
    void base_sendsTheJobAndTheSha_andTheLineNamesTheBase() {
        controller.onJson("GET /diff", Fixtures.diffView(List.of(), List.of(), some(4690L)));

        var result = cli.run("diff", "4711", "--base", "0CF5631");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).last()
                                .isEqualTo("diff 4690 4711 a=PASSED b=FAILED base=4690 added=0 added-failing=0 removed=1 newly-failing=0 newly-passing=0 skip-changed=1 duplicates=0");
        assertThat(controller.received()).extracting(FakeController.Received::target)
                                         .containsExactly("/api/v1/diff?a=4711&baseSha=0cf5631");
    }

    /// A name that is not bare is quoted, so it never breaks the one-test-per-line form.
    @Test
    void testName_withSpaces_isQuoted() {
        controller.onJson("GET /diff",
                          Fixtures.diffView(List.of(),
                                            List.of(Fixtures.test("org.x.ParamTest", "[1] a, b", KeyOutcome.FAILED)),
                                            none()));

        var result = cli.run("diff", "4690", "4711");

        assertThat(result.out()).contains("  \"org.x.ParamTest#[1] a, b\"");
    }

    @Test
    void json_printsTheDiffAnswer_withTheSameExitCode() {
        var view = Fixtures.diffView(List.of(Fixtures.test("org.x.FooTest", "newCase", KeyOutcome.FAILED)), List.of(), none());

        controller.onJson("GET /diff", view);
        var result = cli.run("diff", "4690", "4711", "--json");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).hasSize(1);
        assertThat(TemperJson.mapper().readString(result.out().getFirst(), DiffAnswer.class).unwrap()).isEqualTo(view);
        assertThat(result.out().getFirst()).contains("\"skipChanged\":[{\"class\":\"org.x.OffTest\"");
    }

    @Test
    void refusals_printOneLine() {
        controller.onJson("GET /diff",
                          Fixtures.diffRefused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.B, JobState.CANCELLED),
                          Fixtures.diffRefused(DiffObstacle.PRUNED, DiffOutcome.Side.B, JobState.PASSED));

        var refused = cli.run("diff", "4690", "4711");
        var pruned = cli.run("diff", "4690", "4711");

        assertThat(refused.exit()).isEqualTo(8);
        assertThat(refused.out()).containsExactly("diff 4690 4711 NONE not-judged=b:CANCELLED detail=\"side not judged: CANCELLED\"");
        assertThat(pruned.out()).containsExactly("diff 4690 4711 NONE pruned=b");
    }

    /// Judged before any call (64): one job without `--base`, two with it, a bad id, a short SHA, an unknown option.
    @Test
    void usageErrors_neverReachTheController() {
        for (var args : List.of(List.of("diff", "4690"),
                                List.of("diff", "4690", "4711", "--base", "0cf5631"),
                                List.of("diff", "x", "4711"),
                                List.of("diff", "0", "4711"),
                                List.of("diff", "4711", "--base", "0cf563"),
                                List.of("diff", "4711", "--base", "xyz5631"),
                                List.of("diff", "4690", "4711", "--wait"))) {
            var result = cli.run(args.toArray(String[]::new));

            assertThat(result.exit()).as(String.join(" ", args)).isEqualTo(64);
        }
        assertThat(controller.received()).isEmpty();
    }

    /// A 400 (a prefix of Gates at two SHAs) is 64 with the controller's reason.
    @Test
    void rejectedQuery_is64() {
        controller.on("GET /diff", FakeController.Reply.json(400, "{\"error\":\"SHA prefix 0cf5631 matches a,b\"}"));

        var result = cli.run("diff", "4711", "--base", "0cf5631");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("matches a,b");
    }
}
