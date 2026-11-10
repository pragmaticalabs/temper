package org.pragmatica.temper.controller.host;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.policy.HostSnapshot;
import org.pragmatica.temper.controller.result.JobDecision;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.PortGroupHoldDto;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.UnitFinishRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Starts a granted unit (m0-design §6.5, m1-design §1.3): `PrepareJob` (idempotent), then `StartUnit`, then record
/// the outcome:
///
/// - `UnitStarted` → `UnitStore.started`, through the start path the unsolicited starts take
///   ([WorkerEvents#recordStart], ruling R-13): retried, then dropped, never a reset. The start is registered before
///   `StartUnit` is sent ([WorkerEvents#startAwaited]), so the unit's report, read on the reader thread while the
///   reply resolves on the executor, is collected only after it;
/// - `UnitHolding` (a port-group unit started through bb-hold) → `UnitStore.holding`: the unit stays GRANTED with
///   `port_group = waiting`; its `UnitStarted` or `UnitStartFailed` arrives later, unsolicited ([WorkerEvents]);
/// - `UnitStartFailed{VOID, disk}` → complete with `voidReason=disk`; `{ERROR, infra: …}` → `errorReason=infra: …`
///   (`ERROR(infra)`, the detail kept); `{CANCELLED}` (a `CancelUnit` raced ahead) → complete as cancelled;
/// - `PrepareJob` → `Failure{seed-isolation}` → `ERROR(seed-isolation)` (A13); any other code → `ERROR(infra)`; the
///   worker's detail is kept after the token ([UnitFinishes#workerFailure]).
///
/// A timeout, a dropped link or a store failure (other than the start write, R-13) leaves the unit GRANTED. The
/// session is then RESET, so the next handshake's reconcile resolves the unit from the worker's disk (marker →
/// collect/reattach/lost, no marker → requeue). Resetting is a refinement of §6.5, which only says "leaves it GRANTED": without it a request timeout
/// on a link that stays up would leave the unit GRANTED until some unrelated reconnect.
///
/// `StartUnit.portGroup` asks the worker to hold the port group through bb-hold. It is sent for every unit that needs
/// the group, except a non-Gate unit on a port-isolated host, which starts directly as in M0: the S1 pass justifies
/// `portIsolated` only for non-Gate units, and an unsharded Gate keeps bb-hold until E1 passes (m0-design §12.4,
/// maintainer). The rule is [HostSnapshot#holdsPortGroup], which the grant fit counts by too (ruling R-3). `StartUnit.manifest` is the unit's planned manifest spec.
public record UnitLauncher(ControllerConfig.Host host, Connection session, LinkEnv env, WorkerEvents events) {
    private static final Logger log = LoggerFactory.getLogger(UnitLauncher.class);

    public static UnitLauncher unitLauncher(ControllerConfig.Host host,
                                            Connection session,
                                            LinkEnv env,
                                            WorkerEvents events) {
        return new UnitLauncher(host, session, env, events);
    }

    public Promise<Unit> launch(Granted granted) {
        events.launched(granted.ref(), granted.attempt());

        return prepare(granted).flatMap(_ -> events.startAwaited(granted.ref(),
                                                                 () -> answered(granted)))
                      .fold(result -> settled(granted, result));
    }

    private Promise<Unit> answered(Granted granted) {
        return start(granted).flatMap(reply -> recorded(granted, reply));
    }

    private Promise<Message> prepare(Granted granted) {
        return session.request(new Message.PrepareJob(granted.ref().job().value(),
                                                      granted.sha().value(),
                                                      env.origin()),
                               env.timing().prepare())
                      .flatMap(reply -> Connection.expect(reply, Message.JobPrepared.class).async());
    }

    private Promise<Message> start(Granted granted) {
        return session.request(startUnit(granted),
                               env.timing().start());
    }

