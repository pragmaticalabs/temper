package org.pragmatica.temper.controller.host;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.flow.CauseChain;
import org.pragmatica.temper.controller.result.JobDecision;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.facts.UnitEndFacts;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.AttemptRef;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.StoredEvidence;
import org.pragmatica.temper.store.api.UnitFinishRow;
import org.pragmatica.temper.store.api.UnitManifestRows;
import org.pragmatica.temper.store.api.UnitRow;
import org.pragmatica.temper.store.api.UnitStartedRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.option;


/// Unsolicited worker messages of one host (m0-design §4.2, §6.4, §6.5, m1-design §1.3, §2.5, §3.2):
///
/// - `Heartbeat` → refresh the leases of the units it lists (holding units included), record the host facts.
/// - `UnitStarted` / `UnitStartFailed`, unsolicited for a bb-hold unit whose hold was granted or failed →
///   `UnitStore.started` / `UnitStore.complete`, the same rows as a reply to `StartUnit` ([StartReplies]).
/// - `UnitExited`, then the `UnitManifest` chunks of a unit with a manifest, then the `UnitReport` chunks → once the
///   `last` report chunk arrives: pull the unit's small evidence, ONE `UnitStore.complete` (testcases, folded
///   manifest, evidence row, unit end, and the job's result when it was the last unit), write `result.json` when the
///   job finished, `UnitReportAck`, then pull the full tarball in the background. Every unit frame names its attempt
///   (protocol v3, m4-design §7), and every write about it is scoped to that attempt, which the store checks again
///   under the unit's row lock (§4.3). A report of an attempt that is not the unit's current one on this host
///   (requeued, then granted again here or elsewhere) is stopped before its evidence is pulled ([#ownership], §4.6),
///   acked in its own attempt and discarded ([Settled.Superseded]); a late start or failure changes no row (the
///   store's guard). A unit that is
///   already terminal, or unknown, is acked without writing (idempotent collection; lets the worker clean up an
///   orphan); a unit queued again is neither completed nor acked (reconcile discards what is left of it).
/// - A row that does not parse marks the unit `ERROR(report)`; the rows that did parse are still stored.
/// - `UnitExited.voidReason` (`port-group-lost`, `disk`) voids the unit whatever its rc; the reds are still stored.
/// - A manifest whose `last` chunk never arrived, or that does not parse, is logged and not stored: the job's
///   manifest is then not computed (the record says `computed: false`), never a coverage judged on half a manifest.
/// - `Failure{lost}` naming a unit (its container vanished with no rc, spec §6.4) → when the unit is active on this
///   host, ONE `UnitStore.complete` as `ERROR(lost)`; otherwise it is only logged. Any other failure is logged.
///
/// A report that cannot be completed (the store failed) resets the session it came on: the reconnect's reconcile finds
/// the exited unit and `Collect`s it again, and nothing was written or acked meanwhile. FER for heartbeats: a failed
/// lease refresh is logged and dropped, because the next heartbeat (10 s) refreshes again and an expired lease only
/// changes what `GET /jobs` shows (a lease is never used to re-grant). FER for starts (ruling R-13): a `UnitStarted`
/// that cannot be recorded, on any start path, is retried on the `LinkTiming.startRetry` schedule, each attempt bounded
/// by `LinkTiming.startAttemptTimeout`, then logged and dropped; it never resets the session ([#recordStart]). A
/// dropped start marks the unit UNKNOWN in the background until the mark lands ([#markUnknown]), so a unit that runs
/// never shows QUEUED.
public final class WorkerEvents {
    private static final Logger log = LoggerFactory.getLogger(WorkerEvents.class);
    private static final String REPORT = "report";

    private final ControllerConfig.Host host;
    private final LinkEnv env;
    private final Consumer<HostFacts> facts;
    /// The retry of one start write, on the schedule of `LinkTiming.startRetry` (ruling R-13).
    private final Retry startRetry;
    /// The retry of one UNKNOWN mark: the start schedule, then its last pause again, until the mark lands (R-13,
    /// amended).
    private final Retry unknownRetry;
    private final Map<UnitRef, Assembly> reports = new ConcurrentHashMap<>();
    /// Starts still being recorded, by unit: a `UnitStarted` being written, or a `StartUnit` still in flight
    /// ([#startAwaited]); a collection of the same unit waits for its start.
    private final Map<UnitRef, Promise<Unit>> starts = new ConcurrentHashMap<>();
    /// Dropped starts whose UNKNOWN mark has not landed yet, by unit ([#markUnknown]).
    private final Map<UnitRef, Promise<Unit>> unknowns = new ConcurrentHashMap<>();
    /// The current attempt of each unit on this host: put when this link launches the unit ([#launched]) and for every
    /// unit the store has active here when a handshake reconciles ([#reconciled]). Reconcile names it in its requests.
    private final Map<UnitRef, Integer> attempts = new ConcurrentHashMap<>();
    /// Units whose `UnitStarted` this controller received but whose start is not recorded, with the attempt it
    /// belongs to ([#unrecordedStarts]).
    private final Map<UnitRef, Integer> unrecorded = new ConcurrentHashMap<>();
    /// Set by [#stop]: no background write (start retry, UNKNOWN mark) is tried after it.
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /// A report being received: the exit message, the manifest chunks folded so far (and whether the `last` one
    /// came), and the rows of the report chunks so far.
    record Assembly(Message.UnitExited exited,
                    Option<ManifestOutcomeDto> manifest,
                    boolean manifestComplete,
                    List<TestcaseDto> rows) {
        static Assembly assembly(Message.UnitExited exited) {
            return new Assembly(exited, none(), false, List.of());
        }

        Assembly with(List<TestcaseDto> more) {
            return new Assembly(exited,
                                manifest,
                                manifestComplete,
                                Stream.concat(rows.stream(), more.stream()).toList());
        }

        Assembly withManifest(Message.UnitManifest chunk) {
            return new Assembly(exited,
                                some(manifest.map(folded -> folded.merge(chunk.outcome())).or(chunk.outcome())),
                                chunk.last(),
                                rows);
        }
    }

