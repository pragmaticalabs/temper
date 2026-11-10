package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.function.Supplier;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.refusal.Refusals;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.manifest.PomProbe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;


/// `StartUnit` (m0-design §7.4, m1-design §1.2, §2.3). The reply is always a message: [Message.UnitStarted],
/// [Message.UnitHolding] or [Message.UnitStartFailed]; a start never fails as a request.
///
/// 1. The attempt guard comes first (m4-design §4.4, F3), before anything this process remembers of the unit: only
///    a container whose `temper.attempt` label is the request's attempt is adopted, and only a unit directory holding
///    it (or none) is reused ([AttemptVerdict]). What a lower attempt left (an unlabelled container, like a unit
///    directory without `attempt`, is a pre-M4 unit: attempt 1, as V11's backfill) is removed with its network and
///    unit directory ([UnitDiscard]), together with everything the registry holds of it, logged, and the start
///    proceeds; a higher attempt is refused `ERROR(infra: stale-attempt)` and left untouched.
/// 2. Then a cancel of this attempt ends it `CANCELLED` (a `CancelUnit` raced ahead of the start), and a retried
///    `StartUnit` after a link drop never starts the unit twice: a unit still waiting in bb-hold (its `GrantWatcher`
///    runs here, or its identified holder is alive) is answered `UnitHolding` again; a container that already
///    carries the unit's name and the request's attempt is adopted, the reply rebuilt from the unit files.
/// 3. R-DISK: free space below the floor on the docker root or the workspace filesystem ends `VOID(disk)` and
///    `docker run` never happens.
/// 4. Prepare (runner image, `args`/`timeout`/`manifest-spec.json`, per-unit network and gateway, or the host
///    network for a Gate unit, [UnitNetwork]); the effective-POM probe when the unit has a `ManifestSpec` (before
///    the marker and before any hold, Q-M1-9); record evidence (`facts-start`, Testcontainers session snapshot,
///    `marker`); launch.
/// 5. A direct launch: record (`container`, `start`, `image`, `network`), arm (timer and exit watcher), reply
///    `UnitStarted`. A hold launch (`launched → recorded → armed` becomes `launched → holding`): record `image` and
///    `network`, reply `UnitHolding` with the `GrantWatcher`'s watch ([StartReply]), which the link starts once the
///    reply is written; the unsolicited `UnitStarted` or `UnitStartFailed` follows from the watcher.
///
/// Any failure in step 4 removes the half-made containers (unit and probe) and network, signals a holder that was
/// already spawned (each compensation on its own, so one that fails never skips the others), and ends `ERROR`:
/// `manifest: effective-pom …` when the probe failed (Q-M1-7), else `infra: <detail>`, an infrastructure failure
/// before mvn ran (spec §5.3). A cancel that lands after the re-check
/// just before the launch is honoured by killing the fresh container (`killedBy=cancel`), or by the watcher.
public record UnitStarter(WorkerConfig config,
                          UnitFiles files,
                          DockerCli docker,
                          RunnerImage runnerImage,
                          UnitLauncher launcher,
                          FactsProbe facts,
                          DiskSpace diskSpace,
                          TestcontainersReap reap,
                          UnitRegistry registry,
                          UnitTimer timer,
                          UnitWatcher watcher,
                          PomProbe pomProbe,
                          GrantWatcher grants,
                          HolderProbe holders,
                          JsonMapper mapper,
                          InstantSource clock) {
    private static final Logger LOG = LoggerFactory.getLogger(UnitStarter.class);
    public static final String DISK = "disk";
    /// The attempt of a container that carries no `temper.attempt` label (started before M4, m4-design §4.4), or one
    /// that does not parse: a pre-M4 unit, attempt 1, as a unit directory without `attempt` (V11 backfills 1).
    static final int UNLABELLED = UnitFiles.PRE_M4_ATTEMPT;

    public static UnitStarter unitStarter(WorkerConfig config,
                                          UnitFiles files,
                                          DockerCli docker,
                                          RunnerImage runnerImage,
                                          UnitLauncher launcher,
                                          FactsProbe facts,
                                          DiskSpace diskSpace,
                                          TestcontainersReap reap,
                                          UnitRegistry registry,
                                          UnitTimer timer,
                                          UnitWatcher watcher,
                                          PomProbe pomProbe,
                                          GrantWatcher grants,
                                          HolderProbe holders,
                                          JsonMapper mapper,
                                          InstantSource clock) {
        return new UnitStarter(config,
                               files,
                               docker,
                               runnerImage,
                               launcher,
                               facts,
                               diskSpace,
                               reap,
                               registry,
                               timer,
                               watcher,
                               pomProbe,
                               grants,
                               holders,
                               mapper,
                               clock);
    }

    /// Free bytes on the docker root and the workspace filesystem (R-DISK).
    @FunctionalInterface
    public interface DiskSpace {
        Promise<FreeSpace> free();

        static DiskSpace hostDiskSpace(Path dockerRoot, Path workspaceRoot) {
            return () -> freeOn(dockerRoot, workspaceRoot);
        }

        private static Promise<FreeSpace> freeOn(Path dockerRoot, Path workspaceRoot) {
            return Promise.all(FactsProbe.usableSpace(dockerRoot),
                               FactsProbe.usableSpace(workspaceRoot))
                          .map(FreeSpace::freeSpace);
        }
    }

    public record FreeSpace(long dockerRoot, long workspace) {
        public static FreeSpace freeSpace(long dockerRoot, long workspace) {
            return new FreeSpace(dockerRoot, workspace);
        }
    }

    /// What an attempt of a unit leaves on this host, removed: the adoption guard's and the `DiscardUnit` handler's.
    public UnitDiscard unitDiscard() {
        return UnitDiscard.unitDiscard(files, docker, holders, pomProbe, registry);
    }

    /// A retried `StartUnit` for a unit whose start is still in flight joins that start and gets the same reply. A
    /// start takes the unit's turn, so it never interleaves with a `DiscardUnit` of the unit ([UnitRegistry#inTurn]).
    public Promise<StartReply> start(StartRequest request) {
        return registry.start(request.ref(),
                              request.attempt(),
                              () -> registry.inTurn(request.ref(), () -> adoptedOrStarted(request)));
    }

    // ---- step 2: adopt an existing container ---------------------------------------------------------------
    private Promise<StartReply> adoptedOrStarted(StartRequest request) {
        var name = request.ref().containerName();

        return docker.containerId(name)
                     .flatMap(existing -> judged(request, existing))
                     .fold(result -> beforeCreation(request, result));
    }

    /// A failure before this start created anything (docker unreachable, the disk probe failing) has nothing to
    /// compensate: it is logged and becomes the protocol's `ERROR(infra: …)` reply, which is how a start reports
    /// failure. Every later failure was already turned into a reply by its own branch.
    private Promise<StartReply> beforeCreation(StartRequest request, Result<StartReply> result) {
        return result.fold(cause -> Promise.success(StartReply.startReply(failedEarly(request, cause))),
                           Promise::success);
    }

    private Message failedEarly(StartRequest request, Cause cause) {
        LOG.error("unit {}: start failed before anything was created: {}",
                  request.ref().containerName(),
                  cause);

        return failed(request, StartFailureKind.ERROR, infra(cause));
    }

    /// The attempt guard: the existing container's label, else the unit directory's attempt.
    private Promise<StartReply> judged(StartRequest request, Option<String> existing) {
        return existing.map(id -> adoptedOrReplaced(request, id))
                       .or(() -> heldOrNew(request));
    }

    /// Past the guard, this attempt's own state decides: a cancel of it ends the start `CANCELLED`; a `GrantWatcher` of
    /// it running here makes the unit the watcher's to report, container or not; otherwise `next`.
    private Promise<StartReply> admitted(StartRequest request, Supplier<Promise<StartReply>> next) {
        return registry.isCancelled(request.ref(), request.attempt())
               ? Promise.success(StartReply.startReply(failed(request, StartFailureKind.CANCELLED, "cancelled")))
               : holdingOr(request, next);
    }

    private Promise<StartReply> holdingOr(StartRequest request, Supplier<Promise<StartReply>> next) {
        return registry.isHolding(request.ref(), request.attempt())
               ? stillHolding(request).map(StartReply::startReply)
               : next.get();
    }

    /// The adoption guard (m4-design §4.4): the container's `temper.attempt` label against the request's attempt.
    private Promise<StartReply> adoptedOrReplaced(StartRequest request, String containerId) {
        return docker.attemptLabel(request.ref().containerName())
                     .map(label -> label.or(UNLABELLED))
                     .flatMap(held -> containerVerdict(request, containerId, held));
    }

    private Promise<StartReply> containerVerdict(StartRequest request, String containerId, int held) {
        return switch (AttemptVerdict.verdict(request.attempt(), held)) {
            case ADOPT -> admitted(request, () -> adoptedOrCleaned(request, containerId));
            case REPLACE -> replaced(request, held).flatMap(_ -> heldOrNew(request));
            case REFUSE -> Promise.success(StartReply.startReply(staleRefused(request, held)));
        };
    }

    /// No container: a unit directory holding another attempt (its `.temper/attempt`, or a pre-M4 marker) is judged
    /// the same way; a directory holding none is a fresh start.
    private Promise<StartReply> heldOrNew(StartRequest request) {
        return files.heldAttempt(request.ref())
                    .async()
                    .flatMap(held -> directoryChecked(request, held));
    }

    private Promise<StartReply> directoryChecked(StartRequest request, Option<Integer> held) {
        return held.map(attempt -> directoryVerdict(request, attempt))
                   .or(() -> admitted(request,
                                      () -> holderChecked(request)));
    }

    private Promise<StartReply> directoryVerdict(StartRequest request, int held) {
        return switch (AttemptVerdict.verdict(request.attempt(), held)) {
            case ADOPT -> admitted(request, () -> holderChecked(request));
            case REPLACE -> replaced(request, held).flatMap(_ -> admitted(request, () -> holderChecked(request)));
            case REFUSE -> Promise.success(StartReply.startReply(staleRefused(request, held)));
        };
    }

    /// A superseded attempt's leftovers are removed before the start proceeds; a removal that fails fails the start
    /// before it created anything (`ERROR(infra: …)`), and the next `StartUnit` judges the leftovers again. The
    /// registry forgets the old attempt first (its timer, watch, hold and cancel), so its watcher, woken by the
    /// container's removal, finds it no longer owns the unit and writes and sends nothing ([UnitWatcher]); an exit
    /// processing it had already started is waited for, so nothing of it lands in the next attempt's directory.
    private Promise<Unit> replaced(StartRequest request, int held) {
        LOG.warn("unit {}: attempt {} is superseded by attempt {}: removing its container, network and unit directory",
                 request.ref().containerName(),
                 held,
                 request.attempt());

        return unitDiscard().discard(request.ref(), held);
    }

    /// A higher attempt than the request's is on this host: only a restored database can send this, and the unit
    /// is not killed blind.
    private static Message staleRefused(StartRequest request, int held) {
        LOG.warn("unit {}: this host holds attempt {}, the request is for attempt {}: refused",
                 request.ref().containerName(),
                 held,
                 request.attempt());

        return failed(request, StartFailureKind.ERROR, "infra: " + WorkerError.STALE_ATTEMPT);
    }

    /// A live holder of this unit (a previous worker's, or this one's before its watcher was re-armed) is waiting
    /// for the port group already: watch it again and answer `UnitHolding`, never a second bb-hold (which bb-hold
    /// would refuse as a duplicate anyway).
    private Promise<StartReply> holderChecked(StartRequest request) {
        return holders.status(request.ref())
                      .async()
                      .flatMap(holder -> heldOrNew(request, holder));
    }

    private Promise<StartReply> heldOrNew(StartRequest request, Option<HolderStatus> holder) {
        return isAlive(holder)
               ? rewatched(request)
               : diskChecked(request);
    }

    private static boolean isAlive(Option<HolderStatus> holder) {
        return holder.filter(status -> status != HolderStatus.DEAD)
                     .isPresent();
    }

    /// The watch starts once this `UnitHolding` is written ([StartReply]).
    private Promise<StartReply> rewatched(StartRequest request) {
        return stillHolding(request).map(holding -> StartReply.startReply(holding,
                                                                          GrantWatcher.Watch.watch(request.ref(),
                                                                                                   request.attempt(),
                                                                                                   request.timeout())));
    }

    /// `UnitHolding` rebuilt from the marker and `facts-start.json`.
    private Promise<Message> stillHolding(StartRequest request) {
        var ref = request.ref();

        return Result.all(files.nanos(ref, UnitFile.MARKER)
                               .flatMap(marker -> marker.toResult(WorkerError.General.NO_MARKER)),
                          startFacts(ref))
                     .map((marker, facts) -> unitHolding(ref,
                                                         request.attempt(),
                                                         marker,
                                                         facts))
                     .async();
    }

    private Result<HostFactsDto> startFacts(UnitRef ref) {
        return files.text(ref, UnitFile.FACTS_START)
                    .flatMap(text -> text.toResult(WorkerError.General.NO_MARKER))
                    .flatMap(this::hostFactsOf);
    }

    private Result<HostFactsDto> hostFactsOf(String json) {
        return mapper.readString(json, HostFactsDto.class);
    }

    private Promise<StartReply> adoptedOrCleaned(StartRequest request, String containerId) {
        return adopted(request, containerId).map(StartReply::startReply)
                      .fold(result -> orCleanedUp(request, result));
    }

    private Promise<Message> adopted(StartRequest request, String containerId) {
        var ref = request.ref();

        return adoptedStart(ref, containerId).async()
                           .mapWith(_ -> facts.probe(),
                                    (startOf, host) -> Started.started(containerId,
                                                                       startOf.marker(),
                                                                       startOf.start(),
                                                                       HostFactsDto.hostFactsDto(host)))
                           .flatMap(started -> armed(request, started));
    }

    private Result<StartOf> adoptedStart(UnitRef ref, String containerId) {
        return files.nanos(ref, UnitFile.MARKER)
                    .flatMap(marker -> marker.toResult(WorkerError.General.NO_MARKER))
                    .flatMap(marker -> adoptionRecorded(ref, containerId, marker));
    }

    /// The adopted container's id and start (the marker stands in when `start` was never written).
    private Result<StartOf> adoptionRecorded(UnitRef ref, String containerId, long marker) {
        return files.storeOnce(ref, UnitFile.CONTAINER, containerId)
                    .flatMap(_ -> files.storeOnce(ref,
                                                  UnitFile.START,
                                                  Long.toString(marker)))
                    .flatMap(_ -> files.nanos(ref, UnitFile.START))
                    .map(start -> StartOf.startOf(marker,
                                                  start.or(marker)));
    }

    // ---- step 3: R-DISK ------------------------------------------------------------------------------------
    private Promise<StartReply> diskChecked(StartRequest request) {
        return diskSpace.free()
                        .flatMap(free -> afterDiskCheck(request, free));
    }

    private Promise<StartReply> afterDiskCheck(StartRequest request, FreeSpace free) {
        return Refusals.disk(free.dockerRoot(),
                             free.workspace(),
                             config.diskFloorBytes())
                       .fold(refusal -> Promise.success(StartReply.startReply(diskRefused(request, refusal))),
                             _ -> launchedOrCleaned(request));
    }

    private Message diskRefused(StartRequest request, Cause refusal) {
        LOG.warn("unit {}: {}",
                 request.ref().containerName(),
                 refusal.message());

        return failed(request, StartFailureKind.VOID, DISK);
    }

    // ---- step 4: prepare, evidence, launch, record, arm ----------------------------------------------------
    private Promise<StartReply> launchedOrCleaned(StartRequest request) {
        return launchedAndArmed(request).fold(result -> orCleanedUp(request, result));
    }

    private Promise<StartReply> launchedAndArmed(StartRequest request) {
        return prepared(request).flatMap(this::probed)
                       .flatMap(this::marked)
                       .flatMap(this::launched)
                       .flatMap(launched -> afterLaunch(request, launched));
    }

    /// Condition: a direct launch is recorded and armed; a hold launch is handed to its `GrantWatcher`.
    private Promise<StartReply> afterLaunch(StartRequest request, Launched launched) {
        return switch (launched.launch()) {
            case UnitLauncher.Launch.Running running -> recorded(launched, running.containerId()).flatMap(started -> armed(request,
                                                                                                                           started)).map(StartReply::startReply);
            case UnitLauncher.Launch.Holding holding -> holding(launched, holding);
        };
    }

    private Promise<StartReply> orCleanedUp(StartRequest request, Result<StartReply> result) {
        return result.fold(cause -> cleanedUp(request, cause), Promise::success);
    }

    private Promise<Prepared> prepared(StartRequest request) {
        return runnerImage.ensure(request.runnerVersion())
                          .ensureWith(_ -> stateWritten(request).async())
                          .mapWith(_ -> network(request),
                                   (imageId, network) -> Prepared.prepared(request, imageId, network));
    }

    /// `attempt` first (m4-design §4.2: any unit with a marker has an attempt), then `args`, `timeout` and the
    /// manifest spec.
    private Result<Unit> stateWritten(StartRequest request) {
        return files.store(request.ref(),
                           UnitFile.ATTEMPT,
                           Integer.toString(request.attempt()))
                    .flatMap(_ -> mapper.writeAsString(request.steps()))
                    .flatMap(args -> files.store(request.ref(),
                                                 UnitFile.ARGS,
                                                 args))
                    .flatMap(_ -> files.store(request.ref(),
                                              UnitFile.TIMEOUT,
                                              request.timeout().toString()))
                    .flatMap(_ -> manifestSpecWritten(request));
    }

    /// `manifest-spec.json`: the worker that collects the exit may be a later one, so the spec is on disk.
    private Result<Unit> manifestSpecWritten(StartRequest request) {
        return request.manifest()
                      .map(spec -> manifestSpecStored(request.ref(),
                                                      spec))
                      .or(Result::unitResult);
    }

    private Result<Unit> manifestSpecStored(UnitRef ref, ManifestSpec spec) {
        return mapper.writeAsString(ManifestSpecDto.manifestSpecDto(spec))
                     .flatMap(json -> files.store(ref, UnitFile.MANIFEST_SPEC, json));
    }

    /// The effective-POM probe, for a unit with a `ManifestSpec` (m1-design §2.3).
    private Promise<Prepared> probed(Prepared prepared) {
        return prepared.request()
                       .manifest()
                       .map(spec -> probedWith(prepared, spec))
                       .or(() -> Promise.success(prepared));
    }

    private Promise<Prepared> probedWith(Prepared prepared, ManifestSpec spec) {
        return pomProbe.probe(launch(prepared),
                              spec)
                       .map(_ -> prepared);
    }

    /// Condition: a Gate unit runs on the host network and creates none ([UnitNetwork]); any other unit gets its
    /// per-unit bridge network.
    private Promise<UnitNetwork> network(StartRequest request) {
        return request.gate()
               ? Promise.success(UnitNetwork.HOST)
               : bridge(request.ref());
    }

    /// The per-unit bridge network (created unless it exists) and its IPv4 gateway.
    private Promise<UnitNetwork> bridge(UnitRef ref) {
        return docker.networkExists(ref.networkName())
                     .flatMap(exists -> networkPresent(ref, exists))
                     .flatMap(_ -> docker.networkGateway(ref.networkName()))
                     .map(gateway -> UnitNetwork.unitNetwork(ref.networkName(),
                                                             gateway));
    }

    private Promise<Unit> networkPresent(UnitRef ref, boolean exists) {
        return exists
               ? Promise.success(unit())
               : docker.networkCreate(ref.networkName(),
                                      ref.job().toString(),
                                      ref.unit().value());
    }

    /// `facts-start.json`, the Testcontainers session snapshot, then the marker (the last write before the run).
    private Promise<Marked> marked(Prepared prepared) {
        var ref = prepared.request().ref();

        return facts.probe()
                    .map(HostFactsDto::hostFactsDto)
                    .ensureWith(dto -> factsWritten(ref, dto).async())
                    .ensureWith(_ -> reap.snapshot(ref))
                    .mapWith(_ -> markerWritten(ref).async(),
                             (dto, marker) -> Marked.marked(prepared, dto, marker));
    }

    private Result<Unit> factsWritten(UnitRef ref, HostFactsDto dto) {
        return mapper.writeAsString(dto)
                     .flatMap(json -> files.store(ref, UnitFile.FACTS_START, json));
    }

    private Result<Long> markerWritten(UnitRef ref) {
        return EpochNanos.epochNanos(clock.instant()).ensureWith(marker -> files.store(ref,
                                                                                       UnitFile.MARKER,
                                                                                       Long.toString(marker)));
    }

    /// The cancel re-check just before the launch, then the launch.
    private Promise<Launched> launched(Marked marked) {
        var request = marked.prepared().request();

        return registry.isCancelled(request.ref(), request.attempt())
               ? WorkerError.General.CANCELLED_BEFORE_RUN.promise()
               : launcher.launch(launch(marked.prepared()))
                         .map(launch -> Launched.launched(marked, launch));
    }

    private UnitLaunch launch(Prepared prepared) {
        var job = prepared.request().ref().job();

        return UnitLaunch.unitLaunch(prepared.request(),
                                     config.paths().workspace(job),
                                     config.paths().workspaceHome(job),
                                     config.uid(),
                                     config.gid(),
                                     config.dockerGid(),
                                     config.dockerSocket(),
                                     prepared.network());
    }

    /// `container`, `start`, `image`, `network`; then a cancel that arrived during `docker run` is honoured.
    private Promise<Started> recorded(Launched launched, String containerId) {
        var marked = launched.marked();
        var ref = marked.prepared().request().ref();

        return EpochNanos.epochNanos(clock.instant())
                         .flatMap(start -> launchRecorded(ref, launched, containerId, start))
                         .async()
                         .ensureWith(_ -> lateCancelHonoured(marked.prepared().request(),
                                                             containerId))
                         .map(start -> Started.started(containerId,
                                                       marked.marker(),
                                                       start,
                                                       marked.facts()));
    }

    private Result<Long> launchRecorded(UnitRef ref, Launched launched, String containerId, long start) {
        return files.store(ref, UnitFile.CONTAINER, containerId)
                    .flatMap(_ -> files.store(ref,
                                              UnitFile.START,
                                              Long.toString(start)))
                    .flatMap(_ -> holdIndependentRecorded(ref, launched))
                    .map(_ -> start);
    }

    /// `image` and `network`, which a hold unit records at the enqueue (its `start` and `container` follow the grant).
    private Result<Unit> holdIndependentRecorded(UnitRef ref, Launched launched) {
        return files.store(ref,
                           UnitFile.IMAGE,
                           launched.marked().prepared().imageId())
                    .flatMap(_ -> files.store(ref,
                                              UnitFile.NETWORK,
                                              launched.marked().prepared().network().name()));
    }

    /// bb-hold is waiting: record what the grant will not, answer `UnitHolding`; the unit's `GrantWatcher` starts
    /// once that reply is written ([StartReply]), so its `UnitStarted` can never overtake it.
    private Promise<StartReply> holding(Launched launched, UnitLauncher.Launch.Holding holding) {
        var marked = launched.marked();
        var ref = marked.prepared().request().ref();

        return holdIndependentRecorded(ref, launched).map(_ -> StartReply.startReply(unitHolding(ref,
                                                                                                 marked.prepared()
                                                                                                       .request()
                                                                                                       .attempt(),
                                                                                                 marked.marker(),
                                                                                                 marked.facts()),
                                                                                     GrantWatcher.Watch.watch(launch(marked.prepared()),
                                                                                                              holding)))
                                      .async();
    }

    private static Message unitHolding(UnitRef ref, int attempt, long marker, HostFactsDto facts) {
        return new Message.UnitHolding(ref.job().value(),
                                       ref.unit().value(),
                                       attempt,
                                       marker,
                                       facts);
    }

    private Promise<Unit> lateCancelHonoured(StartRequest request, String containerId) {
        var ref = request.ref();

        return registry.isCancelled(ref, request.attempt())
               ? files.storeOnce(ref, UnitFile.KILLED, UnitCanceller.CANCEL)
                      .async()
                      .flatMap(_ -> docker.kill(containerId))
               : Promise.success(unit());
    }

    /// Timer first (the bound must hold even if the reply never arrives), then the exit watcher, then the reply.
    private Promise<Message> armed(StartRequest request, Started started) {
        var ref = request.ref();

        return timer.arm(ref,
                         request.timeout())
                    .onSuccessRun(() -> watcher.watch(ref,
                                                      request.attempt(),
                                                      started.containerId()))
                    .flatMap(_ -> unitStarted(request, started))
                    .async();
    }

    /// `UnitStarted` from the recorded `image` and `bbhold.ticket`.
    private Result<Message> unitStarted(StartRequest request, Started started) {
        return Result.all(imageId(request.ref()),
                          ticket(request.ref()))
                     .map((imageId, ticket) -> unitStarted(request, started, imageId, ticket));
    }

    private Result<String> imageId(UnitRef ref) {
        return files.text(ref, UnitFile.IMAGE)
                    .map(id -> id.map(String::trim)
                                 .or(""));
    }

    /// The bb-hold ticket a hold unit was granted (an adopted hold unit keeps it); none for a direct unit, which never
    /// has a `bbhold.ticket`.
    private Result<Option<String>> ticket(UnitRef ref) {
        return files.text(ref, UnitFile.BBHOLD_TICKET)
                    .map(ticket -> ticket.map(String::trim));
    }

    private Message unitStarted(StartRequest request, Started started, String imageId, Option<String> ticket) {
        return new Message.UnitStarted(request.ref().job().value(),
                                       request.ref().unit().value(),
                                       request.attempt(),
                                       started.containerId(),
                                       imageId,
                                       started.marker(),
                                       EpochNanos.instant(started.start()),
                                       started.facts(),
                                       ticket);
    }

    /// BER (compensate by inverse): a start that failed after creating anything removes the container and the
    /// network it may have created (`rm -f` and `network rm` are idempotent; a Gate unit created no network, so its
    /// `network rm` finds nothing and succeeds), so a failed start leaves no running unit behind. The compensation's
    /// own failure is logged and the reply still goes out: the names are deterministic, so the leftover is visible
    /// and a retried start adopts or replaces nothing silently.
    ///
    /// A holder spawned before the failure is signalled first (SIGTERM: bb-hold dequeues its ticket), so no ticket
    /// is left waiting for a unit that already failed, and the probe container is removed with the unit's.
    private Promise<StartReply> cleanedUp(StartRequest request, Cause cause) {
        var ref = request.ref();
        var reply = failureReply(request, cause);

        LOG.error("unit {}: start failed: {}", ref.containerName(), cause);

        return attempted(ref,
                         holders.terminate(ref).async()).flatMap(_ -> attempted(ref,
                                                                                docker.remove(ref.containerName())))
                        .flatMap(_ -> attempted(ref,
                                                pomProbe.removed(ref)))
                        .flatMap(_ -> attempted(ref,
                                                docker.networkRemove(ref.networkName())))
                        .map(_ -> StartReply.startReply(reply));
    }

    /// One compensation of a failed start; its failure is logged and the next one still runs, so a holder that
    /// cannot be signalled (a corrupt `bbhold.pid`) never leaves the container or the network behind.
    private static <T> Promise<Unit> attempted(UnitRef ref, Promise<T> compensation) {
        return compensation.mapToUnit()
                           .onFailure(cause -> LOG.error("unit {}: cleanup after a failed start failed: {}",
                                                         ref.containerName(),
                                                         cause))
                           .recover(_ -> unit());
    }

    /// The reply of a failed start: a cancel is `CANCELLED`; a failed effective-POM step is `ERROR(manifest: …)`
    /// (its message is the reason as sent, Q-M1-7); anything else is `ERROR(infra: …)`.
    private static Message failureReply(StartRequest request, Cause cause) {
        return switch (cause) {
            case WorkerError.General.CANCELLED_BEFORE_RUN -> failed(request, StartFailureKind.CANCELLED, "cancelled");
            case WorkerError.EffectivePomFailed probe -> failed(request, StartFailureKind.ERROR, probe.message());
            case WorkerError.EffectivePomNotRun probe -> failed(request, StartFailureKind.ERROR, probe.message());
            default -> failed(request, StartFailureKind.ERROR, infra(cause));
        };
    }

    private static String infra(Cause cause) {
        return "infra: " + cause.message();
    }

    private static Message failed(StartRequest request, StartFailureKind kind, String reason) {
        return new Message.UnitStartFailed(request.ref().job().value(),
                                           request.ref().unit().value(),
                                           request.attempt(),
                                           kind,
                                           reason,
                                           none());
    }

    // ---- growing context -----------------------------------------------------------------------------------
    record Prepared(StartRequest request, String imageId, UnitNetwork network) {
        static Prepared prepared(StartRequest request, String imageId, UnitNetwork network) {
            return new Prepared(request, imageId.trim(), network);
        }
    }

    record Marked(Prepared prepared, HostFactsDto facts, long marker) {
        static Marked marked(Prepared prepared, HostFactsDto facts, long marker) {
            return new Marked(prepared, facts, marker);
        }
    }

    record Launched(Marked marked, UnitLauncher.Launch launch) {
        static Launched launched(Marked marked, UnitLauncher.Launch launch) {
            return new Launched(marked, launch);
        }
    }

    record Started(String containerId, long marker, long start, HostFactsDto facts) {
        static Started started(String containerId, long marker, long start, HostFactsDto facts) {
            return new Started(containerId, marker, start, facts);
        }
    }

    record StartOf(long marker, long start) {
        static StartOf startOf(long marker, long start) {
            return new StartOf(marker, start);
        }
    }
}
