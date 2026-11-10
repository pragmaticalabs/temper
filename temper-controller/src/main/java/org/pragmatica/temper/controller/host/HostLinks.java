package org.pragmatica.temper.controller.host;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.cancel.UnitCanceller;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.UnitRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.option;


/// Every configured host's link, by name. Also the [UnitCanceller]: a `CancelUnit` goes to the link of the host
/// the store says holds the unit. A unit whose host is not UP stays CANCELLING and the host's next handshake reconciles
/// it (reattach + cancel); a `CancelUnit` that fails on an UP link resets that session, which forces that handshake.
public record HostLinks(Map<HostName, HostLink> links, JobStore jobs) implements UnitCanceller {
    private static final Logger log = LoggerFactory.getLogger(HostLinks.class);

    public HostLinks {
        links = Map.copyOf(links);
    }

    public static HostLinks hostLinks(List<HostLink> links, JobStore jobs) {
        return new HostLinks(links.stream().collect(Collectors.toUnmodifiableMap(HostLink::name, Function.identity())),
                             jobs);
    }

    public List<HostLink> all() {
        return links.values()
                    .stream()
                    .sorted((left, right) -> left.name()
                                                 .value()
                                                 .compareTo(right.name().value()))
                    .toList();
    }

    /// The units of every link that run but whose start is not recorded ([HostLink#unrecordedStarts]): the read side
    /// takes this snapshot BEFORE it reads the rows it derives from (`api.Views.unitState`).
    public Set<UnitAttempt> unrecordedStarts() {
        return links.values()
                    .stream()
                    .flatMap(link -> link.unrecordedStarts()
                                         .stream())
                    .collect(Collectors.toUnmodifiableSet());
    }

    public Option<HostLink> link(HostName name) {
        return option(links.get(name));
    }

    public Unit startAll() {
        all().forEach(HostLink::start);

        return unit();
    }

    public Unit stopAll() {
        all().forEach(HostLink::stop);

        return unit();
    }

    @Override
    public Unit cancel(List<UnitRef> units) {
        units.forEach(this::cancelOne);

        return unit();
    }

    private Unit cancelOne(UnitRef ref) {
        jobs.job(ref.job())
            .map(job -> job.flatMap(row -> hostOf(row, ref)))
            .flatMap(host -> send(host, ref))
            .onFailure(cause -> log.warn("CancelUnit for {} not delivered (the next reconcile resends it): {}",
                                         ref.containerName(),
                                         cause.message()));

        return unit();
    }

    /// A unit with no host was never granted; the store cancels such units itself, so there is nothing to kill.
    private Promise<Unit> send(Option<HostName> host, UnitRef ref) {
        return host.flatMap(this::link)
                   .map(link -> cancelOn(link, ref))
                   .or(Promise::unitPromise);
    }

    /// `requestOrReset`: a CancelUnit that fails, or is answered with anything but `Ack`, closes the session that
    /// carried it, so the reconnect's reconcile finds the unit CANCELLING with a running container and re-sends it.
    ///
    /// It names the unit's current attempt as the store has it ([HostLink#currentAttempt]); a store read that fails
    /// sends nothing, and the next reconcile re-sends the cancel.
    private static Promise<Unit> cancelOn(HostLink link, UnitRef ref) {
        return link.currentAttempt(ref)
                   .flatMap(attempt -> link.requestOrReset(cancelUnit(ref, attempt),
                                                           Message.Ack.class))
                   .mapToUnit();
    }

    private static Message cancelUnit(UnitRef ref, int attempt) {
        return new Message.CancelUnit(ref.job().value(),
                                      ref.unit().value(),
                                      attempt);
    }

    private static Option<HostName> hostOf(JobRow job, UnitRef ref) {
        return Option.from(job.units().stream().filter(unit -> unit.name()
                                                                   .equals(ref.unit())).findFirst()).flatMap(UnitRow::host);
    }
}