    /// What collecting a report decided: whether it is acked, and whether this collection completed the unit (whose
    /// full tarball is then pulled).
    sealed interface Settled {
        /// Completed: acked, then the full tarball is pulled. Already terminal (or unknown): acked, so the worker removes
        /// the container. Queued again: not acked, the container stays for reconcile.
        enum Plain implements Settled {
            COMPLETED,
            ALREADY_SETTLED,
            NOT_ACTIVE
        }

        /// The report's `attempt` is not the unit's current one, `current` (m4-design §4.3): nothing was written; it is
        /// acked in its own attempt, so the worker removes that attempt's container and network, and nothing when it
        /// already holds another attempt.
        record Superseded(int attempt, int current) implements Settled {}

        static Settled settled(CompleteOutcome outcome) {
            return switch (outcome) {
                case CompleteOutcome.Completed _ -> Plain.COMPLETED;
                case CompleteOutcome.AlreadyComplete _ -> Plain.ALREADY_SETTLED;
                case CompleteOutcome.NotActive _ -> Plain.NOT_ACTIVE;
                case CompleteOutcome.Superseded superseded -> new Superseded(superseded.attempt(), superseded.current());
            };
        }
    }

    /// Where the store has a unit, read before a frame's write as an early exit (m4-design §4.3, §4.6; the writing
    /// transaction checks host and attempt again): active on this host or on another one, each in its current
    /// attempt; terminal or unknown; queued again.
    sealed interface Ownership {
        record ActiveHere(int attempt) implements Ownership {}

        record Elsewhere(int attempt) implements Ownership {}

        enum Other implements Ownership {
            SETTLED,
            QUEUED
        }
    }

    private WorkerEvents(ControllerConfig.Host host, LinkEnv env, Consumer<HostFacts> facts) {
        this.host = host;
        this.env = env;
        this.facts = facts;
        this.startRetry = startRetry(env.timing().startRetry());
        this.unknownRetry = unknownRetry(env.timing().startRetry());
    }

    /// One attempt more than there are pauses: the first attempt, then one retry after each pause.
    private static Retry startRetry(List<TimeSpan> pauses) {
        return Retry.retry()
                    .attempts(pauses.size() + 1)
                    .strategy(attempt -> pauses.get(attempt - 1));
    }

    /// Unbounded: the pauses of the start schedule in order, then its last pause between all further attempts.
    private static Retry unknownRetry(List<TimeSpan> pauses) {
        return Retry.retry()
                    .attempts(Integer.MAX_VALUE)
                    .strategy(attempt -> pauses.get(Math.min(attempt,
                                                             pauses.size()) - 1));
    }

    public static WorkerEvents workerEvents(ControllerConfig.Host host, LinkEnv env, Consumer<HostFacts> facts) {
        return new WorkerEvents(host, env, facts);
    }

    /// The inbound handler of a [Connection]; runs on its reader thread, so chunks are seen in order.
    public Unit handle(Connection connection, Message message) {
        return switch (message) {
            case Message.Heartbeat heartbeat -> heartbeat(heartbeat);
            case Message.UnitStarted started -> started(started);
            case Message.UnitStartFailed failed -> startFailed(connection, failed);
            case Message.UnitExited exited -> exited(exited);
            case Message.UnitManifest chunk -> manifestChunk(chunk);
            case Message.UnitReport chunk -> chunk(connection, chunk);
            case Message.Failure failure -> failure(connection, failure);
            default -> logged("unexpected unsolicited " + message.getClass().getSimpleName());
        };
    }

    /// Reports in flight, for tests.
    int pendingReports() {
        return reports.size();
    }

    /// Starts in flight, for tests.
    int pendingStarts() {
        return starts.size();
    }

    /// UNKNOWN marks not landed yet, for tests.
    int pendingUnknowns() {
        return unknowns.size();
    }

    /// The units this controller knows run but whose start is not recorded (ruling R-13, amended, m1-design §14: a
    /// job whose start facts were lost shows UNKNOWN while it runs, never QUEUED), as a snapshot for the read side
    /// (`api.Views.unitState`). A unit enters when its start write has been in flight `LinkTiming.unrecordedAfter`
    /// (production 1 s) since its `UnitStarted` arrived ([#stillInFlight]), when an attempt of it fails or times out
    /// first, or when its `UnitStarted` row does not parse; it leaves only once its start is recorded (any later attempt, or a re-sent
    /// start) or the unit completes ([#ended]), whatever happens to its UNKNOWN mark ([#markUnknown]): landed, refused,
    /// or ended on a terminal cause (verification F3). An entry names the attempt the start belongs to
    /// ([UnitAttempt]). A start in flight for less than `unrecordedAfter` whose attempts have not failed is NOT in
    /// it: a healthy start lands well inside that and must never flicker to UNKNOWN, so for up to `unrecordedAfter`
    /// (not "an instant": a slow but healthy store takes all of it) the unit shows GRANTED and its job QUEUED, as
    /// before. Consistency: an entry leaves
    /// only after the store write that supersedes it has committed, so a reader that takes this snapshot BEFORE it
    /// reads the rows never sees a running unit as GRANTED with no entry; leaving late is harmless, as only a GRANTED
    /// unit with no recorded start is derived.
    Set<UnitAttempt> unrecordedStarts() {
        return unrecorded.entrySet()
                         .stream()
                         .map(entry -> UnitAttempt.unitAttempt(entry.getKey(),
                                                               entry.getValue()))
                         .collect(Collectors.toUnmodifiableSet());
    }

