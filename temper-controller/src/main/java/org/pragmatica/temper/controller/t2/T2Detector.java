package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.controller.fleet.FleetWriter;
import org.pragmatica.temper.store.api.DetectorBatch;
import org.pragmatica.temper.store.api.DetectorInput;
import org.pragmatica.temper.store.api.MergeFacts;
import org.pragmatica.temper.store.api.T2Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The `t2:detector` loop: every 5 s and on the fleet wake signal, one batch in one store
/// transaction under the T2 lock: the rows after its cursor, the facts of each merge they report, the pure [T2Plan],
/// and its writes, the events through [FleetWriter#detected] (the restore check and the wake run for them, as for the
/// kiln pull). A batch's success is the detector's `last_ok_at` (period 5 s), so a detector that stops reads stale in
/// the heartbeat by age (`now − lastOkAt > 15 s`).
///
/// FER on a failed batch: logged once when the detector starts failing and once when it recovers; the next run reads
/// the same rows again (nothing committed), and the staleness of `t2:detector` announces one that keeps failing.
public final class T2Detector {
    private static final Logger log = LoggerFactory.getLogger(T2Detector.class);
    private static final TimeSpan EVERY = TimeSpan.timeSpan(T2Plan.PERIOD_S).seconds();

    private final T2Store store;
    private final FleetWriter writer;
    private final Supplier<Instant> clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean failing = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> scheduled = new AtomicReference<>(none());

    private T2Detector(T2Store store, FleetWriter writer, Supplier<Instant> clock) {
        this.store = store;
        this.writer = writer;
        this.clock = clock;
    }

    public static T2Detector t2Detector(T2Store store, FleetWriter writer, Supplier<Instant> clock) {
        return new T2Detector(store, writer, clock);
    }

    /// Starts the 5 s schedule and the wake listener (a run still going when the next is due skips that one).
    public Unit schedule() {
        writer.wake().listen(this::due);
        scheduled.set(some(SharedScheduler.scheduleAtFixedRate(this::due, EVERY)));

        return unit();
    }

    /// The schedule stops; the wake listener stays registered (the signal has no removal) and does nothing.
    public Unit stop() {
        stopped.set(true);
        scheduled.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// One batch: the rows after the cursor, planned and written in one transaction.
    public Promise<Unit> detect() {
        return writer.detected(() -> store.detect(T2Plan.LIMIT, this::planned))
                     .mapToUnit();
    }

    private Unit due() {
        return ! stopped.get() && running.compareAndSet(false, true)
               ? released()
               : unit();
    }

    private Unit released() {
        detect().withResult(result -> outcome(result.isSuccess(),
                                              result.fold(Cause::message, _ -> "")))
              .onResult(_ -> running.set(false));

        return unit();
    }

    /// The facts of each sighting, read one after another on the transaction's connection, then the plan.
    private Promise<DetectorBatch> planned(DetectorInput input, MergeFacts.Reader reader) {
        return T2Plan.sightings(input)
                     .stream()
                     .reduce(Promise.success(List.<T2Plan.Observed> of()),
                             (done, sighting) -> observed(done, sighting, reader),
                             (left, _) -> left)
                     .map(observed -> T2Plan.plan(input,
                                                  observed,
                                                  clock.get()));
    }

    private static Promise<List<T2Plan.Observed>> observed(Promise<List<T2Plan.Observed>> done,
                                                           T2Plan.Sighting sighting,
                                                           MergeFacts.Reader reader) {
        return done.flatMap(list -> appended(list, sighting, reader));
    }

    private static Promise<List<T2Plan.Observed>> appended(List<T2Plan.Observed> list,
                                                           T2Plan.Sighting sighting,
                                                           MergeFacts.Reader reader) {
        return reader.facts(sighting.key())
                     .map(facts -> Stream.concat(list.stream(),
                                                 Stream.of(T2Plan.Observed.observed(sighting, facts)))
                                         .toList());
    }

    /// One WARN when the detector starts failing and one INFO when it recovers, not one per 5 s.
    private Unit outcome(boolean ok, String failure) {
        var wasFailing = failing.getAndSet(!ok);

        return ok == wasFailing
               ? logged(ok, failure)
               : unit();
    }

    private static Unit logged(boolean ok, String failure) {
        return ok
               ? info("t2: detector recovered")
               : warn("t2: detector failing: " + failure);
    }

    private static Unit info(String text) {
        log.info(text);

        return unit();
    }

    private static Unit warn(String text) {
        log.warn(text);

        return unit();
    }
}
