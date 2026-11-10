package org.pragmatica.temper.controller;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.http.Headers;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.git.GitHubPulls;
import org.pragmatica.temper.controller.http.Access;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/// D3, from files on disk through [Main]'s own start path (config → secrets → [org.pragmatica.temper.controller.http.Credentials]):
/// a configured `readTokenFile` must reach the router as the read token, and [Main.Settings#toString] prints neither
/// token.
class MainWiringTest {
    private static final String FULL_TOKEN = "full-s3cret-token";
    private static final String READ_TOKEN = "read-s3cret-token";

    @TempDir
    Path home;

    private Path config(String readTokenLine) throws Exception {
        Files.writeString(home.resolve("token"), FULL_TOKEN + "\n");
        Files.writeString(home.resolve("read-token"), READ_TOKEN + "\n");
        Files.writeString(home.resolve("pg-password"), "pg-pass\n");

        return Files.writeString(home.resolve("controller.toml"), """
            [server]
            port = 7070
            tokenFile = "~/token"
            %s
            [db]
            host = "127.0.0.1"
            port = 55432
            name = "temper"
            user = "temper"
            passwordFile = "~/pg-password"
            [repo]
            origin = "git@github.com:pragmaticalabs/pragmatica.git"
            mirror = "~/mirror.git"
            [portGroup]
            modules = []
            [worker]
            jar = "~/temper-worker.jar"
            [[hosts]]
            name = "bigboy"
            address = "localhost"
            user = "builder"
            key = "~/key"
            knownHosts = "~/known_hosts"
            memoryBudgetMb = 20480
            [github]
            anonymous = true
            """.formatted(readTokenLine));
    }

    private Main.Settings settings(String readTokenLine) throws Exception {
        return Main.settings(config(readTokenLine), home).unwrap();
    }

    private static Option<Access> classify(Main.Settings settings, String token) {
        return Main.credentials(settings)
                   .unwrap()
                   .classify(Headers.fromSingleValueMap(Map.of("Authorization", "Bearer " + token)));
    }

    @Test
    void startPath_classifiesTheConfiguredReadToken_asRead() throws Exception {
        var settings = settings("readTokenFile = \"~/read-token\"");

        assertThat(classify(settings, READ_TOKEN)).isEqualTo(Option.some(Access.READ));
        assertThat(classify(settings, FULL_TOKEN)).isEqualTo(Option.some(Access.FULL));
    }

    @Test
    void startPath_withoutReadTokenFile_knowsNoReadToken() throws Exception {
        var settings = settings("");

        assertThat(classify(settings, READ_TOKEN).isEmpty()).isTrue();
        assertThat(classify(settings, FULL_TOKEN)).isEqualTo(Option.some(Access.FULL));
    }

    @Test
    void settingsToString_printsNeitherToken() throws Exception {
        var withRead = settings("readTokenFile = \"~/read-token\"").toString();
        var withoutRead = settings("").toString();

        assertThat(withRead).doesNotContain(FULL_TOKEN, READ_TOKEN)
                            .contains("token=***", "readToken=***");
        assertThat(withoutRead).doesNotContain(FULL_TOKEN, READ_TOKEN)
                               .contains("readToken=none");
    }

    /// The configured verdict and Mac token files reach [Credentials] as VERDICT and MAC, a Mac
    /// token equal to the verdict token refuses the start (T.distinct), and the settings print neither token.
    @Test
    void startPath_classifiesTheVerdictAndMacTokens_andRefusesThemEqual() throws Exception {
        Files.writeString(home.resolve("verdict-token"), "V3rd1ct-s3cret\n");
        Files.writeString(home.resolve("mac-token"), "M4c-s3cret\n");
        Files.writeString(home.resolve("same-token"), "V3rd1ct-s3cret\n");
        var settings = settings("verdictTokenFile = \"~/verdict-token\"\nmacTokenFile = \"~/mac-token\"");

        assertThat(classify(settings, "V3rd1ct-s3cret")).isEqualTo(Option.some(Access.VERDICT));
        assertThat(classify(settings, "M4c-s3cret")).isEqualTo(Option.some(Access.MAC));
        assertThat(classify(settings, FULL_TOKEN)).isEqualTo(Option.some(Access.FULL));
        assertThat(settings.toString()).doesNotContain("V3rd1ct-s3cret", "M4c-s3cret")
                                       .contains("verdictToken=***", "macToken=***");
        assertThat(settings("").toString()).contains("verdictToken=none", "macToken=none");
        assertThat(Main.settings(config("verdictTokenFile = \"~/verdict-token\"\nmacTokenFile = \"~/same-token\""), home)
                       .isFailure()).isTrue();
    }

    /// R-M2-7: the start path installs the real GitHub judge (production client: follows redirects, the public API,
    /// the configured origin), never a permissive stand-in, beside the git CLI adapters.
    @Test
    void startPath_installsTheGitHubPullRequestJudge() throws Exception {
        var config = settings("").config();

        var git = Main.git(ProcessRunner.processRunner(), config).unwrap();

        assertThat(git.pulls()).isInstanceOf(GitHubPulls.class);
        var judge = (GitHubPulls) git.pulls();
        assertThat(judge.api()).isEqualTo(GitHubPulls.API);
        assertThat(judge.origin()).isEqualTo("git@github.com:pragmaticalabs/pragmatica.git");
        assertThat(judge.timeout()).isEqualTo(GitHubPulls.TIMEOUT);
        assertThat(judge.client().followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
        assertThat(judge.known()).isEmpty();
        assertThat(git.remote()).isInstanceOf(GitCli.class);
        assertThat(git.mirror()).isSameAs(git.remote());
        assertThat(git.tree()).isSameAs(git.remote());
    }
}
