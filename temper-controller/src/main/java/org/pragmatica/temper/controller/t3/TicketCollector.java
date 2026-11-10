package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.SourceSeen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The ticket collection round: every 30 s, every collected host concurrently, never
/// overlapping per host. A read is bounded by its transport (20 s, 8 MiB); a reply is accepted only when [TicketParse]
/// proves it complete. A success is [BbStore#collected] (baseline and alarm marks read, the round's events and marks
/// ([TicketAlarms]), snapshot, collect state, the
/// `tickets:<host>` source, in one transaction, the events through the [FleetWriter] so the restore check and the wake
/// run for them); a failure is [BbStore#failed] and nothing else: the snapshot stays, no event is emitted, and the
/// source ages into `source-stale tickets:<host>` by T1's rule.
///
/// FER: a failed host is logged when its error first appears or changes, and once when it recovers, not every 30 s.
public final class TicketCollector {
    private static final Logger log = LoggerFactory.getLogger(TicketCollector.class);
    private static final TimeSpan EVERY = TimeSpan.timeSpan(BbStore.PERIOD_S).seconds();
    /// The collector's own bound on a read: strictly above the transport's [CommandTransport#READ_BOUND] (20 s), so the
    /// transport's `timeout` reports first and this one only catches a transport whose promise never completes.
    public static final TimeSpan READ_BOUND = TimeSpan.timeSpan(25).seconds();

    /// A collected host, how it is read, and its `[[hosts]]` `retireAt` (none for a `bbHosts` entry): from that instant
    /// on it is not read again (#103).
    public record Target(String host, TicketTransport transport, Option<Instant> retireAt) {
        public static Target target(String host, TicketTransport transport) {
            return new Target(host, transport, none());
        }

        public static Target target(String host, TicketTransport transport, Option<Instant> retireAt) {
            return new Target(host, transport, retireAt);
        }

        /// Retired at the instant itself (m4-design §10).
        boolean retired(Instant now) {
            return retireAt.filter(at -> !now.isBefore(at))
                           .isPresent();
        }
    }

    private final BbStore store;
    private final FleetStore fleet;
    private final FleetWriter writer;
    private final List<Target> targets;
    private final JsonMapper mapper;
    private final Supplier<Instant> clock;
    private final TicketAlarms.Round round;
    private final TimeSpan bound;
    private final TimeSpan stepBound;
    private final Set<Promise<Unit>> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<String, String> failing = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> scheduled = new AtomicReference<>(none());

    private TicketCollector(BbStore store,
                            FleetStore fleet,
                            FleetWriter writer,
                            List<Target> targets,
                            JsonMapper mapper,
                            Supplier<Instant> clock,
                            TicketAlarms.Round round,
                            TimeSpan bound) {
        this.store = store;
        this.fleet = fleet;
        this.writer = writer;
        this.targets = List.copyOf(targets);
        this.mapper = mapper;
        this.clock = clock;
        this.round = round;
        this.bound = bound;
        this.stepBound = TimeSpan.timeSpan(bound.millis() * 2).millis();
    }

    public static TicketCollector ticketCollector(BbStore store,
                                                  FleetStore fleet,
                                                  FleetWriter writer,
                                                  List<Target> targets,
                                                  JsonMapper mapper,
                                                  Supplier<Instant> clock,
                                                  TicketDiff diff) {
        return ticketCollector(store, fleet, writer, targets, mapper, clock, diff, READ_BOUND);
    }

    /// With the collector's own read bound given (tests); the diff only, no alarm.
    public static TicketCollector ticketCollector(BbStore store,
                                                  FleetStore fleet,
                                                  FleetWriter writer,
                                                  List<Target> targets,
                                                  JsonMapper mapper,
                                                  Supplier<Instant> clock,
                                                  TicketDiff diff,
                                                  TimeSpan bound) {
        return ticketCollector(store, fleet, writer, targets, mapper, clock, TicketAlarms.diffOnly(diff), bound);
    }

    /// With the whole round given: the diff and the alarms.
    public static TicketCollector ticketCollector(BbStore store,
                                                  FleetStore fleet,
                                                  FleetWriter writer,
                                                  List<Target> targets,
                                                  JsonMapper mapper,
                                                  Supplier<Instant> clock,
                                                  TicketAlarms.Round round,
                                                  TimeSpan bound) {
        return new TicketCollector(store, fleet, writer, targets, mapper, clock, round, bound);
    }

    /// The hosts this collector reads, in configuration order.
    public List<String> hosts() {
        return targets.stream()
                      .map(Target::host)
                      .toList();
    }

    /// A `fleet_source` row `tickets:<host>` (period 30) for every collected host, so one never read is stale, not absent.
    public Promise<Unit> settle() {
        return fleet.expect(targets.stream()
                                   .map(target -> SourceSeen.sourceSeen(BbStore.source(target.host()),
                                                                        none(),
                                                                        BbStore.PERIOD_S))
                                   .toList());
    }

    /// Starts the 30 s schedule, with a first round at once (a round still going when the next is due skips that host).
    public Unit schedule() {
        scheduled.set(some(SharedScheduler.scheduleAtFixedRate(this::due, EVERY)));
        due();

        return unit();
    }

