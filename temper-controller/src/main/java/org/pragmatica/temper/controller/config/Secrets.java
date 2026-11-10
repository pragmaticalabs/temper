package org.pragmatica.temper.controller.config;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.store.api.DbConfig;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// Reads the secrets `controller.toml` points at: the bearer token (the same file the CLI reads, spec §7), the optional
/// read token (D3), the optional worker token (ruling R-M3-15), the optional verdict and Mac tokens and the database password. Each is the file's content with surrounding whitespace trimmed; an empty
/// file is refused.
public sealed interface Secrets {
    /// One header-safe token: printable ASCII, no whitespace (the CLI's `CliConfig.TOKEN`).
    Pattern HEADER_TOKEN = Pattern.compile("^[\\x21-\\x7E]+$");

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

    /// The read token `[server] readTokenFile` names, if it names one. A read token equal to the full token is
    /// refused: the router would classify it as the full token, so the "read" token would silently write. So is one
    /// that is not a single header-safe token (the CLI's rule for its token file: printable ASCII, no whitespace),
    /// since no request could ever present it. The full token's loading is left as it was.
    static Result<Option<String>> readToken(ControllerConfig.Server server, String fullToken) {
        return Values.optional(server.readTokenFile(), file -> readDistinct(file, fullToken));
    }

    private static Result<String> readDistinct(Path file, String fullToken) {
        return read(file).flatMap(token -> headerSafe(token, "readTokenFile"))
                   .flatMap(token -> distinct(token, fullToken, "readTokenFile", "tokenFile"));
    }

    /// The worker token `[server] workerTokenFile` names, if it names one (ruling R-M3-15), under the read token's rules:
    /// one header-safe token, distinct from the full token and from the read token, since the router classifies a token
    /// by the first one it equals and a worker token equal to either would silently get that one's scope.
    static Result<Option<String>> workerToken(ControllerConfig.Server server,
                                              String fullToken,
                                              Option<String> readToken) {
        return Values.optional(server.workerTokenFile(), file -> workerDistinct(file, fullToken, readToken));
    }

    private static Result<String> workerDistinct(Path file, String fullToken, Option<String> readToken) {
        return read(file).flatMap(token -> headerSafe(token, "workerTokenFile"))
                   .flatMap(token -> distinct(token, fullToken, "workerTokenFile", "tokenFile"))
                   .flatMap(token -> distinct(token,
                                              readToken.or(""),
                                              "workerTokenFile",
                                              "readTokenFile"));
    }

    /// The verdict token `[server] verdictTokenFile` names, if it names one, under the read
    /// token's rules: one header-safe token, distinct from the full, read and worker tokens, since a token equal to
    /// another would be classified as that one (the router takes the first it equals) and lose or gain a scope.
    static Result<Option<String>> verdictToken(ControllerConfig.Server server,
                                               String fullToken,
                                               Option<String> readToken,
                                               Option<String> workerToken) {
        return Values.optional(server.verdictTokenFile(),
                               file -> distinctFromAll(file,
                                                       "verdictTokenFile",
                                                       List.of(Other.other(some(fullToken), "tokenFile"),
                                                               Other.other(readToken, "readTokenFile"),
                                                               Other.other(workerToken, "workerTokenFile"))));
    }

    /// The Mac token `[server] macTokenFile` names, if it names one, under the same rules,
    /// distinct from the verdict token too.
    static Result<Option<String>> macToken(ControllerConfig.Server server,
                                           String fullToken,
                                           Option<String> readToken,
                                           Option<String> workerToken,
                                           Option<String> verdictToken) {
        return Values.optional(server.macTokenFile(),
                               file -> distinctFromAll(file,
                                                       "macTokenFile",
                                                       List.of(Other.other(some(fullToken), "tokenFile"),
                                                               Other.other(readToken, "readTokenFile"),
                                                               Other.other(workerToken, "workerTokenFile"),
                                                               Other.other(verdictToken, "verdictTokenFile"))));
    }

    /// A configured token another one must differ from, and the key that names it.
    record Other(Option<String> token, String key) {
        static Other other(Option<String> token, String key) {
            return new Other(token, key);
        }
    }

    private static Result<String> distinctFromAll(Path file, String key, List<Other> others) {
        return read(file).flatMap(token -> headerSafe(token, key))
                   .flatMap(token -> others.stream()
                                           .reduce(Result.success(token),
                                                   (checked, other) -> checked.flatMap(value -> distinctFrom(value,
                                                                                                             other,
                                                                                                             key)),
                                                   (left, _) -> left));
    }

    /// An absent token is not compared (the empty string is never a token: [#read] refuses an empty file).
    private static Result<String> distinctFrom(String token, Other other, String key) {
        return distinct(token,
                        other.token().or(""),
                        key,
                        other.key());
    }

    private static Result<String> headerSafe(String token, String key) {
        return Verify.ensure(token,
                             Verify.Is::matches,
                             HEADER_TOKEN,
                             ConfigError.Invalid.FACTORY.apply("server",
                                                               key,
                                                               cause("the token must be printable ASCII without whitespace")));
    }

    private static Result<String> distinct(String token, String other, String key, String otherKey) {
        return Verify.ensure(token,
                             Verify.Is::notEqualTo,
                             other,
                             ConfigError.Invalid.FACTORY.apply("server",
                                                               key,
                                                               cause("it holds the same token as " + otherKey)));
    }

    /// The store's connection settings: `[db]` plus the password file's content.
    static Result<DbConfig> dbConfig(ControllerConfig.Database db) {
        return read(db.passwordFile()).flatMap(password -> DbConfig.dbConfig(db.host(),
                                                                             db.port(),
                                                                             db.name(),
                                                                             db.user(),
                                                                             password,
                                                                             DbConfig.DEFAULT_MAX_CONNECTIONS));
    }

    record unused() implements Secrets {}
}
