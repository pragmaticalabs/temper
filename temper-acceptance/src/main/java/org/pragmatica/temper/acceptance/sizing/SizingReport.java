package org.pragmatica.temper.acceptance.sizing;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.acceptance.trust.ControllerApi;
import org.pragmatica.temper.acceptance.trust.Evidence;
import org.pragmatica.temper.acceptance.trust.Flags;
import org.pragmatica.temper.acceptance.trust.HarnessArgs;
import org.pragmatica.temper.acceptance.trust.Judge;
import org.pragmatica.temper.acceptance.trust.Processes;
import org.pragmatica.temper.acceptance.trust.Sequential;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `T1-<date>-sizing.md` (m1-design §8.3) from the T1 jobs, run on the controller host:
///
/// ```
/// SizingReport --controller URL --out DIR (--job N)… [--ember-job N] [--date YYYY-MM-DD] [--token-file PATH]
///              [--memory-budget-mb 20480] [--bb-reactor-mb 8192] [--ember aether/ember]
/// ```
///
/// Per job: per-module test time (through the manifest), per-module wall time (the Reactor Summary of `log.tail`),
/// `memory.peak`, the lowest `MemAvailable`, load and bb tickets at start and end. Derived [G] (the design names the
/// inputs, not the formula): `unit.memory` = the `--ember-job` peak × 1.25 rounded up to 512 MiB; the `rest`
/// reservation = the highest `--job` peak, rounded the same way; `gate.emberShards` = floor((budget − `rest`
/// reservation) / `unit.memory`), reported as "does not fit" below one shard; `rest` = Gate wall − Σ test time of the
/// modules under `--ember`, flagged when it exceeds 40 min (spec §12.1). The bb-* reactor is no term of the shard
/// formula: it is printed as a host-side sum of limits beside it (the sizing report §5). Without `--ember-job` the
/// shard figures are reported as not derivable (an unsharded Gate cannot measure one shard; Q-M1-13), and without a
/// Gate `memory.peak` the reservation and the shard count are.
public sealed interface SizingReport {
    long REST_LIMIT_MINUTES = 40;
    long ROUND_MIB = 512;
    double HEADROOM = 1.25;
    int DEFAULT_BUDGET_MB = 20480;
    int DEFAULT_BB_REACTOR_MB = 8192;
    String DEFAULT_EMBER = "aether/ember";
    String UNKNOWN = "unknown";