    /// This link launches a granted unit in `attempt`, its current attempt here from now on.
    Unit launched(UnitRef ref, int attempt) {
        attempts.put(ref, attempt);

        return unit();
    }

    /// A handshake's reconcile read the units the store has active on this host: each one's current attempt.
    Unit reconciled(List<ActiveUnit> active) {
        active.forEach(unit -> attempts.put(unit.ref(), unit.attempt()));

        return unit();
    }

    /// The link stopped for good (`HostLink.stop`, the controller's shutdown): every start retry and UNKNOWN mark loop
    /// still pending ends at its next attempt without writing (verification F5); nothing new is tried.
    Unit stop() {
        stopped.set(true);

        return unit();
    }

    /// The unit's attempt `attempt` ended: it completed (any terminal state, a completion that found it already
    /// terminal, or one that found it requeued or superseded). It leaves [#unrecordedStarts], and its attempt is
    /// forgotten, unless this link already knows a newer attempt of the unit: a late resolution of attempt n after n+1
    /// was granted to this same host leaves n+1's entry, so its lease refresh goes on (m4-design §4.3). Called only
    /// after the store write that ended it committed.
    Unit ended(UnitRef ref, int attempt) {
        var known = attemptOf(ref);

        return known.filter(newer -> newer > attempt)
                    .map(_ -> unit())
                    .or(() -> forgotten(ref, known));
    }

    /// The store holds the unit terminal (or not at all): no attempt of it is granted again, so whatever this link
    /// knows of it is forgotten.
    Unit endedTerminal(UnitRef ref) {
        return forgotten(ref, attemptOf(ref));
    }

    /// Removes the entry only if it is still the one read, so an attempt launched meanwhile stays.
    private Unit forgotten(UnitRef ref, Option<Integer> known) {
        unrecorded.remove(ref);
        known.onPresent(attempt -> attempts.remove(ref, attempt));

        return unit();
    }

    /// A reconcile found the unit's container running while the store has it GRANTED with no recorded start
    /// ([ReconcileAction.StartUnknown]): known unrecorded at once, before its mark is written, so every read shows it
    /// UNKNOWN even when the store refuses that write (verification F5), bb-hold `waiting` or not.
    Unit reconciledRunning(UnitRef ref, int attempt) {
        unrecorded.put(ref, attempt);

        return unit();
    }

    /// The current attempt of `ref` on this host, if this link launched or reconciled it.
    Option<Integer> attemptOf(UnitRef ref) {
        return option(attempts.get(ref));
    }

    // ---- heartbeat ---------------------------------------------------------------------------------------------
    private Unit heartbeat(Message.Heartbeat heartbeat) {
        env.units()
           .refreshLeases(host.name(),
                          attemptRefs(heartbeat.running()),
                          TimeSpan.fromDuration(env.lease()))
           .onFailure(cause -> log.warn("[{}] lease refresh failed: {}",
                                        host.name(),
                                        cause.message()));
        heartbeatFacts(heartbeat).onSuccess(this::recordFacts)
                      .onFailure(cause -> log.warn("[{}] heartbeat facts unreadable: {}",
                                                   host.name(),
                                                   cause.message()));

        return unit();
    }

    private static Result<HostFacts> heartbeatFacts(Message.Heartbeat heartbeat) {
        return option(heartbeat.facts()).toResult(LinkError.Unexpected.FACTORY.apply("facts", "none"))
                     .flatMap(HostFactsDto::toDomain);
    }

    /// The link keeps the facts in memory and writes `hosts.facts` through its ordered writer.
    private Unit recordFacts(HostFacts latest) {
        facts.accept(latest);

        return unit();
    }

    /// Each running unit's lease is refreshed in the attempt the heartbeat names (protocol v3, m4-design §4.6): a late
    /// attempt's entry matches no row (`LEASES`).
    private static List<AttemptRef> attemptRefs(List<UnitRefDto> running) {
        return option(running).or(List.of())
                     .stream()
                     .flatMap(dto -> dto.toDomain()
                                        .map(ref -> AttemptRef.attemptRef(ref,
                                                                          dto.attempt()))
                                        .option()
                                        .stream())
                     .toList();
    }

    // ---- starts (unsolicited bb-hold starts, and the direct StartUnit reply) --------------------------------------
    /// A hold was granted and the container runs, or the worker re-sends a held unit's start (`Reattach`, `Collect`).
    /// One path for all three, and for the direct `StartUnit` reply ([UnitLauncher], ordered by [#startAwaited]): a
    /// start that cannot be recorded never resets the session ([#recordStart]).
    private Unit started(Message.UnitStarted started) {
        ref(started.job(),
            started.unit()).onSuccess(ref -> recordStart(ref, started))
           .onFailure(cause -> log.warn("[{}] UnitStarted for a bad unit: {}",
                                        host.name(),
                                        cause.message()));

        return unit();
    }

