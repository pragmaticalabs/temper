package org.pragmatica.kiln.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.pragmatica.kiln.service.config.ConfigError;
import org.pragmatica.kiln.service.github.Credential;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;


/// [Main]'s start path from real files (WP9 open item 13): the GitHub token is read by path and never printed. It is in
/// no log line, no `toString` and no failure message, whether it is read, refused (malformed, or open beyond its owner)
/// or the start fails later (kiln-pg unreachable). A set `tokenFile` with no file at its path fails the start (design
/// §9.2); no `tokenFile` key means unauthenticated, and the log says which, by the file's path only.
class MainTest {
    private static final String TOKEN = "github_pat_SECRET0123456789abcdefSECRET";

    @TempDir
    Path home;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger main = (Logger) LoggerFactory.getLogger(Main.class);
    private Level level;

    /// Main's INFO lines are captured whatever the test logging configuration's level.
    @BeforeEach
    void capture() {
        appender.start();
        root.addAppender(appender);
        level = main.getLevel();
        main.setLevel(Level.INFO);
    }

    @AfterEach
    void release() {
        root.detachAppender(appender);
        main.setLevel(level);
    }

    private static String message(Result<?> result) {
        return result.fold(Cause::message, _ -> "");
    }

    /// The appender sits on ROOT, so other tests' background threads append to its list while a test reads it;
    /// AppenderBase.doAppend holds the appender's monitor, so the snapshot is taken under the same lock.
    private List<String> logged() {
        List<ILoggingEvent> events;

        synchronized (appender) {
            events = List.copyOf(appender.list);
        }

        return events.stream()
                     .map(event -> event.getFormattedMessage() + " " + event.getThrowableProxy())
                     .toList();
    }

