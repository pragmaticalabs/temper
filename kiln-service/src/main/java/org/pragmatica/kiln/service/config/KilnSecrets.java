package org.pragmatica.kiln.service.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.utils.Causes.cause;


/// Reads the secrets `kiln.toml` points at, at start: the optional GitHub token, temper's read token and the kiln-pg
/// password. Each is the file's content with surrounding whitespace trimmed. An empty file, or a token that is not one
/// header-safe token, is refused; so is a configured `tokenFile` that cannot be read, a missing one included: only an
/// unset or blank key means unauthenticated (design §9.2, re-ruled for WP9). The token is read by path only: no failure
/// message, log line or `toString` holds its content. A secret file (either token, or the password) whose group or other permission bits are set is
/// refused before it is read (0600 or 0400 only, kiln-f1-design §6); a file system without POSIX attributes is refused
/// too, since nothing then shows who may read the secret. Based on temper's controller `Secrets`.
public sealed interface KilnSecrets {
    /// One header-safe token: printable ASCII, no whitespace.
    Pattern HEADER_TOKEN = Pattern.compile("^[\\x21-\\x7E]+$");

    /// The bits a secret file may hold: its owner's read and write.
    Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    static Result<String> read(Path file) {
        return FileOps.readString(file)
                      .mapError(cause -> ConfigError.Unreadable.FACTORY.apply(file.toString(),
                                                                              cause))
                      .map(String::trim)
                      .flatMap(secret -> Verify.ensure(secret,
                                                       Verify.Is::notBlank,
                                                       ConfigError.Invalid.FACTORY.apply("secret",
                                                                                         file.toString(),
                                                                                         cause("the file is empty"))));
    }

    /// The GitHub token, when `[github] tokenFile` names one; none means GitHub is read unauthenticated.
    static Result<Option<String>> gitHubToken(KilnConfig.GitHub github) {
        return TomlValues.optional(github.tokenFile(), file -> token(file, "github", "tokenFile"));
    }

    /// temper's read token (spec §3), sent as the bearer of every temper read.
    static Result<String> temperToken(KilnConfig.Temper temper) {
        return token(temper.readTokenFile(), "temper", "readTokenFile");
    }

    static Result<String> dbPassword(KilnConfig.Database db) {
        return ownerOnly(db.passwordFile(), "db", "passwordFile").flatMap(KilnSecrets::read);
    }

    private static Result<String> token(Path file, String section, String key) {
        return ownerOnly(file, section, key).flatMap(KilnSecrets::read)
                        .flatMap(token -> Verify.ensure(token,
                                                        Verify.Is::matches,
                                                        HEADER_TOKEN,
                                                        ConfigError.Invalid.FACTORY.apply(section,
                                                                                          key,
                                                                                          cause("the token must be printable ASCII without whitespace"))));
    }

    private static Result<Path> ownerOnly(Path file, String section, String key) {
        return Result.lift(thrown -> ConfigError.Unreadable.FACTORY.apply(file.toString(),
                                                                          Causes.fromThrowable(thrown)),
                           () -> Files.getPosixFilePermissions(file))
                     .flatMap(permissions -> Verify.ensure(permissions,
                                                           OWNER_ONLY::containsAll,
                                                           tooOpen(section, key)))
                     .map(_ -> file);
    }

    private static Fn1<ConfigError, Set<PosixFilePermission>> tooOpen(String section, String key) {
        return permissions -> ConfigError.Invalid.FACTORY.apply(section,
                                                                key,
                                                                cause("the secret file must be 0600 or 0400, found " + PosixFilePermissions.toString(permissions)));
    }

    record unused() implements KilnSecrets {}
}
