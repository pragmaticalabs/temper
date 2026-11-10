package org.pragmatica.temper.controller.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.flow.Collect;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.policy.GrantExplain;
import org.pragmatica.temper.controller.policy.HostSnapshot;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.UnitStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The grant loop (m0-design §6.5): a tick every `interval` and on every wake signal; ticks run one at a time (a
/// tick requested while one runs is coalesced into one more tick after it). Each tick, for every UP host:
///
/// 1. the [HostSnapshot]: `foreignReactors` and free disk from the latest worker facts, `activeUnits`, their
///    memory and the port-group units among them from `UnitStore.active` (GRANTED counts, so a grant whose container
///    has not started, or that waits in bb-hold, still counts). A7: temper units are never counted from `ps`. For the
///    memory floor, MemAvailable from the facts and the memory of the active units those facts cannot reflect yet
///    ([#pendingMemoryMb]);
/// 2. `UnitStore.grant` with a [GrantRound] (at most `cap` grants per tick);
/// 3. each grant is launched on the host's link ([HostLink#launch]: `PrepareJob`, `StartUnit`).
///
/// A host without facts yet is skipped (no foreign-reactor count means the cap cannot be judged). A retired host (its
/// `retireAt` at or before the tick's clock plus the retire window, m4-design §10, #107) is skipped before its round, so it gets no new grant from
/// that tick on; its link stays up, so its running units heartbeat, finish and are collected as before. A round already
/// running when the window opens may still commit, which bounds the overrun by one round (`ROUND_BOUND_TICKS ×
/// tickBound`).
public final class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final HostLinks links;
    private final UnitStore units;
    private final Duration lease;
    private final Duration starvationBound;
    private final Duration retireWindow;
    private final long floorBytes;
    private final int maxHolding;
    private final Supplier<Instant> clock;
    private final TimeSpan tickBound;
    /// Tick requests not yet served: 0 = idle. Only the request that moves it from 0 starts a run, and a run
    /// subtracts the requests it served, so a request that arrives during a run is never lost.
    private final AtomicLong requests = new AtomicLong();
    private final Set<HostName> inFlight = ConcurrentHashMap.newKeySet();

    private static final int ROUND_BOUND_TICKS = 5;

    private final AtomicReference<Option<ScheduledFuture<?>>> periodic = new AtomicReference<>(none());
    private final Map<HostName, AtomicReference<Starvation>> starvation = new ConcurrentHashMap<>();

    private Scheduler(HostLinks links,
                      UnitStore units,
                      Duration lease,
                      Duration starvationBound,
                      Duration retireWindow,
                      long floorBytes,
                      int maxHolding,
                      Supplier<Instant> clock,
                      TimeSpan tickBound) {
        this.links = links;
        this.units = units;
        this.lease = lease;
        this.starvationBound = starvationBound;
        this.retireWindow = retireWindow;
        this.floorBytes = floorBytes;
        this.maxHolding = maxHolding;
        this.clock = clock;
        this.tickBound = tickBound;
    }

    public static Scheduler scheduler(HostLinks links,
                                      UnitStore units,
                                      Duration lease,
                                      Duration starvationBound,
                                      Duration retireWindow,
                                      long floorBytes,
                                      int maxHolding,
                                      Supplier<Instant> clock,
                                      TimeSpan tickBound) {
        return new Scheduler(links,
                             units,
                             lease,
                             starvationBound,
                             retireWindow,
                             floorBytes,
                             maxHolding,
                             clock,
                             tickBound);
    }

    /// Ticks every `interval` and on every wake.
    public Unit start(TimeSpan interval, Wake wake) {
        wake.listen(this::requestTick);
        periodic.set(some(SharedScheduler.scheduleAtFixedRate(this::requestTick, interval)));

        return unit();
    }

    /// Stops the periodic tick (wake-triggered ticks then find no UP host once the links are stopped).
    public Unit stop() {
        periodic.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// Runs a tick now, or once more after the running one (requests during a run coalesce into one more run).
    public Unit requestTick() {
        return requests.getAndIncrement() == 0
               ? run()
               : unit();
    }

    /// A tick is bounded by `tickBound`, so one store call that never resolves cannot stop the scheduler: the next
    /// tick runs regardless (design-out: a tick holds no state that a later tick depends on).
    private Unit run() {
        var served = requests.get();
        // May run late: the tick chain is detached; `finished` only re-runs coalesced requests, so none is lost.
        guardedTick().timeout(tickBound)
                   .onFailure(cause -> log.warn("grant tick ended: {}",
                                                cause.message()))
                   .onResultRun(() -> finished(served));

        return unit();
    }

    private Unit finished(long served) {
        return requests.addAndGet(-served) > 0
               ? run()
               : unit();
    }

    /// One tick over every UP host; resolves when every host's grants are made (launches continue on their own).
    /// A tick that throws while being built still resolves (as a failure), so `finished` always runs and the request
    /// counter always returns to 0.
    private Promise<Unit> guardedTick() {
        return Result.lift(Causes::fromThrowable, this::tick).fold(Cause::promise, tick -> tick);
    }

    /// The clock is read once per tick, so every host is judged retired or not at the same instant.
    public Promise<Unit> tick() {
        var now = clock.get();

        return Promise.allOf(links.all()
                                  .stream()
                                  .filter(HostLink::isUp)
                                  .filter(link -> link.config()
                                                      .refusesGrantsSince(now, retireWindow)
                                                      .isEmpty())
                                  .map(this::tickHost)
                                  .toList()).mapToUnit();
    }

    /// FER: one host's failed tick is logged and does not fail the others; the next tick (one interval later at the
    /// latest) grants again from the store's state, which a failed `grant` transaction left unchanged.
    /// At most one grant round per host at a time, even across ticks: a tick that hit `tickBound` leaves its host's
    /// round running, and later ticks skip that host until the round ends (§6.5 non-reentrant ticks; two overlapping
    /// rounds would compute the cap from two stale snapshots). A round whose store call never resolves releases the
    /// host after `ROUND_BOUND_TICKS × tickBound` (logged), so one hung call cannot starve the host for good; the
    /// store's conditional `UPDATE ... WHERE state = 'QUEUED'` still rules out a double grant then. The release does
    /// not cancel the store call; a retiring host's grant is fenced at COMMIT instead (#111, [#grantOn]).
    private Promise<Unit> tickHost(HostLink link) {
        return inFlight.add(link.name())
               ? roundOn(link).timeout(roundBound())
                        .onFailure(cause -> log.warn("[{}] grant round released: {}",
                                                     link.name(),
                                                     cause.message()))
                        .withResult(_ -> inFlight.remove(link.name()))
               : Promise.unitPromise();
    }

    private TimeSpan roundBound() {
        return TimeSpan.timeSpan(tickBound.nanos() * ROUND_BOUND_TICKS).nanos();
    }

    private Promise<Unit> roundOn(HostLink link) {
        return link.facts()
                   .map(facts -> grantOn(link, facts))
                   .or(Promise::unitPromise)
                   .onFailure(cause -> log.warn("[{}] grant tick failed: {}",
                                                link.name(),
                                                cause.message()))
                   .recover(_ -> unit());
    }

    /// A retiring host's grant carries its fence (#111): the store refuses the COMMIT from `retireAt - retireWindow` on
    /// by the database clock, so a call that outlives the round bound cannot land after the host stops taking grants.
    /// A refused COMMIT fails this round (logged in [#roundOn]) with every unit left QUEUED, so no launch follows and
    /// another host may grant the unit. A host without `retireAt` passes `none`, which the store sends as the explicit
    /// "unfenced" sentinel; the database refuses a grant that carries no fence at all.
    private Promise<Unit> grantOn(HostLink link, HostFacts facts) {
        return units.active(link.name())
                    .map(active -> snapshot(link, facts, active))
                    .flatMap(snapshot -> units.grant(link.name(),
                                                     GrantRound.grantRound(snapshot,
                                                                           starvationOf(link.name()),
                                                                           clock.get(),
                                                                           starvationBound),
                                                     TimeSpan.fromDuration(lease),
                                                     link.config().cap(),
                                                     link.config().grantFence(retireWindow)))
                    .map(granted -> launched(link, granted));
    }

    /// Each launch settles its own outcome (started, failed, or left for reconcile).
    /// Fire-and-forget by design: every launch settles its own outcome (started, finished, or its session reset so
    /// reconcile settles the unit), so the tick has nothing to wait for.
    private static Unit launched(HostLink link, List<Granted> granted) {
        granted.forEach(unit -> launch(link, unit));

        return unit();
    }

    private static Unit launch(HostLink link, Granted granted) {
        link.launch(granted);

        return unit();
    }

    /// Why each unit of `ordered` (the store's grant order) would not start, per configured host (dashboard D1): what
    /// the policy would decide on inputs read now the way a tick reads them (the link, its latest facts,
    /// `UnitStore.active`, the host's starvation state, the clock), not what the last tick decided. The inputs are read
    /// for every host at once (Fork-Join); the hosts are then explained one after another in the configured order
    /// ([GrantExplain#explain(List, List, Instant, Duration)] says what that sequence guarantees against a real tick).
    /// A host the tick would skip (not UP, its previous round still in flight, no facts yet) answers that reason for
    /// every unit. The starvation state is read, never changed.
    /// `Collect.all`: `readyInput` answers an unreadable host as Unknown, so no input fails today; if one ever does, the
    /// digest's 500 is the same and its log names every host.
    public Promise<List<GrantExplain.HostReasons>> explain(List<QueuedUnit> ordered) {
        return explainAt(ordered, clock.get());
    }

    /// One instant for every host's retirement and for the policy, as a tick reads it.
    private Promise<List<GrantExplain.HostReasons>> explainAt(List<QueuedUnit> ordered, Instant now) {
        return Collect.all(links.all().stream().map(link -> explainInput(link, now)).toList()).map(inputs -> GrantExplain.explain(inputs,
                                                                                                                                  ordered,
                                                                                                                                  now,
                                                                                                                                  starvationBound));
    }

    /// A retired host first: it answers `retired` whatever its link (m4-design §10), as the reason that outlasts the
    /// others.
    private Promise<GrantExplain.HostInput> explainInput(HostLink link, Instant now) {
        return link.config()
                   .refusesGrantsSince(now, retireWindow)
                   .map(at -> Promise.<GrantExplain.HostInput> success(GrantExplain.Skipped.skipped(link.name(),
                                                                                                    new GrantExplain.Retired(at))))
                   .or(() -> lostInput(link));
    }

    /// A LOST host next: it answers `lost` with its mark (m4-design §2, §11), since no link state lets it grant until a
    /// handshake recovers it.
    private Promise<GrantExplain.HostInput> lostInput(HostLink link) {
        return link.lost()
                   .map(loss -> Promise.<GrantExplain.HostInput> success(GrantExplain.Skipped.skipped(link.name(),
                                                                                                      new GrantExplain.Lost(loss.reason(),
                                                                                                                            loss.at()))))
                   .or(() -> linkInput(link));
    }

    /// The tick's own checks, in its order: UP, no round in flight, facts. Each is read once, so the skip reason names
    /// the values the decision used (a round ending between two reads would otherwise report `no-link link=UP`). The
    /// link is named as the scheduler sees it: one whose LOST verdict is deferred says `DEFERRED`, and a mark that lands
    /// after the check above says `LOST`.
    private Promise<GrantExplain.HostInput> linkInput(HostLink link) {
        return explainInput(link,
                            link.grantState(),
                            link.isUp(),
                            inFlight.contains(link.name()),
                            link.facts());
    }

    private Promise<GrantExplain.HostInput> explainInput(HostLink link,
                                                         String state,
                                                         boolean up,
                                                         boolean busy,
                                                         Option<HostFacts> facts) {
        return facts.filter(_ -> up && !busy)
                    .map(found -> readyInput(link, found))
                    .or(() -> Promise.success(GrantExplain.Skipped.skipped(link.name(),
                                                                           GrantExplain.skipReason(state,
                                                                                                   up,
                                                                                                   facts.isPresent(),
                                                                                                   busy))));
    }

    /// FER: a host whose active units cannot be read within the tick bound is answered [GrantExplain.Unknown] (with
    /// the failure) and the other hosts are still explained; the digest degrades to "unknown" for that host instead of
    /// failing whole. The read is bounded by `tickBound`, the bound a tick itself runs under. The failure is logged
    /// here; the digest, which a read-only token can fetch, says only that the read failed.
    private Promise<GrantExplain.HostInput> readyInput(HostLink link, HostFacts facts) {
        return units.active(link.name())
                    .timeout(tickBound)
                    .onFailure(cause -> log.warn("[{}] digest: active units not read: {}",
                                                 link.name(),
                                                 cause.message()))
                    .map(active -> ready(link, facts, active))
                    .recover(cause -> GrantExplain.Skipped.skipped(link.name(),
                                                                   new GrantExplain.Unknown(cause)));
    }

    /// Reads the starvation state without creating an entry: the explanation never changes the scheduler's state.
    private GrantExplain.HostInput ready(HostLink link, HostFacts facts, List<ActiveUnit> active) {
        return GrantExplain.Ready.ready(link.name(),
                                        snapshot(link, facts, active),
                                        Option.option(starvation.get(link.name()))
                                              .map(AtomicReference::get)
                                              .or(Starvation.NONE),
                                        link.config().sharedCpu());
    }

    HostSnapshot snapshot(HostLink link, HostFacts facts, List<ActiveUnit> active) {
        return HostSnapshot.hostSnapshot(link.config().cap(),
                                         facts.foreignReactors(),
                                         active.size(),
                                         link.config().memoryBudgetMb(),
                                         active.stream().mapToInt(ActiveUnit::memoryMb).sum(),
                                         Math.min(facts.freeDockerRootBytes(), facts.freeWorkspaceBytes()),
                                         floorBytes,
                                         link.config().portIsolated(),
                                         (int) active.stream().filter(unit -> holdsPortGroup(unit, link)).count(),
                                         maxHolding,
                                         facts.memAvailableKb() / 1024,
                                         pendingMemoryMb(active, facts.at()),
                                         link.config().memFloorGiB() * 1024L);
    }

    /// The memory of the active units the facts sampled at `factsAt` cannot reflect ([HostSnapshot]): those without a
    /// recorded start (GRANTED, and an UNKNOWN unit whose start was not recorded, which may then count twice: the
    /// safe direction), and those whose start is not before the sample. Both instants are the worker's clock; the
    /// cases this comparison still misjudges (a start inside a probe, a hold or adopted unit's early start, a backward
    /// clock step) are listed on [HostSnapshot] and fall under its margin caveat.
    static int pendingMemoryMb(List<ActiveUnit> active, Instant factsAt) {
        return active.stream()
                     .filter(unit -> unreflected(unit, factsAt))
                     .mapToInt(ActiveUnit::memoryMb)
                     .sum();
    }

    private static boolean unreflected(ActiveUnit unit, Instant factsAt) {
        return unit.startedAt()
                   .filter(factsAt::isAfter)
                   .isEmpty();
    }

    private static boolean holdsPortGroup(ActiveUnit unit, HostLink link) {
        return HostSnapshot.holdsPortGroup(unit.kind(),
                                           unit.needsPortGroup(),
                                           link.config().portIsolated());
    }

    /// Hosts with a grant round in flight (diagnostics and tests: zero once a tick's promise has resolved).
    public int roundsInFlight() {
        return inFlight.size();
    }

    /// The host's current starvation state; in memory, so a controller restart resets it (m0-design §3.10 [P]).
    public Starvation starvation(HostName host) {
        return starvationOf(host).get();
    }

    private AtomicReference<Starvation> starvationOf(HostName host) {
        return starvation.computeIfAbsent(host, _ -> new AtomicReference<>(Starvation.NONE));
    }
}
