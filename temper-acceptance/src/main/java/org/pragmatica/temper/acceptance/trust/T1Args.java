package org.pragmatica.temper.acceptance.trust;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.success;


/// The T1 harness command line (m1-design §8.1):
///
/// ```
/// T1Harness --controller URL --out DIR --planted CLASS [--prio N] [--date YYYY-MM-DD] [--temper "temper"]
///           [--bb-report PATH] [--token-file PATH] [--queue-hours Q] [--run-hours R] (--sha S --bb-co PATH)…
///                                                                                   live: steps 1-5
/// T1Harness --controller URL --out DIR [--planted CLASS] --date YYYY-MM-DD [--run N] [--bb-report PATH]
///           [--token-file PATH] --rerun                                         re-run step 4 from the snapshots
/// ```
///
/// `--planted` is required on a live run: the fully qualified test class the red SHA deliberately breaks (maintainer ruling
/// "T1 red", m1-design §14). Only a comparable SHA whose bb `FAILED:` set contains it is T1's red SHA, and the report
/// names it. The live run stores it with its evidence before any SHA is judged, and a re-run judges against the stored
/// class, never a new argument (F1, main-session reading "the planted class is stored", m1-design §14): on `--rerun`
/// `--planted` may be left out, and one that names another class is a usage error ([T1Harness]).
///
/// Defaults: `--prio 5` (maintainer ruling M1-3), `--date` today, `--temper temper`, `--bb-report $HOME/tools/bb-report`,
/// `--token-file $HOME/.config/temper/token`, `--queue-hours 24`, `--run-hours 4`, `--owner t1_harness` (the jobs'
/// `submittedBy`), `--run 1`.
/// `--sha` and `--bb-co` pair up in order. Each live run of a date is numbered (N1): run 1 keeps its evidence in
/// `<out>/T1-<date>/<sha7>/` and writes `<out>/T1-<date>.md`, run N in `<out>/T1-<date>.N/` and `T1-<date>.N.md`, the
/// first number free (see [T1Harness]). `--rerun` judges exactly one run's evidence, run `--run N`, and writes
/// `<out>/T1-<date>[.N]-rerun.md` (an existing one is never written over: the new one is `-rerun.2.md`, …). `--run`
/// without `--rerun` is a usage error: a live run never chooses its number.
///
/// Two wait bounds (owner ruling, option A), each 1 or more: `--queue-hours` bounds the time the job stays QUEUED
/// (a unit's wait in bb-hold for the port group included), `--run-hours` the time from leaving the queue to its end.
/// A job still QUEUED at the first is NOT COMPARABLE (capacity); one still
/// unfinished at the second fails. The single `--wait-hours` they replace covered both, so a healthy shadow head
/// queued behind prio-5 Gates failed while RUNNING; it is refused as a usage error naming the two flags, never quietly
/// read as one of them.
public record T1Args(URI controller,
                     Path tokenFile,
                     int priority,
                     Path out,
                     LocalDate date,
                     List<String> temper,
                     Path bbReport,
                     List<Target> targets,
                     boolean rerun,
                     int run,
                     int queueHours,
                     int runHours,
                     String owner,
                     Option<String> planted) {
    public static final String RERUN = "--rerun";
    public static final String RUN = "--run";
    public static final String PLANTED = "--planted";
    public static final String QUEUE_HOURS = "--queue-hours";
    public static final String RUN_HOURS = "--run-hours";
    public static final String WAIT_HOURS = "--wait-hours";
    public static final Pattern SHA = Pattern.compile("^[0-9a-f]{7,40}$");
    /// A fully qualified class name, as bb-report prints it after `FAILED: `.
    public static final Pattern CLASS = Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$");
    public static final int DEFAULT_PRIORITY = 5;
    /// Owner ruling (option A): shadow heads run at prio 1 behind prio-5 Gates of ~92 min each, so a day of queue
    /// before capacity is the verdict.
    public static final int DEFAULT_QUEUE_HOURS = 24;
    /// Above the Gate unit's own 3 h timeout (m1-design §2, `UnitKind.GATE_UNSHARDED`), so a Gate that overruns ends
    /// with its own ERROR verdict, which the harness judges, before the harness could report a timeout at RUNNING.
    public static final int DEFAULT_RUN_HOURS = 4;
    /// The `submittedBy` of the harness's Gates (`TEMPER_OWNER` for the CLI) [G].
    public static final String DEFAULT_OWNER = "t1_harness";

    public T1Args {
        temper = List.copyOf(temper);
        targets = List.copyOf(targets);
    }

    /// One SHA and the bb checkout that built it.
    public record Target(String sha, Path checkout) {
        static Target target(String sha, Path checkout) {
            return new Target(sha, checkout);
        }
    }

    public static Result<T1Args> t1Args(List<String> args, Map<String, String> env, LocalDate today) {
        var home = HarnessArgs.home(env);

        return Flags.flags(args,
                           Set.of(RERUN))
                    .flatMap(T1Args::withoutWaitHours)
                    .flatMap(flags -> t1Args(flags, home, today));
    }

    private static Result<T1Args> t1Args(Flags flags, Path home, LocalDate today) {
        return Result.all(flags.required("--controller").flatMap(HarnessArgs::controller),
                          integer(flags, "--prio", DEFAULT_PRIORITY),
                          flags.required("--out").map(Path::of),
                          date(flags, today),
                          targets(flags),
                          hours(flags, QUEUE_HOURS, DEFAULT_QUEUE_HOURS),
                          hours(flags, RUN_HOURS, DEFAULT_RUN_HOURS),
                          run(flags),
                          planted(flags))
                     .map((controller, priority, out, date, targets, queueHours, runHours, run, planted) -> new T1Args(controller,
                                                                                                                       path(flags,
                                                                                                                            "--token-file",
                                                                                                                            home.resolve(HarnessArgs.TOKEN_FILE)),
                                                                                                                       priority,
                                                                                                                       out,
                                                                                                                       date,
                                                                                                                       words(flags.value("--temper")
                                                                                                                                  .or("temper")),
                                                                                                                       path(flags,
                                                                                                                            "--bb-report",
                                                                                                                            home.resolve("tools/bb-report")),
                                                                                                                       targets,
                                                                                                                       flags.has(RERUN),
                                                                                                                       run,
                                                                                                                       queueHours,
                                                                                                                       runHours,
                                                                                                                       flags.value("--owner")
                                                                                                                            .or(DEFAULT_OWNER),
                                                                                                                       planted));
    }

    /// `--wait-hours` is refused: its one bound is now two, and reading it as either would reintroduce the failure
    /// the split exists for.
    private static Result<Flags> withoutWaitHours(Flags flags) {
        return given(flags, WAIT_HOURS)
               ? TrustError.Usage.FACTORY.apply(WAIT_HOURS
                                               + " is replaced by " + QUEUE_HOURS
                                               + " Q (time QUEUED, default " + DEFAULT_QUEUE_HOURS
                                               + ") and " + RUN_HOURS
                                               + " R (time from leaving the queue to the end, default " + DEFAULT_RUN_HOURS
                                               + ")").result()
               : success(flags);
    }

    /// `--planted CLASS`, a class name: required on a live run, since without it no red SHA can be judged the planted
    /// one; optional on `--rerun`, which judges against the class the live run stored.
    private static Result<Option<String>> planted(Flags flags) {
        return given(flags, PLANTED) || flags.has(RERUN)
               ? Verify.ensureOption(flags.value(PLANTED), Verify.Is::matches, CLASS, T1Args::notAClass)
               : TrustError.Usage.FACTORY.apply(PLANTED + " is required on a live run").result();
    }

    private static boolean given(Flags flags, String name) {
        return flags.value(name)
                    .isPresent();
    }

    private static TrustError notAClass(String planted) {
        return TrustError.Usage.FACTORY.apply("not a class name: " + planted);
    }

    private static Result<Integer> integer(Flags flags, String name, int fallback) {
        return flags.value(name)
                    .map(HarnessArgs::integer)
                    .or(() -> success(fallback));
    }

    /// `--run N`, 1 or more, only with `--rerun`: the run whose evidence the re-run judges (default 1).
    private static Result<Integer> run(Flags flags) {
        return flags.has(RERUN) || flags.value(RUN)
                                        .isEmpty()
               ? positiveRun(flags)
               : TrustError.Usage.FACTORY.apply("--run needs --rerun: a live run takes the next free number").result();
    }

    private static Result<Integer> positiveRun(Flags flags) {
        return integer(flags, RUN, 1).filter(TrustError.Usage.FACTORY.apply("--run must be 1 or more"),
                                             Verify.Is::positive);
    }

    /// A bound of 1 hour or more: 0 would expire at once, a negative one never parse as a `--timeout`.
    private static Result<Integer> hours(Flags flags, String name, int fallback) {
        return integer(flags, name, fallback).filter(TrustError.Usage.FACTORY.apply(name + " must be 1 or more"),
                                                     Verify.Is::positive);
    }

    private static Result<LocalDate> date(Flags flags, LocalDate today) {
        return flags.value("--date")
                    .map(HarnessArgs::day)
                    .or(() -> success(today));
    }

    private static Path path(Flags flags, String name, Path fallback) {
        return flags.value(name)
                    .map(Path::of)
                    .or(fallback);
    }

    private static List<String> words(String command) {
        return Arrays.stream(command.strip().split("\\s+")).toList();
    }

    /// `--sha`/`--bb-co` paired in order; every SHA a 7-40 char lowercase hex prefix.
    private static Result<List<Target>> targets(Flags flags) {
        var shas = flags.all("--sha");
        var checkouts = flags.all("--bb-co");

        return shas.size() == checkouts.size()
               ? Result.allOf(IntStream.range(0,
                                              shas.size())
                                       .mapToObj(index -> target(shas.get(index),
                                                                 checkouts.get(index)))
                                       .toList())
               : TrustError.Usage.FACTORY.apply("--sha and --bb-co must pair up").result();
    }

    private static Result<Target> target(String sha, String checkout) {
        return Verify.ensure(sha,
                             Verify.Is::matches,
                             SHA,
                             TrustError.Usage.FACTORY.apply("not a SHA: " + sha))
                     .map(valid -> Target.target(valid,
                                                 Path.of(checkout)));
    }
}
