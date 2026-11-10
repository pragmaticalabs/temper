package org.pragmatica.temper.controller.t3;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.BbStore.RoundWrite;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.node.JsonNodeFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// `host-retiring`: every 60 s, on the controller clock (the clock the scheduler retires
/// by), for every `[[hosts]]` host with `retireAt`, the due thresholds are `{3 d if remaining ≤ 3 d} ∪ {1 d if remaining ≤
/// 1 d}`. When a due threshold has no mark, ONE event is emitted, for the smallest due threshold without a mark, and a
/// mark is written for every due one: a controller started at T-12 h says 1, never a stale 3. Nothing at or after
/// `retireAt`. A mark whose subject is not the host's current `retireAt` (changed, removed, or the host gone) is
/// deleted, so a changed `retireAt` re-arms both thresholds. One transaction per tick; ticks never overlap.
public final class RetireWarn {
    private static final Logger log = LoggerFactory.getLogger(RetireWarn.class);
    static final TimeSpan EVERY = TimeSpan.timeSpan(60).seconds();
    /// The thresholds, in days, largest first.
    static final List<Integer> DAYS = List.of(3, 1);
    private static final String SOURCE = "controller";

    /// A configured `[[hosts]]` host and its `retireAt`, if any.
    public record Retire(String host, Option<Instant> retireAt) {
        public static Retire retire(String host, Option<Instant> retireAt) {
            return new Retire(host, retireAt);
        }
    }

    private final BbStore store;
    private final FleetWriter writer;
    private final List<Retire> hosts;
    private final Supplier<Instant> clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean failing = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> scheduled = new AtomicReference<>(none());

    private RetireWarn(BbStore store, FleetWriter writer, List<Retire> hosts, Supplier<Instant> clock) {
        this.store = store;
        this.writer = writer;
        this.hosts = List.copyOf(hosts);
        this.clock = clock;
    }

    public static RetireWarn retireWarn(BbStore store,
                                        FleetWriter writer,
                                        List<Retire> hosts,
                                        Supplier<Instant> clock) {
        return new RetireWarn(store, writer, hosts, clock);
    }

    /// Starts the 60 s schedule, with a first tick at once (a tick still going when the next is due skips that one).
    public Unit schedule() {
        scheduled.set(some(SharedScheduler.scheduleAtFixedRate(this::due, EVERY)));
        due();

        return unit();
    }

    public Unit stop() {
        stopped.set(true);
        scheduled.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// One tick: the `host-retiring` marks read, decided and written with the event in one transaction.
    public Promise<Unit> tick() {
        var now = clock.get();

        return writer.detected(() -> store.retiring(now,
                                                    rows -> decide(hosts, now, rows)))
                     .mapToUnit();
    }

    private Unit due() {
        return ! stopped.get() && running.compareAndSet(false, true)
               ? released()
               : unit();
    }

    private Unit released() {
        tick().withResult(result -> outcome(result.isSuccess(),
                                            result.fold(Cause::message, _ -> "")))
            .withResult(_ -> running.set(false));

        return unit();
    }

    /// One WARN when the tick starts failing and one INFO when it recovers.
    private Unit outcome(boolean ok, String failure) {
        var wasFailing = failing.getAndSet(!ok);

        if (!ok && !wasFailing) {
            log.warn("host-retiring: tick failed: {}", failure);
        } else if (ok && wasFailing) {
            log.info("host-retiring: tick recovered");
        }

        return unit();
    }

    /// The pure decision of one tick over the `host-retiring` marks of every host.
    public static RoundWrite decide(List<Retire> hosts, Instant now, List<BbAlarm> rows) {
        var events = new ArrayList<NewFleetEvent>();
        var fire = new ArrayList<BbAlarm>();
        var clear = rows.stream()
                        .filter(row -> row.kind()
                                          .equals(BbAlarm.HOST_RETIRING))
                        .filter(row -> !current(hosts, row))
                        .toList();

        hosts.forEach(host -> host.retireAt()
                                  .onPresent(retireAt -> due(host.host(),
                                                             retireAt,
                                                             now,
                                                             rows,
                                                             events,
                                                             fire)));

        return RoundWrite.roundWrite(events, fire, clear);
    }

    /// The mark's subject is its host's configured `retireAt`.
    private static boolean current(List<Retire> hosts, BbAlarm row) {
        return hosts.stream()
                    .anyMatch(host -> host.host()
                                          .equals(row.host()) && host.retireAt()
                                                                     .map(Instant::toString)
                                                                     .filter(row.subject()::equals)
                                                                     .isPresent());
    }

    private static void due(String host,
                            Instant retireAt,
                            Instant now,
                            List<BbAlarm> rows,
                            List<NewFleetEvent> events,
                            List<BbAlarm> fire) {
        var remaining = Duration.between(now, retireAt);

        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }

        var subject = retireAt.toString();
        var missing = DAYS.stream()
                          .filter(days -> remaining.compareTo(Duration.ofDays(days)) <= 0)
                          .filter(days -> rows.stream()
                                              .noneMatch(row -> row.host()
                                                                   .equals(host)
                                                                && row.kind()
                                                                      .equals(BbAlarm.HOST_RETIRING)
                                                                && row.subject()
                                                                      .equals(subject)
                                                                && row.threshold()
                                                                      .equals(Integer.toString(days))))
                          .toList();

        if (missing.isEmpty()) {
            return;
        }

        var days = missing.getLast();
        var payload = JsonNodeFactory.instance.objectNode();

        payload.put("host", host);
        payload.put("days", days);
        payload.put("retireAt", subject);
        payload.put("remainingH", remaining.toHours());
        events.add(NewFleetEvent.newFleetEvent(now, SOURCE, BbAlarm.HOST_RETIRING, host, payload.toString()));
        missing.forEach(due -> fire.add(BbAlarm.bbAlarm(host, BbAlarm.HOST_RETIRING, subject, Integer.toString(due), now)));
    }
}
