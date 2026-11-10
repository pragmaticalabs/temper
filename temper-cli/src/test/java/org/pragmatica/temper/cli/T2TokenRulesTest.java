package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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

/// The `--token-file` rules (R2-2) and R3-1: no output of any T2 verb contains a token-file path
/// the CLI resolved or was given.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class T2TokenRulesTest {
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
    private Path envToken;
    private Path verdictToken;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN).verdictToken(T2Fixtures.VERDICT_TOKEN);
        origin = GitOrigin.create(remote, REPO);
        envToken = T2Fixtures.tokenFile(home.resolve("agent/read-token"), CliHarness.TOKEN);
        verdictToken = T2Fixtures.tokenFile(home.resolve(".config/temper/verdict-token"), T2Fixtures.VERDICT_TOKEN);
        cli = CliHarness.against(home, controller)
                        .env("TEMPER_TOKEN_FILE", envToken.toString())
                        .ports(T2Fixtures.ports(origin, new T2Fixtures.GhSpy()));
        b0 = origin.commit(null, "base", Map.of("a.txt", "a\n"));
        p1 = origin.commit(b0, "feature", Map.of("f.txt", "f\n"));
        origin.push(b0, "refs/heads/main").push(p1, "refs/heads/feature").push(p1, "refs/pull/7/head");
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    /// R2-2: without `--token-file` every VERDICT verb is 64 and sends nothing, although `TEMPER_TOKEN_FILE` is set
    /// and `~/.config/temper/verdict-token` exists. Mutation: a default path, or honouring the env → a call is made.
    @Test
    void verdictVerbs_exit64_withoutTokenFile() throws IOException {
        var report = T2Fixtures.report(reports, "r.md", p1).toString();
        var verbs = List.of(new String[] {"merge", GO_ID},
                            new String[] {"verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", report},
                            new String[] {"merge-gate", "--queue", "add", REPO, "7"},
                            new String[] {"merge-gate", "--queue", "remove", REPO, "7"},
                            new String[] {"merge-gate", "--queue", "move", REPO, "7", "--to", "1"},
                            new String[] {"merge-gate", "--control-paths", "add", "--repo", REPO, "tools/**"},
                            new String[] {"merge-gate", "--control-paths", "remove", "--repo", REPO, "tools/**"});

        for (var verb : verbs) {
            var run = cli.run(verb);

            assertThat(run.exit()).as(String.join(" ", verb)).isEqualTo(64);
            assertThat(run.err()).as(String.join(" ", verb)).contains("temper: token-file-required: ")
                                 .contains(" needs --token-file <verdict-token>");
        }
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void tokenFile_mustHoldOneToken_andIsNeverNamed() throws IOException {
        var two = Files.writeString(home.resolve("two-tokens"), "a b\n");
        Files.setPosixFilePermissions(two, PosixFilePermissions.fromString("rw-------"));

        var bad = cli.run("merge", GO_ID, "--token-file", two.toString());
        var missing = cli.run("merge", GO_ID, "--token-file", home.resolve("absent").toString());

        assertThat(bad.exit()).isEqualTo(64);
        assertThat(missing.exit()).isEqualTo(64);
        assertThat(bad.err() + missing.err()).doesNotContain(home.toString());
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void tokenFile_sharedDrawsAWarning_withoutThePath() throws IOException {
        Files.setPosixFilePermissions(verdictToken, PosixFilePermissions.fromString("rw-r--r--"));
        controller.on("POST /fleet/go/" + GO_ID + "/consume", FakeController.Reply.json(409, "{\"error\":\"go-used\"}"));

        var run = cli.run("merge", GO_ID, "--token-file", "~/.config/temper/verdict-token");

        assertThat(run.err()).contains("warning: --token-file is accessible by group/others").doesNotContain(home.toString());
        assertThat(run.out()).containsExactly("refused go-used");
    }

    /// R3-1 (reworded 11-04): the GO's stdout carries `--token-file <verdict-token>` literally and neither the
    /// resolved nor the given token-file path (the report path, here under `$HOME`, is allowed), with
    /// `TEMPER_TOKEN_FILE` and a verdict-token file set; the same scan of every T2 verb's stdout, stderr, help and
    /// errors. Mutation: print the resolved path → red.
    @Test
    void noT2Output_containsATokenFilePath() throws IOException {
        var report = T2Fixtures.report(Files.createDirectories(home.resolve("reviews")), "r.md", p1);
        controller.onJson("GET /fleet/pr-fresh", T2Fixtures.prFresh(p1, b0, T2Fixtures.verdict(5, report, null)));
        controller.on("POST /fleet/go", FakeController.Reply.json(201, controller.write(T2Fixtures.goCreated())));
        controller.on("POST /fleet/go/" + GO_ID + "/consume", FakeController.Reply.json(409, "{\"error\":\"go-used\"}"));
        controller.on("POST /fleet/verdicts", FakeController.Reply.json(201, "{\"id\":1,\"at\":\"2026-11-03T10:00:00Z\",\"reportSha256\":\"" + T2Fixtures.REGISTERED_SHA256 + "\",\"invalidated\":[]}"));
        controller.onJson("GET /fleet/merge-queue", Map.of("queues", List.of()));
        Files.setPosixFilePermissions(envToken, PosixFilePermissions.fromString("rw-r--r--"));

        var go = cli.run("merge-gate", REPO, "7", p1);

        assertThat(go.exit()).isZero();
        assertThat(go.out()).contains("temper merge " + GO_ID + " --token-file <verdict-token>");
        assertThat(String.join("\n", go.out())).as("the report path is allowed (R3-1)").contains(report.toString());

        var runs = new ArrayList<CliHarness.Run>(List.of(go));
        var given = verdictToken.toString();
        runs.add(cli.run("merge", GO_ID, "--token-file", given));
        runs.add(cli.run("merge", GO_ID, "--token-file=" + given));
        runs.add(cli.run("merge", "bad-id", "--token-file", given));
        runs.add(cli.run("merge", GO_ID));
        runs.add(cli.run("merge", "--help"));
        runs.add(cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", report.toString(),
                         "--token-file", given));
        runs.add(cli.run("verdict", REPO, "7", p1, "ACCEPT", "--base", "main", "--by", "t2v", "--report", report.toString(),
                         "--token-file", given + "-absent"));
        runs.add(cli.run("merge-gate", REPO, "7", p1, "--token-file", given));
        runs.add(cli.run("merge-gate", "--queue"));
        runs.add(cli.run("merge-gate", "--queue", "add", REPO, "7", "--token-file", given));
        runs.add(cli.run("merge-gate", "--control-paths", "add", "--repo", REPO, "tools/**", "--token-file", given));
        runs.add(cli.run("merge-gate", "--queue", "remove", REPO, "7", "--token-file", given));

        for (var run : runs) {
            var text = String.join("\n", run.out()) + "\n" + run.err();

            assertThat(text).doesNotContain(envToken.toString())
                            .doesNotContain("agent/read-token")
                            .doesNotContain(".config/temper/verdict-token")
                            .doesNotContain(given)
                            .doesNotContain(CliHarness.TOKEN)
                            .doesNotContain(T2Fixtures.VERDICT_TOKEN);
        }
    }

    /// The ordinary token's missing-file error names no path either (unlike the T1 verbs' message).
    @Test
    void mergeGate_missingOrdinaryToken_isNotNamed() throws IOException {
        Files.delete(envToken);

        var run = cli.run("merge-gate", REPO, "7", p1);

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("the CLI token file is missing or unreadable").doesNotContain(home.toString());
    }
}
