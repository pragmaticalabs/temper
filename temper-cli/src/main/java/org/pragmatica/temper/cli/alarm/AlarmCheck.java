package org.pragmatica.temper.cli.alarm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.temper.cli.config.AlarmConfig;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.MaintenanceView;


/// One evaluation of every condition: the controller's two reads and the local disk, independent of each other.
///
/// `controller-down` fires when the controller does not answer (connect refused, no answer within the transport's
/// timeout, or both reads together not done within [#READ_DEADLINE]) or answers 5xx at each of [#ATTEMPTS] reads,
/// [#RETRY_PAUSE] apart: a controller restart within about 30 s
/// opens no issue. Then `dump-failed`, `dump-stale` and `worker-lost` are not evaluated at all, neither clear nor
/// firing, and only `controller-down` is delivered for the controller. Any other failure of the reads (401, a route
/// the controller lacks, an answer that does not decode) means the controller answered, so `controller-down` is clear,
/// the three are undetermined, and `check-blind` fires ([Evaluation#withBlindness]).
public sealed interface AlarmCheck {
    /// Reads of the controller before it counts as down.
    int ATTEMPTS = 3;
    Duration RETRY_PAUSE = Duration.ofSeconds(15);
    /// Both reads of one attempt, bodies included (the transport's own timeout ends at the headers).
    Duration READ_DEADLINE = Duration.ofSeconds(20);

    /// The longest the controller side can take: every attempt at its deadline, with the pauses between.
    Duration WORST_CASE = READ_DEADLINE.multipliedBy(ATTEMPTS).plus(RETRY_PAUSE.multipliedBy(ATTEMPTS - 1));

    /// What the controller reads need, passed down the retries unchanged.
    /// `skipped` are the codes skipped at this check: when both dump codes are, `/maintenance` is not read at all.
    /// `alarmsRoute` is the link `host-alarms` puts in its issue body (`<controller url>/api/v1/fleet/alarms`).
    record Reads(Controller api,
                 AlarmConfig config,
                 InstantSource clock,
                 Pacer pacer,
                 Duration deadline,
                 List<AlarmCode> skipped,
                 String alarmsRoute) {
        static Reads reads(Controller api,
                           AlarmConfig config,
                           InstantSource clock,
                           Pacer pacer,
                           Duration deadline,
                           List<AlarmCode> skipped,
                           String alarmsRoute) {
            return new Reads(api, config, clock, pacer, deadline, skipped, alarmsRoute);
        }

        boolean readsMaintenance() {
            return ! (skipped.contains(AlarmCode.DUMP_FAILED) && skipped.contains(AlarmCode.DUMP_STALE));
        }
    }

    /// `GET` path of the fleet alarms, relative to the `…/api/v1` base.
    String ALARMS_PATH = "/fleet/alarms";
    /// `temper-*.dump`: the names `PgDump` publishes once a dump is verified (a `.partial` never matches).
    Pattern DUMP_FILE = Pattern.compile("^temper-[^/]*\\.dump$");

    /// Fork-join: the reads and the local checks share nothing. The codes `[alarm] skip` names are not evaluated while
    /// the skip holds (and the local checks of those codes not even run); once it expired, they are evaluated again and
    /// the expiry itself is an undetermined failure, so `check-blind` fires until the config drops the skip. `secret`
    /// (the controller token) is kept out of the `check-blind` detail.
    /// `dump-stale` is judged at the time of the answer it reads, after any retries.
    static Promise<Evaluation> evaluate(Controller api,
                                        AlarmConfig config,
                                        InstantSource clock,
                                        Pacer pacer,
                                        String secret) {
        return evaluate(api, config, clock, clock.instant(), pacer, secret, "");
    }

    /// `started` decides the skip and the local checks; `clock` dates the controller's answers (after retries).
    static Promise<Evaluation> evaluate(Controller api,
                                        AlarmConfig config,
                                        InstantSource clock,
                                        Instant started,
                                        Pacer pacer,
                                        String secret,
                                        String apiBase) {
        return evaluate(api, config, clock, started, pacer, secret, apiBase, READ_DEADLINE);
    }

    /// The same, with `readDeadline` for each attempt instead of [#READ_DEADLINE] (tests use a short one).
    static Promise<Evaluation> evaluate(Controller api,
                                        AlarmConfig config,
                                        InstantSource clock,
                                        Pacer pacer,
                                        String secret,
                                        Duration readDeadline) {
        return evaluate(api, config, clock, clock.instant(), pacer, secret, "", readDeadline);
    }

