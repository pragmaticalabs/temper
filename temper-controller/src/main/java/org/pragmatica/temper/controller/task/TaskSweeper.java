package org.pragmatica.temper.controller.task;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.store.api.SweepOutcome;
import org.pragmatica.temper.store.api.TaskStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The task sweeper (m3-design §6.2, §6.6) and the dispatcher's tick (§5.2 T1), every [#INTERVAL] in the controller:
/// one `TaskStore.sweep` expires leases whose clock passed (requeue, or park at the second expiry of a visit), resumes
/// WAITING tasks whose linked jobs are all terminal, and advances TRIAGED tasks whose `after` tasks reached VERIFY.
///
/// Non-reentrant: a slow sweep is never overlapped by the next tick. A lease is revoked within one interval after its
/// clock passes while the controller runs; while it is down, expiries wait for it (§16). A WAITING task resumes within
/// one interval of its last job finishing (the sweep, not the job's `finished` event, which would put a task's failure
/// into the job's completing transaction [G]).
public final class TaskSweeper {
    private static final Logger log = LoggerFactory.getLogger(TaskSweeper.class);
    /// §6.2 [P].
    public static final TimeSpan INTERVAL = TimeSpan.timeSpan(30).seconds();

    private final TaskStore tasks;
    private final Supplier<Instant> clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> periodic = new AtomicReference<>(none());

    private TaskSweeper(TaskStore tasks, Supplier<Instant> clock) {
        this.tasks = tasks;
        this.clock = clock;
    }

    public static TaskSweeper taskSweeper(TaskStore tasks, Supplier<Instant> clock) {
        return new TaskSweeper(tasks, clock);
    }

    /// At start, first each lease whose TTL lapsed during the downtime gets start + its stage's TTL (m3-design §6.2 [G]:
    /// heartbeats could not reach temper, so an outage must not count as an expiry; once per epoch, never past the step
    /// deadline, live leases untouched), each logged; then a sweep, which applies every step deadline; then a sweep every
    /// `interval`.
    public Unit start(TimeSpan interval) {
        running.set(true);
        released(started());
        periodic.set(some(SharedScheduler.scheduleAtFixedRate(this::tick, interval, interval)));

        return unit();
    }

    /// The start's extension, then its first sweep.
    public Promise<SweepOutcome> started() {
        return tasks.restarted(clock.get())
                    .withSuccess(extended -> extended.forEach(TaskSweeper::logExtended))
                    .flatMap(_ -> sweep());
    }

    private static Unit logExtended(TaskId task) {
        log.info("task {}: lease TTL lapsed while the controller was down; extended to start + the stage's TTL", task);

        return unit();
    }

    public Unit stop() {
        periodic.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// One sweep unless one is in flight.
    public Unit tick() {
        return running.compareAndSet(false, true)
               ? released(sweep())
               : unit();
    }

    /// One sweep; answers what it did.
    public Promise<SweepOutcome> sweep() {
        return tasks.sweep(clock.get())
                    .onSuccess(outcome -> log.debug("task sweep: {}", outcome));
    }

    /// Fire-and-forget: the sweep logs its outcome, and the guard is released as a step of its chain. FER: a failed
    /// sweep is logged and dropped; nothing it would have written is written, and the next tick sweeps again.
    private Unit released(Promise<SweepOutcome> work) {
        work.onFailure(cause -> log.warn("task sweep failed: {}", cause.message())).withResult(_ -> running.set(false));

        return unit();
    }
}
