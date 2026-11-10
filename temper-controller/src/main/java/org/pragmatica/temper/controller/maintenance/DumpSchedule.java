package org.pragmatica.temper.controller.maintenance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// When the dump runs (m1-design §3.4): every day at `[pgDump] at` in the host's zone, and once at start when the
/// newest good dump is older than [#STALE] (or there is none). Runs never overlap: a run requested while one is in
/// flight is skipped.
///
/// **Guarantee:** one dump per day the controller was up at or after the scheduled time. Mechanism: the next run is
/// scheduled after each one; a controller that was down at the scheduled time catches up at start.
public final class DumpSchedule {
    private static final Logger log = LoggerFactory.getLogger(DumpSchedule.class);
    public static final Duration STALE = Duration.ofHours(24);

    private final PgDump dump;
    private final MaintenanceStore store;
    private final LocalTime at;
    private final ZoneId zone;
    private final Supplier<Instant> clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> next = new AtomicReference<>(none());

    private DumpSchedule(PgDump dump, MaintenanceStore store, LocalTime at, ZoneId zone, Supplier<Instant> clock) {
        this.dump = dump;
        this.store = store;
        this.at = at;
        this.zone = zone;
        this.clock = clock;
    }

    public static DumpSchedule dumpSchedule(PgDump dump,
                                            MaintenanceStore store,
                                            LocalTime at,
                                            ZoneId zone,
                                            Supplier<Instant> clock) {
        return new DumpSchedule(dump, store, at, zone, clock);
    }

    /// Catches up when the newest good dump is stale, then schedules the daily run.
    public Unit start() {
        catchUp();

        return scheduleNext();
    }

    public Unit stop() {
        stopped.set(true);
        next.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// The time from `now` to the next `at` in `now`'s zone; a run due exactly now is a day away.
    static Duration untilNext(LocalTime at, ZonedDateTime now) {
        var today = now.with(at);

        return Duration.between(now,
                                today.isAfter(now)
                                ? today
                                : today.plusDays(1));
    }

    /// Whether a run with this newest good dump is due at start.
    static boolean isStale(Option<MaintenanceRun> lastGood, Instant now) {
        return lastGood.map(run -> run.endedAt()
                                      .plus(STALE)
                                      .isBefore(now))
                       .or(true);
    }

    /// One run unless one is in flight; the outcome is logged (the run itself is the `maintenance_runs` row).
    public Promise<Unit> runOnce() {
        return running.compareAndSet(false, true)
               ? logged(dump.dump()).withResult(_ -> running.set(false))
                       .mapToUnit()
               : Promise.unitPromise();
    }

    private static Promise<MaintenanceRun> logged(Promise<MaintenanceRun> run) {
        return run.onSuccess(done -> log.info("pg_dump ok={} {}",
                                              done.ok(),
                                              done.path()))
                  .onFailure(cause -> log.warn("pg_dump run not recorded: {}",
                                               cause.message()));
    }

    /// FER: a failed look-up of the last good dump is logged and skips the catch-up; the daily run still happens.
    private Unit catchUp() {
        // May run late: the catch-up is detached; nothing waits for it.
        store.lastGood(MaintenanceKind.PG_DUMP)
             .onSuccess(this::runIfStale)
             .onFailure(cause -> log.warn("last pg_dump unknown, no catch-up: {}",
                                          cause.message()));

        return unit();
    }

    private Unit runIfStale(Option<MaintenanceRun> last) {
        return isStale(last, clock.get())
               ? catchUpRun()
               : unit();
    }

    /// Fire-and-forget: the run logs its own outcome.
    private Unit catchUpRun() {
        runOnce();

        return unit();
    }

    private Unit scheduleNext() {
        var delay = untilNext(at,
                              ZonedDateTime.ofInstant(clock.get(), zone));

        return stopped.get()
               ? unit()
               : scheduled(SharedScheduler.schedule(this::fire, TimeSpan.fromDuration(delay)));
    }

    private Unit scheduled(ScheduledFuture<?> future) {
        next.set(some(future));

        return unit();
    }

    private Unit fire() {
        // May run late: the timer's run is detached; the next one is scheduled whenever it ends.
        runOnce().onResultRun(this::scheduleNext);

        return unit();
    }
}
