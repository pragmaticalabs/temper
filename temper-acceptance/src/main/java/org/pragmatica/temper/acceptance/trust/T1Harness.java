package org.pragmatica.temper.acceptance.trust;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.ThrowingFn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.Result.success;


/// The T1 comparison harness (m1-design §8.1), run on the controller host: `T1Args` describes the command line. The
/// SHAs run one after another (each Gate takes the port group); the report is written once all are done.
///
/// Neither a report nor its evidence is ever written over (F3, N1, N3). A live run first claims the first free run
/// number of its date: run 1 is `T1-<date>`, run N `T1-<date>.N`. The claim creates the evidence directory
/// `<out>/T1-<date>[.N]/` exclusively and skips a number whose report exists (its evidence moved aside), so two runs
/// started together take different numbers. The run's snapshots go into that directory and its report is
/// `<out>/T1-<date>[.N].md`, created exclusively. `--rerun` judges run `--run N` only and writes
/// `T1-<date>[.N]-rerun.md`, `-rerun.2.md`, …: a name another re-run created first moves on to the next. The path
/// written is printed.
///
/// The planted class is bound to the run before any SHA is judged (F1): a live run stores `--planted` in its evidence
/// directory as [#PLANTED], and a re-run judges against that stored class, refusing (usage) a `--planted` that names
/// another, so a re-run can never pick the class after the results are seen.
///
/// Exit codes: 0 PASS (see [TrustReport]), 1 FAIL, 2 INCOMPLETE (the criterion holds, but a crash or red explanation is
/// unwritten), 64 usage (a command line that does not parse, or a re-run's `--planted` naming another class than the
/// stored one), 69 a failure before any report.
public sealed interface T1Harness {
    int EXIT_PASS = 0;
    int EXIT_FAIL = 1;
    int EXIT_INCOMPLETE = 2;
    int EXIT_USAGE = 64;
    int EXIT_ERROR = 69;
    String REPORT = ".md";
    /// The planted class of a run, in its evidence directory.
    String PLANTED = "planted";

    /// The report written and the overall verdict it states.
    record Written(Path report, String overall) {
        static Written written(Path report, String overall) {
            return new Written(report, overall);
        }
    }

    /// JVM entry point: the signature is the JVM's.
    @Contract
    static void main(String[] args) {
        System.exit(run(Arrays.asList(args), System.getenv()));
    }

    /// F7: a command line that does not parse is a usage error (64), however many of its flags failed: `T1Args`
    /// gathers them with `Result.all`, which wraps even one failure in a composite.
    static int run(List<String> args, Map<String, String> env) {
        return T1Args.t1Args(args,
                             env,
                             LocalDate.now(ZoneOffset.UTC))
                     .fold(T1Harness::usage, T1Harness::harnessedExit);
    }

    @TerminalOperation
    private static int harnessedExit(T1Args args) {
        return harnessed(args).await()
                        .fold(T1Harness::failed, T1Harness::exitCode);
    }

    /// The SHAs judged and the report written; answers the report and its overall verdict.
    static Promise<Written> harnessed(T1Args args) {
        return FileOps.readString(args.tokenFile())
                      .map(String::strip)
                      .flatMap(token -> started(args, token))
                      .async()
                      .flatMap(started -> judged(args, started));
    }

    /// The bearer token, the run's evidence directory, whose name the report repeats, and the planted class bound to
    /// that run.
    record Started(String token, Path evidence, String planted) {
        static Started started(String token, Path evidence, String planted) {
            return new Started(token, evidence, planted);
        }
    }

    private static Result<Started> started(T1Args args, String token) {
        return evidence(args).mapWith(evidence -> planted(args, evidence),
                                      (evidence, planted) -> Started.started(token, evidence, planted));
    }

    /// The run's SHAs judged over its own evidence, and its report written.
    private static Promise<Written> judged(T1Args args, Started started) {
        return ran(args, steps(args, started)).flatMap(runs -> written(args, started.evidence(), started.planted(), runs).async());
    }

    /// F1: the planted class the run is judged against. A live run stores its `--planted` in `evidence`; a re-run
    /// reads the stored class, and a `--planted` naming another is a usage error.
    static Result<String> planted(T1Args args, Path evidence) {
        return args.rerun()
               ? stored(evidence.resolve(PLANTED)).flatMap(stored -> sameAs(args.planted(), stored))
               : args.planted()
                     .toResult(TrustError.Usage.FACTORY.apply(T1Args.PLANTED + " is required on a live run"))
                     .flatMap(planted -> store(evidence, planted));
    }

    private static Result<String> store(Path evidence, String planted) {
        return created(evidence.resolve(PLANTED), planted + "\n").map(_ -> planted);
    }

    private static Result<String> stored(Path file) {
        return FileOps.readString(file)
                      .map(String::strip)
                      .mapError(origin -> TrustError.Io.FACTORY.apply("read the run's stored planted class " + file,
                                                                      origin));
    }

    /// The stored class, unless `--planted` names another one.
    private static Result<String> sameAs(Option<String> given, String stored) {
        return given.filter(planted -> !planted.equals(stored))
                    .map(planted -> differs(planted, stored))
                    .or(() -> success(stored));
    }

    private static Result<String> differs(String planted, String stored) {
        return TrustError.Usage.FACTORY.apply(T1Args.PLANTED
                                             + " " + planted
                                             + " differs from the run's stored planted class " + stored
                                             + "; a re-run judges against the stored class").result();
    }

    /// The evidence directory of this run: a live run claims a new one, a re-run reads run `--run N`'s.
    static Result<Path> evidence(T1Args args) {
        return args.rerun()
               ? success(args.out().resolve(numbered(base(args), args.run())))
               : FileOps.createDirectories(args.out()).flatMap(out -> claimed(out, base(args), 1));
    }

