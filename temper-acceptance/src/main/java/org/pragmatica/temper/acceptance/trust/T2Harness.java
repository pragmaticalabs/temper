package org.pragmatica.temper.acceptance.trust;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The live T2 (m1-design §8.2), run on the controller host:
///
/// ```
/// T2Harness --controller URL --sha CRASHED_SHA --parent-job T1_JOB --out DIR [--probe-class o.p.CrashedForkProbeTest]
///           [--prio N] [--temper "temper"] [--token-file PATH] [--date YYYY-MM-DD] [--wait-hours H] [--owner O]
/// ```
///
/// Submits the crashed-fork SHA's Gate through the CLI, waits, applies [T2Check] and the control (the parent SHA's
/// Gate from T1, `--parent-job`), and writes `<out>/T2-<date>[.N].md`: the first free number, created exclusively, as
/// T1 numbers its runs, so a second run of the same date never writes over the first's report. Exit 0 when every
/// assertion and the control hold, 1 otherwise (a wait that timed out included, its job never judged), 64 usage, 69
/// a failure before the report.
public sealed interface T2Harness {
    String DEFAULT_PROBE = "org.pragmatica.temper.t2.CrashedForkProbeTest";
    /// The `submittedBy` of the harness's Gate (`TEMPER_OWNER` for the CLI) [G].
    String DEFAULT_OWNER = "t2_harness";
    /// T2's single wait bound (`--wait-hours`), queue and run together; T1 split its own in two ([T1Args]).
    int DEFAULT_WAIT_HOURS = 6;
    /// The overall word of a run that is not a pass ([T1Harness#exitFor] maps it to 1).
    String FAIL = "FAIL";

    /// JVM entry point: the signature is the JVM's.
    @Contract
    static void main(String[] args) {
        System.exit(run(Arrays.asList(args), System.getenv()));
    }

    @TerminalOperation
    static int run(List<String> args, Map<String, String> env) {
        return Flags.flags(args,
                           Set.of())
                    .async()
                    .flatMap(flags -> ran(flags, env))
                    .await()
                    .fold(T2Harness::failed, T2Harness::exitCode);
    }

    /// The settings of one run; `judge` reads temper's side only (it never runs bb-report here).
    record Settings(ControllerApi api,
                    TemperCli cli,
                    Judge judge,
                    String sha,
                    long parentJob,
                    String probeClass,
                    int priority,
                    int waitHours,
                    Path base) {
        static Settings settings(Flags flags,
                                 URI controller,
                                 String token,
                                 String sha,
                                 long parentJob,
                                 int priority,
                                 int waitHours,
                                 Path base) {
            var processes = Processes.processes(ProcessRunner.processRunner());
            var api = ControllerApi.controllerApi(controller, token);

            return new Settings(api,
                                TemperCli.temperCli(processes,
                                                    Arrays.asList(flags.value("--temper").or("temper").split("\\s+")),
                                                    Map.of("TEMPER_URL",
                                                           controller.toString(),
                                                           "TEMPER_OWNER",
                                                           flags.value("--owner").or(DEFAULT_OWNER))),
                                Judge.judge(processes, api, Path.of("bb-report")),
                                sha,
                                parentJob,
                                flags.value("--probe-class").or(DEFAULT_PROBE),
                                priority,
                                waitHours,
                                base);
        }
    }

    private static Promise<T1Harness.Written> ran(Flags flags, Map<String, String> env) {
        return settings(flags, env).async()
                       .flatMap(T2Harness::judged);
    }

    private static Result<Settings> settings(Flags flags, Map<String, String> env) {
        var home = HarnessArgs.home(env);
        var date = flags.value("--date").or(LocalDate.now(ZoneOffset.UTC).toString());

        return Result.all(flags.required("--controller").flatMap(HarnessArgs::controller),
                          token(flags, home),
                          flags.required("--sha"),
                          flags.required("--parent-job").flatMap(HarnessArgs::jobId),
                          HarnessArgs.integer(flags.value("--prio").or(String.valueOf(T1Args.DEFAULT_PRIORITY))),
                          HarnessArgs.integer(flags.value("--wait-hours").or(String.valueOf(DEFAULT_WAIT_HOURS))),
                          flags.required("--out").map(out -> base(Path.of(out),
                                                                  date)))
                     .map((controller, token, sha, parentJob, priority, waitHours, base) -> Settings.settings(flags,
                                                                                                              controller,
                                                                                                              token,
                                                                                                              sha,
                                                                                                              parentJob,
                                                                                                              priority,
                                                                                                              waitHours,
                                                                                                              base));
    }

    private static Result<String> token(Flags flags, Path home) {
        return FileOps.readString(flags.value("--token-file").map(Path::of).or(home.resolve(HarnessArgs.TOKEN_FILE))).map(String::strip);
    }

