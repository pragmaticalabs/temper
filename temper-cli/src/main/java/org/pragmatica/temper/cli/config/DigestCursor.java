package org.pragmatica.temper.cli.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import org.pragmatica.lang.Functions.ThrowingFn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.protocol.api.DigestView;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// `TEMPER_DIGEST_CURSOR`, otherwise `~/.config/temper/digest.cursor`: the `temper digest` cursor between runs (the
/// variable gives each consumer sharing a user its own cursor; a shared one hands each event to whichever runs first),
/// the controller's opaque cursor (`<seq>.<token>`, `DigestView.CURSOR`) and a line break, mode 0600. A missing file
/// means "start at the head". A file that does not hold a cursor is exit 64, never read as 0: that would replay the
/// whole event log.
///
/// [#write] replaces the file atomically (a sibling temporary file, then a rename), so a crash leaves the old cursor or
/// the new one, never a torn one.
public sealed interface DigestCursor {
    static Path file(CliContext context) {
        return CliConfig.fileSetting(context, "TEMPER_DIGEST_CURSOR", "digest.cursor");
    }

    static Result<Option<String>> read(Path file) {
        return some(file).filter(Files::isRegularFile)
                   .map(DigestCursor::cursorIn)
                   .or(() -> success(Option.none()));
    }

    static Result<Unit> write(Path file, String cursor) {
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");

        return written(file,
                       () -> Files.createDirectories(file.getParent())).flatMap(_ -> written(file,
                                                                                             () -> Files.writeString(temporary,
                                                                                                                     cursor
                                                                                                                    + "\n")))
                      .flatMap(_ -> written(file,
                                            () -> Files.setPosixFilePermissions(temporary,
                                                                                PosixFilePermissions.fromString("rw-------"))))
                      .flatMap(_ -> written(file,
                                            () -> Files.move(temporary,
                                                             file,
                                                             StandardCopyOption.REPLACE_EXISTING,
                                                             StandardCopyOption.ATOMIC_MOVE)))
                      .mapToUnit();
    }

    private static Result<Option<String>> cursorIn(Path file) {
        return CliConfig.text(file)
                        .map(String::strip)
                        .flatMap(text -> cursorValue(file, text))
                        .map(Option::some);
    }

    private static Result<String> cursorValue(Path file, String text) {
        return Verify.ensure(text,
                             Verify.Is::matches,
                             DigestView.CURSOR,
                             CliError.Usage.FACTORY.apply("Digest cursor file does not hold a cursor: " + file));
    }

    private static <T> Result<T> written(Path file, ThrowingFn0<T> step) {
        return Result.lift(thrown -> CliError.Unwritable.FACTORY.apply(file.toString(), Causes.fromThrowable(thrown)),
                           step);
    }

    record unused() implements DigestCursor {}
}
