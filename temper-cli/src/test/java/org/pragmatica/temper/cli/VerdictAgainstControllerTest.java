package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.T2Fixtures.REPO;

/// `temper verdict` against the stub `POST fleet/verdicts` and a `file://` origin for the carry
/// checks. A refusal on the Mac makes no call at all.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VerdictAgainstControllerTest {
    private static final String VERDICTS = "POST /fleet/verdicts";
    private static final String GO_A = "11111111-2222-4333-8444-555555555555";

    @TempDir
    Path home;
    @TempDir
    Path remote;
    @TempDir
    Path reports;

    private FakeController controller;
    private CliHarness cli;
    private GitOrigin origin;
    private String b0;
    private String p1;
    private Path token;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN).verdictToken(T2Fixtures.VERDICT_TOKEN);
        origin = GitOrigin.create(remote, REPO);
        cli = CliHarness.against(home, controller).ports(T2Fixtures.ports(origin, new T2Fixtures.GhSpy()));
        b0 = origin.commit(null, "base", Map.of("a.txt", "a\n"));
        p1 = origin.commit(b0, "feature", Map.of("f.txt", "f\n"));
        origin.push(b0, "refs/heads/main").push(p1, "refs/pull/7/head");
        token = T2Fixtures.tokenFile(reports.resolve("verdict-token"), T2Fixtures.VERDICT_TOKEN);
        controller.on(VERDICTS,
                      FakeController.Reply.json(201, "{\"id\":42,\"at\":\"2026-11-03T10:00:00Z\",\"reportSha256\":\"" + T2Fixtures.REGISTERED_SHA256 + "\",\"invalidated\":[\"" + GO_A + "\"]}"));
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private CliHarness.Run verdict(String sha, String verdict, String... evidence) {
        var args = new java.util.ArrayList<>(java.util.List.of("verdict", REPO, "7", sha, verdict, "--base", "main", "--by",
                                                               "t2v", "--token-file", token.toString()));
        args.addAll(java.util.List.of(evidence));
        return cli.run(args.toArray(String[]::new));
    }

    private boolean called() {
        return controller.received().stream().anyMatch(call -> call.target().startsWith("/api/v1/fleet/verdicts"));
    }

    private String body() {
        return controller.received().stream().filter(call -> call.target().equals("/api/v1/fleet/verdicts")).findFirst()
                         .orElseThrow().body();
    }

    @Nested
    class Report {
        @Test
        void verdict_registers_whenLastLineNamesTheArguments() throws IOException {
            var report = T2Fixtures.report(reports, "r.md", p1);

            var run = verdict(p1, "ACCEPT", "--report", report.toString());

            var sha256 = T2Fixtures.sha256(report);
            assertThat(run.exit()).isZero();
            assertThat(run.out()).containsExactly("verdict 42 " + REPO + "#7@" + p1.substring(0, 12) + " ACCEPT base=main by=t2v sha256="
                                                  + T2Fixtures.REGISTERED_SHA256.substring(0, 12),
                                                  "invalidated go " + GO_A + " verdict-changed");
            assertThat(body()).isEqualTo("{\"repo\":\"acme/widget\",\"pr\":7,\"sha\":\"" + p1 + "\",\"base\":\"main\",\"verdict\":\"ACCEPT\","
                                         + "\"by\":\"t2v\",\"reportPath\":\"" + report + "\",\"reportSha256\":\"" + sha256
                                         + "\",\"verdictLine\":\"" + T2Fixtures.verdictLine("ACCEPT", p1) + "\"}");
        }

        /// CRLF reads as LF for the line; the digest is over the bytes on disk.
        @Test
        void verdict_readsCrlfReports() throws IOException {
            var report = Files.writeString(reports.resolve("crlf.md"), "round\r\n" + T2Fixtures.verdictLine("FIX", p1) + "\r\n");

            var run = verdict(p1, "FIX", "--report", report.toString());

            assertThat(run.exit()).isZero();
            assertThat(body()).contains("\"reportSha256\":\"" + T2Fixtures.sha256(report) + "\"");
        }

        /// G6.d: the report's last line names another SHA → `refused report-mismatch`, no call.
        @Test
        void verdict_refusesReportMismatch_whenLineNamesAnotherSha() throws IOException {
            var report = T2Fixtures.report(reports, "r.md", b0);

            var run = verdict(p1, "ACCEPT", "--report", report.toString());

            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.out()).containsExactly("refused report-mismatch");
            assertThat(called()).isFalse();
        }

        /// G6.e (R2-5), as written: `VERDICT: FIX …@X` then `VERDICT: ACCEPT …@Y`; ACCEPT for X is refused.
        @Test
        void verdict_refuses_whenFixForXThenAcceptForY() throws IOException {
            var report = Files.writeString(reports.resolve("e.md"),
                                           T2Fixtures.verdictLine("FIX", p1) + "\n" + T2Fixtures.verdictLine("ACCEPT", b0) + "\n");

            assertThat(verdict(p1, "ACCEPT", "--report", report.toString()).out()).containsExactly("refused report-mismatch");
            assertThat(called()).isFalse();
        }

        /// R2-5, the mutation-sensitive form: `ACCEPT …@X` then a later `FIX …@X`; ACCEPT for X is refused (the last line
        /// decides). Mutation: first matching line decides → registered.
        @Test
        void verdict_refuses_whenAcceptIsFollowedByFix() throws IOException {
            var report = Files.writeString(reports.resolve("e2.md"),
                                           T2Fixtures.verdictLine("ACCEPT", p1) + "\nround 2\n" + T2Fixtures.verdictLine("FIX", p1) + "\n");

            assertThat(verdict(p1, "ACCEPT", "--report", report.toString()).out()).containsExactly("refused report-mismatch");
            assertThat(called()).isFalse();
        }

        @Test
        void verdict_refusesReportMismatch_withoutAVerdictLine() throws IOException {
            var report = Files.writeString(reports.resolve("none.md"), "no verdict here\nVERDICT:ACCEPT\n");

            assertThat(verdict(p1, "ACCEPT", "--report", report.toString()).out()).containsExactly("refused report-mismatch");
        }

        /// The line's PR group is unbounded: a number beyond a long is just another PR (a mismatch), never a crash.
        @Test
        void verdict_refusesReportMismatch_forAHugePrInTheLine() throws IOException {
            var report = Files.writeString(reports.resolve("huge.md"),
                                           "VERDICT: ACCEPT " + REPO + "#99999999999999999999999@" + p1 + " base=main\n");

            assertThat(verdict(p1, "ACCEPT", "--report", report.toString()).out()).containsExactly("refused report-mismatch");
        }

        @Test
        void verdict_exits64_forLargeRelativeOrNonUtf8Reports() throws IOException {
            var large = Files.write(reports.resolve("large.md"), new byte[1024 * 1024 + 1]);
            var binary = Files.write(reports.resolve("binary.md"), new byte[] {(byte) 0xC3, (byte) 0x28});

            assertThat(verdict(p1, "ACCEPT", "--report", large.toString()).exit()).isEqualTo(64);
            assertThat(verdict(p1, "ACCEPT", "--report", "relative/report.md").exit()).isEqualTo(64);
            assertThat(verdict(p1, "ACCEPT", "--report", binary.toString()).exit()).isEqualTo(64);
            assertThat(verdict(p1, "ACCEPT", "--report", reports.resolve("absent.md").toString()).exit()).isEqualTo(64);
            assertThat(called()).isFalse();
        }

        @Test
        void verdict_expandsHomeInReportPath() throws IOException {
            Files.writeString(home.resolve("h.md"), T2Fixtures.verdictLine("REJECT", p1) + "\n");

            var run = verdict(p1, "REJECT", "--report", "~/h.md");

            assertThat(run.exit()).isZero();
            assertThat(body()).contains("\"reportPath\":\"" + home.resolve("h.md") + "\"");
        }

        @Test
        void verdict_refusedByServer_whenLineMismatchOnServer() throws IOException {
            var report = T2Fixtures.report(reports, "r.md", p1);
            controller.on(VERDICTS, FakeController.Reply.json(422, "{\"error\":\"VERDICT_LINE_MISMATCH\"}"));

            var run = verdict(p1, "ACCEPT", "--report", report.toString());

            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.out()).containsExactly("refused verdict-line-mismatch");
        }

        @Test
        void verdict_exits77_whenFullTokenGiven() throws IOException {
            var report = T2Fixtures.report(reports, "r.md", p1);
            var full = T2Fixtures.tokenFile(reports.resolve("full"), CliHarness.TOKEN);

            var run = cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", report.toString(),
                              "--token-file", full.toString());

            assertThat(run.exit()).isEqualTo(77);
        }

        @Test
        void verdict_exits64_forUsageErrors() throws IOException {
            var report = T2Fixtures.report(reports, "r.md", p1).toString();

            assertThat(verdict(p1, "MAYBE", "--report", report).exit()).isEqualTo(64);
            assertThat(verdict(p1, "ACCEPT").exit()).isEqualTo(64);
            assertThat(verdict(p1, "ACCEPT", "--report", report, "--carried-from", b0).exit()).isEqualTo(64);
            assertThat(verdict(p1, "FIX", "--carried-from", b0).exit()).isEqualTo(64);
            assertThat(verdict(p1.substring(0, 39), "ACCEPT", "--report", report).exit()).isEqualTo(64);
            assertThat(called()).isFalse();
        }
    }

    @Nested
    class Carry {
        private String old;
        private String b1;

        /// main: b0 → b1 (adds b.txt); the ACCEPTed old head is p1 (on b0); SHA = git's own merge of b1 into p1.
        @BeforeEach
        void carried() throws IOException {
            old = p1;
            b1 = origin.commit(b0, "base moves", Map.of("b.txt", "b\n"));
            origin.push(b1, "refs/heads/main");
        }

        private String mergeOf(String parent2, Map<String, String> handEdits) throws IOException {
            var sha = origin.merge(old, parent2, "Merge main into feature", handEdits);
            origin.push(sha, "refs/pull/7/head");
            return sha;
        }

        @Test
        void verdict_registersCarry_whenMergeIsMechanical() throws IOException {
            var sha = mergeOf(b1, Map.of());

            var run = verdict(sha, "ACCEPT", "--carried-from", old);

            assertThat(run.exit()).isZero();
            assertThat(run.out()).first().isEqualTo("verdict 42 " + REPO + "#7@" + sha.substring(0, 12)
                                                    + " ACCEPT base=main by=t2v sha256=" + T2Fixtures.REGISTERED_SHA256.substring(0, 12));
            assertThat(body()).isEqualTo("{\"repo\":\"acme/widget\",\"pr\":7,\"sha\":\"" + sha + "\",\"base\":\"main\","
                                         + "\"verdict\":\"ACCEPT\",\"by\":\"t2v\",\"carriedFrom\":\"" + old + "\"}");
        }

        /// G6.g: a hand-resolved tree → `carry-hand-resolved`. Mutation: skip `merge-tree` → registered.
        @Test
        void verdict_refusesHandResolved() throws IOException {
            var sha = mergeOf(b1, Map.of("f.txt", "edited during the merge\n"));

            assertThat(verdict(sha, "ACCEPT", "--carried-from", old).out()).containsExactly("refused carry-hand-resolved");
            assertThat(called()).isFalse();
        }

        /// G6.h: parent 2 reachable from the base but off its first-parent history → `carry-parent2-off-base`.
        /// Mutation: test reachability instead of first-parent → registered.
        @Test
        void verdict_refusesParent2OffBase() throws IOException {
            var side = origin.commit(b0, "side branch", Map.of("s.txt", "s\n"));
            var b2 = origin.merge(b1, side, "merge side into main", Map.of());
            origin.push(b2, "refs/heads/main");
            var sha = mergeOf(side, Map.of());

            assertThat(verdict(sha, "ACCEPT", "--carried-from", old).out()).containsExactly("refused carry-parent2-off-base");
            assertThat(called()).isFalse();
        }

        @Test
        void verdict_refusesCarryHeadMoved() throws IOException {
            var sha = mergeOf(b1, Map.of());
            origin.push(old, "refs/pull/7/head");

            assertThat(verdict(sha, "ACCEPT", "--carried-from", old).out()).containsExactly("refused carry-head-moved");
        }

        @Test
        void verdict_refusesCarryNotMerge() throws IOException {
            var sha = origin.commit(old, "not a merge", Map.of("n.txt", "n\n"));
            origin.push(sha, "refs/pull/7/head");

            assertThat(verdict(sha, "ACCEPT", "--carried-from", old).out()).containsExactly("refused carry-not-merge");
        }

        @Test
        void verdict_refusesCarryParent1() throws IOException {
            var sha = mergeOf(b1, Map.of());

            assertThat(verdict(sha, "ACCEPT", "--carried-from", b0).out()).containsExactly("refused carry-parent1 " + old.substring(0, 12));
        }

        @Test
        void verdict_refusesCarryNoAccept_whenServerHasNone() throws IOException {
            var sha = mergeOf(b1, Map.of());
            controller.on(VERDICTS, FakeController.Reply.json(422, "{\"error\":\"CARRY_NO_ACCEPT\"}"));

            var run = verdict(sha, "ACCEPT", "--carried-from", old);

            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.out()).containsExactly("refused carry-no-accept");
        }
    }
}