    private Path secret(String name, String content, String permissions) throws Exception {
        var file = Files.createDirectories(home.resolve(".config/kiln")).resolve(name);

        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));

        return file;
    }

    private Path config(String tokenLine, int dbPort) throws Exception {
        secret("pg-password", "pw", "rw-------");
        secret("read-token", "temper-read", "rw-------");

        return Files.writeString(home.resolve("kiln.toml"),
                                 """
            [db]
            host = "127.0.0.1"
            port = %d
            name = "kiln"
            user = "kiln"
            passwordFile = "~/.config/kiln/pg-password"
            [github]
            repo = "o/r"
            bases = ["main"]
            %s
            [mirror]
            origin = "https://github.com/o/r.git"
            [temper]
            url = "http://127.0.0.1:7070"
            readTokenFile = "~/.config/kiln/read-token"
            """.formatted(dbPort, tokenLine));
    }

    @Test
    void settings_readTheTokenByPath_andNeverPrintIt() throws Exception {
        secret("github-token", TOKEN + "\n", "rw-------");
        var settings = Main.settings(config("tokenFile = \"~/.config/kiln/github-token\"", 1), home).unwrap();

        assertThat(settings.credential().mode()).isEqualTo(Credential.Mode.TOKEN);
        assertThat(settings.credential().bearer().await().unwrap().unwrap()).isEqualTo(TOKEN);
        assertThat(settings.toString()).doesNotContain(TOKEN, "temper-read", "pw,")
                  .contains("gitHubToken=***", "temperToken=***");
        assertThat(logged()).anyMatch(line -> line.contains("with the token from " + home.resolve(".config/kiln/github-token")))
                  .noneMatch(line -> line.contains(TOKEN));
    }

    @Test
    void settings_failOnAMissingTokenFile_readUnauthenticatedOnlyWhenAnonymous() throws Exception {
        var missing = Main.settings(config("tokenFile = \"~/.config/kiln/github-token\"", 1), home);

        assertThat(message(missing)).contains("Cannot read " + home.resolve(".config/kiln/github-token"));
        var anonymous = Main.settings(config("anonymous = true", 1), home).unwrap();

        assertThat(anonymous.credential().mode()).isEqualTo(Credential.Mode.ANONYMOUS);
        assertThat(anonymous.credential().bearer().await().unwrap().isEmpty()).isTrue();
        assertThat(logged()).anyMatch(line -> line.contains("unauthenticated: [github] anonymous = true"));
    }

    private static final String APP_TABLE = """
        [github.app]
        clientId = "Iv23test"
        installationId = 77
        keyFile = "~/.config/kiln/app-key.pem"
        """;

    private static Cause refusal(Result<Main.Settings> result) {
        return result.fold(cause -> cause,
                           settings -> Assertions.<Cause> fail("started as " + settings.credential().mode()));
    }

    private String token() {
        return "tokenFile = \"~/.config/kiln/github-token\"\n";
    }

    /// K.1: `tokenFile` together with `[github.app]` refuses the start with exit 78, though both are valid and either
    /// would start on its own. Mutation: let one win.
    @Test
    void k1_tokenFileTogetherWithTheApp_refusesTheStart_exit78() throws Exception {
        secret("github-token", TOKEN, "rw-------");
        TestAppKey.write(home.resolve(".config/kiln/app-key.pem"), "rw-------");
        assertThat(refusal(Main.settings(config(token() + APP_TABLE, 1), home))).isSameAs(ConfigError.Credential.BOTH);
        assertThat(ConfigError.Credential.BOTH.message()).isEqualTo("config: tokenFile and [github.app] both set");
        assertThat(Main.exitCode(ConfigError.Credential.BOTH)).isEqualTo(78);
        assertThat(Main.settings(config(APP_TABLE, 1),
                                 home).unwrap().credential().mode()).as("the App alone starts")
                  .isEqualTo(Credential.Mode.APP);
        assertThat(Main.settings(config(token(),
                                        1),
                                 home).unwrap().credential().mode()).as("the token alone starts")
                  .isEqualTo(Credential.Mode.TOKEN);
    }

    /// A refused `[server] bind` or `hostNames` is a configuration refusal: exit 78, like a refused credential.
    @Test
    void listenRefusal_exits78() {
        assertThat(Main.exitCode(ConfigError.Listen.listen("bind", "the wildcard address is refused"))).isEqualTo(78);
    }

    /// K.4: no `tokenFile` and no `[github.app]` refuses the start with exit 78; with `anonymous = true` it starts, in
    /// mode ANONYMOUS. Mutation: default to unauthenticated.
    @Test
    void k4_noCredential_refusesTheStart_exit78_unlessAnonymous() throws Exception {
        var none = refusal(Main.settings(config("", 1), home));

        assertThat(none).isSameAs(ConfigError.Credential.NONE);
        assertThat(none.message()).isEqualTo("config: no GitHub credential");
        assertThat(Main.exitCode(none)).isEqualTo(78);
        assertThat(Main.exitCode(Causes.cause("anything else"))).isEqualTo(1);
        assertThat(Main.settings(config("anonymous = true", 1), home).unwrap().credential().mode()).isEqualTo(Credential.Mode.ANONYMOUS);
        secret("github-token", TOKEN, "rw-------");
        assertThat(refusal(Main.settings(config(token() + "anonymous = true",
                                                1),
                                         home))).as("a credential with anonymous = true is a contradiction")
                  .isSameAs(ConfigError.Credential.ANONYMOUS_WITH_CREDENTIAL);
    }

    /// A key file open beyond its owner refuses the start with exit 78 and names the path, never a key.
    @Test
    void anAppKeyOpenToOthers_refusesTheStart_exit78() throws Exception {
        TestAppKey.write(home.resolve(".config/kiln/app-key.pem"), "rw-r--r--");
        var refused = refusal(Main.settings(config(APP_TABLE, 1), home));

        assertThat(Main.exitCode(refused)).isEqualTo(78);
        assertThat(refused.message()).contains("app-key.pem", "group- or other-readable");
    }

    @Test
    void refusedTokens_neverReachTheFailureOrTheLog() throws Exception {
        var file = secret("github-token", TOKEN + " trailing", "rw-------");
        var malformed = Main.settings(config("tokenFile = \"~/.config/kiln/github-token\"", 1), home);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(file, TOKEN);
        var open = Main.settings(config("tokenFile = \"~/.config/kiln/github-token\"", 1), home);

        assertThat(malformed.isFailure()).isTrue();
        assertThat(open.isFailure()).isTrue();
        assertThat(List.of(message(malformed), message(open))).allSatisfy(text -> assertThat(text).doesNotContain(TOKEN)
                                                                                            .contains("tokenFile"));
        assertThat(logged()).noneMatch(line -> line.contains(TOKEN));
    }

    /// A start that fails after the token was read (kiln-pg refuses the connection) names the cause, never the token.
    @Test
    void aFailedStart_neverPrintsTheToken() throws Exception {
        secret("github-token", TOKEN, "rw-------");
        var started = Main.start(config("tokenFile = \"~/.config/kiln/github-token\"", 1), home).await(TimeSpan.timeSpan(60).seconds());

        assertThat(started.isFailure()).isTrue();
        assertThat(message(started)).isNotBlank().doesNotContain(TOKEN);
        assertThat(logged()).noneMatch(line -> line.contains(TOKEN));
    }

    @Test
    void configFile_isTheFirstArgument_elseTheDeployedPath() {
        assertThat(Main.configFile(new String[]{"/etc/kiln.toml", "x"}, home)).isEqualTo(Path.of("/etc/kiln.toml"));
        assertThat(Main.configFile(new String[]{}, home)).isEqualTo(home.resolve(".config/kiln/kiln.toml"));
    }
}
