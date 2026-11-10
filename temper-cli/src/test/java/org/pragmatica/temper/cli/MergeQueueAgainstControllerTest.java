package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.T2Fixtures.GO_ID;
import static org.pragmatica.temper.cli.T2Fixtures.REPO;

/// The queue view and edits and the control-path verbs against the stub routes.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MergeQueueAgainstControllerTest {
    private static final String QUEUE = "GET /fleet/merge-queue";
    private static final String PR_FRESH = "GET /fleet/pr-fresh";

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
        origin.push(b0, "refs/heads/main").push(p1, "refs/heads/feature").push(p1, "refs/pull/7/head");
        token = T2Fixtures.tokenFile(reports.resolve("verdict-token"), T2Fixtures.VERDICT_TOKEN);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private static Map<String, Object> entry(int position, long pr, String goSha) {
        var entry = new LinkedHashMap<String, Object>();

        entry.put("position", position);
        entry.put("pr", pr);
        entry.put("addedBy", "t2wpc");
        entry.put("addedAt", "2026-11-03T09:00:00Z");
        entry.put("go", goSha == null
                        ? null
                        : Map.of("id", GO_ID, "sha", goSha, "expiresAt", "2026-11-03T10:10:00Z"));
        return entry;
    }

    private void queue(Map<String, Object>... entries) {
        controller.onJson(QUEUE, Map.of("queues", List.of(Map.of("repo", REPO, "base", "main", "entries", List.of(entries)))));
    }

    /// Q.a: both entries are behind by a real base change; only position 1 reads `update-branch`, position 2 reads
    /// `wait`. Mutation: advise update-branch for all → red.
    @Test
    @SuppressWarnings("unchecked")
    void queue_advisesOnlyPositionOne() throws IOException {
        var b1 = origin.commit(b0, "base change", Map.of("b.txt", "b\n"));
        origin.push(b1, "refs/heads/main");
        var report = T2Fixtures.report(reports, "r.md", p1);
        queue(entry(1, 7, null), entry(2, 7, null));
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, b1, T2Fixtures.verdict(5, report, null)));

        var run = cli.run("merge-gate", "--queue");

        assertThat(run.exit()).isZero();
        var head = p1.substring(0, 12);
        assertThat(run.out()).containsExactly("1 " + REPO + "#7 head=" + head + " behind_by=1 checks=green verdict=ACCEPT next=update-branch",
                                              "2 " + REPO + "#7 head=" + head + " behind_by=1 checks=green verdict=ACCEPT next=wait");
        assertThat(controller.received()).allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer " + CliHarness.TOKEN));
    }

    @Test
    void queue_advisesMerge_whenRowCarriesAGoForThisHead() throws IOException {
        var report = T2Fixtures.report(reports, "r.md", p1);
        queue(entry(1, 7, p1));
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)));

        assertThat(cli.run("merge-gate", "--queue").out()).containsExactly("1 " + REPO + "#7 head=" + p1.substring(0, 12)
                                                                             + " behind_by=0 checks=green verdict=ACCEPT next=merge go=" + GO_ID);
    }

    @Test
    void queue_advisesGate_withoutAGo_andVerify_withoutAVerdict() throws IOException {
        var report = T2Fixtures.report(reports, "r.md", p1);
        queue(entry(1, 7, b0));
        controller.onJson(PR_FRESH, T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)));
        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).endsWith("next=gate");

        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, null), List.of(T2Fixtures.reason("G6", "verdict-missing"))));
        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).endsWith("verdict=none next=verify");
    }

    @Test
    void queue_classifiesChecks() throws IOException {
        var report = T2Fixtures.report(reports, "r.md", p1);
        queue(entry(1, 7, null));
        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)),
                                                 List.of(T2Fixtures.reason("G4", "check-pending build"))));
        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).contains("checks=pending").endsWith("next=wait-ci");

        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)),
                                                 List.of(T2Fixtures.reason("G5", "check-skipped build (GitHub counts this as passing; merge-gate does not)"))));
        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).contains("checks=red").endsWith("next=blocked:checks-red");

        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)),
                                                 List.of(T2Fixtures.reason("G3", "required-none"))));
        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).contains("checks=missing").endsWith("next=blocked:checks-missing");

        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)),
                                                 List.of(T2Fixtures.reason("G9", "ci-control-changed pom.xml (needs an explicit maintainer ruling)"))));
        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).endsWith("next=blocked:ci-control-changed");
    }

    @Test
    void queue_isBlocked_whenKilnUnavailable() {
        queue(entry(1, 7, null));
        controller.onJson(PR_FRESH,
                          T2Fixtures.withReasons(T2Fixtures.prFresh(p1, b0, null),
                                                 List.of(T2Fixtures.reason("G7", "source-unavailable kiln deadline"))));

        assertThat(cli.run("merge-gate", "--queue").out().getFirst()).contains("checks=missing")
                                                                     .endsWith("next=blocked:source-unavailable");
    }

    @Test
    void queue_printsNothing_whenEmpty_andFiltersAreSent() {
        controller.onJson(QUEUE, Map.of("queues", List.of()));

        var run = cli.run("merge-gate", "--queue", "--repo", REPO, "--base", "main");

        assertThat(run.exit()).isZero();
        assertThat(run.out()).isEmpty();
        assertThat(controller.received().getFirst().target()).isEqualTo("/api/v1/fleet/merge-queue?repo=acme%2Fwidget&base=main");
    }

    @Test
    void queueAdd_printsPosition_withVerdictTokenAndOwner() {
        controller.on("POST /fleet/merge-queue",
                      FakeController.Reply.json(201, "{\"repo\":\"acme/widget\",\"base\":\"main\",\"pr\":7,\"position\":3}"));

        var run = cli.run("merge-gate", "--queue", "add", REPO, "7", "--token-file", token.toString());

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("queued " + REPO + "#7 base=main position=3");
        var call = controller.received().getFirst();
        assertThat(call.authorization()).isEqualTo("Bearer " + T2Fixtures.VERDICT_TOKEN);
        assertThat(call.body()).isEqualTo("{\"repo\":\"acme/widget\",\"pr\":7,\"addedBy\":\"tester\"}");
    }

    @Test
    void queueAdd_sendsBaseAndOwner_whenGiven() {
        controller.on("POST /fleet/merge-queue",
                      FakeController.Reply.json(201, "{\"repo\":\"acme/widget\",\"base\":\"rc5\",\"pr\":7,\"position\":1}"));

        cli.run("merge-gate", "--queue", "add", REPO, "7", "--base", "rc5", "--owner", "cto", "--token-file", token.toString());

        assertThat(controller.received().getFirst().body()).isEqualTo("{\"repo\":\"acme/widget\",\"pr\":7,\"base\":\"rc5\",\"addedBy\":\"cto\"}");
    }

    @Test
    void queueAdd_refused_whenAlreadyQueued() {
        controller.on("POST /fleet/merge-queue", FakeController.Reply.json(409, "{\"error\":\"ALREADY_QUEUED main 2\"}"));

        var run = cli.run("merge-gate", "--queue", "add", REPO, "7", "--token-file", token.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly("refused already-queued main 2");
    }

    /// A `--base` other than the row's is `refused not-queued` (lead guess 1: the base goes in the body).
    @Test
    void queueRemove_refusedNotQueued_whenBaseDiffers() {
        controller.on("POST /fleet/merge-queue/remove", FakeController.Reply.json(404, "{\"error\":\"NOT_QUEUED\"}"));

        var run = cli.run("merge-gate", "--queue", "remove", REPO, "7", "--base", "rc5", "--token-file", token.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly("refused not-queued");
        assertThat(controller.received().getFirst().body()).isEqualTo("{\"repo\":\"acme/widget\",\"pr\":7,\"base\":\"rc5\"}");
    }

    @Test
    void queueMove_printsPosition() {
        controller.on("POST /fleet/merge-queue/move",
                      FakeController.Reply.json(200, "{\"repo\":\"acme/widget\",\"base\":\"main\",\"pr\":7,\"position\":1}"));

        var run = cli.run("merge-gate", "--queue", "move", REPO, "7", "--to", "1", "--token-file", token.toString());

        assertThat(run.out()).containsExactly("moved " + REPO + "#7 base=main position=1");
        assertThat(controller.received().getFirst().body()).isEqualTo("{\"repo\":\"acme/widget\",\"pr\":7,\"to\":1}");
        assertThat(cli.run("merge-gate", "--queue", "move", REPO, "7", "--to", "0", "--token-file", token.toString()).exit()).isEqualTo(64);
    }

    @Test
    void queueRemove_printsPosition() {
        controller.on("POST /fleet/merge-queue/remove",
                      FakeController.Reply.json(200, "{\"repo\":\"acme/widget\",\"base\":\"main\",\"pr\":7,\"position\":2}"));

        assertThat(cli.run("merge-gate", "--queue", "remove", REPO, "7", "--token-file", token.toString()).out())
                .containsExactly("removed " + REPO + "#7 base=main position=2");
    }

    @Test
    void controlPaths_listAddRemove() {
        controller.onJson("GET /fleet/ci-control-paths",
                          Map.of("repo", REPO, "globs", List.of(Map.of("glob", ".github/**", "addedBy", "V14", "addedAt", "2026-11-03T08:00:00Z"))));
        controller.on("POST /fleet/ci-control-paths", FakeController.Reply.json(201, "{}"));
        controller.on("POST /fleet/ci-control-paths/remove", FakeController.Reply.json(404, "{\"error\":\"PATH_NOT_FOUND\"}"));

        assertThat(cli.run("merge-gate", "--control-paths", "list", "--repo", REPO).out())
                .containsExactly(REPO + " .github/** added_by=V14 at=2026-11-03T08:00:00Z");
        assertThat(cli.run("merge-gate", "--control-paths", "add", "--repo", REPO, "tools/**", "--token-file", token.toString()).out())
                .containsExactly("added " + REPO + " tools/**");
        assertThat(cli.run("merge-gate", "--control-paths", "remove", "--repo", REPO, "x/**", "--token-file", token.toString()).out())
                .containsExactly("refused path-not-found");
        assertThat(cli.run("merge-gate", "--control-paths", "add", "--repo", REPO, "../x", "--token-file", token.toString()).exit())
                .isEqualTo(64);
    }
}
