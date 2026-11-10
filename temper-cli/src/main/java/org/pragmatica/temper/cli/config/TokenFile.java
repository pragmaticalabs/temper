package org.pragmatica.temper.cli.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The tokens of the T2 verbs, read with [CliConfig]'s rules (one printable-ASCII token,
/// a warning when group or others can read it) except that **no failure or warning ever names the path** (R3-1): a
/// token-file path the CLI resolved or was given appears in no output.
///
/// - [#ordinary]: `merge-gate`'s token, `TEMPER_TOKEN_FILE` or `~/.config/temper/token`, as every other verb's.
/// - [#verdict]: a VERDICT verb's, only the `--token-file` argument: no default path is read and `TEMPER_TOKEN_FILE` is
///   ignored (R2-2).
///
/// A read that fails after the file was found regular and readable (a race) is reported without its origin: the JDK's
/// message names the path, and every link of a cause chain is printed (Console#oneLine). The R3-1 rule is the reason the
/// origin is not carried.
public record TokenFile(String path) {
    public static final String ORDINARY = "the CLI token file";
    public static final String VERDICT = "--token-file";

    private static final Set<PosixFilePermission> SHARED = Set.of(PosixFilePermission.GROUP_READ,
                                                                  PosixFilePermission.GROUP_WRITE,
                                                                  PosixFilePermission.OTHERS_READ,
                                                                  PosixFilePermission.OTHERS_WRITE);

    public static TokenFile tokenFile(String path) {
        return new TokenFile(path);
    }

    /// Never the path.
    @Override
    public String toString() {
        return VERDICT + " <verdict-token>";
    }

    public static Result<CliConfig> ordinary(CliContext context) {
        return CliConfig.withToken(context,
                                   read(context, CliConfig.fileSetting(context, "TEMPER_TOKEN_FILE", "token"), ORDINARY));
    }

    public static Result<CliConfig> verdict(CliContext context, TokenFile given) {
        return CliConfig.withToken(context,
                                   read(context,
                                        given.resolved(context.home()),
                                        VERDICT));
    }

    /// A leading `~/` is the home.
    Path resolved(Path home) {
        return path.startsWith("~/")
               ? home.resolve(path.substring(2))
               : Path.of(path);
    }

    private static Result<String> read(CliContext context, Path file, String name) {
        return some(file).filter(Files::isRegularFile)
                   .filter(Files::isReadable)
                   .toResult(CliError.Usage.FACTORY.apply(name + " is missing or unreadable"))
                   .onSuccess(present -> warnIfShared(context, present, name))
                   .flatMap(present -> Result.lift(_ -> CliError.Usage.FACTORY.apply(name + " could not be read"),
                                                   () -> Files.readString(present)))
                   .map(String::strip)
                   .flatMap(text -> Verify.ensure(text,
                                                  Verify.Is::matches,
                                                  CliConfig.TOKEN,
                                                  CliError.Usage.FACTORY.apply(name
                                                                              + " must hold one token of printable ASCII")));
    }

    /// FER as [CliConfig#warnIfShared]: a filesystem without POSIX permissions cannot answer, and the warning is
    /// advisory.
    private static Unit warnIfShared(CliContext context, Path file, String name) {
        Result.lift(_ -> CliError.Usage.FACTORY.apply(name + " permissions unreadable"),
                    () -> Files.getPosixFilePermissions(file))
              .option()
              .filter(permissions -> permissions.stream()
                                                .anyMatch(SHARED::contains))
              .onPresent(_ -> context.console()
                                     .problem("warning: " + name + " is accessible by group/others; run chmod 600 on it"));

        return unit();
    }
}
