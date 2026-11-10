package org.pragmatica.temper.acceptance.trust;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.UnitView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// T1's queue phase (option A): polls until the job leaves QUEUED, pacing like `temper wait` (2 s doubling, here to at
/// most 30 s), never pausing past `--queue-hours`, and absorbing a few consecutive transient read failures.
class QueueWaitTest {
    private static final long JOB = 39;

    @Test
    void left_answersTheFirstStateOtherThanQueued_pacingTwoSecondsDoublingToThirty() {
        var clock = new Waits.Clock();

        var left = Waits.queue(Waits.queuedThen(7, JobState.UNKNOWN), clock, Duration.ofHours(24)).left(JOB).await();

        assertThat(left.expect("left the queue")).isEqualTo(JobState.UNKNOWN);
        assertThat(clock.pauses).containsExactly(seconds(2), seconds(4), seconds(8), seconds(16), seconds(30), seconds(30), seconds(30));
    }

    @Test
    void left_failsWithStillWaiting_atTheBound_withoutPausingPastIt() {
        var clock = new Waits.Clock();

        var left = Waits.queue(Waits.queuedThen(Integer.MAX_VALUE, JobState.RUNNING), clock, Duration.ofSeconds(45)).left(JOB).await();

        assertThat(left.<Object>fold(cause -> cause, state -> state)).isInstanceOf(TrustError.StillWaiting.class);
        assertThat(clock.pauses).containsExactly(seconds(2), seconds(4), seconds(8), seconds(16), seconds(15));
        assertThat(clock.elapsed()).isEqualTo(seconds(45));
    }

    @Test
    void left_absorbsFiveConsecutiveTransientFailures_butNotASixth() {
        var survived = Waits.queue(new Waits.Jobs(n -> n < 5 ? Waits.io() : Promise.success(Waits.view(JobState.RUNNING))),
                                   new Waits.Clock(), Duration.ofHours(24)).left(JOB).await();
        var sixth = new Waits.Jobs(n -> n < 6 ? Waits.io() : Promise.success(Waits.view(JobState.RUNNING)));
        var failed = Waits.queue(sixth, new Waits.Clock(), Duration.ofHours(24)).left(JOB).await();

        assertThat(survived.expect("five transient failures absorbed")).isEqualTo(JobState.RUNNING);
        assertThat(failed.<Object>fold(cause -> cause, state -> state)).isInstanceOf(TrustError.Io.class);
        assertThat(sixth.reads.get()).isEqualTo(6);
    }

    @Test
    void left_failsAtOnce_onAFailureThatIsNotTransient() {
        var jobs = new Waits.Jobs(_ -> TrustError.UnexpectedStatus.FACTORY.apply("/jobs/39", 404).promise());

        var left = Waits.queue(jobs, new Waits.Clock(), Duration.ofHours(24)).left(JOB).await();

        assertThat(left.<Object>fold(cause -> cause, state -> state)).isInstanceOf(TrustError.UnexpectedStatus.class);
        assertThat(jobs.reads.get()).isEqualTo(1);
    }

    /// A failure at the bound after the job was seen QUEUED is the bound: capacity, not the read failure.
    @Test
    void left_failsWithStillWaiting_whenTheBoundPassesDuringFailures_afterAWaitWasSeen() {
        var jobs = new Waits.Jobs(n -> n == 0 ? Promise.success(Waits.view(JobState.QUEUED)) : Waits.io());

        var left = Waits.queue(jobs, new Waits.Clock(), Duration.ofSeconds(10)).left(JOB).await();

        assertThat(left.<Object>fold(cause -> cause, state -> state)).isInstanceOf(TrustError.StillWaiting.class);
    }

    /// Only QUEUED is a wait; a granted unit's bb-hold wait for the port group (Q-M1-17), always seen while the job is
    /// QUEUED, is named. RUNNING, UNKNOWN and terminal are not waits, whatever their units say.
    @Test
    void waitOf_isQueuedOnly_namingAPortGroupWait() {
        var waiting = List.of(Waits.gateUnit(UnitState.GRANTED, some(UnitView.WAITING)));
        var portGroupWait = "QUEUED (waiting for the port group since " + Waits.START + ")";

        assertThat(QueueWait.waitOf(Waits.view(JobState.QUEUED))).isEqualTo(some("QUEUED"));
        assertThat(QueueWait.waitOf(Waits.queuedForPortGroup())).isEqualTo(some(portGroupWait));
        assertThat(QueueWait.waitOf(Waits.view(JobState.QUEUED, List.of(Waits.gateUnit(UnitState.GRANTED, some(UnitView.HELD)), waiting.getFirst()))))
            .as("one unit waiting is enough").isEqualTo(some(portGroupWait));
        assertThat(QueueWait.waitOf(Waits.view(JobState.QUEUED, List.of(Waits.gateUnit(UnitState.QUEUED, none()))))).as("a unit without a hold")
                                                                                                                 .isEqualTo(some("QUEUED"));
        assertThat(QueueWait.waitOf(Waits.runningHeld())).isEqualTo(none());
        assertThat(QueueWait.waitOf(Waits.view(JobState.RUNNING, waiting))).isEqualTo(none());
        assertThat(QueueWait.waitOf(Waits.view(JobState.UNKNOWN, waiting))).isEqualTo(none());
        assertThat(QueueWait.waitOf(Waits.view(JobState.FAILED, waiting))).isEqualTo(none());
    }

    @Test
    void left_failsWithStillWaiting_namingThePortGroupWait_atTheBound() {
        var left = Waits.queue(Waits.reading(Waits.view(JobState.QUEUED), Waits.queuedForPortGroup()), new Waits.Clock(),
                               Duration.ofHours(2)).left(JOB).await();

        assertThat(left.<Object>fold(Cause::message, state -> state))
            .isEqualTo("job 39 still QUEUED (waiting for the port group since " + Waits.START + ") after 2h");
    }

    /// A 24 h queue polls thousands of times: the loop must not nest one promise per poll (temper-cli's `Loop` lesson).
    @Test
    void left_survivesTwentyThousandPolls() {
        var left = Waits.queue(Waits.queuedThen(20_000, JobState.RUNNING), new Waits.Clock(), Duration.ofDays(3650)).left(JOB).await();

        assertThat(left.expect("left after 20 000 polls")).isEqualTo(JobState.RUNNING);
    }

    private static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }
}
