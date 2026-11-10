package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.util.List;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Host-link timing (m0-design §6.4). [#DEFAULT] is the design's values; tests shrink them.
///
/// - `hello`: how long a fresh worker may take to say `Hello` (JVM start plus the 30 s host-lock wait).
/// - `silence`: no frame for this long means the link is dead (heartbeats come every 10 s).
/// - `request`, `prepare`, `start`: request timeouts (30 s; 15 min for fetch/checkout/seed; 20 min for a start
///   that builds the runner image).
/// - `backoff`: reconnect delay, 1 s doubling to 60 s with jitter; reset after `stableUp` of UP.
/// - `startRetry`: the pauses between the attempts of one start write (ruling R-13, a marked guess): 200 ms, 1 s,
///   2 s, 5 s, so 5 attempts (the first and 4 retries) over about 8 s, long enough to ride out a Postgres restart.
///   The attempt count is the number of pauses plus one. The UNKNOWN mark of a dropped start (R-13, amended) follows
///   the same pauses, then repeats the last one until the mark lands, so the list is never empty.
/// - `startAttemptTimeout`: how long one attempt of a start write may take before it counts as failed and is retried
///   (ruling R-13, a marked guess: 5 s), so a store that hangs instead of refusing bounds the whole start by the
///   attempts times this timeout plus the pauses (production: 5 × 5 s + about 8 s).
/// - `unrecordedAfter`: how long a start write may be in flight, from the moment its `UnitStarted` arrived, before the
///   controller counts the start as unrecorded and every read shows the unit UNKNOWN (m1-design §14, maintainer ruling: 1 s).
///   An attempt that fails counts it unrecorded at once; a healthy start lands well inside this, so it never shows
///   UNKNOWN, and shows QUEUED for at most this long ([WorkerEvents#recordStart]).
///
/// Two values derive from these, for the read side of ruling R-13 (amended): [#launchAllowance] and [#startBound].
public record LinkTiming(TimeSpan hello,
                         Duration silence,
                         TimeSpan livenessCheck,
                         TimeSpan request,
                         TimeSpan prepare,
                         TimeSpan start,
                         TimeSpan deploy,
                         BackoffStrategy backoff,
                         Duration stableUp,
                         List<TimeSpan> startRetry,
                         TimeSpan startAttemptTimeout,
                         TimeSpan unrecordedAfter) {
    public static final LinkTiming DEFAULT = new LinkTiming(timeSpan(90).seconds(),
                                                            Duration.ofSeconds(35),
                                                            timeSpan(5).seconds(),
                                                            timeSpan(30).seconds(),
                                                            timeSpan(15).minutes(),
                                                            timeSpan(20).minutes(),
                                                            timeSpan(5).minutes(),
                                                            BackoffStrategy.exponential()
                                                                           .initialDelay(timeSpan(1).seconds())
                                                                           .maxDelay(timeSpan(60).seconds())
                                                                           .factor(2.0)
                                                                           .withJitter(),
                                                            Duration.ofSeconds(60),
                                                            List.of(timeSpan(200).millis(),
                                                                    timeSpan(1).seconds(),
                                                                    timeSpan(2).seconds(),
                                                                    timeSpan(5).seconds()),
                                                            timeSpan(5).seconds(),
                                                            timeSpan(1).seconds());

    /// How long a granted unit may legitimately go without a start the worker reported: `prepare` plus `start`, the
    /// request timeouts of `PrepareJob` and `StartUnit`, which a launch sends one after the other right after the grant
    /// ([UnitLauncher]). Past them the launch has its `UnitStarted` (or `UnitHolding`) or has reset the session, so a
    /// longer wait is never a launch still under way. Production: 15 min + 20 min (a start that builds the runner
    /// image); a typical launch takes seconds, and the allowance is sized for the slowest legitimate one, so the read
    /// side never calls a unit still launching UNKNOWN.
    public TimeSpan launchAllowance() {
        return prepare.plus(start);
    }

    /// The start bound (ruling R-13, amended, read side): a GRANTED unit with no recorded start whose grant is older
    /// than this is shown UNKNOWN ([org.pragmatica.temper.controller.api.Views#unitState]); the backstop for a controller
    /// that has no memory of the unit's start (`HostLinks.unrecordedStarts`, e.g. after a restart). It is [#launchAllowance]
    /// plus the whole start-write window: the attempts (pauses plus one) times `startAttemptTimeout`, plus the sum of
    /// the pauses. Production: 35 min + 5 × 5 s + 8.2 s = 35 min 33.2 s.
    public Duration startBound() {
        return startRetry.stream()
                         .map(TimeSpan::duration)
                         .reduce(launchAllowance().duration()
                                                .plus(startAttemptTimeout.duration().multipliedBy(startRetry.size() + 1)),
                                 Duration::plus);
    }

    public static LinkTiming linkTiming(TimeSpan hello,
                                        Duration silence,
                                        TimeSpan livenessCheck,
                                        TimeSpan request,
                                        TimeSpan prepare,
                                        TimeSpan start,
                                        TimeSpan deploy,
                                        BackoffStrategy backoff,
                                        Duration stableUp,
                                        List<TimeSpan> startRetry,
                                        TimeSpan startAttemptTimeout,
                                        TimeSpan unrecordedAfter) {
        return new LinkTiming(hello,
                              silence,
                              livenessCheck,
                              request,
                              prepare,
                              start,
                              deploy,
                              backoff,
                              stableUp,
                              startRetry,
                              startAttemptTimeout,
                              unrecordedAfter);
    }
}