    /// FER (degrade forward, ruling R-13, m1-design §14): on every start path, hold and direct, the store write is
    /// retried on the `LinkTiming.startRetry` schedule, each attempt bounded by `LinkTiming.startAttemptTimeout`, so
    /// the whole start is bounded by attempts × timeout + pauses (production: 5 × 5 s + about 8 s), then the start is
    /// logged and dropped; a row that does not parse is dropped at once, as no retry changes it. Guarantee: the
    /// returned promise always succeeds, so a start that fails on every attempt never resets the session, and the
    /// report the worker sends behind it is read and completes the unit in the same session (a collection of the unit
    /// waits for this promise, or, on the direct path, for the one [#startAwaited] registered before the `StartUnit`
    /// was sent). Accepted degradation (R-13): a dropped start leaves the unit without start facts (no
    /// `started` event, container, ticket or port group), and nothing re-sends it while the session stays up. Its
    /// unit and job show UNKNOWN, never QUEUED (R-13, amended): from the first failed attempt, or once the write has
    /// been in flight `LinkTiming.unrecordedAfter` (1 s, m1-design §14), whichever comes first, through
    /// [#unrecordedStarts], and durably once [#markUnknown] lands. A transient store fault
    /// still resets the session through the failed completion write (or any other store write) of the same session.
    /// The start leaves [#pendingStarts] as a chain step, so whoever sees the returned promise resolve (the direct
    /// path's launch) no longer finds it in flight.
    ///
    /// A start of another attempt than the one this link knows current (launched or reconciled here) is a late frame of
    /// a superseded attempt (m4-design §4.6): it is logged and dropped before it touches anything kept per unit (the
    /// start in flight, the unrecorded entry, the UNKNOWN mark), all of which belong to the current attempt.
    Promise<Unit> recordStart(UnitRef ref, Message.UnitStarted started) {
        return attemptOf(ref).filter(current -> current != started.attempt())
                        .map(current -> lateStart(ref,
                                                  started.attempt(),
                                                  current))
                        .or(() -> currentStart(ref, started));
    }

    private Promise<Unit> lateStart(UnitRef ref, int attempt, int current) {
        log.info("[{}] late UnitStarted of {} attempt {} (current {}) dropped",
                 host.name(),
                 ref.containerName(),
                 attempt,
                 current);

        return Promise.unitPromise();
    }

    private Promise<Unit> currentStart(UnitRef ref, Message.UnitStarted started) {
        var attempt = started.attempt();
        var recorded = StartReplies.startedRow(started)
                                   .async()
                                   .flatMap(row -> startRetry.execute(() -> startAttempt(ref, attempt, row)))
                                   .withSuccess(_ -> unrecorded.remove(ref, attempt))
                                   .recover(cause -> startDropped(ref, attempt, cause));

        starts.put(ref, recorded);
        SharedScheduler.schedule(() -> stillInFlight(ref, attempt, recorded),
                                 env.timing().unrecordedAfter());

        return recorded.withResult(_ -> starts.remove(ref, recorded));
    }

    /// m1-design §14 (maintainer ruling): a start write still in flight `LinkTiming.unrecordedAfter` (production 1 s) after its
    /// `UnitStarted` arrived counts as unrecorded, slow store or hanging one alike, so no read shows the running unit
    /// QUEUED past that. A start recorded (or dropped, which marks it already) by then is left alone; one recorded just
    /// after this check leaves an entry for a started unit, which no view derives from (only a GRANTED unit with no
    /// recorded start is), and its completion removes it.
    private Unit stillInFlight(UnitRef ref, int attempt, Promise<Unit> recorded) {
        return recorded.isResolved()
               ? unit()
               : unrecordedStart(ref, attempt);
    }

    /// The direct path's ordering (verification F1): a `StartUnit` reply resolves on the async executor, while the
    /// report the worker sends right behind it is read on the reader thread, so nothing orders a start registered by
    /// the reply before the collection looks it up. The start is therefore registered here, BEFORE `start` sends the
    /// `StartUnit`, and stays registered until `start` settles: its `UnitStarted` recorded or dropped
    /// ([#recordStart]), any other reply handled, or the request failed (timeout, link down). A collection of the unit
    /// waits for it ([#startRecorded]); every terminal path removes it. No report of the unit can legitimately precede
    /// its start reply on the same session.
    Promise<Unit> startAwaited(UnitRef ref, Supplier<Promise<Unit>> start) {
        var pending = Promise.<Unit> promise();

        starts.put(ref, pending);

        return start.get()
                    .withResult(_ -> startSettled(ref, pending));
    }

    /// `recordStart` may have replaced the entry with its own promise, which removes itself; only this one is removed.
    private Unit startSettled(UnitRef ref, Promise<Unit> pending) {
        starts.remove(ref, pending);
        pending.succeed(unit());

        return unit();
    }

    /// One attempt, bounded by `LinkTiming.startAttemptTimeout` (R-13): a store that hangs fails the attempt, which is
    /// then retried like a refused one. The write a timed-out attempt left in flight may still land; the store records
    /// only the first start of a unit active on this host (`startable`), so it and a retry's write never both apply.
    /// A failed attempt puts the unit in [#unrecordedStarts] before the retry runs, so it shows UNKNOWN from then on,
    /// however short of `unrecordedAfter` it is.
    private Promise<Unit> startAttempt(UnitRef ref, int attempt, UnitStartedRow row) {
        return stopped.get()
               ? LinkError.Stopped.LINK_STOPPED.promise()
               : ownedStart(ref, attempt, row).timeout(env.timing().startAttemptTimeout())
                           .withFailure(_ -> unrecordedStart(ref, attempt));
    }