    static Promise<Evaluation> evaluate(Controller api,
                                        AlarmConfig config,
                                        InstantSource clock,
                                        Instant started,
                                        Pacer pacer,
                                        String secret,
                                        String apiBase,
                                        Duration readDeadline) {
        var now = started;
        var skipped = config.skippedAt(now);

        return Promise.all(controllerConditions(Reads.reads(api,
                                                            config,
                                                            clock,
                                                            pacer,
                                                            readDeadline,
                                                            skipped,
                                                            apiBase + ALARMS_PATH),
                                                ATTEMPTS),
                           Promise.success(localConditions(config, skipped, now)))
                      .map(Evaluation::and)
                      .map(evaluation -> evaluation.without(skipped)
                                                   .and(expiredSkip(config, now))
                                                   .withBlindness(secret));
    }

    /// The disk-only checks: free space and the newest dump file, each unless skipped.
    private static Evaluation localConditions(AlarmConfig config, List<AlarmCode> skipped, Instant now) {
        return unlessSkipped(skipped,
                             AlarmCode.DISK_LOW,
                             () -> diskConditions(config.paths(), config.minFreeBytes())).and(unlessSkipped(skipped,
                                                                                                            AlarmCode.DUMP_FILE_STALE,
                                                                                                            () -> dumpFileConditions(config.dumpDir(),
                                                                                                                                     config.staleAfter(),
                                                                                                                                     now)));
    }

    private static Evaluation unlessSkipped(List<AlarmCode> skipped, AlarmCode code, Supplier<Evaluation> check) {
        return skipped.contains(code)
               ? Evaluation.decided()
               : check.get();
    }

    /// A skip past its `skip-until`: one undetermined failure naming it, so `check-blind` never falls silent.
    private static Evaluation expiredSkip(AlarmConfig config, Instant now) {
        return config.skip()
                     .filter(skip -> skip.expiredOn(AlarmConfig.today(now)))
                     .map(skip -> Evaluation.undetermined(Causes.cause("skip of " + skip.codesText()
                                                                      + " expired on " + skip.until()
                                                                      + " (remove skip and skip-until from [alarm])")))
                     .or(Evaluation::decided);
    }