    /// Stops the schedule and starts no further read; completes within twice [#READ_BOUND] (each host's step, read plus
    /// record, is bounded by that; an abandoned step is logged). An abandoned step may still commit after the caller
    /// closes the store; if it does, it is serialized per host by the store and dropped when stale.
    public Promise<Unit> stop() {
        stopped.set(true);
        scheduled.getAndSet(none()).onPresent(future -> future.cancel(false));

        return Promise.allOf(List.copyOf(inFlight)).mapToUnit();
    }

    private Unit due() {
        if (!stopped.get()) {
            round();
        }

        return unit();
    }

    /// One round: every host concurrently; completes when all have. Never fails.
    public Promise<Unit> round() {
        return Promise.allOf(targets.stream().map(this::guarded).toList()).mapToUnit();
    }

    /// One host's collection, unless one is still running or the host is retired (judged at the clock as its turn comes).
    private Promise<Unit> guarded(Target target) {
        var busy = running.computeIfAbsent(target.host(), _ -> new AtomicBoolean());

        return ! target.retired(clock.get()) && busy.compareAndSet(false, true)
               ? tracked(target, busy)
               : Promise.success(unit());
    }

    /// Registered in flight BEFORE the stopped check, and [#stop] sets the flag before it reads the set: a read either
    /// is awaited by stop or sees the flag and does not start.
    private Promise<Unit> tracked(Target target, AtomicBoolean busy) {
        var done = Promise.<Unit> promise();

        inFlight.add(done);
        done.withResult(_ -> {
            busy.set(false);
            inFlight.remove(done);
        });
        if (stopped.get()) {
            done.succeed(unit());
        } else {
            collect(target).timeout(stepBound)
                   .recover(cause -> abandoned(target.host(),
                                               cause))
                   .withResult(done::resolve);
        }

        return done;
    }

    /// One host, read and recorded; whatever the outcome it is recorded and the promise succeeds. `now` (`t0`) is taken
    /// before the read and is the snapshot's `taken_at`; `t1` after the reply is read, before it is parsed. The skew
    /// is measured against `[t0, t1]`.
    public Promise<Unit> collect(Target target) {
        var now = clock.get();

        return read(target).timeout(bound)
                   .mapError(TicketCollector::boundedRead)
                   .map(output -> new Read(output,
                                           clock.get()))
                   .flatMap(read -> TicketParse.parse(mapper,
                                                      read.output())
                                               .map(reply -> listing(now,
                                                                     read.t1(),
                                                                     reply))
                                               .async())
                   .fold(result -> result.fold(cause -> failed(target.host(),
                                                               now,
                                                               cause),
                                               listing -> stored(target.host(),
                                                                 now,
                                                                 listing)));
    }

    /// A reply as read, with the controller instant right after it.
    private record Read(String output, Instant t1) {}

    private static BbStore.Listing listing(Instant t0, Instant t1, TicketParse.Reply reply) {
        return BbStore.Listing.listing(reply.queue(),
                                       reply.tickets(),
                                       reply.at(),
                                       TicketAlarms.skew(t0, t1, reply.at()));
    }

    /// A transport that throws instead of answering fails the read as one that could not start.
    private static Promise<String> read(Target target) {
        try {
            return target.transport()
                         .read(target.host());
        } catch (RuntimeException thrown) {
            return TicketError.Unstartable.unstartable(String.valueOf(thrown.getMessage())).promise();
        }
    }

    /// The whole step (read plus record) outlived its bound: the host is released for the next round and the abandon is
    /// logged, so [#stop] always completes.
    private static Unit abandoned(String host, Cause cause) {
        log.warn("tickets: {}: step abandoned, may not be recorded: {}", host, cause.message());

        return unit();
    }

    /// A transport that never answered within [#READ_BOUND] is a `timeout`, as one the transport timed out.
    private static Cause boundedRead(Cause cause) {
        return cause instanceof CoreError.Timeout
               ? TicketError.Timeout.TIMEOUT
               : cause;
    }

    /// A store failure is not one of the causes: it is logged, never written as the host's `last_error`.
    private Promise<Unit> stored(String host, Instant now, BbStore.Listing listing) {
        return write(host, now, listing).fold(result -> result.fold(cause -> Promise.success(notStored(host, cause)),
                                                                    _ -> recovered(host)));
    }

    private static Unit notStored(String host, Cause cause) {
        if (cause instanceof BbStore.StaleStep) {
            log.info("tickets: {}: {}", host, cause.message());

            return unit();
        }

        return warned(host, "cannot store the tickets", cause);
    }

    private Promise<Unit> write(String host, Instant now, BbStore.Listing listing) {
        return writer.detected(() -> store.collected(host,
                                                     now,
                                                     listing,
                                                     (baseline, rows) -> round.write(host, now, listing, baseline, rows)))
                     .mapToUnit();
    }

    private Promise<Unit> failed(String host, Instant now, Cause cause) {
        var changed = !cause.message().equals(failing.put(host, cause.message()));

        return store.failed(host,
                            now,
                            cause.message())
                    .recover(storeFailure -> warned(host, "cannot record the failure", storeFailure))
                    .map(_ -> changed
                              ? warned(host, "tickets not collected", cause)
                              : unit());
    }

    private Promise<Unit> recovered(String host) {
        if (failing.remove(host) != null) {
            log.info("tickets: {} collected again", host);
        }

        return Promise.success(unit());
    }

    private static Unit warned(String host, String what, Cause cause) {
        log.warn("tickets: {}: {}: {}", host, what, cause.message());

        return unit();
    }
}
