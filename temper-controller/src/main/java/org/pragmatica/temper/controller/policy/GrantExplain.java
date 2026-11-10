package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;


/// Why a queued unit would not start on a host (dashboard D1). Pure. It answers what [GrantPolicy] would decide on
/// this snapshot now, not what the last tick decided: it runs the grant round exactly as the scheduler's `GrantRound`
/// and the store's `grant` do (at most `cap` rounds of [GrantPolicy#choose], each grant folded into the snapshot with
/// [HostSnapshot#withGranted], each round's starvation state handed to the next, a decision naming no unit ending the
/// rounds), so the units it says would start are by construction the ones `choose` grants. The caller's starvation
/// state is not touched.
///
/// Every other unit gets its reasons against the snapshot at the end of the round, in this order:
///
/// 1. [Capped]: host reactors ≥ cap (rule 1), and nothing else is said;
/// 2. else its [Fit] failures (rule 2), all of them;
/// 3. else [StarvationHold]: it fits, but the head has been blocked for more than `bound` (rule 4).
///
/// There is no fourth reason. A round ends uncapped only when `choose` names no unit, and with the head not starved
/// that happens only when no unit fits (rule 5 backfills the first that does), so a unit that fits at the end of an
/// uncapped round is held by starvation. "Behind the head" is not a reason the policy has: one grant per `choose` is
/// what the repeated rounds are for.
///
/// Several hosts ([#explain(List, List, Instant, Duration)]) are explained one after another in the configured order,
/// each host's round seeing only the units no earlier host's round would grant, the way the store hands a later grant
/// transaction only the rows an earlier, committed one left QUEUED. That is exactly what a tick grants when the hosts'
/// grant transactions run one after another in that order. A real tick runs the hosts concurrently, so two things can
/// differ: the order (another host may take a unit first, and since a fit depends on the host, the set started can then
/// differ too), and overlap (a grant transaction locks every candidate row it read until it commits, and a concurrent
/// one skips them and reads the next queued rows instead, so a host whose transaction overlaps another's grants none
/// of those rows that tick, and may grant a unit beyond the first `UnitStore.CANDIDATE_LIMIT`, which this answers
/// [NotRead]). A host the tick would skip ([Skipped]) takes nothing here.
///
/// A `sharedCpu` host ([Ready#sharedCpu], m4-design §9.2) is explained over the units its grant reads: a
/// `--dedicated-cpu` unit is filtered out before the policy, never "not fit", so it is answered [SharedCpu] and never
/// becomes the host's head; a later (dedicated) host still sees it.
public sealed interface GrantExplain {
    /// Why a unit would not start on a host; [Fit.Failure] are the fit rules.
    sealed interface Reason permits Fit.Failure, Capped, StarvationHold, NoLink, Busy, Unknown, NotRead, Retired, Lost, SharedCpu {}

    /// Host reactors (`foreign + active`, A7) have reached the cap: the round grants nothing more.
    record Capped(int reactors, int cap, int foreign) implements Reason {}

    /// The unit fits, but the head `headUnitId` has been blocked since `since`, longer than the starvation bound, so
    /// the host grants nothing until the head fits (rule 4).
    record StarvationHold(long headUnitId, Instant since) implements Reason {}

    /// The scheduler skips the host: its link is not UP (`link` is the link state's wire form), or it is UP without
    /// worker facts yet (`facts` false), so the cap cannot be judged. Decided by the scheduler, not by the policy.
    record NoLink(String link, boolean facts) implements Reason {}

    /// The scheduler's previous grant round on the host has not ended, so the tick skips the host (one round per host
    /// at a time). Decided by the scheduler, not by the policy; what the running round grants is not known here.
    record Busy() implements Reason {}

    /// The host's active units could not be read (`cause`: the read failed or exceeded the tick bound), so no snapshot
    /// exists to judge it by. What a tick would do on the host is not known.
    record Unknown(Cause cause) implements Reason {}

    /// The host's `retireAt` (`at`) has passed (m4-design §10): the scheduler grants nothing there any more; its running
    /// units finish. Decided by the scheduler, not by the policy, before any other check.
    record Retired(Instant at) implements Reason {}

    /// The host is LOST (m4-design §2): declared at `at` for `reason` (`link-down` or `host-key`). The scheduler grants
    /// nothing there until a handshake recovers it (§2.5), whatever its link does meanwhile. Decided by the scheduler,
    /// not by the policy, after [Retired].
    record Lost(String reason, Instant at) implements Reason {}

    /// The unit's job asked for `--dedicated-cpu` and the host's CPU is shared (m4-design §9.2): the host's grant never
    /// reads the unit. Decided by the store's candidate filter, not by the policy.
    record SharedCpu() implements Reason {}