    private Unit unrecordedStart(UnitRef ref, int attempt) {
        unrecorded.put(ref, attempt);

        return unit();
    }

    private Promise<Unit> ownedStart(UnitRef ref, int attempt, UnitStartedRow row) {
        return ownership(ref).flatMap(owner -> recordedStart(owner, ref, attempt, row));
    }

    /// Written in the frame's attempt: a late start of a superseded attempt changes no row (the store's guard,
    /// m4-design §4.3, §4.6). A unit not active here is only logged.
    private Promise<Unit> recordedStart(Ownership owner, UnitRef ref, int attempt, UnitStartedRow row) {
        return switch (owner) {
            case Ownership.ActiveHere _ -> env.units().started(host.name(), ref, attempt, row).map(_ -> env.wake()
                                                                                                           .signal());
            case Ownership.Elsewhere _, Ownership.Other _ -> Promise.success(notActive("UnitStarted",
                                                                                       ref,
                                                                                       attempt,
                                                                                       owner));
        };
    }

    private Unit startDropped(UnitRef ref, int attempt, Cause cause) {
        log.warn("[{}] UnitStarted of {} not recorded, dropped (the unit completes without start facts): {}",
                 host.name(),
                 ref.containerName(),
                 CauseChain.render(cause));

        return markUnknown(ref, attempt);
    }

    /// FER continued (ruling R-13, amended): the unit of a dropped start runs, so a status of QUEUED would be a wrong
    /// fact. The UNKNOWN mark (`UnitStore.startUnknown`) is a store write, and the store just failed, so it is retried
    /// in the background, not awaited: the start schedule, then its last pause (production 5 s) again, each attempt
    /// bounded by `LinkTiming.startAttemptTimeout`, until it lands. Guarantee: once the store accepts writes again, the
    /// unit and its job read UNKNOWN within one pause plus one attempt, and stay UNKNOWN until a late start (RUNNING)
    /// or the exit facts (completed). A store that serves reads while it refuses or hangs this write is covered on the
    /// read side: the unit is in [#unrecordedStarts] until its start is recorded or it completes, however the loop
    /// ends, so every view shows it UNKNOWN at once (`api.Views.unitState`); a controller that lost that memory (restarted) falls back to `LinkTiming.startBound`
    /// from the grant. The mark is conditional (GRANTED, not started, on this host, in this attempt), so landing after
    /// the completion, a late start, or a requeue and a new grant of the unit (to this host too, verification F4)
    /// changes nothing. A controller restart before it lands loses the loop; the reconnect's reconcile marks a GRANTED
    /// unit with no recorded start whose container runs ([ReconcileAction.StartUnknown]). Only a terminal cause (no
    /// retry changes it, e.g. an unknown unit) ends the loop unlanded; it is logged, and the unit stays in
    /// [#unrecordedStarts] (it is added here too, for a row that did not parse and so never had an attempt). The loop
    /// also ends, without writing, once the link stops ([#stop]) or the unit no longer needs the mark ([#ended], or its
    /// start recorded): verification F5.
    private Unit markUnknown(UnitRef ref, int attempt) {
        unrecorded.put(ref, attempt);
        var marked = unknownRetry.execute(() -> unknownAttempt(ref, attempt))
                                 .onFailure(cause -> log.warn("[{}] {} not marked UNKNOWN: {}",
                                                              host.name(),
                                                              ref.containerName(),
                                                              CauseChain.render(cause)));

        unknowns.put(ref, marked);
        // May run late: the mark loop is detached, and its entry is only counted by the test accessor.
        marked.onResultRun(() -> unknowns.remove(ref, marked));

        return unit();
    }

    /// Scoped to the attempt (`UnitStore.startUnknown` with its number): a mark that lands after the unit was
    /// requeued and granted again, even to this host, changes nothing (verification F4).
    /// The loop ends without writing once the link stopped, or once the unit no longer needs the mark: its start was
    /// recorded or it completed ([#ended]), which takes it out of [#unrecordedStarts] (verification F5).
    private Promise<Unit> unknownAttempt(UnitRef ref, int attempt) {
        return stopped.get()
               ? LinkError.Stopped.LINK_STOPPED.promise()
               : stillUnrecorded(ref, attempt);
    }

    private Promise<Unit> stillUnrecorded(UnitRef ref, int attempt) {
        return option(unrecorded.get(ref)).filter(unrecordedAttempt -> unrecordedAttempt == attempt)
                     .isPresent()
               ? env.units()
                    .startUnknown(host.name(),
                                  ref,
                                  attempt)
                    .timeout(env.timing().startAttemptTimeout())
               : LinkError.Stopped.START_SETTLED.promise();
    }

    /// A hold failed (bb-hold refused, was signalled, or `docker run` failed after the grant): the unit ends as the
    /// worker judged it. A store failure resets the session, and the reconnect's reconcile settles the unit from the
    /// worker's disk (a dead holder: requeue before a grant, lost after one).
    private Unit startFailed(Connection connection, Message.UnitStartFailed failed) {
        ref(failed.job(),
            failed.unit()).onSuccess(ref -> startFailedHere(connection, ref, failed))
           .onFailure(cause -> log.warn("[{}] UnitStartFailed for a bad unit: {}",
                                        host.name(),
                                        cause.message()));

        return unit();
    }

