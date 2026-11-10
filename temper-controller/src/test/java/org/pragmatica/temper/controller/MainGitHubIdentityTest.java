package org.pragmatica.temper.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.config.ConfigError;
import org.pragmatica.temper.controller.config.GitHubIdentity;
import org.pragmatica.temper.controller.git.GitHubPulls;
import org.pragmatica.temper.ghapp.AppAuthError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// The controller's GitHub identity through [Main]'s own start path (r1 F7): the App wires an
/// authenticated judge, `anonymous = true` an unauthenticated one, and a config with neither (C.4) or with an unsafe key
/// refuses the start with exit 78.
class MainGitHubIdentityTest {
    private static final String ANONYMOUS = "[github]\nanonymous = true\n";

    private static final String APP = """
        [github.app]
        clientId = "Iv23liTestClient"
        installationId = 424242
        keyFile = "~/keys/app.pem"
        """;

    @TempDir
    Path home;

    private Path config(String githubSections, String origin) throws Exception {
        Files.writeString(home.resolve("token"), "full-s3cret-token\n");
        Files.writeString(home.resolve("pg-password"), "pg-pass\n");

        return Files.writeString(home.resolve("controller.toml"),
                                 """
            [server]
            port = 7070
            tokenFile = "~/token"
            [db]
            host = "127.0.0.1"
            port = 55432
            name = "temper"
            user = "temper"
            passwordFile = "~/pg-password"
            [repo]
            origin = "%s"
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
            """.formatted(origin) + githubSections);
    }

    private static final String GITHUB_ORIGIN = "git@github.com:pragmaticalabs/pragmatica.git";

    private Main.Settings settings(String githubSections) throws Exception {
        return Main.settings(config(githubSections, GITHUB_ORIGIN), home).unwrap();
    }

    private void key(String mode) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");

        generator.initialize(2048);
        var der = generator.generateKeyPair().getPrivate().getEncoded();
        var pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END PRIVATE KEY-----\n";
        var dir = Files.createDirectories(home.resolve("keys"),
                                          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var file = Files.writeString(dir.resolve("app.pem"), pem);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
    }

    private static Cause failure(Result<?> result) {
        assertThat(result.isFailure()).as(result.toString()).isTrue();

        return result.fold(cause -> cause, _ -> null);
    }

    /// C.4
    @Test
    void withoutGithubApp_andWithoutAnonymous_theStartIsRefused_withExit78() throws Exception {
        var refused = failure(Main.settings(config("", GITHUB_ORIGIN), home));

        assertThat(refused.stream()).contains(ConfigError.General.NO_GITHUB_IDENTITY);
        assertThat(Main.exitCode(refused)).isEqualTo(78);
        assertThat(refused.message()).contains("[github.app]", "anonymous = true");
        assertThat(Main.exitCode(failure(Main.settings(config("[github]\nanonymous = false\n", GITHUB_ORIGIN), home)))).isEqualTo(78);
    }

    @Test
    void anIdleSessionTimeoutRefusal_exitsWith78() {
        var refused = org.pragmatica.temper.store.api.StoreError.IdleSessionTimeout.FACTORY.apply("60s", "150");

        assertThat(Main.exitCode(refused)).isEqualTo(78);
        assertThat(refused.message()).contains("idle_session_timeout", "60s", "150");
    }

    @Test
    void otherStartFailures_exitWith1() {
        assertThat(Main.exitCode(ConfigError.General.NO_VERSION)).isEqualTo(1);
    }

    @Test
    void anonymousTrue_startsWithAnUnauthenticatedJudge() throws Exception {
        var git = Main.git(ProcessRunner.processRunner(), settings(ANONYMOUS).config()).unwrap();

        assertThat(((GitHubPulls) git.pulls()).auth().isEmpty()).isTrue();
    }

    @Test
    void githubApp_startsWithAnAuthenticatedJudge_thatDoesNotFollowRedirects() throws Exception {
        key("rw-------");
        var git = Main.git(ProcessRunner.processRunner(), settings(APP).config()).unwrap();
        var judge = (GitHubPulls) git.pulls();

        assertThat(judge.auth().isPresent()).isTrue();
        assertThat(judge.client().followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        assertThat(judge.api()).isEqualTo(GitHubPulls.API);
        assertThat(git.pulls()).isSameAs(git.merges());
    }

    @Test
    void githubApp_tokenIsNarrowedToTheOriginRepository_andPullRequestsRead() {
        var app = new GitHubIdentity.App("Iv23liTestClient", 424242, home.resolve("app.pem"));
        var config = Main.appAuthConfig(app, GITHUB_ORIGIN).unwrap();

        assertThat(config.repositories()).containsExactly("pragmatica");
        assertThat(config.permissions()).containsExactlyEntriesOf(java.util.Map.of("pull_requests", "read"));
        assertThat(config.apiBase()).isEqualTo(GitHubPulls.API);
    }

    @Test
    void githubApp_withAGroupReadableKey_refusesTheStart_withExit78() throws Exception {
        key("rw-r-----");
        var refused = failure(Main.git(ProcessRunner.processRunner(), settings(APP).config()));

        assertThat(refused).isInstanceOf(AppAuthError.KeyFileTooOpen.class);
        assertThat(Main.exitCode(refused)).isEqualTo(78);
    }

    @Test
    void githubApp_withAMissingKey_refusesTheStart() throws Exception {
        assertThat(Main.git(ProcessRunner.processRunner(), settings(APP).config()).isFailure()).isTrue();
    }

    @Test
    void githubApp_withANonGithubOrigin_refusesTheStart() throws Exception {
        key("rw-------");
        var local = Main.settings(config(APP, "/srv/git/pragmatica.git"), home).unwrap();

        assertThat(Main.git(ProcessRunner.processRunner(), local.config()).isFailure()).isTrue();
    }

    @Test
    void githubApp_andAnonymousTogether_isRefused() throws Exception {
        assertThat(Main.settings(config(ANONYMOUS + APP, GITHUB_ORIGIN), home).isFailure()).isTrue();
    }

    @Test
    void githubApp_missingAKey_namesIt() throws Exception {
        var refused = failure(Main.settings(config("[github.app]\nclientId = \"x\"\ninstallationId = 1\n", GITHUB_ORIGIN),
                                            home));

        assertThat(refused.message()).contains("keyFile");
    }
}
