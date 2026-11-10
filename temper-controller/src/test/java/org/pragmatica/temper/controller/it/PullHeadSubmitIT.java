package org.pragmatica.temper.controller.it;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.Controller;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;

/// Submit by SHA end to end over a real [GitCli] (it-pg, m1-design M1-3, R-M2-7): a Gate at a commit that only a
/// `refs/pull/7/head` of the bare origin holds is accepted, stored as a pushed source at exactly that SHA, found by
/// `GET /jobs?sha=` (the store read `temper gate` will answer from, spec §8.2), when the scripted GitHub API
/// calls PR 7 same-repo. A fork PR head is refused `fork-pr`, a SHA on no ref `source`.
@Tag("pg")
class PullHeadSubmitIT {
    @TempDir
    Path dir;
    private ControllerHarness harness;
    private String pullOnly;
    /// What the scripted GitHub API says of PR 7; read at submit time.
    private volatile PullOrigin origin7 = PullOrigin.SAME_REPO;

    private String run(Path in, String... argv) throws IOException, InterruptedException {
        var process = HermeticProcess.builder(argv).directory(in.toFile()).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();
        return out;
    }

    @BeforeEach
    void start() throws Exception {
        var bare = dir.resolve("origin.git");
        var work = Files.createDirectories(dir.resolve("work"));
        run(dir, "git", "init", "-q", "--bare", "-b", "main", bare.toString());
        run(work, "git", "init", "-q", "-b", "main");
        run(work, "git", "config", "user.email", "t@t");
        run(work, "git", "config", "user.name", "t");
        Files.writeString(work.resolve("README"), "one\n");
        run(work, "git", "add", "README");
        run(work, "git", "commit", "-q", "-m", "one");
        run(work, "git", "push", "-q", bare.toString(), "main");
        run(work, "git", "checkout", "-q", "--detach");
        Files.writeString(work.resolve("PR"), "seven\n");
        run(work, "git", "add", "PR");
        run(work, "git", "commit", "-q", "-m", "pr 7");
        run(work, "git", "push", "-q", bare.toString(), "HEAD:refs/pull/7/head");
        pullOnly = run(work, "git", "rev-parse", "HEAD");
        assertThat(run(bare, "git", "branch", "--contains", pullOnly)).as("the PR head is on no branch").isEmpty();
        var git = GitCli.gitCli(ProcessRunner.processRunner(), bare.toString(), dir.resolve("mirror.git"));
        PullOrigins pulls = pull -> Promise.success(pull.value() == 7 ? origin7 : PullOrigin.FORK);
        harness = new ControllerHarness(Controller.Git.git(git, git, git, pulls), TimeSpan.timeSpan(300).millis());
    }

    @AfterEach
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    private static String gateAt(String sha) {
        return "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"sha\":\"" + sha + "\"}},\"recipe\":{\"gate\":{}}}";
    }

    @Test
    void submitGate_bySha_accepted_andFoundBySha_shaOnlyOnAPullHead() throws Exception {
        var submitted = harness.post("/jobs", gateAt(pullOnly));

        assertThat(submitted.status()).as(submitted.body()).isEqualTo(200);
        var job = jobOf(submitted);
        var listed = harness.get("/jobs?sha=" + pullOnly);
        assertThat(listed.body()).contains("\"job\":" + job + ",", "\"sha\":\"" + pullOnly + "\"");
        var row = harness.store.jobs().job(JobId.jobId(job).unwrap()).await(Pg.WAIT).unwrap().unwrap();
        assertThat(row.unpushed()).as("a PR head is pushed evidence").isFalse();
        assertThat(row.sha().map(Sha::value).or("")).isEqualTo(pullOnly);
        assertThat(row.recipe()).isInstanceOf(Recipe.Gate.class);
    }

    @Test
    void submitGate_bySha_refusedForkPr_shaOnlyOnAForkPullHead() throws Exception {
        origin7 = PullOrigin.FORK;

        var refused = harness.post("/jobs", gateAt(pullOnly));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"fork-pr\"");
    }

    @Test
    void submitGate_bySha_refusedSource_shaOnNoRef() throws Exception {
        var refused = harness.post("/jobs", gateAt("1".repeat(40)));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"source\"");
    }
}
