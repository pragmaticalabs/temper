package org.pragmatica.temper.cli.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.TestSupport;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;

class CliConfigTest {
    @TempDir
    Path home;

    private final Map<String, String> environment = new HashMap<>();
    private final TestSupport.Out out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
    private Path dir;

    @BeforeEach
    void token() throws IOException {
        dir = Files.createDirectories(home.resolve(".config/temper"));
        var token = Files.writeString(dir.resolve("token"), "  the-token \n");

        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
    }

    private CliContext context() {
        var time = TestSupport.time();

        return CliContext.cliContext(name -> Option.option(environment.get(name)), home, time.clock(), time.pacer(), out.console());
    }

    private CliConfig loaded() {
        return CliConfig.cliConfig(context())
                        .fold(cause -> fail(cause.message()), config -> config);
    }

    private ExitCode refusal() {
        return CliConfig.cliConfig(context())
                        .fold(CliError::exitCodeOf, config -> fail("loaded " + config));
    }

    @Test
    void defaults_toBigboy_andReadsTrimmedToken() {
        var config = loaded();

        assertThat(config.endpoint()
                         .api()).hasToString("http://bigboy:7070/api/v1");
        assertThat(config.token()).isEqualTo("the-token");
        assertThat(out.errors()).isEmpty();
    }

    @Test
    void configFile_overridesDefault_andEnvironmentOverridesBoth() throws IOException {
        Files.writeString(dir.resolve("config"), "# temper\nurl = \"https://ci.lan:9443\"  # LAN\n");

        assertThat(loaded().endpoint()
                           .api()).hasToString("https://ci.lan:9443/api/v1");

        environment.put("TEMPER_URL", "http://localhost:17070/api/v1/");

        assertThat(loaded().endpoint()
                           .api()).hasToString("http://localhost:17070/api/v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bigboy:7070", "ftp://bigboy", "http://bigboy:7070/other", "http://"})
    void malformedUrl_isUsageError(String url) {
        environment.put("TEMPER_URL", url);

        assertThat(refusal()).isEqualTo(ExitCode.USAGE);
    }

    @Test
    void tokenFileVariable_overridesTheDefaultToken() throws IOException {
        var read = Files.writeString(home.resolve("read-token"), "the-read-token\n");
        environment.put("TEMPER_TOKEN_FILE", read.toString());

        assertThat(loaded().token()).isEqualTo("the-read-token");
    }

    @Test
    void tokenFileVariable_leadingTildeIsTheHome() throws IOException {
        Files.writeString(dir.resolve("read-token"), "the-read-token\n");
        environment.put("TEMPER_TOKEN_FILE", "~/.config/temper/read-token");

        assertThat(loaded().token()).isEqualTo("the-read-token");
    }

    /// The pin that matters: a named file that is missing is an error, never a quiet fall back to the full token.
    @Test
    void tokenFileVariable_missingFile_isUsageError_notTheDefaultToken() {
        environment.put("TEMPER_TOKEN_FILE", home.resolve("absent").toString());

        assertThat(refusal()).isEqualTo(ExitCode.USAGE);
    }

    @Test
    void missingToken_isUsageError() throws IOException {
        Files.delete(dir.resolve("token"));

        assertThat(refusal()).isEqualTo(ExitCode.USAGE);
    }

    @Test
    void emptyToken_isUsageError() throws IOException {
        Files.writeString(dir.resolve("token"), "\n");

        assertThat(refusal()).isEqualTo(ExitCode.USAGE);
    }

    @Test
    void sharedToken_warnsOnStderr_butIsUsed() throws IOException {
        Files.setPosixFilePermissions(dir.resolve("token"), PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(loaded().token()).isEqualTo("the-token");
        assertThat(out.errors()).contains("warning:", "chmod 600");
    }

    @Test
    void owner_flagWins_thenTemperOwner_thenBbOwner() {
        environment.put("BB_OWNER", "bb");

        assertThat(loaded().ownerOr(Option.none())).isEqualTo(some("bb"));

        environment.put("TEMPER_OWNER", "temper");

        assertThat(loaded().ownerOr(Option.none())).isEqualTo(some("temper"));
        assertThat(loaded().ownerOr(some("flag"))).isEqualTo(some("flag"));
    }
}
