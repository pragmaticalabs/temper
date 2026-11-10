package org.pragmatica.temper.controller.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.policy.GrantPolicy;
import org.pragmatica.temper.controller.policy.HostSnapshot;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;


/// The policy closure passed to `UnitStore.grant` for one host and one tick. The store calls it once per round with
/// the units still queued; the closure owns the state the store does not carry (WP2 handoff): the host snapshot,
/// updated with every unit granted this tick, and the host's starvation state, which outlives the tick.
public record GrantRound(AtomicReference<HostSnapshot> snapshot,
                         AtomicReference<Starvation> starvation,
                         Instant now,
                         Duration bound) implements Fn1<GrantDecision, List<QueuedUnit>> {
    public static GrantRound grantRound(HostSnapshot snapshot,
                                        AtomicReference<Starvation> starvation,
                                        Instant now,
                                        Duration bound) {
        return new GrantRound(new AtomicReference<>(snapshot), starvation, now, bound);
    }

    @Override
    public GrantDecision apply(List<QueuedUnit> queued) {
        var decision = GrantPolicy.choose(snapshot.get(), queued, starvation.get(), now, bound);

        starvation.set(decision.next());
        decision.grant()
                .flatMap(id -> unit(queued, id))
                .onPresent(granted -> snapshot.updateAndGet(current -> current.withGranted(granted)));

        return decision;
    }

    private static Option<QueuedUnit> unit(List<QueuedUnit> queued, long id) {
        return Option.from(queued.stream().filter(unit -> unit.unitId() == id).findFirst());
    }
}