    /// The unit is not among the first `limit` queued units of every CPU class, the rows a dedicated-CPU host's grant
    /// reads while no other grant transaction holds the queue's rows, so no such round sees it. A grant overlapping
    /// another skips that one's locked rows and reads further down the queue, so it can see, and start, this unit. A
    /// shared-CPU host's grant reads the first `limit` units without the `--dedicated-cpu` ones (m4-design §9.2), so
    /// with dedicated units among the first `limit` its window reaches further: on such a host `not-read` can name a
    /// unit its grant would read, never the reverse (the units explained are a prefix of its window). Decided by the
    /// store's candidate read, not by the policy.
    record NotRead(int limit) implements Reason {}

    /// One host's answer: every unit of the queue it was asked about, mapped to its reasons; an empty list is a unit
    /// the round would grant.
    record HostReasons(HostName host, Map<Long, List<Reason>> reasons) {
        public HostReasons {
            reasons = Map.copyOf(reasons);
        }

        public static HostReasons hostReasons(HostName host, Map<Long, List<Reason>> reasons) {
            return new HostReasons(host, reasons);
        }
    }

    /// One host as the explanation sees it: [Ready] with the snapshot and starvation state a tick would read, or
    /// [Skipped] with the reason a tick would not grant there at all.
    sealed interface HostInput {
        HostName host();
    }

    /// `sharedCpu` is the host's CPU class (m4-design §9.1).
    record Ready(HostName host, HostSnapshot snapshot, Starvation starvation, boolean sharedCpu) implements HostInput {
        /// A dedicated-CPU host.
        public static Ready ready(HostName host, HostSnapshot snapshot, Starvation starvation) {
            return new Ready(host, snapshot, starvation, false);
        }

        public static Ready ready(HostName host, HostSnapshot snapshot, Starvation starvation, boolean sharedCpu) {
            return new Ready(host, snapshot, starvation, sharedCpu);
        }

        /// Whether the host's grant reads `unit`: every unit, except a dedicated-CPU one on a shared-CPU host.
        boolean reads(QueuedUnit unit) {
            return ! (sharedCpu && unit.dedicatedCpu());
        }
    }

    record Skipped(HostName host, Reason reason) implements HostInput {
        public static Skipped skipped(HostName host, Reason reason) {
            return new Skipped(host, reason);
        }
    }

    /// The round as it stands: the snapshot with every grant so far, the units still queued, the starvation state the
    /// last `choose` returned, and the units granted.
    record Round(HostSnapshot host, List<QueuedUnit> queued, Starvation starvation, List<Long> granted) {
        Round granting(QueuedUnit unit, Starvation next) {
            return new Round(host.withGranted(unit),
                             queued.stream().filter(other -> other.unitId() != unit.unitId()).toList(),
                             next,
                             Stream.concat(granted.stream(),
                                           Stream.of(unit.unitId())).toList());
        }

        Round ended(Starvation next) {
            return new Round(host, queued, next, granted);
        }
    }

    /// Every host in the given (configured) order, each over the units of `ordered` (the store's grant order) that no
    /// earlier host's round would grant; see the type's comment for what that sequence guarantees. A unit an earlier
    /// host would grant is absent from a later host's answer.
    static List<HostReasons> explain(List<HostInput> hosts, List<QueuedUnit> ordered, Instant now, Duration bound) {
        return hosts.isEmpty()
               ? List.of()
               : explainFrom(hosts, ordered, now, bound);
    }

    private static List<HostReasons> explainFrom(List<HostInput> hosts,
                                                 List<QueuedUnit> ordered,
                                                 Instant now,
                                                 Duration bound) {
        var first = HostReasons.hostReasons(hosts.getFirst().host(),
                                            explainOn(hosts.getFirst(), ordered, now, bound));

        return Stream.concat(Stream.of(first),
                             explain(hosts.subList(1,
                                                   hosts.size()),
                                     notGranted(ordered, first),
                                     now,
                                     bound).stream())
                     .toList();
    }

    private static Map<Long, List<Reason>> explainOn(HostInput host,
                                                     List<QueuedUnit> ordered,
                                                     Instant now,
                                                     Duration bound) {
        return switch (host) {
            case Ready ready -> explainReady(ready, ordered, now, bound);
            case Skipped skipped -> every(ordered, skipped.reason());
        };
    }