    private Unit startFailedHere(Connection connection, UnitRef ref, Message.UnitStartFailed failed) {
        // The reset may run late: this chain is detached (the reader thread never waits for a store write).
        ownership(ref).flatMap(owner -> endedAtStart(owner, ref, failed))
                 .onFailure(cause -> HostLink.reset(connection, cause));

        return unit();
    }

    /// In the frame's attempt: a superseded one is `Superseded` by the completing transaction, nothing written.
    private Promise<Unit> endedAtStart(Ownership owner, UnitRef ref, Message.UnitStartFailed failed) {
        return switch (owner) {
            case Ownership.ActiveHere _ -> completedWith(ref,
                                                         failed.attempt(),
                                                         StartReplies.startFailure(failed, Instant.now()));
            case Ownership.Elsewhere _, Ownership.Other _ -> Promise.success(notActive("UnitStartFailed",
                                                                                       ref,
                                                                                       failed.attempt(),
                                                                                       owner));
        };
    }

    // ---- reports -----------------------------------------------------------------------------------------------
    private Unit exited(Message.UnitExited exited) {
        ref(exited.job(),
            exited.unit()).onSuccess(ref -> reports.put(ref,
                                                        Assembly.assembly(exited)))
           .onFailure(cause -> log.warn("[{}] UnitExited for a bad unit: {}",
                                        host.name(),
                                        cause.message()));

        return unit();
    }

    private Unit manifestChunk(Message.UnitManifest chunk) {
        return ref(chunk.job(),
                   chunk.unit()).map(ref -> manifestOf(ref, chunk))
                  .or(() -> logged("UnitManifest for a bad unit " + chunk.job() + "/" + chunk.unit()));
    }

    private Unit manifestOf(UnitRef ref, Message.UnitManifest chunk) {
        return option(reports.computeIfPresent(ref,
                                               (_, assembly) -> assembly.withManifest(chunk))).map(_ -> unit())
                     .or(() -> logged("UnitManifest without UnitExited for " + ref.containerName()));
    }

    private Unit chunk(Connection connection, Message.UnitReport chunk) {
        return ref(chunk.job(),
                   chunk.unit()).map(ref -> received(connection, ref, chunk))
                  .or(() -> logged("UnitReport for a bad unit " + chunk.job() + "/" + chunk.unit()));
    }

    private Unit received(Connection connection, UnitRef ref, Message.UnitReport chunk) {
        return option(reports.computeIfPresent(ref,
                                               (_, assembly) -> assembly.with(rowsOf(chunk)))).map(_ -> lastChunk(connection,
                                                                                                                  ref,
                                                                                                                  chunk))
                     .or(() -> logged("UnitReport without UnitExited for " + ref.containerName()));
    }

    private static List<TestcaseDto> rowsOf(Message.UnitReport chunk) {
        return option(chunk.rows()).or(List.of());
    }

    private Unit lastChunk(Connection connection, UnitRef ref, Message.UnitReport chunk) {
        return chunk.last()
               ? finished(connection, ref)
               : unit();
    }

    private Unit finished(Connection connection, UnitRef ref) {
        option(reports.remove(ref)).onPresent(assembly -> collect(connection, ref, assembly));

        return unit();
    }

    /// Completes the unit (when it is active on this host), then acks the report and pulls the full tarball.
    Promise<Unit> collect(Connection connection, UnitRef ref, Assembly assembly) {
        // The ack and the reset may run late: the one caller (`finished`) does not wait for the collection.
        return startRecorded(ref).flatMap(_ -> ownership(ref))
                            .flatMap(owner -> settle(owner, ref, assembly))
                            .onSuccess(settled -> acknowledge(connection,
                                                              ref,
                                                              assembly.exited().attempt(),
                                                              settled))
                            .onFailure(cause -> HostLink.reset(connection, cause))
                            .mapToUnit();
    }

    /// A `UnitStarted` of the same unit still being recorded is recorded first, so the completion sees the start: the
    /// worker re-sends a held unit's start right before its exit report (integration F4), and both writes lock the
    /// unit's row in whatever order they arrive. The wait ends when the start is recorded or dropped after its last
    /// attempt ([#recordStart]), or, for a `StartUnit` in flight, when it settles ([#startAwaited]); neither promise
    /// ever fails, so the wait never fails the collection either.
    private Promise<Unit> startRecorded(UnitRef ref) {
        return option(starts.get(ref)).or(Promise::unitPromise);
    }

    /// The report's attempt, from its `UnitExited` (protocol v3), is completed only when it is the unit's current one
    /// on this host; another attempt is `Superseded` before any evidence is pulled (m4-design §4.6). A QUEUED unit
    /// (requeued) answers `NotActive`: a stale report, not acked, so the container stays for reconcile.
    private Promise<Settled> settle(Ownership owner, UnitRef ref, Assembly assembly) {
        var attempt = assembly.exited().attempt();

        return switch (owner) {
            case Ownership.ActiveHere here when here.attempt() == attempt -> completedHere(ref, attempt, assembly);
            case Ownership.ActiveHere here -> Promise.success(new Settled.Superseded(attempt, here.attempt()));
            case Ownership.Elsewhere elsewhere -> Promise.success(new Settled.Superseded(attempt, elsewhere.attempt()));
            case Ownership.Other.SETTLED -> settledBefore(ref);
            case Ownership.Other.QUEUED -> Promise.success(Settled.Plain.NOT_ACTIVE);
        };
    }

    private Promise<Settled> completedHere(UnitRef ref, int attempt, Assembly assembly) {
        return complete(ref, attempt, assembly).withSuccess(env.evidence()::finished)
                       .withSuccess(_ -> ended(ref, attempt))
                       .map(Settled::settled);
    }

