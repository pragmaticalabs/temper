package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Every producer's way into the stream: the store insert (under the order lock), then, once it committed, the restore
/// check, the high-water mark and the fleet wake signal (long-polls wake on it), before the caller's next step.
///
/// The restore check of an insert (design §2.2 Correction 2) reads nothing: the insert's lowest
/// committed seq is compared with the mark read BEFORE the insert ([HighWater.Mark#behind]). At or below it, a restore
/// set the sequence back and the insert reused a seq, so the epoch rotates. It runs for every insert, so none is
/// skipped. The rotations and the minute's check against the database ([#verified]) run one at a time, in order
/// ([#queued]): a check that finds another running waits for it, then runs.
///
/// A rotation that fails (the database stopped answering right after the insert committed) does not drop the proof:
/// it is kept in memory as a [Pending] rotation, retried before the next insert and by every minute check until it
/// succeeds or the epoch has changed already; meanwhile `selfcheck:highwater` reads stale ([#rotationPending]). A
/// process crash inside that window loses it: then only the minute's mark-vs-head check remains.
public record FleetWriter(FleetStore store,
                          HighWater highWater,
                          Wake wake,
                          Supplier<Instant> clock,
                          AtomicReference<Promise<Unit>> checks,
                          AtomicReference<Option<Pending>> pending) {
    private static final Logger log = LoggerFactory.getLogger(FleetWriter.class);

    /// A seq used twice whose rotation has not succeeded yet: the mark read before the insert and the insert's lowest
    /// seq at or below it.
    public record Pending(HighWater.Mark mark, long first) {}

    public static FleetWriter fleetWriter(FleetStore store, HighWater highWater, Wake wake, Supplier<Instant> clock) {
        return new FleetWriter(store,
                               highWater,
                               wake,
                               clock,
                               new AtomicReference<>(Promise.unitPromise()),
                               new AtomicReference<>(Option.none()));
    }

    public Promise<Option<Long>> append(List<NewFleetEvent> events) {
        return retried().flatMap(_ -> inserted(() -> store.append(events)));
    }

    public Promise<Option<Long>> pulled(KilnBatch batch) {
        return retried().flatMap(_ -> inserted(() -> store.pulled(batch)));
    }

    public Promise<Option<Long>> staleness(String name,
                                           boolean stale,
                                           Option<Instant> readLastOkAt,
                                           NewFleetEvent event) {
        return retried().flatMap(_ -> inserted(() -> store.staleness(name, stale, readLastOkAt, event)));
    }

    /// The T2 detector's batch: one store transaction whose events are checked and wake the
    /// stream as every other producer's.
    public Promise<Option<Long>> detected(Supplier<Promise<Option<FleetSeqs>>> batch) {
        return retried().flatMap(_ -> inserted(batch));
    }

    /// A T2 route's transaction that inserts events among its own writes (a verdict's): checked
    /// and woken as every other producer's, its own value handed on; `seqs` reads what it inserted.
    public <T> Promise<T> transaction(Supplier<Promise<T>> transaction, Fn1<Option<FleetSeqs>, T> seqs) {
        return retried().flatMap(_ -> written(transaction, seqs));
    }

    /// Whether a rotation is pending (its last attempt failed): `selfcheck:highwater` then reads stale.
    public boolean rotationPending() {
        return pending.get()
                      .isPresent();
    }

    /// The mark is read BEFORE the insert (after any pending rotation, which moves it to the new epoch).
    private Promise<Option<Long>> inserted(Supplier<Promise<Option<FleetSeqs>>> insert) {
        return written(insert, seqs -> seqs).map(seqs -> seqs.map(FleetSeqs::last));
    }

    /// The mark is read BEFORE the transaction; once it committed, the check and the wake run for what it inserted.
    private <T> Promise<T> written(Supplier<Promise<T>> transaction, Fn1<Option<FleetSeqs>, T> seqs) {
        var before = highWater.mark();

        return transaction.get()
                          .flatMap(value -> committed(before,
                                                      seqs.apply(value)).map(_ -> value));
    }

    /// A pending rotation is attempted again first; FER on a failure: it stays pending, and the caller goes on.
    private Promise<Unit> retried() {
        return pending.get()
                      .map(this::rotated)
                      .or(Promise::unitPromise);
    }

    /// A rotation's own `epoch-changed` event ([TemperEpoch]), with the new epoch's mark already set. It is not
    /// checked: the check would wait behind the rotation that waits for this event.
    Promise<Unit> announce(List<NewFleetEvent> events) {
        return store.append(events)
                    .map(this::advanced)
                    .map(_ -> wake.signal());
    }

    /// The running check against the database ([TemperEpoch#verify]), after any check already running; whether the
    /// high-water file was written.
    ///
    /// FER on a failed check (the database did not answer): logged, answered as not written; the next minute checks
    /// again, and `selfcheck:highwater` goes stale if none succeeds.
    public Promise<Boolean> verified() {
        return retried().flatMap(_ -> queued(() -> TemperEpoch.verify(this,
                                                                      clock.get())))
                      .recover(FleetWriter::unchecked);
    }

    private static boolean unchecked(Cause cause) {
        log.warn("fleet: high-water check failed: {}", cause.message());

        return false;
    }

    private Promise<Unit> committed(HighWater.Mark before, Option<FleetSeqs> seqs) {
        return checked(before, seqs).map(_ -> wake.signal());
    }

    private Promise<Unit> checked(HighWater.Mark before, Option<FleetSeqs> seqs) {
        return seqs.filter(inserted -> before.behind(inserted.first()))
                   .map(inserted -> rotated(new Pending(before,
                                                        inserted.first())))
                   .or(() -> Promise.success(advanced(seqs)));
    }

    private Unit advanced(Option<FleetSeqs> seqs) {
        return seqs.map(inserted -> highWater.advance(inserted.last()))
                   .or(unit());
    }

    /// A seq used twice: the epoch rotates, after any check running, unless a rotation since the mark was read did it;
    /// either way nothing is pending any more.
    ///
    /// FER on a failed rotation: logged, the insert's own outcome stands, and the proof is kept as pending ([#retried]).
    private Promise<Unit> rotated(Pending proof) {
        return queued(() -> rotation(proof.mark())).map(_ -> settled())
                     .recover(cause -> unrotated(proof, cause));
    }

    private Unit settled() {
        pending.set(Option.none());

        return unit();
    }

    private Promise<Boolean> rotation(HighWater.Mark before) {
        return highWater.mark()
                        .epoch()
                        .equals(before.epoch())
               ? TemperEpoch.rotate(this,
                                    clock.get())
                            .map(_ -> true)
               : Promise.success(false);
    }

    private Unit unrotated(Pending proof, Cause cause) {
        pending.set(Option.some(proof));
        log.warn("fleet: seq {} reused at or below mark {}, the epoch rotation failed (kept pending): {}",
                 proof.first(),
                 proof.mark(),
                 cause.message());

        return unit();
    }

    /// Runs `check` once every check queued before it has finished, whatever its outcome.
    private <T> Promise<T> queued(Supplier<Promise<T>> check) {
        var done = Promise.<Unit> promise();

        return checks.getAndSet(done)
                     .fold(_ -> check.get())
                     .withResult(_ -> done.succeed(unit()));
    }
}
