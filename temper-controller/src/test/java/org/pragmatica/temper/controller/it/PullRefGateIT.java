package org.pragmatica.temper.controller.it;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.Controller;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/// Ruling R-M2-7's end-to-end acceptance for WP-A (m2-design §1.6 `PullRefGateIT`): a SHA reachable only from
/// `refs/pull/<n>/head` of the origin is submittable by `--sha` and, once its Gate finished, gate-answerable. The
/// origin is a real repository and the controller's git is the real `GitCli` over it (the fake worker runs the Gate).
///
/// The GitHub API that judges a PR head's origin (#22's fork-PR guard) is scripted: PR 7 is same-repo. The branch case
/// runs the same path with a SHA on a branch, the control for the instrument (submit by SHA, the Gate run,
/// `GET /gate/{sha}`). Probe: revert the `refs/pull/*/head` refspec → the PR-only submit is `422 REFUSED(source)`: red.
@Tag("pg")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PullRefGateIT {
    private Path dir;
    private String onBranch;
    private String prOnly;
    private ControllerHarness harness;

    @BeforeAll
    void setUp() throws Exception {
        dir = Files.createTempDirectory("pull-ref-gate");
        var origin = Files.createDirectories(dir.resolve("origin"));

        run(origin, "git", "init", "-q", "-b", "main");
        run(origin, "git", "config", "user.email", "t@t");
        run(origin, "git", "config", "user.name", "t");
        Files.writeString(origin.resolve("pom.xml"), "<project/>\n");
        run(origin, "git", "add", ".");
        run(origin, "git", "commit", "-q", "-m", "on main");
        onBranch = run(origin, "git", "rev-parse", "HEAD");
        Files.writeString(origin.resolve("pr.txt"), "a pull request's change\n");
        run(origin, "git", "add", ".");
        run(origin, "git", "commit", "-q", "-m", "pull request head");
        prOnly = run(origin, "git", "rev-parse", "HEAD");
        run(origin, "git", "update-ref", "refs/pull/7/head", prOnly);
        run(origin, "git", "reset", "-q", "--hard", onBranch);
        assertThat(run(origin, "git", "branch", "--contains", prOnly)).as("on no branch").isEmpty();

        var git = GitCli.gitCli(ProcessRunner.processRunner(), origin.toString(), dir.resolve("mirror.git"));

        PullOrigins pulls = pull -> Promise.success(pull.value() == 7 ? PullOrigin.SAME_REPO : PullOrigin.FORK);

        harness = new ControllerHarness(Controller.Git.git(git, git, git, pulls), TimeSpan.timeSpan(500).millis());
    }

    @AfterAll
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /// The control: a SHA on a branch goes through the same real git path to a PASSED gate answer.
    @Test
    void branchSha_isSubmittable_andGateAnswerable() throws Exception {
        assertSubmittableAndAnswered(onBranch);
    }

    @Test
    void pullRequestOnlySha_isSubmittable_andGateAnswerable() throws Exception {
        assertSubmittableAndAnswered(prOnly);
    }

    private void assertSubmittableAndAnswered(String sha) throws Exception {
        var submitted = harness.post("/jobs", gateJob(sha));

        assertThat(submitted.status()).as(submitted.body()).isEqualTo(200);
        var job = ControllerHarness.jobOf(submitted);

        harness.waitForState(job, "PASSED");
        var gate = harness.get("/gate/" + sha);

        assertThat(gate.status()).as(gate.body()).isEqualTo(200);
        assertThat(gate.body()).contains("\"answer\":\"PASSED\"", "\"job\":" + job, "\"sha\":\"" + sha + "\"");
        assertThat(harness.get("/gate/" + sha.substring(0, 7)).body()).as("its prefix").contains("\"answer\":\"PASSED\"");
    }

    private static String gateJob(String sha) {
        return "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"sha\":\"" + sha + "\"}},\"recipe\":{\"gate\":{}}}";
    }

    private static String run(Path in, String... argv) throws IOException, InterruptedException {
        var process = HermeticProcess.builder(List.of(argv)).directory(in.toFile()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

        assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + output).isZero();
        return output;
    }
}
