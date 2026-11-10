package org.pragmatica.temper.controller.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.InstanceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Positive control T.distinct: the optional verdict and Mac tokens are loaded as the read token
/// is, and a start is refused when either equals the full, read or worker token or the other, since the router would
/// classify it as that one and the scope would silently change.
class T2SecretsTest {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final String WORKER = "worker-token";
    private static final String VERDICT = "verdict-token";
    private static final String MAC = "mac-token";

    @TempDir
    Path dir;

    private ControllerConfig.Server server(Option<Path> verdictFile, Option<Path> macFile) {
        return ControllerConfig.Server.server("0.0.0.0", 7070, dir.resolve("token"), none(), InstanceId.DEFAULT, none(), verdictFile, macFile);
    }

    private Path written(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }

    private Result<Option<String>> verdict(String content) throws Exception {
        return Secrets.verdictToken(server(some(written("verdict", content)), none()), FULL, some(READ), some(WORKER));
    }

    private Result<Option<String>> mac(String content) throws Exception {
        return Secrets.macToken(server(none(), some(written("mac", content))), FULL, some(READ), some(WORKER), some(VERDICT));
    }

    private static void refused(Result<Option<String>> loaded, String key, String otherKey) {
        loaded.onSuccess(value -> Assertions.fail("expected a failure, got " + value))
              .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(ConfigError.Invalid.class, invalid -> {
                  assertThat(invalid.key()).isEqualTo(key);
                  assertThat(invalid.origin().message()).isEqualTo("it holds the same token as " + otherKey);
              }));
    }

    @Test
    void absentFiles_areNoTokens() {
        assertThat(Secrets.verdictToken(server(none(), none()), FULL, none(), none()).unwrap()).isEqualTo(none());
        assertThat(Secrets.macToken(server(none(), none()), FULL, none(), none(), none()).unwrap()).isEqualTo(none());
    }

    @Test
    void distinctTokens_areTheTrimmedFileContents() throws Exception {
        assertThat(verdict(VERDICT + "\n").unwrap()).isEqualTo(some(VERDICT));
        assertThat(mac("  " + MAC + "\n").unwrap()).isEqualTo(some(MAC));
    }

    /// T.distinct: the verdict token equal to the full, read or worker token refuses the start.
    @ParameterizedTest
    @CsvSource({FULL + ",tokenFile", READ + ",readTokenFile", WORKER + ",workerTokenFile"})
    void verdictToken_equalToAnother_isRefused(String token, String otherKey) throws Exception {
        refused(verdict(token + "\n"), "verdictTokenFile", otherKey);
    }

    /// T.distinct: the Mac token equal to the full, read, worker or verdict token refuses the start (the verdict
    /// token equal to the Mac token included).
    @ParameterizedTest
    @CsvSource({FULL + ",tokenFile", READ + ",readTokenFile", WORKER + ",workerTokenFile", VERDICT + ",verdictTokenFile"})
    void macToken_equalToAnother_isRefused(String token, String otherKey) throws Exception {
        refused(mac(token + "\n"), "macTokenFile", otherKey);
    }

    @Test
    void tokens_notHeaderSafe_orEmpty_areRefused() throws Exception {
        verdict("two words\n").onSuccess(value -> Assertions.fail("expected a failure, got " + value));
        mac("\n").onSuccess(value -> Assertions.fail("expected a failure, got " + value));
        assertThat(verdict("two words\n").isFailure()).isTrue();
        assertThat(mac("\n").isFailure()).isTrue();
    }
}
