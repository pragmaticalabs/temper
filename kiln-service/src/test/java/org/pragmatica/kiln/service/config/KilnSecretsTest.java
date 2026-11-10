package org.pragmatica.kiln.service.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.service.config.KilnConfig.GitHub;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.vo.Url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The secrets `kiln.toml` names, read at start: no `tokenFile` means unauthenticated; a configured file that is
/// missing, empty, not one header-safe token, or open to its group or others is refused.
class KilnSecretsTest {
    @TempDir
    Path dir;

    /// A token file as kiln-f1-design §6 writes it: 0600.
    private Path secret(String name, String content) throws IOException {
        return secret(name, content, "rw-------");
    }

    private Path secret(String name, String content, String permissions) throws IOException {
        return Files.setPosixFilePermissions(Files.writeString(dir.resolve(name), content),
                                             PosixFilePermissions.fromString(permissions));
    }

    private static <T> Cause refused(Result<T> result) {
        return result.fold(cause -> cause, value -> Assertions.fail("expected a failure, got " + value));
    }

    private static GitHub gitHub(Path tokenFile) {
        return GitHub.gitHub("o/r", List.of(), some(tokenFile), Duration.ofMinutes(10));
    }

    private static KilnConfig.Temper temper(Path tokenFile) {
        return KilnConfig.Temper.temper(Url.url("http://127.0.0.1:7070").unwrap(), tokenFile, Duration.ofMinutes(10));
    }

    @Test
    void gitHubToken_isNone_withoutTokenFile() {
        assertThat(KilnSecrets.gitHubToken(GitHub.gitHub("o/r", List.of(), none(), Duration.ofMinutes(10)))
                              .unwrap()
                              .isEmpty()).isTrue();
    }

    @Test
    void gitHubToken_readsTheTrimmedToken() throws Exception {
        var file = secret("github-token", "  github_pat_x1\n");

        assertThat(KilnSecrets.gitHubToken(gitHub(file))
                              .unwrap()
                              .unwrap()).isEqualTo("github_pat_x1");
    }

    /// Design §9.2 (re-ruled for WP9): a configured token file that is missing is a typo, not a request for
    /// unauthenticated reads; a dangling link is missing too. Only an unset or blank key means unauthenticated.
    @Test
    void gitHubToken_refusesAMissingConfiguredFile() throws Exception {
        var dangling = Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("nowhere"));