    private Promise<Settled> settledBefore(UnitRef ref) {
        return Promise.<Settled> success(Settled.Plain.ALREADY_SETTLED).withSuccess(_ -> endedTerminal(ref));
    }

    /// The small tarball is pulled first, so `complete` records it in the same transaction (m1-design §3.2); it is
    /// stored under the attempt's own directory, so a pull racing a requeue never overwrites another attempt's file.
    private Promise<CompleteOutcome> complete(UnitRef ref, int attempt, Assembly assembly) {
        return env.evidence()
                  .small(host, ref, attempt)
                  .flatMap(small -> completed(ref, attempt, assembly, small));
    }

    private Promise<CompleteOutcome> completed(UnitRef ref,
                                               int attempt,
                                               Assembly assembly,
                                               Option<StoredEvidence> small) {
        var parsed = assembly.rows().stream().map(TestcaseDto::toDomain).toList();
        var rows = parsed.stream()
                         .flatMap(row -> row.option()
                                            .stream())
                         .filter(row -> row.unit()
                                           .equals(ref.unit()))
                         .toList();
        var malformed = rows.size() != parsed.size();

        return env.units()
                  .complete(host.name(),
                            ref,
                            attempt,
                            finishRow(assembly.exited(),
                                      malformed),
                            rows,
                            manifestRows(ref, assembly),
                            small,
                            JobDecision::decide);
    }

    /// The folded manifest, only once its `last` chunk arrived and it parses.
    private Option<UnitManifestRows> manifestRows(UnitRef ref, Assembly assembly) {
        return assembly.manifestComplete()
               ? assembly.manifest()
                         .flatMap(folded -> parsedManifest(ref, folded))
               : incompleteManifest(ref, assembly);
    }

    /// FER: a manifest that does not parse is logged and not stored; the job's manifest is then not computed
    /// (`computed: false`), so no coverage is claimed from it.
    private Option<UnitManifestRows> parsedManifest(UnitRef ref, ManifestOutcomeDto folded) {
        return folded.toDomain()
                     .onFailure(cause -> log.warn("[{}] manifest of {} unreadable, not stored: {}",
                                                  host.name(),
                                                  ref.containerName(),
                                                  cause.message()))
                     .option()
                     .map(UnitManifestRows::unitManifestRows);
    }

    private Option<UnitManifestRows> incompleteManifest(UnitRef ref, Assembly assembly) {
        assembly.manifest()
                .onPresent(_ -> logged("manifest of " + ref.containerName()
                                      + " ended without its last chunk; not stored"));

        return none();
    }

    static UnitFinishRow finishRow(Message.UnitExited exited, boolean malformedRows) {
        return UnitFinishRow.unitFinishRow(some(exited.rc()),
                                           exited.killedBy().map(KilledBy::toDomain),
                                           option(exited.endedAt()).or(Instant::now),
                                           option(exited.endFacts()).flatMap(WorkerEvents::endFacts),
                                           exited.testsAttr(),
                                           exited.voidReason().map(VoidReason::wire),
                                           errorReason(exited, malformedRows));
    }

    /// FER (degrade forward): end facts that do not parse are logged with their cause and dropped; the unit's
    /// verdict does not depend on them. Guarantee: the rc, kill cause and testcases are still recorded. Mechanism:
    /// `unit_facts` phase `end` is simply not written for this unit.
    private static Option<UnitEndFacts> endFacts(UnitEndFactsDto dto) {
        return dto.toDomain()
                  .onFailure(cause -> log.warn("end facts unreadable, not recorded: {}",
                                               cause.message()))
                  .option();
    }

    private static Option<String> errorReason(Message.UnitExited exited, boolean malformedRows) {
        return exited.errorReason()
                     .orElse(() -> reportIf(malformedRows));
    }

    private static Option<String> reportIf(boolean malformedRows) {
        return malformedRows
               ? some(REPORT)
               : none();
    }

    /// The ack names the attempt of the report it acknowledges (protocol v3), so a worker holding another attempt of
    /// the unit ignores it and removes nothing.
    private Unit acknowledge(Connection connection, UnitRef ref, int attempt, Settled settled) {
        return switch (settled) {
            case Settled.Plain.COMPLETED -> ackedThenPulled(connection, ref, attempt);
            case Settled.Plain.ALREADY_SETTLED -> acked(connection, ref, attempt);
            case Settled.Superseded superseded -> discarded(connection, ref, superseded);
            case Settled.Plain.NOT_ACTIVE -> logged("report of " + ref.containerName() + " not acked: the unit is not active on this host");
        };
    }

    private Unit acked(Connection connection, UnitRef ref, int attempt) {
        connection.send(new Message.UnitReportAck(ref.job().value(),
                                                  ref.unit().value(),
                                                  attempt))
                  .onFailure(cause -> log.warn("[{}] UnitReportAck for {} not sent: {}",
                                               host.name(),
                                               ref.containerName(),
                                               cause.message()));
        env.wake().signal();

        return unit();
    }

    private Unit ackedThenPulled(Connection connection, UnitRef ref, int attempt) {
        acked(connection, ref, attempt);

        return env.evidence()
                  .full(host, ref, attempt);
    }

    private Unit discarded(Connection connection, UnitRef ref, Settled.Superseded superseded) {
        log.info("[{}] stale report of {} attempt {} (current {}) discarded",
                 host.name(),
                 ref.containerName(),
                 superseded.attempt(),
                 superseded.current());

        return acked(connection, ref, superseded.attempt());
    }