    /// Submit, wait, judge. Only a job that ended is judged: a wait that timed out is a FAIL report naming the bound
    /// and the state it stopped at (T1's head 11 / job 39 judged an unfinished job and died on HTTP 404 from `/result`).
    static Promise<T1Harness.Written> judged(Settings settings) {
        return settings.cli()
                       .submitGate(settings.sha(),
                                   settings.priority())
                       .flatMap(job -> waited(settings, job));
    }

    private static Promise<T1Harness.Written> waited(Settings settings, long job) {
        return settings.cli()
                       .finished(job,
                                 timeSpan(settings.waitHours()).hours())
                       .fold(wait -> afterWait(settings, job, wait));
    }

    /// A finished job is judged; a [TrustError.WaitTimedOut] is reported, never judged; any other failure stays one.
    private static Promise<T1Harness.Written> afterWait(Settings settings, long job, Result<String> wait) {
        return wait.fold(cause -> notFinished(settings, job, cause), _ -> compared(settings, job));
    }

    /// FER (degrade forward): a wait that timed out becomes T2's FAIL report (exit 1), so the run is never a pass and
    /// its report names why; every other failure keeps T2's failure before the report (69).
    private static Promise<T1Harness.Written> notFinished(Settings settings, long job, Cause cause) {
        return cause instanceof TrustError.WaitTimedOut timedOut
               ? timedOut(settings, job, timedOut).async()
               : cause.promise();
    }

    private static Result<T1Harness.Written> timedOut(Settings settings, long job, TrustError.WaitTimedOut timedOut) {
        return report(settings.base(),
                      Stream.of("# T2 crashed fork",
                                "",
                                subject(settings, job),
                                "- **stopped:** " + timedOut.message(),
                                "",
                                "**T2: " + FAIL + "**")
                            .collect(Collectors.joining("\n", "", "\n"))).map(report -> T1Harness.Written.written(report,
                                                                                                                  FAIL));
    }

    /// The control reads the parent's stored result record, which exists only once the parent job ended (the
    /// controller answers 404 before), so it needs no wait check: an unfinished parent fails before the report (69).
    private static Promise<T1Harness.Written> compared(Settings settings, long job) {
        return Promise.all(settings.judge().temperSide(job),
                           settings.api().result(settings.parentJob()))
                      .flatMap((crashed, parent) -> written(settings, job, crashed, parent).async());
    }

    private static Result<T1Harness.Written> written(Settings settings,
                                                     long job,
                                                     TemperSide crashed,
                                                     ResultRecordDto parent) {
        var check = T2Check.t2Check(crashed, settings.probeClass());
        var control = T2Check.controlHolds(parent);
        var overall = check.passed() && control
                      ? "PASS"
                      : FAIL;

        return report(settings.base(),
                      Stream.of(Stream.of("# T2 crashed fork",
                                          "",
                                          subject(settings, job),
                                          "- verdict " + crashed.result()
                                                                .verdict()
                                         + " " + crashed.result()
                                                        .reason()
                                                        .or("")),
                                check.lines(),
                                Stream.of("- control: parent job " + settings.parentJob()
                                         + " is " + parent.verdict()
                                         + " " + parent.reason()
                                                       .or("")
                                         + ", not missing-classes " + (control
                                                                       ? TrustReport.PASS
                                                                       : TrustReport.FAIL),
                                          "",
                                          "**T2: " + overall + "**"))
                            .flatMap(lines -> lines)
                            .collect(Collectors.joining("\n", "", "\n"))).map(report -> T1Harness.Written.written(report,
                                                                                                                  overall));
    }

    private static String subject(Settings settings, long job) {
        return "- crashed SHA " + settings.sha() + ", job " + job + ", probe " + settings.probeClass();
    }

    /// `<out>/T2-<date>`, the base the report is numbered from.
    static Path base(Path out, String date) {
        return out.resolve("T2-" + date);
    }

    /// `<base>.md`, else `<base>.2.md`, …: the first free, created exclusively (never written over).
    static Result<Path> report(Path base, String text) {
        return T1Harness.firstFreeReport(base, text, 1);
    }

    private static int exitCode(T1Harness.Written written) {
        System.out.println("T2 " + written.overall() + ": " + written.report());

        return T1Harness.exitFor(written.overall());
    }

    private static int failed(Cause cause) {
        System.err.println("T2 harness failed: " + cause.message());

        return usage(cause)
               ? T1Harness.EXIT_USAGE
               : T1Harness.EXIT_ERROR;
    }

    /// F7, as in [T1Harness]: `Result.all` wraps even one failed flag in a composite, so a failure is a usage error
    /// when every cause it holds is one; a token file that cannot be read beside them is not.
    private static boolean usage(Cause cause) {
        return cause.stream()
                    .allMatch(TrustError.Usage.class::isInstance);
    }

    record unused() implements T2Harness {}
}
