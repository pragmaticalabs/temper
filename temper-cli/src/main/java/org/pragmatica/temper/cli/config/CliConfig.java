package org.pragmatica.temper.cli.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// The CLI's resolved configuration (m0-design §8, as amended by the WP5a brief):
/// - controller URL: `TEMPER_URL`, otherwise `url = "…"` in `~/.config/temper/config`, otherwise
///   [ControllerEndpoint#DEFAULT_URL];
/// - bearer token: the contents of `TEMPER_TOKEN_FILE`, otherwise `~/.config/temper/token`, trimmed (the variable lets
///   an agent sharing the owner's user present the read token, D3); a missing file, or one that does not
///   hold exactly one printable-ASCII token (it becomes an HTTP header), is exit 64; a file readable by group
///   or others draws a warning on stderr [P];
/// - owner (submit, and `known add`'s `createdBy`): `--owner`, otherwise `TEMPER_OWNER`, otherwise `BB_OWNER`.
public record CliConfig(ControllerEndpoint endpoint, String token, Option<String> owner) {
    private static final Pattern URL_LINE = Pattern.compile("^\\s*url\\s*=\\s*\"?([^\"#\\s]+)\"?\\s*(?:#.*)?$");
    /// One header-safe token: printable ASCII, no whitespace.
    static final Pattern TOKEN = Pattern.compile("^[\\x21-\\x7E]+$");

    private static final Set<PosixFilePermission> SHARED = Set.of(PosixFilePermission.GROUP_READ,
                                                                  PosixFilePermission.GROUP_WRITE,
                                                                  PosixFilePermission.OTHERS_READ,
                                                                  PosixFilePermission.OTHERS_WRITE);

    public static Result<CliConfig> cliConfig(CliContext context) {
        return Result.all(endpoint(context), token(context)).map((endpoint, token) -> new CliConfig(endpoint,
                                                                                                    token,
                                                                                                    envOwner(context)));
    }

    /// The configuration with `token` read by the caller's own rules (the T2 verbs, [TokenFile]): the same endpoint and
    /// owner as [#cliConfig].
    static Result<CliConfig> withToken(CliContext context, Result<String> token) {
        return Result.all(endpoint(context), token).map((endpoint, value) -> new CliConfig(endpoint,
                                                                                           value,
                                                                                           envOwner(context)));
    }

    /// The owner for a submit: the `--owner` flag wins over the environment.
    public Option<String> ownerOr(Option<String> flag) {
        return flag.orElse(owner);
    }

    static Path configDir(Path home) {
        return home.resolve(".config")
                   .resolve("temper");
    }

    /// A file the environment may relocate: `variable`'s path when set (a leading `~/` is the home), otherwise `name`
    /// under `~/.config/temper`.
    static Path fileSetting(CliContext context, String variable, String name) {
        return context.env(variable)
                      .map(raw -> expanded(raw,
                                           context.home()))
                      .or(() -> configDir(context.home()).resolve(name));
    }

    private static Path expanded(String raw, Path home) {
        return raw.startsWith("~/")
               ? home.resolve(raw.substring(2))
               : Path.of(raw);
    }

    private static Option<String> envOwner(CliContext context) {
        return context.env("TEMPER_OWNER")
                      .orElse(() -> context.env("BB_OWNER"));
    }

    private static Result<ControllerEndpoint> endpoint(CliContext context) {
        return context.env("TEMPER_URL")
                      .map(Result::success)
                      .or(() -> fileUrl(configDir(context.home()).resolve("config")))
                      .flatMap(ControllerEndpoint::controllerEndpoint);
    }

    private static Result<String> fileUrl(Path file) {
        return existing(file).map(CliConfig::contents)
                       .or(() -> success(none()))
                       .map(CliConfig::urlOrDefault);
    }

    /// The file's `url` setting; a missing file, or one without `url`, means the default.
    private static String urlOrDefault(Option<String> text) {
        return text.flatMap(CliConfig::urlSetting)
                   .or(ControllerEndpoint.DEFAULT_URL);
    }

    private static Result<String> token(CliContext context) {
        var file = fileSetting(context, "TEMPER_TOKEN_FILE", "token");

        return existing(file).toResult(CliError.Usage.FACTORY.apply("Token file missing: " + file
                                                                   + " (put the controller's bearer token there, mode 0600)"))
                       .onSuccess(present -> warnIfShared(context, present))
                       .flatMap(CliConfig::text)
                       .map(String::strip)
                       .flatMap(text -> Verify.ensure(text,
                                                      Verify.Is::matches,
                                                      TOKEN,
                                                      CliError.Usage.FACTORY.apply("Token file must hold one token of printable ASCII: " + file)));
    }

    private static Option<Path> existing(Path file) {
        return some(file).filter(Files::isRegularFile);
    }

    private static Result<Option<String>> contents(Path file) {
        return text(file).map(Option::some);
    }

    static Result<String> text(Path file) {
        return Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(file.toString(), Causes.fromThrowable(thrown)),
                           () -> Files.readString(file));
    }

    private static Option<String> urlSetting(String text) {
        return Option.from(text.lines()
                               .map(URL_LINE::matcher)
                               .filter(Matcher::matches)
                               .map(matcher -> matcher.group(1))
                               .findFirst());
    }

    /// Warns when the token is readable or writable by group or others. A filesystem without POSIX
    /// permissions cannot answer; the warning is advisory, so that case is skipped (FER: degrade to no
    /// warning, the token is still used).
    static Unit warnIfShared(CliContext context, Path file) {
        Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(file.toString(),
                                                                Causes.fromThrowable(thrown)),
                    () -> Files.getPosixFilePermissions(file))
              .option()
              .filter(permissions -> permissions.stream()
                                                .anyMatch(SHARED::contains))
              .onPresent(_ -> context.console()
                                     .problem("warning: " + file
                                             + " is accessible by group/others; run chmod 600 " + file));

        return unit();
    }
}
