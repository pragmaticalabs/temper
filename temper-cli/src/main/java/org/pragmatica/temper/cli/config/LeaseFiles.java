package org.pragmatica.temper.cli.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.ThrowingFn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;


/// The lease files (m3-design §11.2 [P]): `claim` writes a task's lease token to `~/.config/temper/leases/<task>-<epoch>`
/// (directory 0700, file 0600); every lease-token call reads it from `TEMPER_LEASE_FILE`, else from the highest epoch's
/// file of that task. The token is never printed and never an argv element.
///
/// A lease file lives exactly as long as the lease it holds (review #4): a claim removes the task's older files before it
/// writes the new one, and a release, a wait, a park under the lease and any `409 lease-lost` delete it ([#forget]), so
/// no later command silently presents a stale lease.
public sealed interface LeaseFiles {
    /// `<task>-<epoch>`.
    Pattern NAME = Pattern.compile("^([1-9][0-9]{0,18})-([1-9][0-9]{0,18})$");

    /// A lease the CLI holds: the task, the epoch it was granted at, and the token.
    record Held(long task, long epoch, String token, Path file) {
        @Override
        public String toString() {
            return "Held[task=" + task + ", epoch=" + epoch + "]";
        }
    }

    static Path directory(CliContext context) {
        return CliConfig.configDir(context.home()).resolve("leases");
    }

    /// Writes the token of `task` at `epoch`, owner-only: the directory 0700, the file created 0600 before the token
    /// goes in (a file left by an earlier claim of the same epoch is replaced).
    static Result<Path> write(CliContext context, long task, long epoch, String token) {
        var file = directory(context).resolve(task + "-" + epoch);

        return earlierRemoved(context, task).flatMap(_ -> written(file, token));
    }

    /// Every lease file the task has: a new claim's lease replaces them all.
    private static Result<Unit> earlierRemoved(CliContext context, long task) {
        return Result.allOf(Option.option(directory(context).toFile().listFiles())
                                  .map(LeaseFiles::files)
                                  .or(List.of())
                                  .stream()
                                  .map(File::toPath)
                                  .filter(file -> isOfTask(file, task))
                                  .map(LeaseFiles::deleted)
                                  .toList()).mapToUnit();
    }

    private static List<File> files(File[] listed) {
        return List.of(listed);
    }

    /// Deletes the lease file `held` was read from.
    static Result<Unit> forget(Held held) {
        return deleted(held.file());
    }

    /// FER: a lease the controller answered `lease-lost` is gone, so its file is deleted; a delete that fails is dropped,
    /// because the next call under that file is refused `lease-lost` again and deletes it then. Any other failure leaves
    /// the file, whose lease may still hold.
    static Unit forgetIfLost(Held held, Cause cause) {
        return cause instanceof CliError.LeaseLost
               ? forget(held).or(Unit.unit())
               : Unit.unit();
    }

    private static Result<Unit> deleted(Path file) {
        return lifted(file, () -> Files.deleteIfExists(file)).mapToUnit();
    }

    private static Result<Path> written(Path file, String token) {
        return lifted(file,
                      () -> Files.createDirectories(file.getParent())).flatMap(dir -> lifted(file,
                                                                                             () -> Files.setPosixFilePermissions(dir,
                                                                                                                                 PosixFilePermissions.fromString("rwx------"))))
                     .flatMap(_ -> lifted(file,
                                          () -> Files.deleteIfExists(file)))
                     .flatMap(_ -> lifted(file,
                                          () -> Files.createFile(file,
                                                                 PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))))
                     .flatMap(_ -> lifted(file,
                                          () -> Files.writeString(file, token + "\n")));
    }

    private static <T> Result<T> lifted(Path file, ThrowingFn0<T> operation) {
        return Result.lift(thrown -> CliError.Unwritable.FACTORY.apply(file.toString(), Causes.fromThrowable(thrown)),
                           operation);
    }

    /// The lease of `task`: `TEMPER_LEASE_FILE` when set (its name must be this task's), else the highest epoch's file
    /// of the task; none held is a usage error (claim it first).
    static Result<Held> held(CliContext context, long task) {
        return found(context, task).flatMap(held -> held.toResult(CliError.Usage.FACTORY.apply("no lease held for task " + task
                                                                                              + ": claim it first, or set TEMPER_LEASE_FILE")));
    }

    /// As [#held], with no lease held answered as none (a park then goes as the maintainer's); a lease file that is there but
    /// unusable is still the failure.
    static Result<Option<Held>> found(CliContext context, long task) {
        return context.env("TEMPER_LEASE_FILE")
                      .map(Path::of)
                      .orElse(() -> highestEpoch(directory(context),
                                                 task))
                      .map(file -> read(file, task).map(Option::some))
                      .or(() -> Result.success(Option.none()));
    }

    /// `File.listFiles` answers null for a missing or unreadable directory, which is no lease held.
    private static Option<Path> highestEpoch(Path directory, long task) {
        return Option.option(directory.toFile().listFiles()).flatMap(files -> Option.from(Arrays.stream(files)
                                                                                                .map(File::toPath)
                                                                                                .filter(file -> isOfTask(file,
                                                                                                                         task))
                                                                                                .max(Comparator.comparingLong(file -> epochOf(file).or(0L)))));
    }

    private static boolean isOfTask(Path file, long task) {
        return taskOf(file).filter(found -> found == task)
                     .isPresent();
    }

    private static Result<Held> read(Path file, long task) {
        return Result.all(Verify.ensure(taskOf(file).or(0L),
                                        found -> found == task,
                                        CliError.Usage.FACTORY.apply("lease file " + file
                                                                    + " is not task " + task
                                                                    + "'s (<task>-<epoch>)")),
                          epochOf(file).toResult(CliError.Usage.FACTORY.apply("lease file " + file
                                                                             + " is not named <task>-<epoch>")),
                          CliConfig.text(file)
                                   .map(String::strip)
                                   .flatMap(token -> Verify.ensure(token,
                                                                   Verify.Is::matches,
                                                                   CliConfig.TOKEN,
                                                                   CliError.Usage.FACTORY.apply("lease file " + file
                                                                                               + " must hold one token"))))
                     .map((found, epoch, token) -> new Held(found, epoch, token, file));
    }

    private static Option<Long> taskOf(Path file) {
        return named(file).map(matcher -> Long.parseLong(matcher.group(1)));
    }

    private static Option<Long> epochOf(Path file) {
        return named(file).map(matcher -> Long.parseLong(matcher.group(2)));
    }

    private static Option<Matcher> named(Path file) {
        return Option.option(file.getFileName())
                     .map(name -> NAME.matcher(name.toString()))
                     .filter(Matcher::matches);
    }

    record unused() implements LeaseFiles {}
}
