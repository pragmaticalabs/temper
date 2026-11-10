package org.pragmatica.temper.controller.maintenance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.evidence.EvidenceError;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.Tarballs;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The nightly database dump (m1-design §3.4, Zone C):
///
/// 1. `docker exec <container> pg_dump -U <user> -d <db> -Fc`, stdout to `pgdump/temper-<yyyymmddThhmmssZ>.dump.partial`
///    (container-local auth is `trust`, so no password travels);
/// 2. `docker exec -i <container> pg_restore --list`, fed the file on stdin: rc 0 and a non-empty table of contents;
/// 3. the file is forced and renamed into place, its directory forced.
///
/// Every run, good or not, is a `maintenance_runs` row; a failed run removes its partial file (BER) and leaves the
/// previous dumps alone, so only verified dumps ever carry the final name (which [#DUMP] matches).
///
/// **Guarantee:** a published dump is one consistent snapshot (pg_dump's own transaction) whose archive TOC was
/// readable; that is not a full restore.
public record PgDump(ProcessRunner runner,
                     EvidenceLayout layout,
                     String container,
                     String user,
                     String database,
                     MaintenanceStore store,
                     Supplier<Instant> clock,
                     TimeSpan timeout) {
    private static final Logger log = LoggerFactory.getLogger(PgDump.class);
    /// A verified dump's file name; the timestamp is the dump's start, UTC.
    public static final Pattern DUMP = Pattern.compile("^temper-(\\d{8}T\\d{6}Z)\\.dump$");

    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public static final TimeSpan TIMEOUT = timeSpan(60).minutes();
    private static final String STDERR = ".stderr";
    private static final String TOC = ".toc";
    private static final String LIST = "pg_restore --list";

    public static PgDump pgDump(ProcessRunner runner,
                                EvidenceLayout layout,
                                String container,
                                String user,
                                String database,
                                MaintenanceStore store,
                                Supplier<Instant> clock) {
        return new PgDump(runner, layout, container, user, database, store, clock, TIMEOUT);
    }

    /// One dump; answers the recorded run. Fails only when the run cannot be recorded.
    public Promise<MaintenanceRun> dump() {
        var started = clock.get();
        var target = layout.pgDumps().resolve("temper-" + STAMP.format(started) + ".dump");

        return published(target).fold(result -> recorded(started, target, result));
    }

    /// Dumped, verified, then moved into place; answers the size.
    private Promise<Long> published(Path target) {
        return dumped(target).flatMap(bytes -> verifiedKeeping(target, bytes))
                     .flatMap(bytes -> movedKeeping(target, bytes).async());
    }

    private Promise<Long> verifiedKeeping(Path target, long bytes) {
        return verified(target).map(_ -> bytes);
    }

    private static Result<Long> movedKeeping(Path target, long bytes) {
        return Tarballs.moved(EvidenceLayout.partial(target),
                              target)
                       .map(_ -> bytes);
    }

    /// The instant a verified dump's name carries; none for any other file name.
    public static Option<Instant> takenAt(String fileName) {
        var matcher = DUMP.matcher(fileName);

        return matcher.matches()
               ? DateTime.parseLocalDateTime(matcher.group(1),
                                             STAMP)
                         .map(stamp -> stamp.toInstant(ZoneOffset.UTC))
                         .option()
               : none();
    }

    // ---- dump --------------------------------------------------------------------------------------------------
    private Promise<Long> dumped(Path target) {
        var partial = EvidenceLayout.partial(target);

        return dumpCommand(target).async()
                          .flatMap(runner::run)
                          .flatMap(completed -> exitedZero("pg_dump", completed).async())
                          .flatMap(_ -> Tarballs.size(partial).async());
    }

    private Result<Command> dumpCommand(Path target) {
        return Tarballs.directoriesFor(target)
                       .flatMap(_ -> command(List.of("docker",
                                                     "exec",
                                                     container,
                                                     "pg_dump",
                                                     "-U",
                                                     user,
                                                     "-d",
                                                     database,
                                                     "-Fc")))
                       .map(command -> command.stdoutTo(EvidenceLayout.partial(target))
                                              .stderrTo(sibling(target, STDERR)));
    }

    // ---- verify ------------------------------------------------------------------------------------------------
    /// `pg_restore --list` reads the archive from stdin; its table of contents goes to a sibling file.
    private Promise<Unit> verified(Path target) {
        var toc = sibling(target, TOC);

        return listCommand(target, toc).flatMap(runner::spawn)
                          .async()
                          .flatMap(process -> listed(process,
                                                     EvidenceLayout.partial(target)))
                          .flatMap(rc -> listedNonEmpty(target, toc, rc).async());
    }

    private Result<Command> listCommand(Path target, Path toc) {
        return command(List.of("docker", "exec", "-i", container, "pg_restore", "--list")).map(command -> command.stdoutTo(toc)
                                                                                                                 .stderrTo(sibling(target,
                                                                                                                                   STDERR)));
    }

    /// The dump is copied to the process's stdin, which is then closed; the exit code and the TOC are the verdict, and a
    /// failure there kills the process. `pg_restore --list` stops reading once it has the table of contents, so copying a
    /// dump larger than the pipe buffers ends in a broken pipe (bigboy, 2026-10-15, a 5.7 MB dump): a write or close
    /// error is logged and left to the exit code, which is non-zero when the archive really could not be read.
    private Promise<Integer> listed(Spawned process, Path dump) {
        // The kill may run late: it is a best-effort signal to an abandoned process, whose outcome nothing reads.
        return fed(process, dump).flatMap(_ -> process.exit()
                                                      .timeout(timeout))
                  .onFailure(_ -> process.kill());
    }

    private static Promise<Unit> fed(Spawned process, Path dump) {
        return Promise.lift(PgDump::listFailed,
                            () -> Files.copy(dump,
                                             process.stdin()))
                      .map(_ -> unit())
                      .recover(PgDump::stoppedReading)
                      .flatMap(_ -> Promise.lift(PgDump::listFailed,
                                                 process.stdin()::close))
                      .map(_ -> unit())
                      .recover(PgDump::stoppedReading);
    }

    private static Unit stoppedReading(Cause cause) {
        log.info("pg_restore --list stopped reading its stdin early ({}); its exit code decides", chained(cause));

        return unit();
    }

    private static EvidenceError listFailed(Throwable thrown) {
        return step(LIST, Causes.fromThrowable(thrown));
    }

    private static Result<Unit> listedNonEmpty(Path target, Path toc, int rc) {
        return listedZero(rc).flatMap(_ -> nonEmptyToc(target, toc));
    }

    private static Result<Unit> listedZero(int rc) {
        return rc == 0
               ? success(unit())
               : step(LIST, cause("exit " + rc)).result();
    }

    /// A TOC line that is not a `;` comment names an archive entry.
    private static Result<Unit> nonEmptyToc(Path target, Path toc) {
        return FileOps.readString(toc)
                      .mapError(cause -> EvidenceError.Io.FACTORY.apply("read " + toc, cause))
                      .filter(_ -> EvidenceError.EmptyDump.FACTORY.apply(target.getFileName().toString()),
                              PgDump::hasEntry)
                      .map(_ -> unit());
    }

    private static boolean hasEntry(String toc) {
        return toc.lines()
                  .map(String::strip)
                  .anyMatch(line -> !line.isEmpty() && !line.startsWith(";"));
    }

    // ---- record ------------------------------------------------------------------------------------------------
    private Promise<MaintenanceRun> recorded(Instant started, Path target, Result<Long> outcome) {
        var run = outcome.fold(cause -> failedRun(started, target, cause),
                               bytes -> MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP,
                                                                      started,
                                                                      clock.get(),
                                                                      true,
                                                                      none(),
                                                                      some(target),
                                                                      some(bytes)));

        cleanedUp(target);

        return store.record(run)
                    .map(_ -> run);
    }

    /// BER (compensate by inverse): the failed run's partial file goes, so no unverified dump keeps a name.
    /// The detail carries the whole cause chain (a step's own exit code or I/O error), not only the outer step.
    private MaintenanceRun failedRun(Instant started, Path target, Cause cause) {
        var detail = chained(cause);

        log.warn("pg_dump {} failed: {}", target.getFileName(), detail);
        deleted(EvidenceLayout.partial(target));

        return MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP,
                                             started,
                                             clock.get(),
                                             false,
                                             some(detail),
                                             none(),
                                             none());
    }

    private static String chained(Cause cause) {
        return cause.message() + cause.source()
                                      .map(source -> ": " + chained(source))
                                      .or("");
    }

    private static Unit cleanedUp(Path target) {
        deleted(sibling(target, STDERR));

        return deleted(sibling(target, TOC));
    }

    /// FER: a leftover side file is logged and left; it never carries a dump's name.
    private static Unit deleted(Path file) {
        return FileOps.deleteIfExists(file)
                      .onFailure(cause -> log.warn("cannot remove {}: {}",
                                                   file,
                                                   cause.message()))
                      .mapToUnit()
                      .or(unit());
    }

    private Result<Command> command(List<String> argv) {
        return Command.command(argv,
                               Map.of(),
                               timeout)
                      .map(Command::inheritingEnvironment);
    }

    private static Result<Completed> exitedZero(String operation, Completed completed) {
        return completed.requireZero()
                        .mapError(cause -> step(operation, cause));
    }

    private static EvidenceError step(String operation, Cause cause) {
        return EvidenceError.DumpFailed.FACTORY.apply(operation, cause);
    }

    private static Path sibling(Path target, String suffix) {
        return target.resolveSibling(target.getFileName() + suffix);
    }
}
