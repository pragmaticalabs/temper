package org.pragmatica.temper.worker.link;

import java.time.Duration;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.gc.JobGc;
import org.pragmatica.temper.worker.unit.GrantWatcher;
import org.pragmatica.temper.worker.unit.HolderProbe;
import org.pragmatica.temper.worker.unit.LogTailer;
import org.pragmatica.temper.worker.unit.StartReply;
import org.pragmatica.temper.worker.unit.StartRequest;
import org.pragmatica.temper.worker.unit.UnitCanceller;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRefs;
import org.pragmatica.temper.worker.unit.UnitRegistry;
import org.pragmatica.temper.worker.unit.UnitStarter;
import org.pragmatica.temper.worker.unit.UnitTimer;
import org.pragmatica.temper.worker.unit.UnitWatcher;
import org.pragmatica.temper.worker.workspace.WorkspacePreparer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Dispatches one controller request to its handler (m0-design §4.2). Handshake and lifecycle messages
/// (`Welcome`, `Incompatible`, `Shutdown`) are the frame loop's; a worker-to-controller type is refused.
///
/// Protocol v3 (m4-design §4.2): a unit request (`Reattach`, `Collect`, `CancelUnit`, `LogTail`) names the unit's
/// attempt. When this host holds another attempt (the unit directory's; with none, the container's `temper.attempt`
/// label, [#attemptHere]) the request does nothing and is answered `Failure{stale-attempt}` ([#current]); a host
/// holding none (never started here, or discarded) is the handler's to answer, as before M4. A `CancelUnit` for an
/// attempt above the one held is for a start still to replace it ([#cancelled]). `UnitReportAck` (no reply) is
/// ignored and logged instead ([#acknowledged]). `DiscardUnit` removes exactly the attempt it names ([#discarded]).
public record Router(WorkspacePreparer preparer,
                     UnitStarter starter,
                     UnitWatcher watcher,
                     UnitTimer timer,
                     UnitCanceller canceller,
                     LogTailer tailer,
                     FactsProbe facts,
                     CgroupSampler cgroups,
                     UnitFiles files,
                     UnitRegistry registry,
                     DockerCli docker,
                     GrantWatcher grants,
                     HolderProbe holders,
                     JobGc gc) implements RequestHandler {
    private static final Logger LOG = LoggerFactory.getLogger(Router.class);

    public static Router router(WorkspacePreparer preparer,
                                UnitStarter starter,
                                UnitWatcher watcher,
                                UnitTimer timer,
                                UnitCanceller canceller,
                                LogTailer tailer,
                                FactsProbe facts,
                                CgroupSampler cgroups,
                                UnitFiles files,
                                UnitRegistry registry,
                                DockerCli docker,
                                GrantWatcher grants,
                                HolderProbe holders,
                                JobGc gc) {
        return new Router(preparer,
                          starter,
                          watcher,
                          timer,
                          canceller,
                          tailer,
                          facts,
                          cgroups,
                          files,
                          registry,
                          docker,
                          grants,
                          holders,
                          gc);
    }

    @Override
    public Promise<Reply> route(Message message) {
        return switch (message) {
            case Message.PrepareJob prepare -> prepared(prepare);
            case Message.StartUnit start -> started(start);
            case Message.Reattach reattach -> reattached(reattach);
            case Message.Collect collect -> collected(collect);
            case Message.CancelUnit cancel -> cancelled(cancel);
            case Message.UnitReportAck ack -> acknowledged(ack);
            case Message.LogTail tail -> tailed(tail);
            case Message.FactsRequest _ -> currentFacts();
            case Message.GcJobs jobs -> gcDone(jobs);
            case Message.DiscardUnit discard -> discarded(discard);
            default -> WorkerError.UnexpectedMessage.FACTORY.apply(message.getClass().getSimpleName()).promise();
        };
    }

    /// `StartUnit` → `UnitStarted`, `UnitHolding` or `UnitStartFailed`; only a malformed request fails
    /// (`bad-request`). A hold unit's `GrantWatcher` starts as the reply's follow-up, after `UnitHolding` is written.
    private Promise<Reply> started(Message.StartUnit start) {
        return StartRequest.startRequest(start)
                           .async()
                           .flatMap(starter::start)
                           .map(reply -> replied(reply, grants));
    }

    /// The reply, with a hold unit's grant watch as its follow-up: the watch starts only once the reply is written.
    static Reply replied(StartReply reply, GrantWatcher grants) {
        return reply.watch()
                    .map(watch -> Reply.respond(reply.message(),
                                                () -> watched(grants, watch)))
                    .or(() -> Reply.respond(reply.message()));
    }

    /// A [Reply.FollowUp]: its `Promise<Unit>` shape is that interface's, though starting a watch cannot fail.
    @Contract
    private static Promise<Unit> watched(GrantWatcher grants, GrantWatcher.Watch watch) {
        return Promise.success(grants.watch(watch));
    }

    /// `LogTail` → `LogChunk`.
    private Promise<Reply> tailed(Message.LogTail tail) {
        return current(tail.job(),
                       tail.unit(),
                       tail.attempt()).flatMap(ref -> tailer.tail(ref,
                                                                  tail.lines()))
                      .map(Reply::respond);
    }

    /// `FactsRequest` → `Facts`.
    private Promise<Reply> currentFacts() {
        return facts.probe()
                    .map(HostFactsDto::hostFactsDto)
                    .map(dto -> Reply.respond(new Message.Facts(dto)));
    }

    /// `GcJobs` → `GcDone` (m1-design §4.3).
    private Promise<Reply> gcDone(Message.GcJobs jobs) {
        return gc.collect(jobs)
                 .map(Reply::respond);
    }

    /// `PrepareJob` → `JobPrepared{job, ws}`.
    private Promise<Reply> prepared(Message.PrepareJob prepare) {
        return Result.all(JobId.jobId(prepare.job()),
                          Sha.sha(prepare.sha()))
                     .async()
                     .flatMap((job, sha) -> preparer.prepare(job,
                                                             sha,
                                                             prepare.originUrl()))
                     .map(workspace -> Reply.respond(new Message.JobPrepared(prepare.job(),
                                                                             workspace.toString())));
    }

    /// `Reattach`: a unit with an `rc` is resent (as on `Collect`); a live or exited container gets the
    /// controller's authoritative timeout (written and armed: a passed deadline fires at once) and a watcher, and
    /// a hold unit's container is handed to the `HoldMonitor` again (a holder that died while no worker was
    /// attached is caught on the next heartbeat); a hold unit with no container whose holder is alive is still
    /// waiting (reconcile rule 3): its timeout is written and its `GrantWatcher` re-armed; any other unit whose
    /// container is gone with no `rc` is lost.
    private Promise<Reply> reattached(Message.Reattach reattach) {
        return current(reattach.job(), reattach.unit(), reattach.attempt()).flatMap(ref -> reattachedUnit(ref, reattach));
    }

    private Promise<Reply> reattachedUnit(UnitRef ref, Message.Reattach reattach) {
        return files.exists(ref, UnitFile.RC)
               ? Promise.success(collectReply(ref, reattach.attempt()))
               : files.container(ref)
                      .async()
                      .flatMap(container -> rearmed(ref, reattach, container));
    }

    private Promise<Reply> rearmed(UnitRef ref, Message.Reattach reattach, String container) {
        return docker.status(container)
                     .flatMap(status -> rearmedAt(ref, reattach, container, status));
    }

    private Promise<Reply> rearmedAt(UnitRef ref, Message.Reattach reattach, String container, ContainerStatus status) {
        return holders.status(ref)
                      .async()
                      .flatMap(holder -> rearmedFor(ref, reattach, container, status, holder));
    }

    private Promise<Reply> rearmedFor(UnitRef ref,
                                      Message.Reattach reattach,
                                      String container,
                                      ContainerStatus status,
                                      Option<HolderStatus> holder) {
        return status == ContainerStatus.ABSENT
               ? rewatchedOrLost(ref, reattach, container, holder)
               : rearmedRunning(ref, reattach, container, holder.isPresent());
    }

    private Promise<Reply> rewatchedOrLost(UnitRef ref,
                                           Message.Reattach reattach,
                                           String container,
                                           Option<HolderStatus> holder) {
        return holder.filter(status -> status != HolderStatus.DEAD)
                     .isPresent()
               ? rewatched(ref, reattach.attempt(), reattach.timeout())
               : WorkerError.ContainerLost.FACTORY.apply(container).promise();
    }

    /// The grant watch starts as the `Ack`'s follow-up, as it does after `UnitHolding`.
    private Promise<Reply> rewatched(UnitRef ref, int attempt, Duration timeout) {
        return files.store(ref,
                           UnitFile.TIMEOUT,
                           timeout.toString())
                    .map(_ -> Reply.respond(ack(),
                                            () -> watched(grants,
                                                          GrantWatcher.Watch.watch(ref, attempt, timeout))))
                    .async();
    }

    /// A held unit whose container runs, or has exited since the reconnect's scan (verification V2-3: reconcile saw it
    /// running and sent `Reattach`, not `Collect`), gets its `UnitStarted` again, after the `Ack`: the controller may
    /// never have received the first (it was lost with the link), and its row would stay waiting, with no container
    /// or ticket, until the exit. The controller records only a unit's first start (`UnitStore.started` is a no-op on a
    /// repeat), so the re-send is harmless when the first one did arrive. Such a unit's exit watcher is armed only
    /// after the re-send is posted, so an exit right after the `Reattach` cannot post its report ahead of the start;
    /// the follow-up runs whether or not the `Ack` was written, and the timer is armed before either.
    private Promise<Reply> rearmedRunning(UnitRef ref, Message.Reattach reattach, String container, boolean holdUnit) {
        var timeout = reattach.timeout();
        var attempt = reattach.attempt();

        return files.store(ref,
                           UnitFile.TIMEOUT,
                           timeout.toString())
                    .flatMap(_ -> timer.arm(ref, timeout))
                    .onSuccessRun(() -> watchedAgain(ref, attempt, container, holdUnit))
                    .map(_ -> rearmedReply(ref, attempt, container, holdUnit))
                    .async();
    }

    private Reply rearmedReply(UnitRef ref, int attempt, String container, boolean resendStart) {
        return resendStart
               ? Reply.respond(ack(), () -> resentThenWatched(ref, attempt, container))
               : Reply.respond(ack());
    }

    private Promise<Unit> resentThenWatched(UnitRef ref, int attempt, String container) {
        return grants.resent(ref, container)
                     .map(_ -> watcher.watch(ref, attempt, container));
    }

    /// The exit watcher (for a hold unit, whose start is re-sent, only after the re-send), and for a hold unit the
    /// `HoldMonitor`.
    private Unit watchedAgain(UnitRef ref, int attempt, String container, boolean holdUnit) {
        exitWatchedNow(ref, attempt, container, !holdUnit);

        return holdUnit
               ? registry.hold(ref, container)
               : Unit.unit();
    }

    private Unit exitWatchedNow(UnitRef ref, int attempt, String container, boolean now) {
        return now
               ? watcher.watch(ref, attempt, container)
               : Unit.unit();
    }

    /// `Collect` → `Ack`, then (after the `Ack` is written) a held unit's `UnitStarted` again and the exit report. A unit with no `rc` whose container is
    /// gone is answered `Failure{lost}` instead of an `Ack`, so the loss is a reply the controller can attribute.
    private Promise<Reply> collected(Message.Collect collect) {
        return current(collect.job(), collect.unit(), collect.attempt()).flatMap(ref -> collectable(ref,
                                                                                                    collect.attempt()));
    }

    private Promise<Reply> collectable(UnitRef ref, int attempt) {
        return files.exists(ref, UnitFile.RC)
               ? Promise.success(collectReply(ref, attempt))
               : files.container(ref)
                      .async()
                      .flatMap(container -> collectableAt(ref, attempt, container));
    }

    private Promise<Reply> collectableAt(UnitRef ref, int attempt, String container) {
        return docker.status(container)
                     .flatMap(status -> collectableFor(ref, attempt, container, status));
    }

    private Promise<Reply> collectableFor(UnitRef ref, int attempt, String container, ContainerStatus status) {
        return status == ContainerStatus.ABSENT
               ? WorkerError.ContainerLost.FACTORY.apply(container).promise()
               : Promise.success(collectReply(ref, attempt));
    }

    /// `Ack`, then (after it is written) a held unit's `UnitStarted` again and the exit report, in that order.
    private Reply collectReply(UnitRef ref, int attempt) {
        return Reply.respond(ack(), () -> collectedAfterStart(ref, attempt));
    }

    private Promise<Unit> collectedAfterStart(UnitRef ref, int attempt) {
        return grants.resentBeforeCollect(ref)
                     .flatMap(_ -> watcher.collectRequested(ref, attempt));
    }

    /// `CancelUnit` → `Ack{state}`. A host holding a LOWER attempt than the cancel's has not started the named one
    /// yet: a `StartUnit` of it may be replacing the lower one right now. That is no stale request; the cancel is
    /// remembered for the named attempt and nothing of the lower one is touched ([UnitCanceller#cancelAhead]).
    private Promise<Reply> cancelled(Message.CancelUnit cancel) {
        return UnitRefs.unitRef(cancel.job(),
                                cancel.unit())
                       .async()
                       .flatMap(ref -> cancelledHere(ref,
                                                     cancel.attempt()))
                       .map(state -> Reply.respond(new Message.Ack(some(state.name()))));
    }

    private Promise<UnitCanceller.CancelState> cancelledHere(UnitRef ref, int attempt) {
        return attemptHere(ref).flatMap(held -> cancelledFor(ref, attempt, held));
    }

    private Promise<UnitCanceller.CancelState> cancelledFor(UnitRef ref, int attempt, Option<Integer> held) {
        return held.filter(found -> found < attempt)
                   .isPresent()
               ? Promise.success(canceller.cancelAhead(ref, attempt))
               : heldOrNone(ref, held, attempt).async()
                           .flatMap(current -> canceller.cancel(current, attempt));
    }

    /// `UnitReportAck`: remove the container, then its network (none for a Gate unit: `network rm` finds nothing);
    /// forget the unit, as a chain step (`withResult`, not an `on*` callback, which runs after the dependents), so the
    /// ack resolves only once the unit's timer is cancelled. No reply.
    ///
    /// Attempt-scoped (protocol v3): when the unit directory holds another attempt, or the container's `temper.attempt`
    /// label names another, the ack is of a superseded report (m4-design §4.3 acks `Superseded`) and removing by name
    /// would kill the live attempt's container: nothing is removed or forgotten, and the ack is logged as ignored.
    /// The ack takes the unit's turn ([UnitRegistry#inTurn]): its attempt check and its removal never interleave with a
    /// `StartUnit` replacing the attempt or a `DiscardUnit` of it.
    ///
    /// FER (degrade forward): a removal that fails leaves a stopped container or an empty network named
    /// `temper-<job>-<unit>` behind; it is logged, holds no unit state, and the report is already persisted, so
    /// the ack is still honoured.
    private Promise<Reply> acknowledged(Message.UnitReportAck ack) {
        return UnitRefs.unitRef(ack.job(),
                                ack.unit())
                       .async()
                       .flatMap(ref -> registry.inTurn(ref,
                                                       () -> removedIfCurrent(ref,
                                                                              ack.attempt())))
                       .map(_ -> Reply.silent());
    }

    private Promise<Unit> removedIfCurrent(UnitRef ref, int attempt) {
        return otherAttempt(ref, attempt).flatMap(other -> removedOrIgnored(ref, attempt, other));
    }

    private Promise<Unit> removedOrIgnored(UnitRef ref, int attempt, Option<Integer> other) {
        return other.map(held -> ignoredAck(ref, attempt, held))
                    .or(() -> removed(ref, attempt));
    }

    /// Another attempt than `attempt` on this host: the unit directory's, else the container's label; none when both
    /// name `attempt` or hold none (a unit started before M4).
    private Promise<Option<Integer>> otherAttempt(UnitRef ref, int attempt) {
        return files.heldAttempt(ref)
                    .or(none())
                    .filter(held -> held != attempt)
                    .map(other -> Promise.success(some(other)))
                    .or(() -> docker.attemptLabel(ref.containerName())
                                    .map(label -> otherThan(label, attempt)));
    }

    private static Option<Integer> otherThan(Option<Integer> held, int attempt) {
        return held.filter(found -> found != attempt);
    }

    private static Promise<Unit> ignoredAck(UnitRef ref, int attempt, int held) {
        LOG.info("unit {}: UnitReportAck for attempt {} ignored: this host holds attempt {}",
                 ref.containerName(),
                 attempt,
                 held);

        return Promise.unitPromise();
    }

    private Promise<Unit> removed(UnitRef ref, int attempt) {
        return cleanedUp(ref).onFailure(cause -> LOG.warn("unit {}: cleanup after the report ack failed: {}",
                                                          ref.containerName(),
                                                          cause))
                        .recover(_ -> Unit.unit())
                        .withResult(_ -> forgotten(ref, attempt));
    }

    /// Drops what the registry holds of the acknowledged attempt; a later attempt's cancel stays.
    private Unit forgotten(UnitRef ref, int attempt) {
        registry.forget(ref, attempt);

        return Unit.unit();
    }

    private Promise<Unit> cleanedUp(UnitRef ref) {
        return files.container(ref)
                    .async()
                    .flatMap(this::containerRemoved)
                    .flatMap(_ -> docker.networkRemove(ref.networkName()));
    }

    private Promise<Unit> containerRemoved(String container) {
        // May run late: nothing reads an acked container's sample, and `forget` stops the heartbeat sampling it.
        return docker.remove(container)
                     .onSuccessRun(() -> cgroups.forget(container));
    }

    /// The unit a request names, when this host holds the attempt it names or none at all; otherwise
    /// `Failure{stale-attempt}` (m4-design §4.2).
    private Promise<UnitRef> current(long job, String unit, int attempt) {
        return UnitRefs.unitRef(job, unit)
                       .async()
                       .flatMap(ref -> attemptChecked(ref, attempt));
    }

    private Promise<UnitRef> attemptChecked(UnitRef ref, int attempt) {
        return attemptHere(ref).flatMap(held -> heldOrNone(ref, held, attempt).async());
    }

    /// The attempt this host holds of the unit: the unit directory's (`attempt`, or 1 for a pre-M4 marker); with none,
    /// the container's `temper.attempt` label (a container whose directory is gone); none when neither names one.
    ///
    /// FER (degrade forward): an unreadable `attempt` is logged and the label decides, as `StateScan` reports such a
    /// unit with no attempt; a damaged file never wedges the requests about its unit.
    private Promise<Option<Integer>> attemptHere(UnitRef ref) {
        return files.heldAttempt(ref)
                    .onFailure(cause -> LOG.error("unit {}: unreadable attempt, judged by the container label: {}",
                                                  ref.containerName(),
                                                  cause))
                    .or(none())
                    .map(held -> Promise.success(some(held)))
                    .or(() -> docker.attemptLabel(ref.containerName()));
    }

    private static Result<UnitRef> heldOrNone(UnitRef ref, Option<Integer> held, int attempt) {
        return held.filter(other -> other != attempt)
                   .map(other -> WorkerError.StaleAttempt.FACTORY.apply(ref.containerName(),
                                                                        other,
                                                                        attempt)
                                                                 .<UnitRef> result())
                   .or(() -> success(ref));
    }

    // ---- DiscardUnit (protocol v3, m4-design §4.5) -----------------------------------------------------------
    /// `DiscardUnit` → `Ack`: when this host holds exactly the named attempt (its unit directory's, or with no
    /// directory its container's `temper.attempt` label), the attempt is removed whole ([UnitDiscard]) and the unit
    /// forgotten; another attempt, or nothing at all, is left untouched and acked: the named attempt is not on this
    /// host either way, which is all the controller asks. A removal that fails is answered `Failure`, and the
    /// reconcile that sent it fails and retries.
    ///
    /// The discard takes the unit's turn ([UnitRegistry#inTurn]): it runs after a `StartUnit` of the unit in flight and
    /// before one that arrives meanwhile, so it judges, and deletes, a directory no start is writing.
    ///
    /// An unreadable `attempt` (which `StartUnit` reports as no attempt, and the controller then reads as 1) never
    /// wedges the reconcile: the container's label decides; a container labelled with another attempt is left; with no
    /// label at all the unit is discarded, and that is logged.
    private Promise<Reply> discarded(Message.DiscardUnit discard) {
        return UnitRefs.unitRef(discard.job(),
                                discard.unit())
                       .async()
                       .flatMap(ref -> registry.inTurn(ref,
                                                       () -> discardedIfHeld(ref,
                                                                             discard.attempt())))
                       .map(_ -> Reply.respond(ack()));
    }

    private Promise<Unit> discardedIfHeld(UnitRef ref, int attempt) {
        return files.heldAttempt(ref)
                    .fold(cause -> unreadableJudged(ref, attempt, cause),
                          held -> heldOrLabelled(ref, held).flatMap(found -> discardedOrUntouched(ref, attempt, found)));
    }

    private Promise<Unit> discardedOrUntouched(UnitRef ref, int attempt, Option<Integer> held) {
        return held.filter(found -> found == attempt)
                   .map(_ -> discardedNow(ref, attempt))
                   .or(() -> untouched(ref, attempt, held));
    }

    /// The unit directory's attempt; with none, the container's label (a container whose directory is gone).
    private Promise<Option<Integer>> heldOrLabelled(UnitRef ref, Option<Integer> held) {
        return held.isPresent()
               ? Promise.success(held)
               : docker.attemptLabel(ref.containerName());
    }

    private Promise<Unit> unreadableJudged(UnitRef ref, int attempt, Cause cause) {
        LOG.error("unit {}: unreadable attempt, the DiscardUnit for attempt {} is judged by the container label: {}",
                  ref.containerName(),
                  attempt,
                  cause);

        return docker.attemptLabel(ref.containerName())
                     .flatMap(label -> discardedByLabel(ref, attempt, label));
    }

    private Promise<Unit> discardedByLabel(UnitRef ref, int attempt, Option<Integer> label) {
        return label.isPresent()
               ? discardedOrUntouched(ref, attempt, label)
               : discardedNow(ref, attempt);
    }

    private Promise<Unit> discardedNow(UnitRef ref, int attempt) {
        LOG.info("unit {}: discarding superseded attempt {}", ref.containerName(), attempt);

        return starter.unitDiscard()
                      .discard(ref, attempt);
    }

    private static Promise<Unit> untouched(UnitRef ref, int attempt, Option<Integer> held) {
        LOG.info("unit {}: DiscardUnit for attempt {} leaves {} untouched",
                 ref.containerName(),
                 attempt,
                 held.map(other -> "attempt " + other).or("nothing"));

        return Promise.unitPromise();
    }

    private static Message ack() {
        return new Message.Ack(none());
    }
}
