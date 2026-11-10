package org.pragmatica.temper.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.ReadPointStamp;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.ReadPointStampDto;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// `task claim --checkout DIR` (m3-design §6.5, lesson (d), ruling R-M3-24): the clone is made by temper, never chosen
/// by the worker. `DIR` must be absent or an empty directory, checked BEFORE the claim (a refused DIR claims nothing);
/// after the claim, a fresh `git clone --no-checkout` of the task's repository into `DIR`, `git checkout --detach
/// <readPoint>`, and the stamp `DIR/.temper-read-point` = `{task, epoch, readPoint, at}`.
sealed interface Checkout {
    Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    TimeSpan CLONE = TimeSpan.timeSpan(30).minutes();
    TimeSpan QUICK = TimeSpan.timeSpan(2).minutes();

    /// `DIR`, if it is absent or an empty directory.
    static Result<Path> emptyTarget(String dir) {
        var path = Path.of(dir);

        return isUsable(path)
               ? Result.success(path)
               : CommandLine.usage("--checkout " + dir + " must be absent or an empty directory");
    }

    private static boolean isUsable(Path path) {
        return ! Files.exists(path) || Files.isDirectory(path) && Option.option(path.toFile().list())
                                                                        .filter(names -> names.length == 0)
                                                                        .isPresent();
    }

    /// Clone, detach at the read point, stamp; any failure is [CliError.CheckoutFailed] (the lease stays held).
    static Promise<ReadPointStamp> made(ProcessRunner runner,
                                        JsonMapper json,
                                        Path dir,
                                        ClaimResponse claim,
                                        Instant at) {
        return Sha.sha(claim.readPoint())
                  .async()
                  .map(readPoint -> ReadPointStamp.readPointStamp(claim.task(),
                                                                  claim.epoch(),
                                                                  readPoint,
                                                                  at))
                  .flatMap(stamp -> cloned(runner, json, dir, claim, stamp))
                  .mapError(cause -> CliError.CheckoutFailed.FACTORY.apply(dir.toString(),
                                                                           cause));
    }

    private static Promise<ReadPointStamp> cloned(ProcessRunner runner,
                                                  JsonMapper json,
                                                  Path dir,
                                                  ClaimResponse claim,
                                                  ReadPointStamp stamp) {
        return git(runner,
                   List.of("git",
                           "clone",
                           "--no-checkout",
                           "--quiet",
                           "--",
                           claim.cloneUrl(),
                           dir.toString()),
                   CLONE).flatMap(_ -> git(runner,
                                           List.of("git",
                                                   "-C",
                                                   dir.toString(),
                                                   "checkout",
                                                   "--quiet",
                                                   "--detach",
                                                   claim.readPoint()),
                                           QUICK))
                  .flatMap(_ -> stamped(json, dir, stamp));
    }

    private static Promise<Completed> git(ProcessRunner runner, List<String> argv, TimeSpan timeout) {
        return Command.command(argv, ENV, timeout)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(runner::run)
                      .flatMap(Checkout::succeeded);
    }

    private static Promise<Completed> succeeded(Completed completed) {
        return completed.requireZero()
                        .async();
    }

    /// The stamp beside the clone's files, and named in the clone's `.git/info/exclude` first, so `git add -A` in the
    /// clone never commits it and `git status` stays clean (review #10).
    private static Promise<ReadPointStamp> stamped(JsonMapper json, Path dir, ReadPointStamp stamp) {
        return excluded(dir).flatMap(_ -> stampWritten(json, dir, stamp))
                       .async();
    }

    private static Result<Path> excluded(Path dir) {
        var exclude = dir.resolve(".git").resolve("info").resolve("exclude");

        return Result.lift(thrown -> CliError.Unwritable.FACTORY.apply(exclude.toString(), Causes.fromThrowable(thrown)),
                           () -> Files.writeString(Files.createDirectories(exclude.getParent()).resolve("exclude"),
                                                   "\n" + ReadPointStamp.FILE + "\n",
                                                   StandardOpenOption.CREATE,
                                                   StandardOpenOption.APPEND));
    }

    private static Result<ReadPointStamp> stampWritten(JsonMapper json, Path dir, ReadPointStamp stamp) {
        var file = dir.resolve(ReadPointStamp.FILE);

        return json.writeAsString(ReadPointStampDto.readPointStampDto(stamp))
                   .flatMap(text -> Result.lift(thrown -> CliError.Unwritable.FACTORY.apply(file.toString(),
                                                                                            Causes.fromThrowable(thrown)),
                                                () -> Files.writeString(file, text + "\n")))
                   .map(_ -> stamp);
    }

    record unused() implements Checkout {}
}