    /// What the derivation needs besides the jobs.
    record Budget(int memoryBudgetMb, int bbReactorMb, String emberPrefix) {
        static Budget budget(int memoryBudgetMb, int bbReactorMb, String emberPrefix) {
            return new Budget(memoryBudgetMb, bbReactorMb, emberPrefix);
        }
    }

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
                    .flatMap(flags -> written(flags, env))
                    .await()
                    .fold(SizingReport::failed, SizingReport::done);
    }

    private static Promise<Path> written(Flags flags, Map<String, String> env) {
        var tokenFile = flags.value("--token-file")
                             .map(Path::of)
                             .or(HarnessArgs.home(env).resolve(HarnessArgs.TOKEN_FILE));

        return Result.all(flags.required("--controller").flatMap(HarnessArgs::controller),
                          HarnessArgs.token(tokenFile),
                          jobIds(flags.all("--job")),
                          budgetOf(flags))
                     .async()
                     .flatMap((controller, token, jobs, budget) -> report(flags,
                                                                          ControllerApi.controllerApi(controller, token),
                                                                          jobs,
                                                                          budget));
    }

    private static Result<List<Long>> jobIds(List<String> texts) {
        return Result.allOf(texts.stream().map(HarnessArgs::jobId).toList());
    }

    private static Result<Budget> budgetOf(Flags flags) {
        return Result.all(HarnessArgs.integer(flags.value("--memory-budget-mb").or(String.valueOf(DEFAULT_BUDGET_MB))),
                          HarnessArgs.integer(flags.value("--bb-reactor-mb").or(String.valueOf(DEFAULT_BB_REACTOR_MB))))
                     .map((memory, bb) -> Budget.budget(memory,
                                                        bb,
                                                        flags.value("--ember").or(DEFAULT_EMBER)));
    }

    private static Promise<Path> report(Flags flags, ControllerApi api, List<Long> jobs, Budget budget) {
        var date = flags.value("--date").or(LocalDate.now(ZoneOffset.UTC).toString());
        var target = Path.of(flags.value("--out").or("."),
                             "T1-" + date + "-sizing.md");
        var reader = Reader.reader(api);

        return Promise.all(Sequential.each(jobs, reader::sizing),
                           emberJob(flags, reader))
                      .flatMap((sized, ember) -> writtenTo(target,
                                                           markdown(date, sized, ember, budget)));
    }

    private static Promise<Path> writtenTo(Path target, String text) {
        return FileOps.writeString(target, text)
                      .map(_ -> target)
                      .async();
    }

    /// The `--ember-job` run's sizing; none without the flag.
    private static Promise<Option<JobSizing>> emberJob(Flags flags, Reader reader) {
        return flags.value("--ember-job")
                    .map(text -> emberSizing(text, reader))
                    .or(() -> Promise.success(none()));
    }

    private static Promise<Option<JobSizing>> emberSizing(String text, Reader reader) {
        return HarnessArgs.jobId(text)
                          .async()
                          .flatMap(reader::sizing)
                          .map(Option::some);
    }

    /// The report, pure. A job [JobSizing#comparable] refuses (a unit ran on a shared-CPU host, R-M4-9) is named under
    /// "Excluded" and sizes nothing: no section, no term of the derivation; a refused `--ember-job` leaves the shard
    /// figures not derivable.
    static String markdown(String date, List<JobSizing> jobs, Option<JobSizing> ember, Budget budget) {
        var judged = jobs.stream().map(JobSizing::comparable).toList();
        var comparable = judged.stream().flatMap(job -> job.option()
                                                           .stream()).toList();
        var judgedEmber = ember.map(JobSizing::comparable);
        var comparableEmber = judgedEmber.flatMap(job -> job.option());
        var excluded = Stream.concat(judged.stream(),
                                     judgedEmber.stream())
                             .flatMap(job -> job.fold(cause -> Stream.of("- " + cause.message()),
                                                      _ -> Stream.<String> empty()))
                             .distinct()
                             .toList();

        return Stream.of(Stream.of("# T1 sizing, " + date,
                                   "",
                                   "Inputs: memory budget " + budget.memoryBudgetMb()
                                  + " MiB, one bb-* reactor " + budget.bbReactorMb()
                                  + " MiB [G], Ember modules under `" + budget.emberPrefix()
                                  + "`.",
                                   "Honesty: test time is not wall time (drift and pruned classes are `" + JobSizing.UNATTRIBUTED
                                  + "`); wall time includes compile time; `memory.peak` is"
                                  + " a lower bound that excludes Testcontainers siblings; the host `MemAvailable`"
                                  + " minimum includes them and the bb-* load.",
                                   ""),
                         excluded(excluded),
                         comparable.stream().flatMap(job -> section(job, budget)),
                         derived(comparable, comparableEmber, budget))
                     .flatMap(lines -> lines)
                     .collect(Collectors.joining("\n", "", "\n"));
    }

    private static Stream<String> excluded(List<String> refused) {
        return refused.isEmpty()
               ? Stream.empty()
               : Stream.concat(Stream.of("## Excluded (shared CPU, m4-design §9.4)", ""),
                               Stream.concat(refused.stream(), Stream.of("")));
    }

    private static Stream<String> section(JobSizing job, Budget budget) {
        return Stream.of(header(job, budget),
                         rows(job.testSecondsByModule()),
                         Stream.of("",
                                   "| module (Reactor Summary) | wall time |",
                                   "|---|---|"),
                         rows(job.wallSecondsByModule()),
                         Stream.of(""))
                     .flatMap(lines -> lines);
    }

    private static Stream<String> header(JobSizing job, Budget budget) {
        var wall = job.wall().map(SizingReport::minutes).or(UNKNOWN);
        var peak = job.peakMib().map(SizingReport::mib).or(UNKNOWN);
        var lowest = job.memAvailableMinMib().map(SizingReport::mib).or(UNKNOWN);
        var start = job.start().map(SizingReport::facts).or(UNKNOWN);
        var end = job.end().map(UnitEndFactsDto::host).map(SizingReport::facts).or(UNKNOWN);

        return Stream.of("## job " + job.job(),
                         "",
                         "- Gate wall: " + wall,
                         "- memory.peak: " + peak,
                         "- host MemAvailable minimum: " + lowest,
                         "- at start: " + start,
                         "- at end: " + end,
                         "- Σ test time under `" + budget.emberPrefix()
                        + "`: " + minutes(job.testSecondsUnder(budget.emberPrefix())),
                         "",
                         "| module (manifest path) | test time |",
                         "|---|---|");
    }

    private static String mib(long value) {
        return value + " MiB";
    }

    /// One table row per module, the longest first.
    private static Stream<String> rows(Map<String, Double> seconds) {
        return seconds.keySet()
                      .stream()
                      .sorted(Comparator.comparing(seconds::get).reversed())
                      .map(module -> row(module, seconds));
    }

    private static String row(String module, Map<String, Double> seconds) {
        return "| " + module + " | " + minutes(seconds.getOrDefault(module, 0.0)) + " |";
    }

    private static String facts(HostFactsDto facts) {
        var running = count(facts.bbRunning());
        var waiting = count(facts.bbWaiting());

        return "load1 " + facts.load1()
             + ", load5 " + facts.load5()
             + ", bb running " + running
             + ", bb waiting " + waiting
             + ", MemAvailable " + facts.memAvailableKb() / 1024
             + " MiB";
    }

    private static String count(Option<Integer> tickets) {
        return tickets.map(String::valueOf)
                      .or("?");
    }

    /// `unit.memory`, the `rest` reservation, `gate.emberShards` with the host-side sum beside one bb-* reactor, and
    /// `rest` per Gate job.
    static Stream<String> derived(List<JobSizing> jobs, Option<JobSizing> ember, Budget budget) {
        var unitMemory = ember.flatMap(JobSizing::peakMib).map(SizingReport::unitMemoryMib);
        var restMemory = restReservationMib(jobs);

        return Stream.of(Stream.of("## Derived [G]",
                                   "",
                                   unitMemory.map(mib -> "- unit.memory = " + mib
                                                        + " MiB (Ember ModuleTest peak × " + HEADROOM
                                                        + ", rounded up to " + ROUND_MIB
                                                        + " MiB)")
                                             .or("- unit.memory, gate.emberShards: not derivable; run one `ModuleTest -pl " + budget.emberPrefix()
                                                + "` on a T1 SHA and pass --ember-job (Q-M1-13)"),
                                   restMemory.map(mib -> "- rest reservation = " + mib
                                                        + " MiB (max Gate peak × " + HEADROOM
                                                        + ")")
                                             .or("- rest reservation: not derivable (no Gate job has a memory.peak)")),
                         unitMemory.map(unit -> shardLines(unit, restMemory, budget)).or(Stream::empty),
                         jobs.stream().map(job -> rest(job, budget)))
                     .flatMap(lines -> lines);
    }

    static long unitMemoryMib(long peakMib) {
        return (long) Math.ceil(peakMib * HEADROOM / ROUND_MIB) * ROUND_MIB;
    }

    /// The `rest` unit's limit: the highest Gate `memory.peak`, with the `unit.memory` headroom and rounding.
    static Option<Long> restReservationMib(List<JobSizing> jobs) {
        return Option.from(jobs.stream().flatMap(job -> job.peakMib()
                                                           .stream()).max(Long::compare)).map(SizingReport::unitMemoryMib);
    }

    /// `rest` runs beside the shards, so its limit is reserved first; the bb-* reactor is a host constraint, already
    /// priced into the choice of budget (the sizing report §5), so it is no term here. None when not one shard fits.
    static Option<Long> shards(long unitMemoryMib, long restMib, Budget budget) {
        return some((budget.memoryBudgetMb() - restMib) / unitMemoryMib).filter(count -> count >= 1);
    }

    private static Stream<String> shardLines(long unitMib, Option<Long> restMemory, Budget budget) {
        return restMemory.map(restMib -> fitted(unitMib, restMib, budget))
                         .or(() -> Stream.of("- gate.emberShards: not derivable (no rest reservation)"));
    }

    private static Stream<String> fitted(long unitMib, long restMib, Budget budget) {
        return shards(unitMib, restMib, budget).map(count -> Stream.of(shardsLine(count, unitMib, restMib, budget),
                                                                       hostLine(count, unitMib, restMib, budget)))
                     .or(() -> Stream.of("- gate.emberShards: does not fit (budget " + budget.memoryBudgetMb()
                                        + " MiB − rest " + restMib
                                        + " MiB < one shard " + unitMib
                                        + " MiB)"));
    }

    private static String shardsLine(long count, long unitMib, long restMib, Budget budget) {
        return "- gate.emberShards = " + count
             + " = floor((budget " + budget.memoryBudgetMb()
             + " MiB − rest reservation " + restMib
             + " MiB) / unit.memory " + unitMib
             + " MiB)";
    }

    private static String hostLine(long count, long unitMib, long restMib, Budget budget) {
        return "- beside one bb-* reactor: " + restMib
             + " + " + count
             + "×" + unitMib
             + " + " + budget.bbReactorMb()
             + " = " + (restMib + count * unitMib + budget.bbReactorMb())
             + " MiB of limits [compare with the host's memory; see the sizing report §5]";
    }

    private static String rest(JobSizing job, Budget budget) {
        return job.wall()
                  .map(wall -> restLine(job.job(),
                                        wall.toSeconds() - job.testSecondsUnder(budget.emberPrefix())))
                  .or("- job " + job.job() + ": rest not derivable (no Gate wall time)");
    }

    private static String restLine(long job, double restSeconds) {
        return "- job " + job
             + ": rest = " + minutes(restSeconds) + (restSeconds > REST_LIMIT_MINUTES * 60
                                                     ? " — **exceeds " + REST_LIMIT_MINUTES + " min (spec §12.1)**"
                                                     : "");
    }

    private static String minutes(Duration duration) {
        return minutes((double) duration.toSeconds());
    }

    private static String minutes(double seconds) {
        return String.format("%.1f min", seconds / 60);
    }

    private static int done(Path report) {
        System.out.println("sizing report " + report);

        return 0;
    }

    private static int failed(Cause cause) {
        System.err.println("sizing report failed: " + cause.message());

        return 69;
    }

    /// Reads one job's sizing data: temper's side through the API, the facts from the evidence tarball.
    record Reader(ControllerApi api, Judge judge, Evidence evidence, JsonMapper json) {
        static Reader reader(ControllerApi api) {
            var processes = Processes.processes(ProcessRunner.processRunner());

            return new Reader(api,
                              Judge.judge(processes, api, Path.of("bb-report")),
                              Evidence.evidence(processes),
                              TemperJson.mapper());
        }

        Promise<JobSizing> sizing(long job) {
            return judge.temperSide(job)
                        .flatMap(side -> withFacts(job, side));
        }

        private Promise<JobSizing> withFacts(long job, TemperSide side) {
            return Promise.all(member(side, Evidence.FACTS_START, HostFactsDto.class),
                               member(side, Evidence.FACTS_END, UnitEndFactsDto.class)).map((start, end) -> JobSizing.jobSizing(job,
                                                                                                                                side,
                                                                                                                                start,
                                                                                                                                end));
        }

        /// FER (degrade forward): facts that are not on this host or do not parse are reported as unknown; the
        /// report states it per job.
        private <T> Promise<Option<T>> member(TemperSide side, String member, Class<T> type) {
            return Evidence.firstTarball(side.result())
                           .map(tarball -> decodedMember(tarball, member, type))
                           .or(() -> Promise.success(none()))
                           .recover(_ -> none());
        }

        private <T> Promise<Option<T>> decodedMember(Path tarball, String member, Class<T> type) {
            return evidence.member(tarball, member)
                           .flatMap(text -> decoded(text, type))
                           .map(Option::some);
        }

        private <T> Promise<T> decoded(String text, Class<T> type) {
            return json.readString(text, type)
                       .async();
        }
    }

    record unused() implements SizingReport {}
}