    /// The first free run from `n` on: its evidence directory created exclusively. A number whose report exists or
    /// whose directory another run created first is skipped.
    private static Result<Path> claimed(Path out, String base, int n) {
        var dir = out.resolve(numbered(base, n));

        return Files.exists(report(dir), LinkOption.NOFOLLOW_LINKS)
               ? claimed(out, base, n + 1)
               : orNext(exclusive(dir, () -> Files.createDirectory(dir)),
                        () -> claimed(out, base, n + 1));
    }

    private static T1Steps steps(T1Args args, Started started) {
        var processes = Processes.processes(ProcessRunner.processRunner());
        var api = ControllerApi.controllerApi(args.controller(), started.token());

        return T1Steps.t1Steps(processes,
                               TemperCli.temperCli(processes,
                                                   args.temper(),
                                                   Map.of("TEMPER_URL",
                                                          args.controller().toString(),
                                                          "TEMPER_OWNER",
                                                          args.owner())),
                               api,
                               Judge.judge(processes, api, args.bbReport()),
                               started.evidence(),
                               args.priority(),
                               args.queueHours(),
                               args.runHours());
    }

    private static Promise<List<ShaRun>> ran(T1Args args, T1Steps steps) {
        return args.rerun()
               ? rerun(steps)
               : Sequential.each(args.targets(), steps::live);
    }

    private static Promise<List<ShaRun>> rerun(T1Steps steps) {
        return steps.snapshotDirs()
                    .async()
                    .flatMap(dirs -> Sequential.each(dirs, steps::rerun));
    }

    static Result<Written> written(T1Args args, Path evidence, String planted, List<ShaRun> runs) {
        return reported(args,
                        evidence,
                        TrustReport.t1(args.date(), planted, runs, args.rerun())).map(report -> Written.written(report,
                                                                                                                TrustReport.overall(planted,
                                                                                                                                    runs)));
    }

    /// A live run's report has its run's name, which the evidence claim made its own; a re-run's takes the next free.
    private static Result<Path> reported(T1Args args, Path evidence, String text) {
        return args.rerun()
               ? rerunReport(evidence, text, 1)
               : created(report(evidence), text);
    }

    /// `<run>-rerun.md`, `<run>-rerun.2.md`, …: the first that no other re-run created first (N3).
    private static Result<Path> rerunReport(Path evidence, String text, int n) {
        return firstFreeReport(evidence.resolveSibling(evidence.getFileName() + "-rerun"),
                               text,
                               n);
    }

    /// `<base>.md`, `<base>.2.md`, …, from number `n` on: the first that no other run created first (N3), created
    /// exclusively, so no report is ever written over. T2 numbers its reports the same way.
    static Result<Path> firstFreeReport(Path base, String text, int n) {
        var report = base.resolveSibling(numbered(base.getFileName().toString(),
                                                  n) + REPORT);

        return orNext(created(report, text), () -> firstFreeReport(base, text, n + 1));
    }

    /// The report of the run whose evidence is `evidence`: `<out>/T1-<date>[.N].md`.
    static Path report(Path evidence) {
        return evidence.resolveSibling(evidence.getFileName() + REPORT);
    }

    static String numbered(String base, int n) {
        return base + suffix(n);
    }

    private static String suffix(int n) {
        return n == 1
               ? ""
               : "." + n;
    }

    private static String base(T1Args args) {
        return "T1-" + args.date();
    }

    /// `CREATE_NEW`: a report that exists already (a concurrent run) fails the write with `Taken`, never is
    /// overwritten.
    private static Result<Path> created(Path report, String text) {
        return exclusive(report,
                         () -> Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW));
    }

    /// `create`, which makes `path` only when it does not exist: one that exists fails with `Taken`.
    private static Result<Path> exclusive(Path path, ThrowingFn0<Path> create) {
        return Result.lift(thrown -> notCreated(path, thrown), create);
    }

    private static TrustError notCreated(Path path, Throwable thrown) {
        return thrown instanceof FileAlreadyExistsException
               ? TrustError.Taken.FACTORY.apply(path, Causes.fromThrowable(thrown))
               : TrustError.Io.FACTORY.apply("create " + path, Causes.fromThrowable(thrown));
    }

    /// `created`, or `next` when another run took the path (N3); any other failure stands.
    private static Result<Path> orNext(Result<Path> created, Supplier<Result<Path>> next) {
        return created.fold(cause -> takenOrFailed(cause, next), _ -> created);
    }

    /// FER (degrade forward): a name another run took is not a failure of this run; it takes the next number, so
    /// neither run's report nor evidence is lost or overwritten. Bounded by the names that exist.
    private static Result<Path> takenOrFailed(Cause cause, Supplier<Result<Path>> next) {
        return cause instanceof TrustError.Taken
               ? next.get()
               : cause.result();
    }

    private static int exitCode(Written written) {
        System.out.println("T1 " + written.overall() + ": " + written.report());

        return exitFor(written.overall());
    }

    static int exitFor(String overall) {
        return switch (overall) {
            case "PASS" -> EXIT_PASS;
            case "INCOMPLETE" -> EXIT_INCOMPLETE;
            default -> EXIT_FAIL;
        };
    }

    private static int usage(Cause cause) {
        cause.stream().forEach(each -> System.err.println("T1 harness failed: " + each.message()));

        return EXIT_USAGE;
    }

    private static int failed(Cause cause) {
        System.err.println("T1 harness failed: " + cause.message());

        return cause instanceof TrustError.Usage
               ? EXIT_USAGE
               : EXIT_ERROR;
    }

    record unused() implements T1Harness {}
}
