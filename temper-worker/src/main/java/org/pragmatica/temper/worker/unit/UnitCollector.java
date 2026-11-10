package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.facts.UnitEndFacts;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.manifest.ManifestCollector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.utils.Causes.cause;


/// A unit's exit processing (m0-design §7.4 "On container exit"), idempotent across `Collect`s and workers:
///
/// 1. `rc` (first: it is the re-attach discriminator), then `end`;
/// 2. in parallel: the `log` (and `log.tail`, its last 1 MiB), report collection, the Testcontainers reap, end
///    facts, and the manifest (a unit with a `ManifestSpec`, m1-design §2.5);
/// 3. `facts-end.json` (the end facts as sent, Testcontainers leftovers and the lowest sampled `MemAvailable`
///    included), then `exited.json` (the `UnitExited` as sent, with `voidReason` from `.temper/void`), then the
///    messages.
///
/// When `exited.json` already exists the exit was processed before: the same `UnitExited` is resent, with the
/// rows re-parsed from the copies in `reports/` and the manifest from `manifest/manifest.json`, so a lost ack
/// never changes what the controller receives.
public record UnitCollector(WorkerPaths paths,
                            UnitFiles files,
                            DockerCli docker,
                            ReportCollector reports,
                            TestcontainersReap reap,
                            FactsProbe facts,
                            CgroupSampler cgroups,
                            ManifestCollector manifests,
                            JsonMapper mapper,
                            InstantSource clock) {
    private static final Logger LOG = LoggerFactory.getLogger(UnitCollector.class);

    public static UnitCollector unitCollector(WorkerPaths paths,
                                              UnitFiles files,
                                              DockerCli docker,
                                              ReportCollector reports,
                                              TestcontainersReap reap,
                                              FactsProbe facts,
                                              CgroupSampler cgroups,
                                              ManifestCollector manifests,
                                              JsonMapper mapper,
                                              InstantSource clock) {
        return new UnitCollector(paths, files, docker, reports, reap, facts, cgroups, manifests, mapper, clock);
    }

    /// Processes (or reproduces) the unit's exit. `waitedRc` is the rc `docker wait` returned, when it did.
    public Promise<Collected> collect(UnitRef ref, Option<Integer> waitedRc) {
        return files.exists(ref, UnitFile.EXITED)
               ? resent(ref)
               : processed(ref, waitedRc);
    }

    // ---- resend --------------------------------------------------------------------------------------------
    private Promise<Collected> resent(UnitRef ref) {
        return Result.all(recordedExit(ref),
                          manifests.recorded(ref))
                     .map(Recorded::recorded)
                     .async()
                     .mapWith(_ -> reports.parseCopied(ref),
                              (recorded, outcome) -> Collected.collected(recorded.exited(),
                                                                         manifestChunks(ref,
                                                                                        recorded.exited().attempt(),
                                                                                        recorded.manifest()),
                                                                         outcome.chunks(ref,
                                                                                        recorded.exited().attempt())));
    }

    /// The manifest and report chunks carry the attempt of the `UnitExited` they follow (protocol v3).
    private static List<Message.UnitManifest> manifestChunks(UnitRef ref,
                                                             int attempt,
                                                             Option<ManifestOutcomeDto> manifest) {
        return manifest.map(outcome -> Message.UnitManifest.unitManifests(ref.job().value(),
                                                                          ref.unit().value(),
                                                                          attempt,
                                                                          outcome))
                       .or(List.of());
    }

    private Result<Message.UnitExited> recordedExit(UnitRef ref) {
        var file = files.path(ref, UnitFile.EXITED);

        return FileOps.readString(file)
                      .flatMap(this::message)
                      .flatMap(message -> unitExited(file, message))
                      .mapError(origin -> WorkerError.CorruptState.FACTORY.apply(file, origin));
    }

    private Result<Message> message(String json) {
        return mapper.readString(json, Message.class);
    }

    private static Result<Message.UnitExited> unitExited(Path file, Message message) {
        return message instanceof Message.UnitExited exited
               ? success(exited)
               : cause("not a UnitExited: " + file).result();
    }

    // ---- first processing ----------------------------------------------------------------------------------
    private Promise<Collected> processed(UnitRef ref, Option<Integer> waitedRc) {
        return container(ref).flatMap(container -> exitRecorded(ref, container, waitedRc))
                        .flatMap(exit -> gathered(ref, exit))
                        .flatMap(gathered -> assembled(ref, gathered));
    }

    private Promise<String> container(UnitRef ref) {
        return files.container(ref)
                    .async();
    }

    /// Step 1: `rc` then `end`, each written once (a recorded value always wins over a newer reading).
    private Promise<ExitRecord> exitRecorded(UnitRef ref, String container, Option<Integer> waitedRc) {
        return rc(ref, container, waitedRc).flatMap(rc -> persisted(ref, container, rc).async());
    }

    private Result<ExitRecord> persisted(UnitRef ref, String container, int rc) {
        return files.storeOnce(ref,
                               UnitFile.RC,
                               Integer.toString(rc))
                    .flatMap(_ -> endWritten(ref))
                    .flatMap(_ -> recorded(ref, container));
    }

    private Promise<Integer> rc(UnitRef ref, String container, Option<Integer> waitedRc) {
        return files.integer(ref, UnitFile.RC)
                    .async()
                    .flatMap(recorded -> recordedOrInspected(recorded.orElse(waitedRc),
                                                             container));
    }

    private Promise<Integer> recordedOrInspected(Option<Integer> known, String container) {
        return known.map(Promise::success)
                    .or(() -> docker.exitCode(container));
    }

    private Result<Boolean> endWritten(UnitRef ref) {
        return EpochNanos.epochNanos(clock.instant()).flatMap(now -> files.storeOnce(ref,
                                                                                     UnitFile.END,
                                                                                     Long.toString(now)));
    }

    private Result<ExitRecord> recorded(UnitRef ref, String container) {
        return Result.all(files.integer(ref, UnitFile.RC).flatMap(rc -> rc.toResult(missing(ref, UnitFile.RC))),
                          files.nanos(ref, UnitFile.END).flatMap(end -> end.toResult(missing(ref, UnitFile.END))))
                     .map((rc, end) -> ExitRecord.exitRecord(container,
                                                             rc,
                                                             EpochNanos.instant(end)));
    }

    /// Step 2 (Fork-Join): the five reads are independent of each other (the manifest reads the compiled classes
    /// and the effective POM, the reports the XML; neither writes what the other reads).
    private Promise<Gathered> gathered(UnitRef ref, ExitRecord exit) {
        return Promise.all(logged(ref,
                                  exit.container()),
                           reports.collect(ref),
                           reap.await(ref),
                           facts.probe(),
                           manifests.collect(ref))
                      .map((_, outcome, leftovers, host, manifest) -> Gathered.gathered(exit,
                                                                                        outcome,
                                                                                        leftovers,
                                                                                        host,
                                                                                        manifest));
    }

    /// FER (degrade forward): the log is evidence, not an input to the verdict. A failure to stream or cap it is
    /// logged and the unit's report still goes out; `LogTail` then falls back to `docker logs` until the
    /// container is removed.
    private Promise<Unit> logged(UnitRef ref, String container) {
        var log = paths.logFile(ref);
        var partial = log.resolveSibling("log.tmp");
        var tail = paths.logTail(ref);

        return docker.logsTo(container, partial)
                     .flatMap(_ -> published(partial, log, tail).async())
                     .onFailure(cause -> LOG.warn("unit {}: log not written: {}",
                                                  ref.containerName(),
                                                  cause))
                     .recover(_ -> unit());
    }

    /// The capped `log`, then `log.tail` (its last 1 MiB, so the small evidence set needs no `tail` in bash).
    private static Result<Unit> published(Path partial, Path log, Path tail) {
        return LogFiles.keepTail(partial, LogFiles.CAP_BYTES)
                       .flatMap(_ -> FileOps.moveAtomic(partial, log))
                       .flatMap(_ -> LogFiles.copyTail(log, tail, LogFiles.TAIL_FILE_BYTES));
    }

    /// Step 3: end facts and the message, both recorded before anything is sent.
    private Promise<Collected> assembled(UnitRef ref, Gathered gathered) {
        return memAvailableMinKb(ref).map(memMin -> endFacts(gathered.host(),
                                                             gathered.exit(),
                                                             cgroups.lastSample(gathered.exit().container()),
                                                             gathered.leftovers(),
                                                             memMin))
                                .flatMap(endFacts -> exitedWritten(ref, gathered, endFacts))
                                .map(exited -> Collected.collected(exited,
                                                                   manifestChunks(ref,
                                                                                  exited.attempt(),
                                                                                  gathered.manifest()),
                                                                   gathered.outcome().chunks(ref,
                                                                                             exited.attempt())))
                                .async();
    }

    private Result<Message.UnitExited> exitedWritten(UnitRef ref, Gathered gathered, UnitEndFactsDto endFacts) {
        return Result.all(files.attempt(ref),
                          killedBy(ref),
                          threadDumps(ref),
                          voidReason(ref))
                     .map((attempt, killed, dumps, voided) -> exited(ref,
                                                                     attempt,
                                                                     gathered,
                                                                     endFacts,
                                                                     killed,
                                                                     dumps,
                                                                     voided))
                     .flatMap(exited -> evidenceWritten(ref, endFacts, exited));
    }

    /// The lowest host `MemAvailable` the heartbeat sampled while the unit ran; none when it never sampled.
    private Result<Option<Long>> memAvailableMinKb(UnitRef ref) {
        return files.count(ref, UnitFile.MEM_AVAILABLE_MIN);
    }

    /// `.temper/void` as the wire enum; an unknown value is ignored rather than guessed (as [#killCause]).
    private Result<Option<VoidReason>> voidReason(UnitRef ref) {
        return files.text(ref, UnitFile.VOID)
                    .map(UnitCollector::voidCause);
    }

    private static Option<VoidReason> voidCause(Option<String> recorded) {
        return recorded.map(String::trim)
                       .flatMap(UnitCollector::voidCause);
    }

    static Option<VoidReason> voidCause(String recorded) {
        return Option.from(Arrays.stream(VoidReason.values())
                                 .filter(reason -> reason.wire()
                                                         .equals(recorded))
                                 .findFirst());
    }

    private static UnitEndFactsDto endFacts(HostFacts host,
                                            ExitRecord exit,
                                            Option<CgroupSampler.CgroupSample> sample,
                                            TcLeftovers leftovers,
                                            Option<Long> memAvailableMinKb) {
        return UnitEndFactsDto.unitEndFactsDto(UnitEndFacts.unitEndFacts(host,
                                                                         sample.flatMap(CgroupSampler.CgroupSample::memoryPeakBytes),
                                                                         sample.map(CgroupSampler.CgroupSample::at)
                                                                               .or(exit.endedAt()),
                                                                         sample.map(CgroupSampler.CgroupSample::cpuStat)
                                                                               .or(Map.of()),
                                                                         leftovers,
                                                                         memAvailableMinKb));
    }

    private static Message.UnitExited exited(UnitRef ref,
                                             int attempt,
                                             Gathered gathered,
                                             UnitEndFactsDto endFacts,
                                             Option<KilledBy> killedBy,
                                             int threadDumps,
                                             Option<VoidReason> voidReason) {
        return new Message.UnitExited(ref.job().value(),
                                      ref.unit().value(),
                                      attempt,
                                      gathered.exit().rc(),
                                      killedBy,
                                      gathered.exit().endedAt(),
                                      endFacts,
                                      gathered.outcome().reportFiles(),
                                      gathered.outcome().testsAttr(),
                                      threadDumps,
                                      gathered.outcome().errorReason(),
                                      voidReason);
    }

    private Result<Message.UnitExited> evidenceWritten(UnitRef ref,
                                                       UnitEndFactsDto endFacts,
                                                       Message.UnitExited exited) {
        return stored(ref, UnitFile.FACTS_END, endFacts).flatMap(_ -> stored(ref, UnitFile.EXITED, exited))
                     .map(_ -> exited);
    }

    private Result<Unit> stored(UnitRef ref, UnitFile file, Object value) {
        return mapper.writeAsString(value)
                     .flatMap(json -> files.store(ref, file, json));
    }

    /// `killed` as the wire enum; an unknown value is ignored rather than guessed.
    private Result<Option<KilledBy>> killedBy(UnitRef ref) {
        return files.text(ref, UnitFile.KILLED)
                    .map(UnitCollector::killCause);
    }

    private static Option<KilledBy> killCause(Option<String> recorded) {
        return recorded.map(String::trim)
                       .flatMap(UnitCollector::killCause);
    }

    static Option<KilledBy> killCause(String recorded) {
        return switch (recorded) {
            case UnitTimer.TIMEOUT -> some(KilledBy.TIMEOUT);
            case UnitCanceller.CANCEL -> some(KilledBy.CANCEL);
            default -> none();
        };
    }

    /// The number of successful dumps (`threaddumps/*.txt`).
    private Result<Integer> threadDumps(UnitRef ref) {
        var directory = paths.threadDumpsDir(ref);

        return FileOps.isDirectory(directory)
               ? FileOps.list(directory).map(UnitCollector::dumpCount)
               : success(0);
    }

    private static int dumpCount(List<Path> entries) {
        return (int) entries.stream()
                            .map(Path::toString)
                            .filter(name -> name.endsWith(".txt"))
                            .count();
    }

    private WorkerError.MissingState missing(UnitRef ref, UnitFile file) {
        return WorkerError.MissingState.FACTORY.apply(files.path(ref, file));
    }

    /// The recorded exit: container, rc and end time.
    record ExitRecord(String container, int rc, Instant endedAt) {
        static ExitRecord exitRecord(String container, int rc, Instant endedAt) {
            return new ExitRecord(container, rc, endedAt);
        }
    }

    /// Step 2's facts, gathered on top of the exit record.
    record Gathered(ExitRecord exit,
                    ReportOutcome outcome,
                    TcLeftovers leftovers,
                    HostFacts host,
                    Option<ManifestOutcomeDto> manifest) {
        static Gathered gathered(ExitRecord exit,
                                 ReportOutcome outcome,
                                 TcLeftovers leftovers,
                                 HostFacts host,
                                 Option<ManifestOutcomeDto> manifest) {
            return new Gathered(exit, outcome, leftovers, host, manifest);
        }
    }

    /// An exit processed before: the `UnitExited` and the manifest outcome as sent.
    record Recorded(Message.UnitExited exited, Option<ManifestOutcomeDto> manifest) {
        static Recorded recorded(Message.UnitExited exited, Option<ManifestOutcomeDto> manifest) {
            return new Recorded(exited, manifest);
        }
    }
}