    Message.StartUnit startUnit(Granted granted) {
        return new Message.StartUnit(granted.ref().job().value(),
                                     granted.ref().unit().value(),
                                     granted.attempt(),
                                     granted.kind().name(),
                                     granted.steps(),
                                     granted.timeout(),
                                     granted.memoryMb(),
                                     env.cpuShares(),
                                     env.runnerVersion(),
                                     env.mavenOpts(),
                                     env.tcHubPrefix(),
                                     portGroup(granted),
                                     granted.manifest().map(ManifestSpecDto::manifestSpecDto),
                                     env.instance().value());
    }

    /// bb-hold's `<owner> <prio>`: the configured owner tag and the job priority (advisory, Q-M1-12).
    private Option<PortGroupHoldDto> portGroup(Granted granted) {
        return HostSnapshot.holdsPortGroup(granted.kind(), granted.needsPortGroup(), host.portIsolated())
               ? some(PortGroupHoldDto.portGroupHoldDto(env.portGroupOwner().value(),
                                                        granted.priority()))
               : none();
    }

    private Promise<Unit> recorded(Granted granted, Message reply) {
        return switch (reply) {
            case Message.UnitStarted started -> events.recordStart(granted.ref(), started);
            case Message.UnitHolding holding -> recordedHolding(granted, holding);
            case Message.UnitStartFailed failed -> finish(granted,
                                                          failed.attempt(),
                                                          StartReplies.startFailure(failed, Instant.now()));
            default -> LinkError.Unexpected.FACTORY.apply("UnitStarted, UnitHolding or UnitStartFailed",
                                                          reply.getClass().getSimpleName()).promise();
        };
    }

    /// In `attempt`: the one the reply names (protocol v3), the request's own when the worker failed it.
    private Promise<Unit> finish(Granted granted, int attempt, UnitFinishRow row) {
        return env.units()
                  .complete(host.name(),
                            granted.ref(),
                            attempt,
                            row,
                            List.of(),
                            none(),
                            none(),
                            JobDecision::decide)
                  .withSuccess(env.evidence()::finished)
                  .withSuccess(_ -> events.ended(granted.ref(),
                                                 attempt))
                  .mapToUnit();
    }

    private Promise<Unit> recordedHolding(Granted granted, Message.UnitHolding holding) {
        return StartReplies.holdingRow(holding)
                           .async()
                           .flatMap(row -> env.units()
                                              .holding(host.name(),
                                                       granted.ref(),
                                                       holding.attempt(),
                                                       row));
    }

    /// The single place the launch's outcome is settled; always signals the wake (capacity or an event changed).
    private Promise<Unit> settled(Granted granted, Result<Unit> result) {
        // The signal may run late: a wake only prompts a tick, which reads the store, not this launch.
        return result.fold(cause -> failed(granted, cause),
                           Promise::success)
                     .onResultRun(() -> env.wake()
                                           .signal());
    }

    /// A worker `Failure` ends the unit (seed-isolation or infra). Anything else (timeout, link down, store down,
    /// protocol mismatch) leaves it GRANTED and resets THIS session (a failed start write never gets here: it is
    /// retried and then dropped, with the degradation ruling R-13 accepts), so the reconnect's reconcile settles the
    /// unit from the worker's disk (BER of the half-done start: requeue without a marker, collect/reattach/lost with one).
    /// Every reset is a chain step, done before the launch settles, so the tick its wake prompts never grants on the
    /// session being reset.
    private Promise<Unit> failed(Granted granted, Cause cause) {
        log.warn("[{}] starting {} failed: {}",
                 host.name(),
                 granted.ref().containerName(),
                 cause.message());

        return switch (cause) {
            case LinkError.WorkerFailure failure -> finish(granted,
                                                           granted.attempt(),
                                                           UnitFinishes.errored(UnitFinishes.workerFailure(failure.code(),
                                                                                                           failure.detail()),
                                                                                Instant.now())).withFailure(stored -> HostLink.reset(session,
                                                                                                                                     stored));
            default -> Promise.success(HostLink.reset(session, cause));
        };
    }
}
