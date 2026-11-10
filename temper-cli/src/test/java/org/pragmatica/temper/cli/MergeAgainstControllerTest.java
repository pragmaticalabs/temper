package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.cli.merge.GhMerge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.T2Fixtures.GO_ID;
import static org.pragmatica.temper.cli.T2Fixtures.REPO;

/// `temper merge <go-id> --token-file <path>` (with the lead's 11-03 amendment) against the stub
/// controller's consume/outcome routes, a `file://` origin and a `GhMerge` spy: the spy's call count is what proves a
/// refusal stopped before the merge call.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MergeAgainstControllerTest {
    private static final String CONSUME = "POST /fleet/go/" + GO_ID + "/consume";
    private static final String OUTCOME = "POST /fleet/go/" + GO_ID + "/outcome";

    @TempDir
    Path home;
    @TempDir
    Path remote;
    @TempDir
    Path reports;

    private FakeController controller;
    private CliHarness cli;
    private GitOrigin origin;
    private T2Fixtures.GhSpy gh;
    private String b0;
    private String p1;
    private Path report;
    private Path token;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN).verdictToken(T2Fixtures.VERDICT_TOKEN);
        origin = GitOrigin.create(remote, REPO);
        gh = new T2Fixtures.GhSpy();
        cli = CliHarness.against(home, controller).ports(T2Fixtures.ports(origin, gh));
        b0 = origin.commit(null, "base", Map.of("a.txt", "a\n"));
        p1 = origin.commit(b0, "feature", Map.of("f.txt", "f\n"));
        origin.push(b0, "refs/heads/main").push(p1, "refs/heads/feature").push(p1, "refs/pull/7/head");
        report = T2Fixtures.report(reports, "round1.md", p1);
        token = T2Fixtures.tokenFile(reports.resolve("verdict-token"), T2Fixtures.VERDICT_TOKEN);
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, b0, report));
        controller.on(OUTCOME, FakeController.Reply.json(200, "{\"id\":\"" + GO_ID + "\",\"outcome\":\"x\",\"event\":null}"));
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private CliHarness.Run merge() {
        return cli.run("merge", GO_ID, "--token-file", token.toString());
    }

    private List<String> outcomes() {
        return controller.received()
                         .stream()
                         .filter(call -> (call.method() + " " + call.target()).equals(OUTCOME.replace("POST ", "POST /api/v1")))
                         .map(FakeController.Received::body)
                         .toList();
    }

    private String consumedLine() {
        return "consumed go " + GO_ID + " " + REPO + "#7@" + p1.substring(0, 12) + " base=main base_tip=" + b0.substring(0, 12);
    }

    @Test
    void merge_mergesThroughGh_whenNothingMoved() {
        var run = merge();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly(consumedLine(), "merged " + REPO + "#7 go=" + GO_ID + " base_tip_before=" + b0);
        assertThat(gh.calls).containsExactly(REPO + " 7 " + p1);
        assertThat(outcomes()).containsExactly("{\"outcome\":\"merging\",\"baseTipBefore\":\"" + b0 + "\"}",
                                               "{\"outcome\":\"merged\"}");
        assertThat(controller.received()).allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer "
                                                                                                        + T2Fixtures.VERDICT_TOKEN));
    }

    /// Only empty commits since the GO: (b) passes, (c) passes, and the merge records the current tip.
    @Test
    void merge_merges_whenBaseMovedOnlyByEmptyCommits() throws IOException {
        var know = origin.empty(b0, "know: since the GO");
        origin.push(know, "refs/heads/main");

        var run = merge();

        assertThat(run.exit()).isZero();
        assertThat(gh.calls).hasSize(1);
        assertThat(outcomes().getFirst()).contains(know);
    }

    /// GO.f: the base moved between consume and the re-check → `refused base-moved-since-go`, gh called 0 times.
    /// Mutation: skip step 2 → gh called once.
    @Test
    void merge_refuses_whenBaseMovedSinceGo() throws IOException {
        var b1 = origin.commit(b0, "landed after the GO", Map.of("b.txt", "b\n"));
        origin.push(b1, "refs/heads/main");

        var run = merge();

        var reason = "base-moved-since-go 1 non-empty commits, first " + b1.substring(0, 12);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly(consumedLine(), "refused " + reason);
        assertThat(gh.calls).isEmpty();
        assertThat(outcomes()).containsExactly("{\"outcome\":\"refused\",\"detail\":\"" + reason + "\"}");
    }

    /// The GO's tip `x` is already in the gate cache (an earlier gate fetched it), then the base is force-pushed back to
    /// b0. Only the ancestor check refuses: mutant "ancestor always true" finds an empty `x..b0` and merges (gh called).
    @Test
    void merge_refusesRewound_whenBaseWentBackwards() throws IOException {
        var x = origin.commit(b0, "the GO's tip", Map.of("x.txt", "x\n"));
        origin.push(x, "refs/heads/main");
        controller.onJson("GET /fleet/pr-fresh", T2Fixtures.prFresh(p1, x, T2Fixtures.verdict(5, report, null)));
        cli.run("merge-gate", REPO, "7", p1);
        origin.push(b0, "refs/heads/main");
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, x, report));

        var run = merge();

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).last().isEqualTo("refused base-rewound");
        assertThat(gh.calls).isEmpty();
    }

    /// (c) G8 in full: a GO whose own tip already differed from the merge-base (a READ-token GO) is refused.
    @Test
    void merge_refusesFullG8_whenGoTipWasAlreadyAhead() throws IOException {
        var b1 = origin.commit(b0, "before the GO", Map.of("b.txt", "b\n"));
        origin.push(b1, "refs/heads/main");
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, b1, report));

        var run = merge();

        assertThat(run.out()).last()
                             .isEqualTo("refused base-moved-since-go base-moved 1 non-empty commits, first " + b1.substring(0, 12));
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_refusesHeadMoved_whenPullHeadMoved() throws IOException {
        var p2 = origin.commit(p1, "pushed after the GO", Map.of("g.txt", "g\n"));
        origin.push(p2, "refs/pull/7/head");

        var run = merge();

        assertThat(run.out()).last().isEqualTo("refused head-moved refs/pull/7/head " + p2);
        assertThat(gh.calls).isEmpty();
    }

    /// GO.i: the report edited between GO and merge → `refused report-changed`, gh called 0 times.
    /// Mutation: skip 2(d) → gh called once.
    @Test
    void merge_refusesReportChanged_whenReportEditedAfterGo() throws IOException {
        Files.writeString(report, Files.readString(report) + "# a late round\n");

        var run = merge();

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).last().isEqualTo("refused report-changed");
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_refusesGitUnavailable_whenOriginIsGone() throws IOException {
        origin.run(origin.bare(), "update-ref", "-d", "refs/pull/7/head");

        var run = merge();

        assertThat(run.out()).last().isEqualTo("refused source-unavailable git fetch");
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_refusedGoUsed_whenConsumeConflicts() {
        controller.on(CONSUME, FakeController.Reply.json(409, "{\"error\":\"go-used\"}"));

        var run = merge();

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly("refused go-used");
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_refusedNotFound_whenGoUnknown() {
        controller.on(CONSUME, FakeController.Reply.json(404, "{\"error\":\"GO_NOT_FOUND\"}"));

        assertThat(merge().out()).containsExactly("refused go-not-found");
    }

    @Test
    void merge_refusedInvalidated_withReason() {
        controller.on(CONSUME, FakeController.Reply.json(409, "{\"error\":\"go-invalidated verdict-changed\"}"));

        assertThat(merge().out()).containsExactly("refused go-invalidated verdict-changed");
    }

    @Test
    void merge_refusesOutcomeUnrecorded_whenMergingNotRecorded() {
        controller.on(OUTCOME, FakeController.Reply.json(500, "{\"error\":\"down\"}"));

        var run = merge();

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).last().isEqualTo("refused outcome-unrecorded");
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_printsRecordedNo_whenMergedNotRecorded() {
        controller.on(OUTCOME,
                      FakeController.Reply.json(200, "{}"),
                      FakeController.Reply.json(500, "{\"error\":\"down\"}"));

        var run = merge();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).last().isEqualTo("recorded=no");
        assertThat(gh.calls).hasSize(1);
    }

    @Test
    void merge_exits3_whenGhFails() {
        gh.exit = GhMerge.GhExit.ghExit(1, "GraphQL: Head branch was modified");

        var run = merge();

        assertThat(run.exit()).isEqualTo(3);
        assertThat(run.out()).last().isEqualTo("merge-failed gh exit 1: GraphQL: Head branch was modified");
        assertThat(outcomes()).last()
                              .isEqualTo("{\"outcome\":\"merge-failed\",\"detail\":\"gh exit 1: GraphQL: Head branch was modified\"}");
    }

    /// T.full (CLI side): the full token reaches no VERDICT route: a token file holding it is refused 403 → 77.
    @Test
    void merge_exits77_whenTokenFileHoldsTheFullToken() throws IOException {
        var full = T2Fixtures.tokenFile(reports.resolve("full"), CliHarness.TOKEN);

        var run = cli.run("merge", GO_ID, "--token-file", full.toString());

        assertThat(run.exit()).isEqualTo(77);
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_exits69_whenControllerUnreachable() throws IOException {
        controller.close();

        assertThat(merge().exit()).isEqualTo(69);
    }

    @Test
    void merge_exits64_forBadGoId() {
        assertThat(cli.run("merge", "not-a-uuid", "--token-file", token.toString()).exit()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    /// The help text, verbatim.
    @Test
    void usage_carriesTheMergeGuaranteeVerbatim() {
        var run = cli.run("merge", "--help");

        assertThat(run.err()).contains("Enforced: the head by GitHub (--match-head-commit); single use and sibling invalidation by temper before the merge call; the base by temper, re-checked about a second before the merge call. GitHub's merge API has no base precondition: a base push landing between the re-check and the merge call is not caught.");
    }
}
