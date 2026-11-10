package org.pragmatica.temper.worker.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.hold.GrantLine;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.HolderStatusDto;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.HolderProbe;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRefs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// The startup scan (m0-design §7.2 step 3): every `jobs/<job>/units/<unit>` directory becomes a [DiskUnitDto]
/// for `Hello` (marker present, `rc` contents, container state), which the controller reconciles (spec §6.4):
/// rc → collect; live container → re-attach; neither → requeue if never started (Q-11), else `ERROR(lost)`.
/// Directories whose names do not parse as a job id and unit name are not temper's and are skipped.
///
/// A hold unit (m1-design §1.5) also reports its holder (`WAITING`, `HELD`, `DEAD`) and the ticket of the
/// `granted` line in `bbhold.err`, which split reconcile rules 3-5. The holder is read BEFORE the container (a
/// holder exits because its container did, so this order never pairs a dead holder with a container that was
/// still running a moment before). A dead holder whose container is absent is inspected a second time after
/// `absentRecheck`, which covers a `docker run` child that outlived bb-hold by a moment.
public record StateScan(WorkerPaths paths,
                        UnitFiles files,
                        DockerCli docker,
                        HolderProbe holders,
                        TimeSpan absentRecheck) {
    private static final Logger LOG = LoggerFactory.getLogger(StateScan.class);

    public static StateScan stateScan(WorkerPaths paths,
                                      UnitFiles files,
                                      DockerCli docker,
                                      HolderProbe holders,
                                      TimeSpan absentRecheck) {
        return new StateScan(paths, files, docker, holders, absentRecheck);
    }

    public Promise<List<DiskUnitDto>> scan() {
        return unitRefs(paths.jobsDir()).mapError(origin -> WorkerError.Io.FACTORY.apply("scan " + paths.jobsDir(),
                                                                                         origin))
                       .async()
                       .flatMap(this::diskUnits);
    }

    /// Fork-Join over the units: each one needs its own `docker inspect`.
    private Promise<List<DiskUnitDto>> diskUnits(List<UnitRef> refs) {
        return Promise.allOf(refs.stream().map(this::diskUnit).toList()).flatMap(StateScan::allSucceeded);
    }

    private static Promise<List<DiskUnitDto>> allSucceeded(List<Result<DiskUnitDto>> results) {
        return Result.allOf(results).async();
    }

    private Promise<DiskUnitDto> diskUnit(UnitRef ref) {
        var holder = holder(ref);

        return docker.status(ref.containerName())
                     .flatMap(status -> rechecked(ref, holder, status))
                     .flatMap(status -> labelled(ref, status))
                     .map(labelled -> DiskUnitDto.diskUnitDto(ref.job().value(),
                                                              ref.unit().value(),
                                                              files.exists(ref, UnitFile.MARKER),
                                                              rc(ref),
                                                              labelled.status(),
                                                              holder.map(HolderStatusDto::from),
                                                              ticket(ref),
                                                              labelled.instance(),
                                                              attempt(ref)));
    }

    /// A container's status and, when it runs, its `temper.instance` label (F2: reconcile ignores another
    /// controller instance's running containers instead of killing them as orphans).
    record Labelled(ContainerStatus status, Option<String> instance) {}

    private Promise<Labelled> labelled(UnitRef ref, ContainerStatus status) {
        return status == ContainerStatus.RUNNING
               ? docker.instanceLabel(ref.containerName())
                       .map(instance -> new Labelled(status, instance))
               : Promise.success(new Labelled(status, none()));
    }

    private Promise<ContainerStatus> rechecked(UnitRef ref, Option<HolderStatus> holder, ContainerStatus status) {
        return status == ContainerStatus.ABSENT && holder.filter(HolderStatus.DEAD::equals)
                                                         .isPresent()
               ? Promise.<Unit> promise(absentRecheck, Result::unitResult).flatMap(_ -> docker.status(ref.containerName()))
               : Promise.success(status);
    }

    /// FER (degrade forward): an unreadable holder (a corrupt `bbhold.pid`) is reported `DEAD` and logged: the unit
    /// cannot be identified as waiting, so reconcile treats it by its `granted` line (requeue, or lost), and the
    /// worker still starts.
    private Option<HolderStatus> holder(UnitRef ref) {
        return holders.status(ref)
                      .onFailure(cause -> LOG.error("unit {}: unreadable holder: {}",
                                                    ref.containerName(),
                                                    cause))
                      .or(some(HolderStatus.DEAD));
    }

    /// FER (degrade forward): an unreadable `bbhold.err` reports no ticket and is logged; reconcile then treats the
    /// unit as never granted, and the same read fails visibly on the watcher's next look.
    private Option<String> ticket(UnitRef ref) {
        return files.text(ref, UnitFile.BBHOLD_ERR)
                    .onFailure(cause -> LOG.error("unit {}: unreadable bbhold.err: {}",
                                                  ref.containerName(),
                                                  cause))
                    .map(text -> text.flatMap(GrantLine::ticket))
                    .or(none());
    }

    /// The unit's `.temper/attempt` (protocol v3, m4-design §7); none for a directory written before M4.
    ///
    /// FER (degrade forward): a corrupt `attempt` is reported as absent and logged (as a corrupt [#rc]), so one damaged
    /// file cannot keep the worker from starting; the controller then reads the unit as attempt 1. The worker's
    /// requests read the file the same way (`Router`): an unreadable `attempt` names no attempt, the container's
    /// `temper.attempt` label decides, and a `DiscardUnit` of an unlabelled unit removes it.
    private Option<Integer> attempt(UnitRef ref) {
        return files.integer(ref, UnitFile.ATTEMPT)
                    .onFailure(cause -> LOG.error("unit {}: unreadable attempt: {}",
                                                  ref.containerName(),
                                                  cause))
                    .or(none());
    }

    /// FER (degrade forward): a corrupt `rc` is reported as absent and logged, so one damaged file cannot keep
    /// the worker from starting; the controller then collects or declares the unit lost, and `Collect` fails
    /// visibly on the same file.
    private Option<Integer> rc(UnitRef ref) {
        return files.integer(ref, UnitFile.RC)
                    .onFailure(cause -> LOG.error("unit {}: unreadable rc: {}",
                                                  ref.containerName(),
                                                  cause))
                    .or(none());
    }

    /// `jobs/<job>/units/<unit>` directories that parse as a [UnitRef]; none when `jobs/` does not exist yet.
    static Result<List<UnitRef>> unitRefs(Path jobsDir) {
        return FileOps.isDirectory(jobsDir)
               ? FileOps.list(jobsDir).map(StateScan::unitsOfJobs)
               : success(List.of());
    }

    private static List<UnitRef> unitsOfJobs(List<Path> jobs) {
        return jobs.stream()
                   .filter(Files::isDirectory)
                   .flatMap(StateScan::unitsOf)
                   .toList();
    }

    /// FER (degrade forward): a directory that is not a job (non-numeric name) or has no readable `units/` holds
    /// no unit temper started, so it contributes nothing to the scan.
    private static Stream<UnitRef> unitsOf(Path jobDir) {
        return Number.parseLong(nameOf(jobDir))
                     .flatMap(job -> unitsUnder(jobDir, job))
                     .or(List.of())
                     .stream();
    }

    private static Result<List<UnitRef>> unitsUnder(Path jobDir, long job) {
        return FileOps.list(jobDir.resolve("units")).map(units -> refs(job, units));
    }

    private static List<UnitRef> refs(long job, List<Path> units) {
        return units.stream()
                    .filter(Files::isDirectory)
                    .map(unit -> UnitRefs.unitRef(job,
                                                  nameOf(unit)))
                    .flatMap(Result::stream)
                    .toList();
    }

    private static String nameOf(Path path) {
        return option(path.getFileName()).map(Path::toString)
                     .or("");
    }
}
