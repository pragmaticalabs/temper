package org.pragmatica.temper.acceptance.trust;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.acceptance.trust.Loop.Turn;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.UnitView;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.promise;


/// T1's queue phase (owner ruling, option A): polls `GET /jobs/{id}` until the job leaves QUEUED, bounded by
/// `--queue-hours`. One bound over queue and run alike failed a healthy head: shadow heads run at prio 1 behind prio-5
/// Gates of ~92 min each, so job 39 queued 4 h 40 min, ran 1 h 32 min, and the 6 h wait expired while it was RUNNING.
/// The queue wait gets its own bound here, the run its own after it ([T1Steps]). A unit's wait in bb-hold for the
/// port group (m1-design Q-M1-17) is queue time too, and is seen while the job is still QUEUED ([#waitOf]). Polls
/// every 2 s, doubling to at most 30 s, in a [Loop], so a 24 h queue uses constant stack and memory; no pause runs
/// past the bound. A job still QUEUED at the bound fails with [TrustError.StillWaiting], which names a port-group wait
/// when there is one: its SHA is NOT COMPARABLE (capacity), and the job is left as it is, never cancelled, so a later
/// `--rerun` can judge it once it reached a final state.
///
/// Recovery: as `temper wait` does, up to [#TOLERATED_FAILURES] consecutive transient read failures ([TrustError.Io],
/// or a 5xx outside the route's contract) are absorbed (FER: retry forward through a controller restart; the read is a
/// GET, so repeating it is harmless). One more fails the phase, unless the bound passed first while the job was last
/// seen QUEUED: then it is [TrustError.StillWaiting] with that wait. Every other failure ends the phase at once.
public record QueueWait(Fn1<Promise<JobView>, Long> jobs,
                        InstantSource clock,
                        Fn1<Promise<Unit>, Duration> pacer,
                        Duration bound) {
    public static final Duration FIRST_INTERVAL = Duration.ofSeconds(2);
    public static final Duration MAX_INTERVAL = Duration.ofSeconds(30);
    public static final int TOLERATED_FAILURES = 5;
    public static final String QUEUED = "QUEUED";
    private static final int SERVER_ERROR = 500;

    /// One iteration's knowledge: the bound's instant, the next poll interval, consecutive transient failures, and
    /// how the job was last seen waiting ([#waitOf]), none before the first read.
    record Poll(Instant deadline, Duration interval, int failures, Option<String> seenWaiting) {
        static Poll poll(Instant deadline, Duration interval, int failures, Option<String> seenWaiting) {
            return new Poll(deadline, interval, failures, seenWaiting);
        }
    }

    public static QueueWait queueWait(Fn1<Promise<JobView>, Long> jobs,
                                      InstantSource clock,
                                      Fn1<Promise<Unit>, Duration> pacer,
                                      Duration bound) {
        return new QueueWait(jobs, clock, pacer, bound);
    }

    /// The pause on core's scheduler.
    public static Promise<Unit> scheduled(Duration duration) {
        return promise(TimeSpan.fromDuration(duration), Result::unitResult);
    }

    /// The state of `job` once it is no longer QUEUED (RUNNING, UNKNOWN, or one already terminal); the bound counts
    /// from this call.
    public Promise<JobState> left(long job) {
        return Loop.loop(Poll.poll(clock.instant().plus(bound),
                                   FIRST_INTERVAL,
                                   0,
                                   none()),
                         poll -> step(job, poll));
    }

    /// How `view` still waits, none once it is no longer QUEUED: `QUEUED`, or `QUEUED (waiting for the port group
    /// since <instant>)` when one of its units is granted but its bb-hold still waits for the group. The store sets
    /// `waiting` only on a granted unit that has not started, and the start sets `held` as the job turns RUNNING, so a
    /// bb-hold wait is always seen while the job is QUEUED.
    static Option<String> waitOf(JobView view) {
        return view.state() == JobState.QUEUED
               ? some(queuedWait(view))
               : none();
    }

    private static String queuedWait(JobView view) {
        return Option.from(view.units().stream().filter(QueueWait::waitsForPortGroup).findFirst())
                     .map(QueueWait::portGroupWait)
                     .or(QUEUED);
    }

    private static boolean waitsForPortGroup(UnitView unit) {
        return unit.portGroup()
                   .filter(UnitView.WAITING::equals)
                   .isPresent();
    }

    private static String portGroupWait(UnitView unit) {
        return QUEUED
             + " (waiting for the port group since " + unit.portGroupSince()
                                                           .map(Instant::toString)
                                                           .or("an unknown time")
             + ")";
    }

    private Promise<Turn<Poll, JobState>> step(long job, Poll poll) {
        return jobs.apply(job)
                   .fold(read -> read.fold(cause -> afterFailure(job, cause, poll),
                                           view -> afterRead(job, view, poll)));
    }

    private Promise<Turn<Poll, JobState>> afterRead(long job, JobView view, Poll poll) {
        return waitOf(view).map(wait -> waiting(job, wait, poll))
                     .or(() -> Promise.success(Turn.done(view.state())));
    }

    private Promise<Turn<Poll, JobState>> waiting(long job, String wait, Poll poll) {
        return remaining(poll).isPositive()
               ? pollAgain(Poll.poll(poll.deadline(), longer(poll.interval()), 0, some(wait)),
                           poll)
               : stillWaiting(job, wait);
    }

    private Promise<Turn<Poll, JobState>> afterFailure(long job, Cause cause, Poll poll) {
        return isTolerable(cause, poll.failures())
               ? retryOrExpire(job, cause, poll)
               : cause.promise();
    }

    /// A tolerated failure past the bound is the bound: StillWaiting when the job was seen waiting, else the failure.
    private Promise<Turn<Poll, JobState>> retryOrExpire(long job, Cause cause, Poll poll) {
        return remaining(poll).isPositive()
               ? pollAgain(Poll.poll(poll.deadline(), poll.interval(), poll.failures() + 1, poll.seenWaiting()),
                           poll)
               : expiredAfterFailure(job, cause, poll);
    }

    private Promise<Turn<Poll, JobState>> expiredAfterFailure(long job, Cause cause, Poll poll) {
        return poll.seenWaiting()
                   .map(wait -> stillWaiting(job, wait))
                   .or(cause::promise);
    }

    private Promise<Turn<Poll, JobState>> pollAgain(Poll next, Poll poll) {
        return pacer.apply(shorter(poll.interval(),
                                   remaining(poll)))
                    .map(_ -> Turn.again(next));
    }

    private Promise<Turn<Poll, JobState>> stillWaiting(long job, String wait) {
        return TrustError.StillWaiting.FACTORY.apply(job,
                                                     wait,
                                                     bound.toHours())
                                              .promise();
    }

    private static boolean isTolerable(Cause cause, int failures) {
        return failures < TOLERATED_FAILURES && isTransient(cause);
    }

    private static boolean isTransient(Cause cause) {
        return switch (cause) {
            case TrustError.Io _ -> true;
            case TrustError.UnexpectedStatus unexpected -> unexpected.status() >= SERVER_ERROR;
            default -> false;
        };
    }

    private Duration remaining(Poll poll) {
        return Duration.between(clock.instant(), poll.deadline());
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0
               ? first
               : second;
    }

    private static Duration longer(Duration interval) {
        return shorter(interval.multipliedBy(2), MAX_INTERVAL);
    }
}
