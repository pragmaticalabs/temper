package org.pragmatica.temper.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.T2Fixtures.GO_ID;
import static org.pragmatica.temper.cli.T2Fixtures.PR;
import static org.pragmatica.temper.cli.T2Fixtures.REPO;

/// `temper merge-gate` end to end: the real CLI against the stub controller serving the
/// JSON and a `file://` origin standing in for GitHub. Each positive control marked C names its mutation.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MergeGateAgainstControllerTest {
    private static final String PR_FRESH = "GET /fleet/pr-fresh";
    private static final String GO = "POST /fleet/go";

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
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private void fresh(Map<String, Object> reply) {
        controller.onJson(PR_FRESH, reply);
    }

    private Map<String, Object> clean() throws IOException {
        return T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null));
    }

    private CliHarness.Run gate() {
        return cli.run("merge-gate", REPO, Long.toString(PR), p1);
    }

    private boolean posted(String route) {
        return controller.received().stream().anyMatch(call -> (call.method() + " " + call.target()).startsWith(route));
    }

    private static void assertNo(CliHarness.Run run, String... reasons) {
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.out()).first().isEqualTo("NO");
        assertThat(run.out().subList(1, run.out().size() - 1)).containsExactly(reasons);
        assertThat(run.out()).last().asString().startsWith("as-of ");
    }

    @Nested
    class Go {
        /// The whole GO block, exactly, and the POST carries the judged tip and the server's evaluation.
        @Test
        void mergeGate_printsGo_whenEverythingIsClean() throws IOException {
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            var run = gate();

            assertThat(run.exit()).isZero();
            assertThat(run.out()).hasSize(6);
            assertThat(run.out().get(0)).isEqualTo("GO " + REPO + "#7@" + p1);
            assertThat(run.out().get(1)).isEqualTo("base main tip=" + b0);
            assertThat(run.out().get(2)).isEqualTo("verdict ACCEPT by=t2v at=2026-11-03T09:00:00Z report=" + report
                                                   + " sha256=" + T2Fixtures.sha256(report).substring(0, 12));
            assertThat(run.out().get(3)).isEqualTo("go " + GO_ID + " expires=2026-11-03T10:10:01Z");
            assertThat(run.out().get(4)).isEqualTo("temper merge " + GO_ID + " --token-file <verdict-token>");
            assertThat(run.out().get(5)).matches("as-of \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
            var body = controller.received().stream().filter(call -> call.method().equals("POST")).findFirst().orElseThrow().body();
            assertThat(body).contains("\"baseTip\":\"" + b0 + "\"", "\"verdictId\":5", "\"fleetEpoch\":\"" + T2Fixtures.EPOCH + "\"",
                                      "\"fleetSeq\":812", "\"checkedAt\":\"" + T2Fixtures.CHECKED_AT + "\"",
                                      "\"freshToken\":\"v1.1762163999000.812.c2lnbmF0dXJl\"");
            assertThat(controller.received().getFirst().authorization()).isEqualTo("Bearer " + CliHarness.TOKEN);
        }

        /// Lead ruling 2026-11-04: the freshToken HMAC binds kiln's baseTip, so the GO body carries pr-fresh's tip even
        /// when G8 fetched a later one (an empty `know:` commit kiln has not polled yet).
        /// Mutation: send the fetched tip instead -> red here.
        @Test
        void mergeGate_goBodyCarriesKilnsBaseTip_notTheFetchedOne() throws IOException {
            var know = origin.empty(b0, "know: a ruling");
            origin.push(know, "refs/heads/main");
            fresh(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)));
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            var run = gate();

            assertThat(run.out()).first().isEqualTo("GO " + REPO + "#7@" + p1);
            var body = controller.received().stream().filter(call -> call.method().equals("POST")).findFirst().orElseThrow().body();
            assertThat(body).contains("\"baseTip\":\"" + b0 + "\"").doesNotContain(know);
        }

        /// A carried verdict prints its line, and G6-Mac checks the report's line against `carriedFrom`.
        @Test
        void mergeGate_printsCarriedLine_whenVerdictIsCarried() throws IOException {
            var old = origin.commit(b0, "older head", Map.of("f.txt", "old\n"));
            var carriedReport = T2Fixtures.report(reports, "carried.md", old);
            fresh(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(6, carriedReport, old)));
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            var run = gate();

            assertThat(run.exit()).isZero();
            assertThat(run.out().get(3)).isEqualTo("verdict carried from " + old);
        }

        /// G8.b: behind only by an empty `know:` commit and a `[skip ci]` no-tree commit → GO.
        /// Mutation: compare SHAs instead of trees → red here.
        @Test
        void mergeGate_isGo_whenBaseMovedOnlyByEmptyCommits() throws IOException {
            var know = origin.empty(b0, "know: a ruling");
            var skip = origin.empty(know, "[skip ci] bump");
            origin.push(skip, "refs/heads/main");
            fresh(T2Fixtures.prFresh(p1, skip, T2Fixtures.verdict(5, report, null)));
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            var run = gate();

            assertThat(run.out()).first().isEqualTo("GO " + REPO + "#7@" + p1);
            assertThat(run.out().get(1)).isEqualTo("base main tip=" + skip);
        }
    }

    @Nested
    class HeadChecks {
        /// G1.a: the branch moved, refs/pull still at SHA → `head-moved refs/heads/<headRef> <actual>`.
        /// Mutation: compare refs/pull only → GO.
        @Test
        void mergeGate_isHeadMoved_whenBranchDiffersFromSha() throws IOException {
            var p2 = origin.commit(p1, "pushed later", Map.of("g.txt", "g\n"));
            origin.push(p2, "refs/heads/feature");
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            assertNo(gate(), "head-moved refs/heads/feature " + p2);
            assertThat(posted(GO)).isFalse();
        }

        @Test
        void mergeGate_isHeadMovedMissing_whenBranchIsGone() throws IOException {
            origin.run(origin.bare(), "update-ref", "-d", "refs/heads/feature");
            fresh(clean());

            assertNo(gate(), "head-moved refs/heads/feature missing");
        }

        /// G1.b: only refs/pull lags → `head-ref-lag`. Mutation: report it as head-moved, or pass.
        @Test
        void mergeGate_isHeadRefLag_whenOnlyPullRefLags() throws IOException {
            origin.push(b0, "refs/pull/7/head");
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            assertNo(gate(), "head-ref-lag");
        }

        /// G1.c: an `insteadOf` in the user's git config cannot redirect the explicit URL.
        /// Mutation: drop the GIT_CONFIG_* hardening → the redirect serves a moved branch → NO.
        @Test
        void mergeGate_ignoresUserInsteadOf() throws IOException {
            var decoy = GitOrigin.create(remote.resolve("decoy"), REPO);
            var other = decoy.commit(null, "decoy", Map.of("x.txt", "x\n"));
            decoy.push(other, "refs/heads/feature").push(other, "refs/pull/7/head").push(other, "refs/heads/main");
            Files.writeString(home.resolve(".gitconfig"),
                              "[url \"" + decoy.base() + "\"]\n\tinsteadOf = " + origin.base() + "\n");
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            var run = gate();

            assertThat(run.out()).first().isEqualTo("GO " + REPO + "#7@" + p1);
        }
    }

    @Nested
    class BaseChecks {
        /// G8.a: green checks then a real base change → `base-moved`. Mutation: delete G8 → GO.
        @Test
        void mergeGate_isBaseMoved_whenBaseHasANonEmptyCommit() throws IOException {
            var b1 = origin.commit(b0, "real change", Map.of("b.txt", "b\n"));
            origin.push(b1, "refs/heads/main");
            fresh(T2Fixtures.prFresh(p1, b1, T2Fixtures.verdict(5, report, null)));
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            assertNo(gate(), "base-moved 1 non-empty commits, first " + b1.substring(0, 12));
            assertThat(posted(GO)).isFalse();
        }

        /// G8.c: behind by X and its revert → `base-moved 2`, first X. Mutation: tree(mb) vs tree(tip) only → GO.
        @Test
        void mergeGate_countsChangeAndRevert() throws IOException {
            var x = origin.commit(b0, "change x", Map.of("x.txt", "x\n"));
            var revert = origin.commit(x, "revert x", java.util.Collections.singletonMap("x.txt", null));
            origin.push(revert, "refs/heads/main");
            fresh(T2Fixtures.prFresh(p1, revert, T2Fixtures.verdict(5, report, null)));

            assertNo(gate(), "base-moved 2 non-empty commits, first " + x.substring(0, 12));
        }

        /// G8.d: two merge-bases (criss-cross) → `base-ambiguous`. Mutation: take the first merge-base.
        @Test
        void mergeGate_isAmbiguous_withTwoMergeBases() throws IOException {
            var a1 = origin.commit(b0, "main side", Map.of("m.txt", "m\n"));
            var c1 = origin.commit(b0, "pr side", Map.of("p.txt", "p\n"));
            var headMerge = origin.merge(c1, a1, "merge main into pr", Map.of());
            var baseMerge = origin.merge(a1, c1, "merge pr into main", Map.of());
            origin.push(baseMerge, "refs/heads/main").push(headMerge, "refs/heads/feature").push(headMerge, "refs/pull/7/head");
            var headReport = T2Fixtures.report(reports, "criss.md", headMerge);
            fresh(T2Fixtures.prFresh(headMerge, baseMerge, T2Fixtures.verdict(5, headReport, null)));

            var run = cli.run("merge-gate", REPO, "7", headMerge);

            assertNo(run, "base-ambiguous");
        }

        /// G8.e: the base force-pushed backwards (kiln's tip not an ancestor) → `base-rewound`.
        /// Mutation: skip the ancestor check → GO.
        @Test
        void mergeGate_isRewound_whenKilnTipIsNotAnAncestor() throws IOException {
            var x = origin.commit(b0, "pushed then rewound", Map.of("r.txt", "r\n"));
            origin.push(x, "refs/heads/main").push(b0, "refs/heads/main");
            fresh(T2Fixtures.prFresh(p1, x, T2Fixtures.verdict(5, report, null)));
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            assertNo(gate(), "base-rewound");
        }

        /// G8.f: the fetched base differs from what ls-remote showed → `fetch-mismatch`. Mutation: skip the compare.
        @Test
        void mergeGate_isFetchMismatch_whenBaseMovesBetweenLsRemoteAndFetch() throws IOException {
            var b1 = origin.empty(b0, "lands between ls-remote and fetch");
            cli.ports(T2Fixtures.ports(origin,
                                       gh,
                                       T2Fixtures.interleaved(ProcessRunner.processRunner(), "ls-remote", () -> pushed(b1)),
                                       Duration.ofSeconds(45)));
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            assertNo(gate(), "fetch-mismatch refs/heads/main " + b0.substring(0, 12) + " " + b1.substring(0, 12));
        }

        private void pushed(String sha) {
            try {
                origin.push(sha, "refs/heads/main");
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }

        @Test
        void mergeGate_isBaseUntracked_whenKilnDoesNotTrackTheBase() throws IOException {
            var reply = clean();
            reply.put("baseTip", java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(Map.of("tracked", false))));
            fresh(reply);

            assertNo(gate(), "source-unavailable base-untracked");
        }

        @Test
        void mergeGate_isGitUnavailable_whenTheRepoCannotBeFetched() throws IOException {
            origin.run(origin.bare(), "update-ref", "-d", "refs/heads/main");
            fresh(clean());

            var run = gate();

            assertThat(run.exit()).isEqualTo(1);
            assertThat(run.out()).contains("source-unavailable git fetch");
        }
    }

    @Nested
    class ReportChecks {
        /// G6.c: the report edited after registration → `report-changed`. Mutation: no re-hash by default → GO.
        @Test
        void mergeGate_isReportChanged_whenReportEdited() throws IOException {
            fresh(clean());
            Files.writeString(report, Files.readString(report).replace("all good", "all good, edited"));
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            assertNo(gate(), "report-changed");
        }

        /// G6.f: a round appended after registration → `report-changed`. Mutation: hash fewer bytes (up to the
        /// deciding line) → GO.
        @Test
        void mergeGate_isReportChanged_whenRoundAppended() throws IOException {
            fresh(clean());
            Files.writeString(report, Files.readString(report) + "# round 2\n");

            assertNo(gate(), "report-changed");
        }

        /// G6.d at gate: the registered report's last VERDICT line names another SHA → `report-mismatch`.
        @Test
        void mergeGate_isReportMismatch_whenLastLineNamesAnotherSha() throws IOException {
            var other = T2Fixtures.report(reports, "other.md", b0);
            fresh(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, other, null)));

            assertNo(gate(), "report-mismatch");
        }

        @Test
        void mergeGate_isReportChanged_whenReportIsGone() throws IOException {
            fresh(clean());
            Files.delete(report);

            assertNo(gate(), "report-changed");
        }

        /// A FIX is the server's reason; G6-Mac reads nothing for it.
        @Test
        void mergeGate_printsServerReasonsInCheckOrder() throws IOException {
            fresh(T2Fixtures.withReasons(clean(),
                                         List.of(T2Fixtures.reason("G6", "verdict-FIX"),
                                                 T2Fixtures.reason("G4", "check-red build 9001"),
                                                 T2Fixtures.reason("G9", "ci-control-changed pom.xml (needs an explicit maintainer ruling)"),
                                                 T2Fixtures.reason("G2", "head-moved pulls " + b0))));

            assertNo(gate(),
                     "head-moved pulls " + b0,
                     "check-red build 9001",
                     "ci-control-changed pom.xml (needs an explicit maintainer ruling)",
                     "verdict-FIX");
            assertThat(posted(GO)).isFalse();
        }
    }

    @Nested
    class Temper {
        /// A G7 from the controller stops the reads: NO with it alone, no git run.
        @Test
        void mergeGate_stopsOnServerG7() throws IOException {
            fresh(T2Fixtures.withReasons(clean(), List.of(T2Fixtures.reason("G7", "source-unavailable kiln deadline"))));
            origin.push(b0, "refs/heads/feature");

            assertNo(gate(), "source-unavailable kiln deadline");
        }

        @Test
        void mergeGate_exits77_withNoBlock_whenTokenRefused() {
            controller.on(PR_FRESH, FakeController.Reply.json(403, "{\"error\":\"forbidden: read-only token\"}"));

            var run = gate();

            assertThat(run.exit()).isEqualTo(77);
            assertThat(run.out()).first().isEqualTo("NO");
            assertThat(run.out()).contains("source-unavailable temper 403");
        }

        @Test
        void mergeGate_isTemperStatus_whenControllerAnswers500() {
            controller.on(PR_FRESH, FakeController.Reply.json(500, "{\"error\":\"boom\"}"));

            assertNo(gate(), "source-unavailable temper 500");
        }

        @Test
        void mergeGate_isUnreachable_whenNoController() throws IOException {
            controller.close();

            assertNo(gate(), "source-unavailable temper unreachable");
        }

        /// R3-3.e (C part): the creation-time scan refuses → `NO go-invalidated checks-changed`, exit 1.
        @Test
        void mergeGate_isGoInvalidated_whenCreationScanRefuses() throws IOException {
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(409, "{\"error\":\"GO_INVALIDATED checks-changed\"}"));

            assertNo(gate(), "go-invalidated checks-changed");
        }

        @Test
        void mergeGate_isGoInvalidated_withKebabCode_whenTokenExpired() throws IOException {
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(422, "{\"error\":\"FRESH_TOKEN_EXPIRED\"}"));

            assertNo(gate(), "go-invalidated fresh-token-expired");
        }

        /// 412 on `POST fleet/go` is exit 13 with `source-unavailable temper 412`.
        @Test
        void mergeGate_exits13_whenGoRefusedForVersionSkew() throws IOException {
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(412, "{\"error\":\"version skew\"}"));

            var run = gate();

            assertThat(run.exit()).isEqualTo(13);
            assertThat(run.out()).contains("source-unavailable temper 412");
        }

        /// G7.a: the controller does not answer → `NO source-unavailable budget-exceeded` within the budget (the
        /// test's budget is 2 s; production's is 45 s). Mutation: no budget timer → the call waits for the transport's
        /// 60 s and the test times out.
        @Test
        @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
        void mergeGate_isBudgetExceeded_whenControllerDoesNotAnswer() {
            controller.hang(PR_FRESH);
            cli.ports(T2Fixtures.ports(origin, gh, ProcessRunner.processRunner(), Duration.ofSeconds(2)));
            var started = System.nanoTime();

            var run = gate();

            assertNo(run, "source-unavailable budget-exceeded");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        }
    }

    @Nested
    class Usage {
        /// F5: a SHA prefix is exit 64, no call. Mutation: accept 7-40 hex.
        @Test
        void mergeGate_exits64_forShaPrefix() {
            var run = cli.run("merge-gate", REPO, "7", p1.substring(0, 12));

            assertThat(run.exit()).isEqualTo(64);
            assertThat(controller.received()).isEmpty();
        }

        @Test
        void mergeGate_lowercasesSha() throws IOException {
            fresh(clean());
            controller.on(GO, FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));

            var run = cli.run("merge-gate", REPO, "7", p1.toUpperCase());

            assertThat(run.exit()).isZero();
        }

        @Test
        void mergeGate_exits64_forBadRepoOrPr() {
            assertThat(cli.run("merge-gate", "acme", "7", p1).exit()).isEqualTo(64);
            assertThat(cli.run("merge-gate", REPO, "0", p1).exit()).isEqualTo(64);
            assertThat(cli.run("merge-gate", REPO, "-3", p1).exit()).isEqualTo(64);
            assertThat(controller.received()).isEmpty();
        }

        /// A `--token-file` given to merge-gate is 64.
        @Test
        void mergeGate_exits64_withTokenFile() {
            var run = cli.run("merge-gate", REPO, "7", p1, "--token-file", "/tmp/x");

            assertThat(run.exit()).isEqualTo(64);
            assertThat(controller.received()).isEmpty();
        }
    }
}