        assertThat(refused(KilnSecrets.gitHubToken(gitHub(dir.resolve("absent"))))).isInstanceOf(ConfigError.Unreadable.class);
        assertThat(refused(KilnSecrets.gitHubToken(gitHub(dangling)))).isInstanceOf(ConfigError.Unreadable.class);
    }

    /// A directory at the path is there: refused, never read as no token.
    @Test
    void gitHubToken_refusesADirectoryAtThePath() throws Exception {
        var directory = Files.createDirectory(dir.resolve("token-dir"));

        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        assertThat(KilnSecrets.gitHubToken(gitHub(directory)).isFailure()).isTrue();
    }

    @Test
    void gitHubToken_refusesAnEmptyFileOrATokenWithWhitespace() throws Exception {
        var empty = secret("empty", " \n");
        var spaced = secret("spaced", "a b");

        assertThat(refused(KilnSecrets.gitHubToken(gitHub(empty))).message()).contains("the file is empty");
        assertThat(refused(KilnSecrets.gitHubToken(gitHub(spaced))).message()).isEqualTo("Invalid [github] tokenFile: the token must be printable ASCII without whitespace");
    }

    @Test
    void temperToken_readsTheToken_refusesAMissingOrBadFile() throws Exception {
        var file = secret("read-token", "r-123\n");
        var bad = secret("bad", "ré");

        assertThat(KilnSecrets.temperToken(temper(file)).unwrap()).isEqualTo("r-123");
        assertThat(refused(KilnSecrets.temperToken(temper(dir.resolve("absent"))))).isInstanceOf(ConfigError.Unreadable.class);
        assertThat(refused(KilnSecrets.temperToken(temper(bad))).message()).startsWith("Invalid [temper] readTokenFile");
    }

    /// Verification F-15: a token file is 0600 or 0400; any group or other bit, or an owner execute bit, is refused
    /// before the token is read.
    @ParameterizedTest
    @ValueSource(strings = {"rw-r--r--", "rw-r-----", "rw----r--", "rw--w----", "rw-----w-", "r--r--r--", "rwx------",
                            "rw------x", "rw---x---"})
    void tokens_refuseAFileOpenBeyondItsOwner(String permissions) throws Exception {
        var file = secret("open-" + permissions.replace('-', '_'), "t-1", permissions);

        assertThat(refused(KilnSecrets.gitHubToken(gitHub(file))).message()).isEqualTo("Invalid [github] tokenFile: the secret file must be 0600 or 0400, found "
                                                                                      + permissions);
        assertThat(refused(KilnSecrets.temperToken(temper(file))).message()).startsWith("Invalid [temper] readTokenFile: the secret file must be 0600 or 0400");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-------", "r--------"})
    void tokens_readAnOwnerOnlyFile(String permissions) throws Exception {
        var file = secret("owner-" + permissions.replace('-', '_'), "t-1\n", permissions);

        assertThat(KilnSecrets.gitHubToken(gitHub(file))
                              .unwrap()
                              .unwrap()).isEqualTo("t-1");
        assertThat(KilnSecrets.temperToken(temper(file)).unwrap()).isEqualTo("t-1");
    }

    /// A password is read as written (trimmed), with no token alphabet.
    @Test
    void dbPassword_readsTheTrimmedFile_refusesAnEmptyOne() throws Exception {
        var file = secret("pg-password", "pass word\n");
        var empty = secret("empty", "");

        assertThat(KilnSecrets.dbPassword(KilnConfig.Database.database("h", 1, "n", "u", file, Path.of("/hwm"))).unwrap()).isEqualTo("pass word");
        assertThat(refused(KilnSecrets.dbPassword(KilnConfig.Database.database("h", 1, "n", "u", empty, Path.of("/hwm"))))).isInstanceOf(ConfigError.Invalid.class);
    }

    /// A missing password file fails at its permission read; a directory passes that read and fails in the read itself.
    @Test
    void dbPassword_refusesAMissingFileOrADirectory() throws Exception {
        var directory = Files.createDirectory(dir.resolve("pg-dir"),
                                              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        var missing = refused(KilnSecrets.dbPassword(KilnConfig.Database.database("h", 1, "n", "u", dir.resolve("absent"), Path.of("/hwm"))));
        var unreadable = refused(KilnSecrets.dbPassword(KilnConfig.Database.database("h", 1, "n", "u", directory, Path.of("/hwm"))));

        assertThat(missing).isInstanceOf(ConfigError.Unreadable.class);
        assertThat(missing.message()).isEqualTo("Cannot read " + dir.resolve("absent"));
        assertThat(unreadable).isInstanceOf(ConfigError.Unreadable.class);
        assertThat(unreadable.message()).isEqualTo("Cannot read " + directory);
    }

    /// The password is a secret like the tokens: 0600 or 0400 only (lead ruling 2026-10-30).
    @ParameterizedTest
    @ValueSource(strings = {"rw-r--r--", "rw-r-----", "rw----r--", "rwx------", "rw------x"})
    void dbPassword_refusesAFileOpenBeyondItsOwner(String permissions) throws Exception {
        var file = secret("pg-" + permissions.replace('-', '_'), "pw", permissions);

        assertThat(refused(KilnSecrets.dbPassword(KilnConfig.Database.database("h", 1, "n", "u", file, Path.of("/hwm")))).message()).isEqualTo("Invalid [db] passwordFile: the secret file must be 0600 or 0400, found "
                                                                                                                              + permissions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-------", "r--------"})
    void dbPassword_readsAnOwnerOnlyFile(String permissions) throws Exception {
        var file = secret("pg-" + permissions.replace('-', '_'), "pw\n", permissions);

        assertThat(KilnSecrets.dbPassword(KilnConfig.Database.database("h", 1, "n", "u", file, Path.of("/hwm"))).unwrap()).isEqualTo("pw");
    }
}
