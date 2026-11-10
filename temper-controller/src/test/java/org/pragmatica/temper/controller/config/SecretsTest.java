package org.pragmatica.temper.controller.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.InstanceId;

import static org.assertj.core.api.Assertions.assertThat;

/// D3: the optional read token. Absent, there is none; present, it is read like the full token; equal to the full
/// token, the start fails, since the router would take it for the full token and the "read" token would write.
class SecretsTest {
    private static final String FULL_TOKEN = "full-token";

    @TempDir
    Path dir;

    private ControllerConfig.Server server(Option<Path> readTokenFile) {
        return ControllerConfig.Server.server("0.0.0.0", 7070, dir.resolve("token"), readTokenFile, InstanceId.DEFAULT);
    }

    private Path written(String content) throws Exception {
        return Files.writeString(dir.resolve("read-token"), content);
    }

    @Test
    void readToken_isNone_whenNoFileIsConfigured() {
        assertThat(Secrets.readToken(server(Option.none()), FULL_TOKEN)
                          .unwrap()
                          .isEmpty()).isTrue();
    }

    @Test
    void readToken_isTheTrimmedFileContent_whenConfigured() throws Exception {
        var file = written("read-token\n");

        assertThat(Secrets.readToken(server(Option.some(file)), FULL_TOKEN)
                          .unwrap()
                          .or("absent")).isEqualTo("read-token");
    }

    @Test
    void readToken_isRefused_whenEqualToTheFullToken() throws Exception {
        var file = written(FULL_TOKEN + "\n");

        Secrets.readToken(server(Option.some(file)), FULL_TOKEN)
               .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
               .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(ConfigError.Invalid.class,
                                                                            invalid -> assertThat(invalid.key()).isEqualTo("readTokenFile")));
    }

    /// A token no request could present (the CLI's rule: printable ASCII, no whitespace) is refused at start.
    @Test
    void readToken_isRefused_whenNotAHeaderSafeToken() throws Exception {
        for (var content : List.of("two words", "tab\tinside", "t\u00f6ken", "be\u0007ll")) {
            var file = written(content + "\n");

            Secrets.readToken(server(Option.some(file)), FULL_TOKEN)
                   .onSuccess(value -> Assertions.fail("expected a failure for " + content + ", got " + value))
                   .onFailure(cause -> assertThat(cause.message()).as(content)
                                                                  .contains("Invalid [server] readTokenFile"));
        }
    }

    @Test
    void readToken_isRefused_whenTheConfiguredFileIsMissing() {
        Secrets.readToken(server(Option.some(dir.resolve("no-such-file"))), FULL_TOKEN)
               .onSuccess(value -> Assertions.fail("expected a failure, got " + value))
               .onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.Unreadable.class));
    }
}
