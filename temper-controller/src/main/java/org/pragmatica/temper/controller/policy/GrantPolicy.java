package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Which queued unit a host gets next (spec §6.1, m0-design §3.10). Pure. `ordered` is the store's queue order
/// (priority desc, submit time asc, id asc). The rules, in order:
///
/// 1. **Cap (A7):** host reactors (`foreign + active`) ≥ cap → grant nothing; starvation unchanged.
/// 2. **Fit:** `activeMemory + memoryMb ≤ budget`, `memAvailable − pendingMemory − memoryMb ≥ memFloor` (only when the
///    host sets a memory floor, [HostSnapshot]), `freeBytes ≥ floor`, and, for a unit that goes through bb-hold
///    ([HostSnapshot#holds]: it needs the port group and is a Gate or the host is not port-isolated), a free
///    port-group slot ([HostSnapshot#portGroupFree]: fewer than `maxHolding` temper units wait for or hold the
///    group; m1-design §1.3, Q-M1-12, ruling R-3).
/// 3. **Head:** the head fits → grant it, starvation reset.
/// 4. **Starvation:** the head does not fit → remember since when it is blocked (first time this head is seen
///    blocked); once blocked for MORE than `bound`, grant nothing, so the head gets the next free capacity.
/// 5. **Backfill:** otherwise grant the first unit that fits.
///
/// An empty queue resets the starvation state.
public sealed interface GrantPolicy {
    Duration STARVATION_BOUND = Duration.ofSeconds(1200);

    static GrantDecision choose(HostSnapshot host,
                                List<QueuedUnit> ordered,
                                Starvation starvation,
                                Instant now,
                                Duration bound) {
        return host.reactors() >= host.cap()
               ? GrantDecision.grantDecision(none(), starvation)
               : uncapped(host, ordered, starvation, now, bound);
    }

    /// The fit rule, read from [Fit] so that [GrantExplain] names the same rules this decides by.
    static boolean fits(HostSnapshot host, QueuedUnit unit) {
        return Fit.fit(host, unit).fits();
    }

    private static GrantDecision uncapped(HostSnapshot host,
                                          List<QueuedUnit> ordered,
                                          Starvation starvation,
                                          Instant now,
                                          Duration bound) {
        return ordered.isEmpty()
               ? GrantDecision.grantDecision(none(), Starvation.NONE)
               : withHead(host, ordered, starvation, now, bound);
    }

    private static GrantDecision withHead(HostSnapshot host,
                                          List<QueuedUnit> ordered,
                                          Starvation starvation,
                                          Instant now,
                                          Duration bound) {
        var head = ordered.getFirst();

        return fits(host, head)
               ? GrantDecision.grantDecision(some(head.unitId()), Starvation.NONE)
               : blockedHead(host, ordered, blocked(head, starvation, now), now, bound);
    }

    /// The same head keeps its first blocked time; a new head starts its own clock.
    private static Starvation blocked(QueuedUnit head, Starvation starvation, Instant now) {
        return starvation.headUnitId()
                         .filter(id -> id == head.unitId())
                         .flatMap(_ -> starvation.blockedSince())
                         .map(_ -> starvation)
                         .or(() -> Starvation.starvation(some(head.unitId()),
                                                         some(now)));
    }

    private static GrantDecision blockedHead(HostSnapshot host,
                                             List<QueuedUnit> ordered,
                                             Starvation starvation,
                                             Instant now,
                                             Duration bound) {
        return starved(starvation, now, bound)
               ? GrantDecision.grantDecision(none(), starvation)
               : GrantDecision.grantDecision(backfill(host, ordered), starvation);
    }

    /// The head has been blocked for MORE than `bound`: grant nothing. Not private only so that [GrantExplain] judges
    /// the hold by the same rule.
    static boolean starved(Starvation starvation, Instant now, Duration bound) {
        return starvation.blockedSince()
                         .filter(since -> Duration.between(since, now).compareTo(bound) > 0)
                         .isPresent();
    }

    private static Option<Long> backfill(HostSnapshot host, List<QueuedUnit> ordered) {
        return Option.from(ordered.stream().skip(1).filter(unit -> fits(host, unit)).map(QueuedUnit::unitId).findFirst());
    }

    record unused() implements GrantPolicy {}
}