    /// The round over the units the host's grant reads; every other unit is [SharedCpu].
    private static Map<Long, List<Reason>> explainReady(Ready ready,
                                                        List<QueuedUnit> ordered,
                                                        Instant now,
                                                        Duration bound) {
        var read = ordered.stream().collect(Collectors.partitioningBy(ready::reads));

        return merged(explain(ready.snapshot(), read.get(true), ready.starvation(), now, bound),
                      every(read.get(false), new SharedCpu()));
    }

    private static Map<Long, List<Reason>> merged(Map<Long, List<Reason>> first, Map<Long, List<Reason>> second) {
        return Stream.concat(first.entrySet().stream(),
                             second.entrySet().stream())
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static List<QueuedUnit> notGranted(List<QueuedUnit> ordered, HostReasons host) {
        return ordered.stream()
                      .filter(unit -> !host.reasons()
                                           .get(unit.unitId())
                                           .isEmpty())
                      .toList();
    }

    /// Why a tick would skip a host it cannot grant on, in the order the tick checks: not UP, then a round still in
    /// flight, then no worker facts.
    static Reason skipReason(String link, boolean up, boolean facts, boolean busy) {
        return up && busy
               ? new Busy()
               : new NoLink(link, facts);
    }

    /// Every unit of `ordered` (the store's grant order) mapped to its reasons on `host`; empty for a unit the round
    /// would grant.
    static Map<Long, List<Reason>> explain(HostSnapshot host,
                                           List<QueuedUnit> ordered,
                                           Starvation starvation,
                                           Instant now,
                                           Duration bound) {
        var end = round(new Round(host, ordered, starvation, List.of()), now, bound);

        return ordered.stream()
                      .collect(Collectors.toUnmodifiableMap(QueuedUnit::unitId,
                                                            unit -> reasons(end, unit, now, bound)));
    }

    /// The store's rounds: at most `cap` (its `maxGrants`, the host's cap) calls of `choose`.
    private static Round round(Round round, Instant now, Duration bound) {
        return round.granted()
                    .size() >= round.host()
                                    .cap()
               ? round
               : next(round,
                      GrantPolicy.choose(round.host(), round.queued(), round.starvation(), now, bound),
                      now,
                      bound);
    }

    /// As `GrantRound`: the starvation state is the decision's in every case; a granted unit is folded in and the next
    /// round runs; no unit, or one not in the list, ends the rounds.
    private static Round next(Round round, GrantDecision decision, Instant now, Duration bound) {
        return decision.grant()
                       .flatMap(id -> unit(round.queued(),
                                           id))
                       .map(unit -> round(round.granting(unit,
                                                         decision.next()),
                                          now,
                                          bound))
                       .or(() -> round.ended(decision.next()));
    }

    private static Option<QueuedUnit> unit(List<QueuedUnit> queued, long id) {
        return Option.from(queued.stream().filter(unit -> unit.unitId() == id).findFirst());
    }

    private static List<Reason> reasons(Round end, QueuedUnit unit, Instant now, Duration bound) {
        return end.granted()
                  .contains(unit.unitId())
               ? List.of()
               : blocked(end, unit, now, bound);
    }

    private static List<Reason> blocked(Round end, QueuedUnit unit, Instant now, Duration bound) {
        return end.host()
                  .reactors() >= end.host()
                                    .cap()
               ? List.of(capped(end.host()))
               : unfitOrHeld(end,
                             Fit.fit(end.host(), unit),
                             now,
                             bound);
    }

    private static Reason capped(HostSnapshot host) {
        return new Capped(host.reactors(), host.cap(), host.foreignReactors());
    }

    private static List<Reason> unfitOrHeld(Round end, Fit fit, Instant now, Duration bound) {
        return fit.fits()
               ? held(end.starvation(), now, bound)
               : List.copyOf(fit.failures());
    }

    /// Empty only if the round's own logic was broken (see the type's comment); the property test pins that it is not.
    private static List<Reason> held(Starvation starvation, Instant now, Duration bound) {
        return GrantPolicy.starved(starvation, now, bound)
               ? hold(starvation)
               : List.of();
    }

    private static List<Reason> hold(Starvation starvation) {
        return Option.all(starvation.headUnitId(),
                          starvation.blockedSince())
                     .map(StarvationHold::new)
                     .map(GrantExplain::only)
                     .or(List.of());
    }

    private static List<Reason> only(Reason reason) {
        return List.of(reason);
    }

    /// A host the tick would skip: every unit gets the same reason.
    private static Map<Long, List<Reason>> every(List<QueuedUnit> ordered, Reason reason) {
        return ordered.stream()
                      .collect(Collectors.toUnmodifiableMap(QueuedUnit::unitId,
                                                            _ -> only(reason)));
    }

    record unused() implements GrantExplain {}
}