    // ---- lost units ---------------------------------------------------------------------------------------------
    private Unit failure(Connection connection, Message.Failure failure) {
        logged("worker failure " + failure.code() + ": " + failure.detail());

        return lostUnit(failure).map(dto -> lostNamed(connection, dto))
                       .or(unit());
    }

    private Unit lostNamed(Connection connection, UnitRefDto dto) {
        return namedUnit(dto).map(ref -> lost(connection,
                                              ref,
                                              dto.attempt()))
                        .or(unit());
    }

    /// The unit an unsolicited `Failure{lost}` names, with its attempt; any other failure names none.
    private static Option<UnitRefDto> lostUnit(Message.Failure failure) {
        return UnitFinishes.LOST.equals(failure.code())
               ? failure.unit()
               : none();
    }

    /// A unit name that does not parse is logged with its cause and treated as no unit: nothing can be ended.
    private Option<UnitRef> namedUnit(UnitRefDto dto) {
        return dto.toDomain()
                  .onFailure(cause -> log.warn("[{}] lost Failure names a bad unit {}/{}: {}",
                                               host.name(),
                                               dto.job(),
                                               dto.unit(),
                                               cause.message()))
                  .option();
    }

    /// A store failure while ending the unit resets the session, exactly as a report that cannot be completed does
    /// ([#collect]): the reconnect's reconcile finds the worker's marker with neither container nor rc and ends the
    /// unit `ERROR(lost)` ([ReconcileAction.MarkLost]). Nothing is written meanwhile, so no failure is absorbed.
    private Unit lost(Connection connection, UnitRef ref, int attempt) {
        // The reset may run late: this chain is detached (the reader thread never waits for a store write).
        ownership(ref).flatMap(owner -> endedLost(owner, ref, attempt))
                 .onFailure(cause -> HostLink.reset(connection, cause));

        return unit();
    }

    /// In the failure's attempt: a superseded one is `Superseded` by the completing transaction (m4-design §4.6).
    private Promise<Unit> endedLost(Ownership owner, UnitRef ref, int attempt) {
        return switch (owner) {
            case Ownership.ActiveHere _ -> completedLost(ref, attempt);
            case Ownership.Elsewhere _, Ownership.Other _ -> Promise.success(notActive("lost Failure",
                                                                                       ref,
                                                                                       attempt,
                                                                                       owner));
        };
    }

    private Promise<Unit> completedLost(UnitRef ref, int attempt) {
        return completedWith(ref,
                             attempt,
                             UnitFinishes.lost(Instant.now()));
    }

    /// A unit that ends without a report (lost, or its hold failed): no testcases, no manifest, no evidence.
    private Promise<Unit> completedWith(UnitRef ref, int attempt, UnitFinishRow row) {
        return env.units()
                  .complete(host.name(),
                            ref,
                            attempt,
                            row,
                            List.of(),
                            none(),
                            none(),
                            JobDecision::decide)
                  .withSuccess(env.evidence()::finished)
                  .withSuccess(_ -> ended(ref, attempt))
                  .map(_ -> env.wake()
                               .signal());
    }

    // ---- ownership ---------------------------------------------------------------------------------------------
    private Promise<Ownership> ownership(UnitRef ref) {
        return env.units()
                  .active(host.name())
                  .flatMap(active -> activeHereOr(active, ref));
    }

    private Unit notActive(String frame, UnitRef ref, int attempt, Ownership owner) {
        return logged(frame
                     + " of " + ref.containerName()
                     + " attempt " + attempt
                     + " ignored: the unit is not active on this host (" + owner
                     + ")");
    }

    private Promise<Ownership> activeHereOr(List<ActiveUnit> active, UnitRef ref) {
        return Option.from(active.stream().filter(unit -> unit.ref()
                                                              .equals(ref)).findFirst())
                     .map(unit -> Promise.<Ownership> success(new Ownership.ActiveHere(unit.attempt())))
                     .or(() -> notActiveHere(ref));
    }

    private Promise<Ownership> notActiveHere(UnitRef ref) {
        return env.jobs()
                  .job(ref.job())
                  .map(job -> ownershipIn(job, ref));
    }

    /// An unknown job or unit is settled (nothing to write; acking lets the worker clean up).
    private static Ownership ownershipIn(Option<JobRow> job, UnitRef ref) {
        return job.flatMap(row -> unitNamed(row,
                                            ref.unit()))
                  .map(WorkerEvents::ownershipOf)
                  .or(Ownership.Other.SETTLED);
    }

    private static Option<UnitRow> unitNamed(JobRow job, UnitName name) {
        return Option.from(job.units().stream().filter(unit -> unit.name()
                                                                   .equals(name)).findFirst());
    }

    private static Ownership ownershipOf(UnitRow unit) {
        return switch (unit.state()) {
            case QUEUED -> Ownership.Other.QUEUED;
            case GRANTED, UNKNOWN, RUNNING, CANCELLING -> new Ownership.Elsewhere(unit.attempt());
            case PASSED, FAILED, EMPTY, VOID, ERROR, CANCELLED -> Ownership.Other.SETTLED;
        };
    }

    private static Result<UnitRef> ref(long job, String unit) {
        return Result.all(JobId.jobId(job),
                          UnitName.unitName(unit))
                     .map(UnitRef::unitRef);
    }

    private Unit logged(String text) {
        log.warn("[{}] {}", host.name(), text);

        return unit();
    }
}
