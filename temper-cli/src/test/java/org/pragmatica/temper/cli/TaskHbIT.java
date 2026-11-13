package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.temper.testsupport.HermeticProcess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.temper.cli.FakeController.Reply;
import static org.assertj.core.api.Assertions.assertThat;


/// Lane registry WP3 (vt39 F3): `task hb <id> --tree DIR` measures head, dirty and unpushed itself with git and always
/// sends the report. Nothing on the command line can state a count (`--dirty` is not an option), so an agent cannot
/// misreport; a path that is not a git tree is 64 and no heartbeat goes out.
///
/// No upstream: `unpushed` is the commits no remote-tracking ref contains; a tree without any remote reads its whole
/// history (over-reporting, never under-reporting).
///
/// Probe: take `dirty` from anywhere but `git status --porcelain` (hard-code 0) -> `hb_countsModifiedAndUntrackedEntries` red.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TaskHbIT {
    @TempDir
    Path home;

    @TempDir
    Path work;

    private FakeController controller;
    private CliHarness cli;
    private Path tree;
    private Path origin;

    @BeforeEach
    void start() throws Exception {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
        var dir = Files.createDirectories(home.resolve(".config/temper/leases"));
        var file = Files.writeString(dir.resolve("42-7"), "lease-token-7\n");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        controller.on("POST /tasks/42/heartbeat", Reply.json(204, ""));
        origin = Files.createDirectories(work.resolve("origin.git"));
        git(origin, "init", "--quiet", "--bare");
        tree = Files.createDirectories(work.resolve("tree"));
        git(tree, "init", "--quiet", "--initial-branch=feat");
        git(tree, "config", "user.email", "t@example.org");
        git(tree, "config", "user.name", "t");
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private String lastBody() {
        return controller.received()
                         .getLast()
                         .body();
    }

    private CliHarness.Run hb() {
        return cli.run("task", "hb", "42", "--tree", tree.toString());
    }

    private String commit(String name) throws Exception {
        Files.writeString(tree.resolve(name), name);
        git(tree, "add", name);
        git(tree, "commit", "--quiet", "-m", name);

        return git(tree, "rev-parse", "HEAD");
    }

    @Test
    void hb_countsModifiedAndUntrackedEntries_andSendsHeadWithTheLease() throws Exception {
        var head = commit("a");

        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "-u", "origin", "feat");
        Files.writeString(tree.resolve("a"), "changed");
        Files.writeString(tree.resolve("new1"), "x");
        Files.writeString(tree.resolve("new2"), "x");
        var run = hb();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).containsExactly("task 42 HEARTBEAT epoch=7 head=" + head.substring(0, 7)
                                             + " dirty=3 unpushed=0");
        assertThat(controller.received().getLast().authorization()).isEqualTo("Lease lease-token-7");
        assertThat(lastBody()).contains("\"epoch\":7")
                  .contains("\"head\":\"" + head + "\"")
                  .contains("\"dirty\":3")
                  .contains("\"unpushed\":0");
    }

    @Test
    void hb_cleanAndPushed_reportsZeros() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "-u", "origin", "feat");
        hb();
        assertThat(lastBody()).contains("\"dirty\":0").contains("\"unpushed\":0");
    }

    @Test
    void hb_withAnUpstream_countsTheCommitsAheadOfIt() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "-u", "origin", "feat");
        commit("b");
        commit("c");
        hb();
        assertThat(lastBody()).contains("\"unpushed\":2");
    }

    @Test
    void hb_noUpstreamButPushedWithoutDashU_countsWhatNoRemoteRefHas() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "origin", "feat");
        commit("b");
        hb();
        assertThat(lastBody()).contains("\"unpushed\":1");
    }

    @Test
    void hb_branchCutFromOriginMainAndPushedWithoutDashU_readsZero_thoughItsUpstreamIsOriginMain() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "origin", "feat:main");
        git(tree, "fetch", "--quiet", "origin");
        git(tree, "checkout", "--quiet", "-b", "lane", "origin/main");
        commit("b");
        git(tree, "push", "--quiet", "origin", "lane");
        assertThat(git(tree, "rev-parse", "--abbrev-ref", "@{upstream}")).isEqualTo("origin/main");
        hb();
        assertThat(lastBody()).contains("\"unpushed\":0");
    }

    @Test
    void hb_localCommitPushedNowhere_countsOne_thoughOtherCommitsArePushed() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "-u", "origin", "feat");
        commit("b");
        hb();
        assertThat(lastBody()).contains("\"unpushed\":1");
    }

    @Test
    void hb_detachedHeadOnAPushedCommit_readsZero() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "origin", "feat");
        git(tree, "checkout", "--quiet", "--detach", "HEAD");
        hb();
        assertThat(lastBody()).contains("\"unpushed\":0");
    }

    @Test
    void hb_noUpstreamAndNoRemote_countsTheWholeHistory() throws Exception {
        commit("a");
        commit("b");
        commit("c");
        hb();
        assertThat(lastBody()).contains("\"unpushed\":3");
    }

    @Test
    void hb_noCommitYet_orNotATree_is64_andSendsNothing() throws Exception {
        assertThat(hb().exit()).isEqualTo(64);
        assertThat(cli.run("task",
                           "hb",
                           "42",
                           "--tree",
                           work.resolve("missing").toString()).exit()).isEqualTo(64);
        assertThat(cli.run("task",
                           "hb",
                           "42",
                           "--tree",
                           Files.createDirectories(work.resolve("plain")).toString())
                      .exit()).isEqualTo(64);
        assertThat(controller.received().stream().filter(request -> request.target()
                                                                           .endsWith("/heartbeat"))).isEmpty();
    }

    /// Only the `rev-list --count HEAD --not --remotes` step fails (a corrupt remote-tracking ref); rev-parse and status
    /// still work. A git failure is refusal 64 with nothing sent, never a zero report.
    /// Probe: `.recover(_ -> 0)` on the unpushed count -> this test red.
    @Test
    void hb_revListFailsAlone_is64_sendsNothing_andPrintsNoReport() throws Exception {
        commit("a");
        git(tree, "remote", "add", "origin", origin.toString());
        git(tree, "push", "--quiet", "-u", "origin", "feat");
        var bad = tree.resolve(".git/refs/remotes/origin/bad");

        Files.writeString(bad, "1".repeat(40) + "\n");
        var run = hb();

        assertThat(run.exit()).isEqualTo(64);
        assertThat(String.join("\n", run.out())).doesNotContain("HEARTBEAT").doesNotContain("unpushed=");
        assertThat(controller.received().stream().filter(request -> request.target()
                                                                           .endsWith("/heartbeat"))).isEmpty();
    }

    /// The agent has no way to state the numbers, and a tree is not optional.
    @Test
    void hb_takesNoCountOption_andRequiresTheTree() throws Exception {
        commit("a");
        assertThat(cli.run("task", "hb", "42", "--tree", tree.toString(), "--dirty", "0").exit()).isEqualTo(64);
        assertThat(cli.run("task", "hb", "42").exit()).isEqualTo(64);
        assertThat(controller.received().stream().filter(request -> request.target()
                                                                           .endsWith("/heartbeat"))).isEmpty();
    }

    @Test
    void hb_leaseLost_is12_andForgetsTheLeaseFile() throws Exception {
        commit("a");
        controller.on("POST /tasks/42/heartbeat", Reply.json(409, "{\"error\":\"lease-lost\"}"));
        assertThat(hb().exit()).isEqualTo(12);
        assertThat(home.resolve(".config/temper/leases/42-7")).doesNotExist();
    }

    private static String git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));
        var process = HermeticProcess.builder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8).strip();

        assertThat(process.waitFor()).as(String.join(" ", command) + ": " + output).isZero();

        return output;
    }
}