    /// FER: a directory that cannot be listed leaves `dump-file-stale` undetermined (`check-blind`); an empty one
    /// fires.
    static Evaluation dumpFileConditions(Path dir, Duration staleAfter, Instant now) {
        return Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(dir.toString(),
                                                                       Causes.fromThrowable(thrown)),
                           () -> Files.list(dir))
                     .map(AlarmCheck::newestDump)
                     .fold(Evaluation::undetermined,
                           newest -> Evaluation.decided(Conditions.dumpFileStale(newest, now, staleAfter, dir)));
    }

    /// The newest modification time among the `temper-*.dump` regular files of the listing, which it closes.
    private static Option<Instant> newestDump(Stream<Path> files) {
        try (files) {
            return Option.from(files.filter(AlarmCheck::isDump)
                                    .map(file -> Instant.ofEpochMilli(file.toFile().lastModified()))
                                    .max(Comparator.naturalOrder()));
        }
    }

    private static boolean isDump(Path file) {
        return DUMP_FILE.matcher(file.getFileName().toString()).matches() && Files.isRegularFile(file);
    }

    /// Iteration over at most `attempts` reads: a down answer is read again after [#RETRY_PAUSE] until it is not down
    /// or the attempts are used up.
    private static Promise<Evaluation> controllerConditions(Reads reads, int attempts) {
        return controllerRead(reads).flatMap(evaluation -> confirmed(reads, attempts, evaluation));
    }

    private static Promise<Evaluation> confirmed(Reads reads, int attempts, Evaluation evaluation) {
        return evaluation.fires(AlarmCode.CONTROLLER_DOWN) && attempts > 1
               ? reads.pacer()
                      .pause(RETRY_PAUSE)
                      .flatMap(_ -> controllerConditions(reads, attempts - 1))
               : Promise.success(evaluation);
    }

    /// FER: a failed read is this check's finding, not its failure. It degrades forward into `controller-down`
    /// (or into undetermined conditions), which is what the check exists to report; [#controllerConditions] reads
    /// again, and so does the next timer run.
    private static Promise<Evaluation> controllerRead(Reads reads) {
        return Promise.all(maintenanceOf(reads),
                           reads.api().hosts(),
                           hostAlarmsOf(reads))
                      .map((maintenance, hosts, alarms) -> alarmsAnswered(maintenance, hosts, alarms, reads))
                      .timeout(TimeSpan.fromDuration(reads.deadline()))
                      .recover(AlarmCheck::unanswered);
    }

    /// The round's finding, or `controller-down` when the alarms read got no answer or a 5xx.
    private static Evaluation alarmsAnswered(Option<MaintenanceView> maintenance,
                                             List<HostView> hosts,
                                             Evaluation alarms,
                                             Reads reads) {
        return alarms.undetermined()
                     .stream()
                     .filter(AlarmCheck::unanswering)
                     .findFirst()
                     .map(AlarmCheck::unanswered)
                     .orElseGet(() -> answered(maintenance,
                                               hosts,
                                               reads.config(),
                                               reads.clock().instant()).and(alarms));
    }

    /// FER: a path that cannot be measured leaves `disk-low` undetermined (reported, exit 64) instead of failing
    /// the other conditions' delivery.
    static Evaluation diskConditions(List<Path> paths, long minFreeBytes) {
        return Result.allOf(paths.stream().map(AlarmCheck::freeSpace).toList()).fold(Evaluation::undetermined,
                                                                                     measured -> Evaluation.decided(Conditions.diskLow(measured,
                                                                                                                                       minFreeBytes)));
    }

    private static Evaluation unanswered(Cause cause) {
        return down(cause)
               ? Evaluation.decided(Condition.firing(AlarmCode.CONTROLLER_DOWN, described(cause)))
               : Evaluation.decided(Condition.clear(AlarmCode.CONTROLLER_DOWN)).and(Evaluation.undetermined(cause));
    }

    /// Every member of a composite, each on one line with its chain, joined by "; ".
    private static String described(Cause cause) {
        return cause.stream()
                    .map(Console::oneLine)
                    .collect(Collectors.joining("; "));
    }

    /// `host-alarms` from `GET /fleet/alarms`, read on its own: a controller without the route (404) or an answer that
    /// does not decode leaves only `host-alarms` undetermined, never clear, and never blinds the other reads. A failure
    /// that says the controller is down is caught by [#answered], which turns the round into `controller-down`.
    private static Promise<Evaluation> hostAlarmsOf(Reads reads) {
        return reads.api()
                    .fleetAlarms()
                    .map(view -> Evaluation.decided(Conditions.hostAlarms(view,
                                                                          reads.alarmsRoute())))
                    .recover(Evaluation::undetermined);
    }

    /// `/maintenance` only when a dump code is evaluated: a controller without the route is then not blindness.
    private static Promise<Option<MaintenanceView>> maintenanceOf(Reads reads) {
        return reads.readsMaintenance()
               ? reads.api()
                      .maintenance()
                      .map(Option::some)
               : Promise.success(Option.none());
    }

    private static Evaluation answered(Option<MaintenanceView> maintenance,
                                       List<HostView> hosts,
                                       AlarmConfig config,
                                       Instant now) {
        return Evaluation.decided(Condition.clear(AlarmCode.CONTROLLER_DOWN))
                         .and(maintenance.map(view -> dumpConditions(view, config, now)).or(Evaluation::decided))
                         .and(Evaluation.decided(Conditions.workerLost(hosts)));
    }

    private static Evaluation dumpConditions(MaintenanceView maintenance, AlarmConfig config, Instant now) {
        return Evaluation.decided(Conditions.dumpFailed(maintenance.latestDump()),
                                  Conditions.dumpStale(maintenance.lastGoodDumpEndedAt(), now, config.staleAfter()));
    }

    /// Either read (or both, as a composite) failing to get an answer, or getting a 5xx.
    private static boolean down(Cause cause) {
        return cause.stream()
                    .anyMatch(AlarmCheck::unanswering);
    }

    private static boolean unanswering(Cause cause) {
        return switch (cause) {
            case CliError.Unreachable _, CoreError.Timeout _ -> true;
            case CliError.UnexpectedStatus status -> status.status() >= 500;
            default -> false;
        };
    }

    private static Result<FreeSpace> freeSpace(Path path) {
        return Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(path.toString(), Causes.fromThrowable(thrown)),
                           () -> FreeSpace.freeSpace(path,
                                                     Files.getFileStore(path).getUsableSpace()));
    }

    record unused() implements AlarmCheck {}
}
