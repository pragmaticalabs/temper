package org.pragmatica.temper.worker.unit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.worker.InFlight;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The worker's in-process view of its units, shared by the request handlers, the exit watchers and the
/// heartbeat. Everything durable lives in the unit files; this only coordinates the live process:
///
/// - `cancelled`: the attempt a `CancelUnit` named, by unit, consulted by `StartUnit` before and after `docker run`;
/// - `timers`: each unit's armed timeout (re-arming replaces it);
/// - `watched`: units with a live `docker wait`, by container id (the heartbeat's `running` list);
/// - `collections`: the exit processing of a unit, by attempt, shared by the watcher and `Collect` so it runs once,
///   and kept until `UnitReportAck` so a lost report can be resent. Keyed by attempt so a later attempt never joins
///   an earlier one's (a late `Collect(n)` after n was forgotten leaves one behind until the next forget);
/// - `starts`: the in-flight `StartUnit` of a unit, by attempt. A retried `StartUnit` of the same attempt (the
///   controller's request timed out while an image was building) joins it instead of racing it; one of another attempt
///   never takes the first one's reply, it queues in the unit's turn instead. A `CancelUnit` that finds no container
///   while a start is in flight answers `CANCELLING`: that start will see the cancel and end the unit itself;
/// - `holding` (M1): units with a live `GrantWatcher`, by the attempt it watches, waiting in bb-hold for the port
///   group. They have no container yet, and the heartbeat lists them so the controller refreshes their leases
///   (m1-design §1.3);
/// - `heldContainers` (M1): units whose container runs under a granted hold, by container; the `HoldMonitor` checks their
///   holder on every heartbeat (§1.4);
/// - `turns` (M4): the unit's last queued `StartUnit`, `DiscardUnit` or `UnitReportAck`; each runs after the one
///   before it ([#inTurn]);
/// - `owners` (M4): the attempt the unit's timer, watch, collection and held container belong to, set when that
///   attempt's watch or exit processing starts ([#startWatching], [#collection]); dropped by [#forget] and, for a tag
///   left behind (a failed collection, a report never acknowledged), by the job GC ([#released]).
///
/// Attempts (protocol v3, m4-design §4.2): the process outlives a unit's attempts, so what one attempt left here
/// must not decide for the next. A cancel and a hold name their attempt; the rest (a timer, a watch, a collection, a
/// held container) is one attempt's at a time and is dropped by [#forget] when that attempt is replaced, discarded or
/// acknowledged, never by a forget of an earlier attempt ([#owners]). A watch is identified by its container, so an
/// old watcher can tell it no longer owns the unit.
public record UnitRegistry(Map<UnitRef, Integer> cancelled,
                           Map<UnitRef, Timers.Scheduled> timers,
                           Map<UnitRef, String> watched,
                           Map<AttemptKey, Promise<Collected>> collections,
                           Map<AttemptKey, Promise<StartReply>> starts,
                           Map<UnitRef, Integer> holding,
                           Map<UnitRef, String> heldContainers,
                           Map<UnitRef, Promise<Unit>> turns,
                           Map<UnitRef, Integer> owners) {
    public static UnitRegistry unitRegistry() {
        return new UnitRegistry(new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>(),
                                new ConcurrentHashMap<>());
    }

    /// The in-flight start of this attempt of the unit, or a new one from `start`; forgotten once it resolves.
    public Promise<StartReply> start(UnitRef ref, int attempt, Supplier<Promise<StartReply>> start) {
        return InFlight.joined(starts, new AttemptKey(ref, attempt), start);
    }

    /// Whether a `StartUnit` of the unit, of any attempt, is still running.
    public boolean isStarting(UnitRef ref) {
        return starts.keySet()
                     .stream()
                     .anyMatch(key -> key.ref()
                                         .equals(ref));
    }

    /// Whether a `StartUnit` of this attempt of the unit is still running.
    public boolean isStarting(UnitRef ref, int attempt) {
        return starts.containsKey(new AttemptKey(ref, attempt));
    }

    /// Runs `operation` after the unit's previous `StartUnit`, `DiscardUnit` or `UnitReportAck` has resolved, whatever
    /// its outcome (m4-design §4.5): a discard or an ack never interleaves with a start of the same unit.
    public synchronized <T> Promise<T> inTurn(UnitRef ref, Supplier<Promise<T>> operation) {
        var previous = option(turns.get(ref)).or(Promise::unitPromise);
        var next = previous.fold(_ -> operation.get());
        var turn = next.mapToUnit().recover(_ -> unit());

        turns.put(ref, turn);

        return next.withResult(_ -> turns.remove(ref, turn));
    }

    public Unit cancel(UnitRef ref, int attempt) {
        cancelled.put(ref, attempt);

        return unit();
    }

    /// Whether a `CancelUnit` named this attempt of the unit.
    public boolean isCancelled(UnitRef ref, int attempt) {
        return option(cancelled.get(ref)).filter(cancelledAttempt -> cancelledAttempt == attempt)
                     .isPresent();
    }

    /// Installs a unit's timer, cancelling the one it replaces.
    public Unit replaceTimer(UnitRef ref, Timers.Scheduled timer) {
        option(timers.put(ref, timer)).onPresent(Timers.Scheduled::cancel);

        return unit();
    }

    /// `true` when this call started watching this attempt's container (the caller then owns the `docker wait`).
    public synchronized boolean startWatching(UnitRef ref, int attempt, String containerId) {
        return option(watched.putIfAbsent(ref, containerId)).onEmpty(() -> owned(ref, attempt))
                     .isEmpty();
    }

    public Unit stopWatching(UnitRef ref) {
        watched.remove(ref);

        return unit();
    }

    /// Ends the watch only if it is still the one of `containerId`: an old attempt's watcher never ends a new one's.
    public Unit stopWatching(UnitRef ref, String containerId) {
        watched.remove(ref, containerId);

        return unit();
    }

    /// Whether the unit's watch is the one of `containerId` (a replaced or discarded attempt's watcher's is not).
    public boolean watches(UnitRef ref, String containerId) {
        return option(watched.get(ref)).filter(containerId::equals)
                     .isPresent();
    }

    /// `true` when this call started holding the unit (the caller then owns its `GrantWatcher`).
    public boolean startHolding(UnitRef ref, int attempt) {
        return option(holding.putIfAbsent(ref, attempt)).isEmpty();
    }

    /// Ends the hold only if it is still this attempt's.
    public Unit stopHolding(UnitRef ref, int attempt) {
        holding.remove(ref, attempt);

        return unit();
    }

    /// Whether a `GrantWatcher` of the unit runs in this worker.
    public boolean isHolding(UnitRef ref) {
        return holding.containsKey(ref);
    }

    /// Whether this worker's `GrantWatcher` of the unit watches this attempt.
    public boolean isHolding(UnitRef ref, int attempt) {
        return option(holding.get(ref)).filter(held -> held == attempt)
                     .isPresent();
    }

    /// The unit's container runs under a granted hold; the `HoldMonitor` checks it from now on.
    public Unit hold(UnitRef ref, String containerId) {
        heldContainers.put(ref, containerId);

        return unit();
    }

    /// The units whose container runs under a granted hold.
    public List<RunningUnit> held() {
        return heldContainers.entrySet()
                             .stream()
                             .map(RunningUnit::runningUnit)
                             .toList();
    }

    /// The units whose leases the heartbeat refreshes: every watched unit and every unit waiting in bb-hold.
    public List<UnitRef> leased() {
        return Stream.concat(watched.keySet().stream(),
                             holding.keySet().stream())
                     .distinct()
                     .toList();
    }

    /// Whether any unit of `job` is known here: starting, holding, held, watched, timed or being collected.
    public boolean knows(JobId job) {
        return Stream.of(starts.keySet().stream().map(AttemptKey::ref).collect(Collectors.toSet()),
                         holding.keySet(),
                         heldContainers.keySet(),
                         watched.keySet(),
                         timers.keySet(),
                         collections.keySet().stream().map(AttemptKey::ref).collect(Collectors.toSet()))
                     .flatMap(Set::stream)
                     .anyMatch(ref -> ref.job()
                                         .equals(job));
    }

    /// Whether no unit of `job` is known here ([#knows]); if so, the job's owner tags go too. The job GC is the one
    /// point where a unit is over for good (it runs only for a terminal job, which never starts a unit again), so a tag
    /// that outlived what it protected (a failed collection, a report never acknowledged) ends there; dropping it any
    /// earlier would need every other entry of the attempt checked first. Atomic with [#collection] and
    /// [#startWatching], which set the tags.
    public synchronized boolean released(JobId job) {
        return ! knows(job) && ownersDropped(job);
    }

    private boolean ownersDropped(JobId job) {
        owners.keySet().removeIf(ref -> ref.job()
                                           .equals(job));

        return true;
    }

    /// The units with a live watcher and their container ids.
    public List<RunningUnit> running() {
        return watched.entrySet()
                      .stream()
                      .map(RunningUnit::runningUnit)
                      .toList();
    }

    /// This attempt's exit processing: the in-flight or finished one, or a new one from `collect`; never another
    /// attempt's. A failed processing is forgotten so the next `Collect` retries it.
    public synchronized Promise<Collected> collection(UnitRef ref, int attempt, Supplier<Promise<Collected>> collect) {
        owned(ref, attempt);

        return InFlight.joinedUntilFailed(collections, new AttemptKey(ref, attempt), collect);
    }

    /// The owner only grows: a late request of an earlier attempt never takes the unit from a later one.
    private Unit owned(UnitRef ref, int attempt) {
        owners.merge(ref, attempt, Math::max);

        return unit();
    }

    /// The exit processing of the watcher of `containerId`, as [#collection], or none once that watch is no longer
    /// the unit's. Atomic with [#forget], so a replaced attempt's watcher either starts its processing before the
    /// forget (which then hands it to the caller to wait for) or not at all.
    public synchronized Option<Promise<Collected>> watchedCollection(UnitRef ref,
                                                                     int attempt,
                                                                     String containerId,
                                                                     Supplier<Promise<Collected>> collect) {
        return watches(ref, containerId)
               ? some(collection(ref, attempt, collect))
               : none();
    }

    /// Drops everything about a unit's attempts up to `attempt`: one replaced or discarded, or whose report the
    /// controller acknowledged. A cancel or a hold of a later attempt (a `CancelUnit` raced ahead of its start) stays,
    /// and so do the timer, watch, collection and held container when they belong to a later attempt ([#owners]); an
    /// entry no attempt owns yet (a timer armed just before its watch starts) is dropped. The exit processings of the
    /// attempts up to `attempt` go whoever owns the unit. Returns a promise that resolves once each of them is over,
    /// whatever its outcome, for a caller about to remove the unit directory.
    public synchronized Promise<Unit> forget(UnitRef ref, int attempt) {
        cancelled.computeIfPresent(ref, (_, cancelledAttempt) -> laterOrNull(cancelledAttempt, attempt));
        holding.computeIfPresent(ref, (_, held) -> laterOrNull(held, attempt));
        option(owners.get(ref)).filter(owner -> owner > attempt).onEmpty(() -> dropped(ref));

        return Promise.allOf(droppedCollections(ref, attempt)).mapToUnit();
    }

    private Unit dropped(UnitRef ref) {
        option(timers.remove(ref)).onPresent(Timers.Scheduled::cancel);
        watched.remove(ref);
        heldContainers.remove(ref);
        owners.remove(ref);

        return unit();
    }

    private List<Promise<Collected>> droppedCollections(UnitRef ref, int attempt) {
        return collections.keySet()
                          .stream()
                          .filter(key -> key.ref()
                                            .equals(ref) && key.attempt() <= attempt)
                          .toList()
                          .stream()
                          .flatMap(key -> option(collections.remove(key)).stream())
                          .toList();
    }

    /// A `ConcurrentHashMap.computeIfPresent` remapping: `null` removes the entry.
    @NullReturn
    private static Integer laterOrNull(Integer recorded, int attempt) {
        return recorded > attempt
               ? recorded
               : null;
    }

    /// One attempt of a unit, the key of its in-flight start and of its exit processing.
    public record AttemptKey(UnitRef ref, int attempt) {}

    /// A unit with a live watcher.
    public record RunningUnit(UnitRef ref, String containerId) {
        static RunningUnit runningUnit(Map.Entry<UnitRef, String> watched) {
            return new RunningUnit(watched.getKey(), watched.getValue());
        }
    }
}
