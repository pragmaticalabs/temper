package org.pragmatica.temper.worker.unit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// A unit's `.temper/` state files. Every write goes to `<name>.tmp`, is forced to the device, renamed over
/// the target atomically, and the directory is forced: a reader (a later worker re-attaching) sees either the
/// old content or the new, and a crash cannot leave a torn `rc` (the re-attach discriminator).
public record UnitFiles(WorkerPaths paths) {
    public static UnitFiles unitFiles(WorkerPaths paths) {
        return new UnitFiles(paths);
    }

    public Path path(UnitRef ref, UnitFile file) {
        return paths.stateFile(ref, file);
    }

    public boolean exists(UnitRef ref, UnitFile file) {
        return FileOps.exists(path(ref, file));
    }

    /// The unit's container: the recorded id, or its name `temper-<job>-<unit>` (docker accepts either) when no
    /// id was recorded yet.
    public Result<String> container(UnitRef ref) {
        return text(ref, UnitFile.CONTAINER).map(id -> recordedOrNamed(ref, id));
    }

    private static String recordedOrNamed(UnitRef ref, Option<String> id) {
        return id.map(String::trim)
                 .filter(recorded -> !recorded.isEmpty())
                 .or(ref.containerName());
    }

    /// The attempt of a unit directory written before M4: it has a marker and no `attempt` (m4-design §7).
    public static final int PRE_M4_ATTEMPT = 1;

    /// The unit's attempt as its frames carry it: `attempt`, or [#PRE_M4_ATTEMPT] when the file does not exist;
    /// corrupt content fails.
    public Result<Integer> attempt(UnitRef ref) {
        return integer(ref, UnitFile.ATTEMPT).map(attempt -> attempt.or(PRE_M4_ATTEMPT));
    }

    /// The attempt this host holds of the unit (m4-design §4.2): `attempt`; [#PRE_M4_ATTEMPT] for a unit with a marker
    /// and no `attempt`; none when the directory holds no attempt at all (never started here, or already discarded).
    public Result<Option<Integer>> heldAttempt(UnitRef ref) {
        return integer(ref, UnitFile.ATTEMPT).map(attempt -> attempt.orElse(() -> markedBeforeM4(ref)));
    }

    private Option<Integer> markedBeforeM4(UnitRef ref) {
        return exists(ref, UnitFile.MARKER)
               ? some(PRE_M4_ATTEMPT)
               : none();
    }

    /// Writes `content` atomically and durably.
    public Result<Unit> store(UnitRef ref, UnitFile file, String content) {
        return storeAtomic(path(ref, file), content);
    }

    /// Writes only if the file does not exist yet; `true` when this call wrote it. Serialised per instance, so
    /// two in-process writers (the timeout timer and a cancel writing `killed`) cannot both win.
    public synchronized Result<Boolean> storeOnce(UnitRef ref, UnitFile file, String content) {
        return exists(ref, file)
               ? success(false)
               : store(ref, file, content).map(_ -> true);
    }

    /// The file's text, or `none()` when it does not exist.
    public Result<Option<String>> text(UnitRef ref, UnitFile file) {
        return optionalText(path(ref, file));
    }

    /// The file's trimmed content as a long (epoch nanos), or `none()` when absent; corrupt content fails.
    public Result<Option<Long>> nanos(UnitRef ref, UnitFile file) {
        return parsed(ref, file, Number::parseLong);
    }

    /// The file's trimmed content as a long count (`mem-available-min-kb`), or `none()` when absent; corrupt
    /// content fails.
    public Result<Option<Long>> count(UnitRef ref, UnitFile file) {
        return parsed(ref, file, Number::parseLong);
    }

    /// The file's trimmed content as an int (`rc`), or `none()` when absent; corrupt content fails.
    public Result<Option<Integer>> integer(UnitRef ref, UnitFile file) {
        return parsed(ref, file, Number::parseInt);
    }

    /// Writes `content` to `target` through a forced sibling and an atomic rename (parents created first).
    public static Result<Unit> storeAtomic(Path target, String content) {
        var temporary = target.resolveSibling(target.getFileName() + ".tmp");
        var directory = target.getParent();

        return FileOps.createDirectories(directory)
                      .flatMap(_ -> forced(temporary, content))
                      .flatMap(_ -> FileOps.moveAtomic(temporary, target))
                      .flatMap(_ -> FileOps.forceDirectory(directory))
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("write " + target, origin));
    }

    private static Result<Unit> forced(Path file, String content) {
        return FileOps.writeBytesForced(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /// A file's text, or `none()` when it does not exist.
    public static Result<Option<String>> optionalText(Path file) {
        return FileOps.exists(file)
               ? FileOps.readString(file)
                        .map(Option::some)
                        .mapError(origin -> WorkerError.Io.FACTORY.apply("read " + file, origin))
               : success(none());
    }

    private <T> Result<Option<T>> parsed(UnitRef ref, UnitFile file, Fn1<Result<T>, String> parser) {
        return text(ref, file).flatMap(content -> parsedText(path(ref, file), content, parser));
    }

    private static <T> Result<Option<T>> parsedText(Path file, Option<String> text, Fn1<Result<T>, String> parser) {
        return Values.optional(text.map(String::trim),
                               raw -> parser.apply(raw)
                                            .mapError(origin -> WorkerError.CorruptState.FACTORY.apply(file, origin)));
    }
}
