package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.config.TokenFile;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.T2Fixtures.GO_ID;
import static org.pragmatica.temper.cli.T2Fixtures.REPO;

/// The T2 verbs' edge cases the PIT run found unpinned: undecodable or incomplete answers,
/// a git that cannot run, the budget spent between git steps, and the queue view's remaining advice rules.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class T2EdgeCasesTest {
    private static final String PR_FRESH = "GET /fleet/pr-fresh";
    private static final String GO = "POST /fleet/go";
    private static final String CONSUME = "POST /fleet/go/" + GO_ID + "/consume";

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
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private Map<String, Object> clean() throws IOException {
        return T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null));
    }

    private CliHarness.Run gate() {
        return cli.run("merge-gate", REPO, "7", p1);
    }

    private static List<String> reasons(CliHarness.Run run) {
        return run.out().subList(1, run.out().size() - 1);
    }

    private static ProcessRunner failing() {
        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return ExecError.StartFailed.FACTORY.apply("git", Causes.cause("no git here")).promise();
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return Causes.cause("not used").result();
            }
        };
    }

    @Test
    void mergeGate_sendsTheShaItGates() throws IOException {
        controller.onJson(PR_FRESH, clean());
        controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

        gate();

        assertThat(controller.received().getFirst().target()).isEqualTo("/api/v1/fleet/pr-fresh?repo=acme%2Fwidget&pr=7&sha=" + p1);
    }

    @Test
    void mergeGate_isBadPayload_forUndecodableOrIncompleteAnswers() throws IOException {
        controller.on(PR_FRESH, FakeController.Reply.json(200, "not json"));
        assertThat(reasons(gate())).containsExactly("source-unavailable temper bad-payload");

        var pullless = clean();
        pullless.put("pull", null);
        controller.onJson(PR_FRESH, pullless);
        assertThat(reasons(gate())).containsExactly("source-unavailable temper bad-payload");

        var tokenless = clean();
        tokenless.put("freshToken", null);
        controller.onJson(PR_FRESH, tokenless);
        assertThat(reasons(gate())).containsExactly("source-unavailable temper bad-payload");
        assertThat(controller.received()).noneMatch(call -> call.method().equals("POST"));
    }

    @Test
    void mergeGate_isBaseUntracked_whenBaseTipIsAbsent() throws IOException {
        var reply = clean();
        reply.remove("baseTip");
        controller.onJson(PR_FRESH, reply);

        assertThat(reasons(gate())).containsExactly("source-unavailable base-untracked");
    }

    @Test
    void mergeGate_isGitUnavailable_whenGitCannotRun() throws IOException {
        cli.ports(T2Fixtures.ports(origin, gh, failing(), Duration.ofSeconds(45)));
        controller.onJson(PR_FRESH, clean());

        assertThat(reasons(gate())).containsExactly("source-unavailable git ls-remote");
    }

    /// The budget spent between git steps stops the Mac checks with `budget-exceeded`, and no GO is created.
    @Test
    void mergeGate_isBudgetExceeded_whenSpentBetweenGitSteps() throws IOException {
        cli.ports(T2Fixtures.ports(origin, gh, T2Fixtures.interleaved(ProcessRunner.processRunner(), "ls-remote", T2EdgeCasesTest::nap),
                                   Duration.ofMillis(1500)));
        controller.onJson(PR_FRESH, clean());
        controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

        var run = gate();

        assertThat(run.exit()).isEqualTo(1);
        assertThat(reasons(run)).containsExactly("source-unavailable budget-exceeded");
        assertThat(controller.received()).noneMatch(call -> call.method().equals("POST"));
    }

    private static void nap() {
        try {
            Thread.sleep(1700);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /// A server reason does not stop the Mac checks (only a G7 does), and G6-Mac reads nothing for a FIX.
    @Test
    void mergeGate_joinsServerAndMacReasons_andSkipsReportForFix() throws IOException {
        var b1 = origin.commit(b0, "real change", Map.of("b.txt", "b\n"));
        origin.push(b1, "refs/heads/main");
        var fix = T2Fixtures.verdict(5, report, null);
        fix.put("verdict", "FIX");
        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b1, fix), List.of(T2Fixtures.reason("G6", "verdict-FIX"))));

        assertThat(reasons(gate())).containsExactly("base-moved 1 non-empty commits, first " + b1.substring(0, 12), "verdict-FIX");
    }

    @Test
    void mergeGate_isReportChanged_forAnUnusableReportPath() throws IOException {
        var verdict = T2Fixtures.verdict(5, report, null);
        verdict.put("reportPath", "/tmp/bad\u0000path");
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, b0, verdict));

        assertThat(reasons(gate())).containsExactly("report-changed");
    }

    /// Unrelated histories: `merge-base --all` exits 1 with no base, which is `base-ambiguous`, not a git failure.
    @Test
    void mergeGate_isAmbiguous_forUnrelatedHistories() throws IOException {
        var orphan = origin.commit(null, "unrelated", Map.of("u.txt", "u\n"));
        origin.push(orphan, "refs/heads/feature").push(orphan, "refs/pull/7/head");
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(orphan, b0, T2Fixtures.verdict(5, T2Fixtures.report(reports, "o.md", orphan), null)));

        assertThat(reasons(cli.run("merge-gate", REPO, "7", orphan))).containsExactly("base-ambiguous");
    }

    /// The deciding line is the last one starting `VERDICT: `, not the file's last line.
    @Test
    void verdict_readsTheLastVerdictLine_notTheLastLine() throws IOException {
        var signed = Files.writeString(reports.resolve("signed.md"), T2Fixtures.verdictLine("ACCEPT", p1) + "\nsigned: t2v\n");
        controller.on("POST /fleet/verdicts", FakeController.Reply.json(201, "{\"id\":9,\"at\":\"2026-11-03T10:00:00Z\",\"reportSha256\":\"" + T2Fixtures.REGISTERED_SHA256 + "\",\"invalidated\":[]}"));

        var run = cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", signed.toString(),
                          "--token-file", token.toString());

        assertThat(run.exit()).isZero();
    }

    /// R2-5, positive: of several `VERDICT:` lines the last decides, so FIX then ACCEPT registers the ACCEPT.
    @Test
    void verdict_registers_whenTheLastOfSeveralLinesNamesTheArguments() throws IOException {
        var rounds = Files.writeString(reports.resolve("rounds.md"),
                                       T2Fixtures.verdictLine("FIX", p1) + "\nround 2\n" + T2Fixtures.verdictLine("ACCEPT", p1) + "\n");
        controller.on("POST /fleet/verdicts", FakeController.Reply.json(201, "{\"id\":9,\"at\":\"2026-11-03T10:00:00Z\",\"reportSha256\":\"" + T2Fixtures.REGISTERED_SHA256 + "\",\"invalidated\":[]}"));

        var run = cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", rounds.toString(),
                          "--token-file", token.toString());

        assertThat(run.exit()).isZero();
        assertThat(controller.received().getFirst().body()).contains("\"verdictLine\":\"" + T2Fixtures.verdictLine("ACCEPT", p1) + "\"");
    }

    /// A 500 is outside the contract (69), a 400 is a usage error (64): neither is printed as a refusal.
    @Test
    void verdict_exitCodes_forUnexpectedAnswers() throws IOException {
        controller.on("POST /fleet/verdicts", FakeController.Reply.json(500, "{\"error\":\"boom\"}"));
        var args = new String[] {"verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", report.toString(),
                                 "--token-file", token.toString()};

        assertThat(cli.run(args).exit()).isEqualTo(69);
        controller.on("POST /fleet/verdicts", FakeController.Reply.json(400, "{\"error\":\"bad by\"}"));
        assertThat(cli.run(args).exit()).isEqualTo(64);
    }

    @Test
    void verdictCarry_refusesGitUnavailable_whenHeadCannotBeFetched() throws IOException {
        origin.run(origin.bare(), "update-ref", "-d", "refs/pull/7/head");

        var run = cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--carried-from", b0,
                          "--token-file", token.toString());

        assertThat(run.out()).containsExactly("refused source-unavailable git fetch");
    }

    @Test
    void merge_refusesBadPayload_whenConsumeReplyNamesAnUnusableRepo() throws IOException {
        var reply = T2Fixtures.consumed(p1, b0, report);
        reply.put("repo", "../../etc");
        controller.onJson(CONSUME, reply);
        controller.on("POST /fleet/go/" + GO_ID + "/outcome", FakeController.Reply.json(200, "{}"));

        var run = cli.run("merge", GO_ID, "--token-file", token.toString());

        assertThat(run.out()).last().isEqualTo("refused source-unavailable temper bad-payload");
        assertThat(gh.calls).isEmpty();
    }

    @Test
    void merge_refusedWithRawBody_whenConflictIsNotJson() {
        controller.on(CONSUME, new FakeController.Reply(409, "go-used", "text/plain"));

        assertThat(cli.run("merge", GO_ID, "--token-file", token.toString()).out()).containsExactly("refused go-used");
    }

    private static Map<String, Object> entry(int position) {
        var entry = new LinkedHashMap<String, Object>();

        entry.put("position", position);
        entry.put("pr", 7);
        entry.put("addedBy", "t2wpc");
        entry.put("addedAt", "2026-11-03T09:00:00Z");
        entry.put("go", null);
        return entry;
    }

    private String queueLine(Map<String, Object> fresh) {
        controller.onJson("GET /fleet/merge-queue", Map.of("queues", List.of(Map.of("repo", REPO, "base", "main", "entries", List.of(entry(1))))));
        controller.onJson(PR_FRESH, fresh);
        return cli.run("merge-gate", "--queue").out().getFirst();
    }

    @Test
    void queue_blocksOnVerdictAndOnNotOpen() throws IOException {
        var fix = T2Fixtures.verdict(5, report, null);
        fix.put("verdict", "FIX");
        assertThat(queueLine(T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, fix), List.of(T2Fixtures.reason("G6", "verdict-FIX")))))
                .endsWith("verdict=FIX next=blocked:verdict-FIX");
        assertThat(queueLine(T2Fixtures.withReasons(clean(), List.of(T2Fixtures.reason("G2", "not-open merged")))))
                .endsWith("next=blocked:not-open");
    }

    @Test
    void queue_printsUnknownHead_whenItsFirstReadFails() {
        controller.onJson("GET /fleet/merge-queue", Map.of("queues", List.of(Map.of("repo", REPO, "base", "main", "entries", List.of(entry(1))))));
        controller.on(PR_FRESH, FakeController.Reply.json(500, "{\"error\":\"down\"}"));

        assertThat(cli.run("merge-gate", "--queue").out())
                .containsExactly("1 " + REPO + "#7 head=unknown behind_by=unknown checks=missing verdict=none next=blocked:source-unavailable");
    }

    @Test
    void queueEdits_sendTheirBodies() {
        controller.on("POST /fleet/merge-queue/move", FakeController.Reply.json(200, "{\"repo\":\"acme/widget\",\"base\":\"rc5\",\"pr\":7,\"position\":2}"));
        controller.on("POST /fleet/merge-queue/remove", FakeController.Reply.json(200, "{\"repo\":\"acme/widget\",\"base\":\"main\",\"pr\":7,\"position\":1}"));

        cli.run("merge-gate", "--queue", "move", REPO, "7", "--to", "2", "--base", "rc5", "--token-file", token.toString());
        cli.run("merge-gate", "--queue", "remove", REPO, "7", "--token-file", token.toString());

        assertThat(controller.received()).extracting(FakeController.Received::body)
                                         .containsExactly("{\"repo\":\"acme/widget\",\"pr\":7,\"to\":2,\"base\":\"rc5\"}",
                                                          "{\"repo\":\"acme/widget\",\"pr\":7}");
        assertThat(cli.run("merge-gate", "--queue", "move", REPO, "7", "--to", "two", "--token-file", token.toString()).exit()).isEqualTo(64);
    }

    @Test
    void tokenFile_neitherPrintsItsPath_norWarnsWhenPrivate() {
        controller.on(CONSUME, FakeController.Reply.json(409, "{\"error\":\"go-used\"}"));

        var run = cli.run("merge", GO_ID, "--token-file", token.toString());

        assertThat(TokenFile.tokenFile("/secret/place").toString()).isEqualTo("--token-file <verdict-token>");
        assertThat(run.err()).doesNotContain("accessible by group/others");
    }
}
