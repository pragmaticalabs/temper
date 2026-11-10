package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.cli.merge.GhMerge;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.T2Fixtures.GO_ID;
import static org.pragmatica.temper.cli.T2Fixtures.REPO;

/// The findings of the WP-C verification (t2wpcv, 9d5c42d2) and the lead's 2026-11-04 rulings, each
/// pinned: a hanging git stays inside the budget, G8 and merge step 2(b) walk the base's first-parent line, a lone CR
/// does not split a report line, unnamed answers exit 64/69 (never 5), a gh timeout is `merge-unknown`, an
/// `--x=value` token echoes no value, the stub refuses the full token on VERDICT routes, and the queue view reads the
/// pull-only pr-fresh shape.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class T2VerificationFixesTest {
    private static final String PR_FRESH = "GET /fleet/pr-fresh";
    private static final String GO = "POST /fleet/go";
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
        report = T2Fixtures.report(reports, "r.md", p1);
        token = T2Fixtures.tokenFile(reports.resolve("verdict-token"), T2Fixtures.VERDICT_TOKEN);
        controller.on(OUTCOME, FakeController.Reply.json(200, "{}"));
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private CliHarness.Run gate(String sha) {
        return cli.run("merge-gate", REPO, "7", sha);
    }

    private CliHarness.Run merge() {
        return cli.run("merge", GO_ID, "--token-file", token.toString());
    }

    private static List<String> reasons(CliHarness.Run run) {
        return run.out().subList(1, run.out().size() - 1);
    }

    private void goCreated() {
        controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));
    }


    // --- 1. a hanging git (verification p10) ---

    /// Mutation p10 (git bound 1 day instead of the budget's) → the gate hangs past the test timeout.
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void mergeGate_isBudgetExceeded_whenGitHangs() throws IOException {
        cli.ports(T2Fixtures.ports(origin, gh, T2Fixtures.hangingGit(ProcessRunner.processRunner()), Duration.ofSeconds(2)));
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)));
        goCreated();

        var run = gate(p1);

        assertThat(run.exit()).isEqualTo(1);
        assertThat(reasons(run)).containsExactly("source-unavailable budget-exceeded");
        assertThat(controller.received()).noneMatch(call -> call.method().equals("POST"));
    }

    /// The per-call cap bounds every git step of `temper merge` (production 35 s; 1 s here): refused, gh never called.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void merge_refusesWithoutGh_whenGitHangs() throws IOException {
        cli.ports(T2Fixtures.ports(origin, gh, T2Fixtures.hangingGit(ProcessRunner.processRunner()), Duration.ofSeconds(45),
                                   Duration.ofSeconds(1)));
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, b0, report));

        var run = merge();

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).last().asString().startsWith("refused source-unavailable git ");
        assertThat(gh.calls).isEmpty();
    }

    // --- 2. the first-parent walk (verification p1) ---

    /// main = merge of a two-commit side branch: one first-parent commit, the merge itself. Mutation p1 (no
    /// `--first-parent`) → 3 commits, first = the side branch's oldest.
    private String sideMerge() throws IOException {
        var s1 = origin.commit(b0, "side one", Map.of("s1.txt", "1\n"));
        var s2 = origin.commit(s1, "side two", Map.of("s2.txt", "2\n"));
        var merged = origin.merge(b0, s2, "merge side into main", Map.of());

        origin.push(merged, "refs/heads/main");
        return merged;
    }

    /// main = `-s ours` merge of a non-empty side branch: an empty first-parent commit. Mutation p1 → NO.
    private String oursMerge() throws IOException {
        var s1 = origin.commit(b0, "side change", Map.of("s.txt", "s\n"));
        var merged = origin.mergeOurs(b0, s1, "merge side, keep main");

        origin.push(merged, "refs/heads/main");
        return merged;
    }

    @Test
    void mergeGate_countsTheFirstParentLine_forASideBranchMerge() throws IOException {
        var merged = sideMerge();
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, merged, T2Fixtures.verdict(5, report, null)));

        assertThat(reasons(gate(p1))).containsExactly("base-moved 1 non-empty commits, first " + merged.substring(0, 12));
    }

    @Test
    void mergeGate_isGo_forAnEmptyOursMerge() throws IOException {
        var merged = oursMerge();
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, merged, T2Fixtures.verdict(5, report, null)));
        goCreated();

        assertThat(gate(p1).out()).first().isEqualTo("GO " + REPO + "#7@" + p1);
    }

    @Test
    void merge_refusesSinceGo_onTheFirstParentLine_forASideBranchMerge() throws IOException {
        var merged = sideMerge();
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, b0, report));

        var run = merge();

        assertThat(run.out()).last().isEqualTo("refused base-moved-since-go 1 non-empty commits, first " + merged.substring(0, 12));
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_merges_afterAnEmptyOursMerge() throws IOException {
        oursMerge();
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, b0, report));

        assertThat(merge().exit()).isZero();
        assertThat(gh.calls).hasSize(1);
    }

    // --- 3. a lone CR ---

    /// One LF line `VERDICT: FIX …\rVERDICT: ACCEPT …` does not parse: `report-mismatch`, never ACCEPT.
    @Test
    void verdict_refuses_whenALoneCrHidesASecondLine() throws IOException {
        var crs = Files.writeString(reports.resolve("cr.md"),
                                    T2Fixtures.verdictLine("FIX", p1) + "\r" + T2Fixtures.verdictLine("ACCEPT", p1) + "\n");

        var run = cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", crs.toString(),
                          "--token-file", token.toString());

        assertThat(run.out()).containsExactly("refused report-mismatch");
        assertThat(controller.received()).isEmpty();
    }

    // --- 4. unnamed answers: 400 → 64, anything else → 69, never 5 ---

    @Test
    void merge_unnamedConsumeAnswers_exit64or69() {
        controller.on(CONSUME, FakeController.Reply.json(400, "{\"error\":\"bad id\"}"));
        assertThat(merge().exit()).isEqualTo(64);
        for (var status : List.of(422, 500, 503)) {
            controller.on(CONSUME, FakeController.Reply.json(status, "{\"error\":\"NOT_READY\"}"));
            assertThat(merge().exit()).as("consume " + status).isEqualTo(69);
        }
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void queueAndPathReads_unnamedAnswers_exit64or69() {
        controller.on("GET /fleet/merge-queue", FakeController.Reply.json(500, "{\"error\":\"down\"}"));
        assertThat(cli.run("merge-gate", "--queue").exit()).isEqualTo(69);
        controller.on("GET /fleet/merge-queue", FakeController.Reply.json(400, "{\"error\":\"bad base\"}"));
        assertThat(cli.run("merge-gate", "--queue").exit()).isEqualTo(64);
        controller.on("GET /fleet/ci-control-paths", FakeController.Reply.json(503, "{\"error\":\"NOT_READY\"}"));
        assertThat(cli.run("merge-gate", "--control-paths", "list", "--repo", REPO).exit()).isEqualTo(69);
    }

    // --- 5. gh timeout (ruling 11-04 item 1) ---

    @Test
    void merge_isMergeUnknown_whenGhTimesOut_andRecordsNothingMore() throws IOException {
        controller.onJson(CONSUME, T2Fixtures.consumed(p1, b0, report));
        gh.exit = GhMerge.GhExit.timedOut(120);

        var run = merge();

        assertThat(run.exit()).isEqualTo(3);
        assertThat(run.out()).last().isEqualTo("merge-unknown gh timed out after 120s; kiln records the outcome");
        assertThat(controller.received().stream().filter(call -> call.target().endsWith("/outcome")).map(FakeController.Received::body))
                .containsExactly("{\"outcome\":\"merging\",\"baseTipBefore\":\"" + b0 + "\"}");
    }

    // --- 6. `--x=value` echoes the name only ---

    @Test
    void unknownEqualsOption_echoesItsNameOnly() {
        var run = cli.run("merge", GO_ID, "--token-file=/secret/place/verdict-token");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("unknown option '--token-file'").doesNotContain("/secret/place");
    }

    // --- 8. the stub ---

    /// The full token is refused on a VERDICT route even when the stub has no verdict token set.
    @Test
    void stub_refusesFullTokenOnVerdictRoutes_byDefault() throws IOException {
        try (var plain = FakeController.start(CliHarness.TOKEN)) {
            plain.onJson(CONSUME, T2Fixtures.consumed(p1, b0, report));
            var full = T2Fixtures.tokenFile(reports.resolve("full"), CliHarness.TOKEN);
            var other = CliHarness.against(home.resolve("other"), plain).ports(T2Fixtures.ports(origin, gh));

            assertThat(other.run("merge", GO_ID, "--token-file", full.toString()).exit()).isEqualTo(77);
            assertThat(gh.calls).isEmpty();
        }
    }

    /// The queue view's first read is the pull-only shape (ruling 11-03 item 2), the second the full one at its head.
    @Test
    void queue_readsThePullOnlyShapeFirst() throws IOException {
        controller.onJson("GET /fleet/merge-queue",
                          Map.of("queues", List.of(Map.of("repo", REPO, "base", "main", "entries",
                                                          List.of(Map.of("position", 1, "pr", 7, "addedBy", "t2wpc",
                                                                         "addedAt", "2026-11-03T09:00:00Z"))))));
        controller.on(PR_FRESH,
                      FakeController.Reply.json(200, controller.write(T2Fixtures.pullOnly(p1))),
                      FakeController.Reply.json(200, controller.write(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)))));

        var run = cli.run("merge-gate", "--queue");

        assertThat(run.out()).containsExactly("1 " + REPO + "#7 head=" + p1.substring(0, 12)
                                              + " behind_by=0 checks=green verdict=ACCEPT next=gate");
        assertThat(controller.received().stream().map(FakeController.Received::target).filter(target -> target.contains("pr-fresh")))
                .containsExactly("/api/v1/fleet/pr-fresh?repo=acme%2Fwidget&pr=7",
                                 "/api/v1/fleet/pr-fresh?repo=acme%2Fwidget&pr=7&sha=" + p1);
    }
}
