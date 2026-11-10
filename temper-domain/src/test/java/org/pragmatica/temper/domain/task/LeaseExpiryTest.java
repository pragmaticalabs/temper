package org.pragmatica.temper.domain.task;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.task.Tasks.T0;
import static org.pragmatica.temper.domain.task.Tasks.applied;
import static org.pragmatica.temper.domain.task.Tasks.state;

/// m3-design §11.5 `LeaseExpiryTest`, the exact "stuck past its lease twice" of §6.2 (ruling R-M3-11): the first expiry in
/// a visit requeues in the same visit; the second in the same visit parks `stuck-lease`; a new visit starts at 0;
/// requeues, WAITING and releases do not reset the count, and a release is not an expiry; an active linked job holds the
/// TTL but never the step deadline.
///
/// Probes: reset `visit_expiries` on requeue (`secondExpiryInTheVisit_parksStuckLease` stays READY: red); count releases
/// as expiries (`release_isNotAnExpiry` goes red).
class LeaseExpiryTest {
    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void firstExpiry_requeuesInTheSameVisit() {
        var expired = expire(claimed(state(Stage.CODE, Status.READY), T0), T0.plus(TTL).plusSeconds(1));

        assertThat(expired.state().stage()).isEqualTo(Stage.CODE);
        assertThat(expired.state().status()).isEqualTo(Status.READY);
        assertThat(expired.state().counters().visit()).isEqualTo(1);
        assertThat(expired.state().counters().visitExpiries()).isEqualTo(1);
        assertThat(expired.parked().isEmpty()).isTrue();
    }

    @Test
    void secondExpiryInTheVisit_parksStuckLease() {
        var once = expire(claimed(state(Stage.CODE, Status.READY), T0), T0.plus(TTL).plusSeconds(1)).state();
        var reclaimedAt = T0.plus(TTL).plusSeconds(2);
        var twice = expire(claimed(once, reclaimedAt), reclaimedAt.plus(TTL).plusSeconds(1));

        assertThat(twice.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(twice.state().status()).isEqualTo(Status.HELD);
        assertThat(twice.state().raisedFrom()).isEqualTo(some(Stage.CODE));
        assertThat(twice.parked().map(ParkRequest::kind)).isEqualTo(some(RulingKind.STUCK_LEASE));
        assertThat(twice.state().epoch()).isEqualTo(5);
    }

    @Test
    void expiryAfterANewVisit_requeuesAgain() {
        var once = expire(claimed(state(Stage.CODE, Status.READY), T0), T0.plus(TTL).plusSeconds(1)).state();
        var handedOn = applied(claimed(once, T0.plus(TTL).plusSeconds(2)), Tasks.evidence(Stage.VERIFY), T0.plus(TTL).plusSeconds(3)).state();
        var claimedAt = T0.plus(TTL).plusSeconds(4);
        var inVerify = expire(claimed(handedOn, claimedAt), claimedAt.plus(TTL).plusSeconds(1));

        assertThat(handedOn.counters().visitExpiries()).isZero();
        assertThat(inVerify.state().stage()).isEqualTo(Stage.VERIFY);
        assertThat(inVerify.state().status()).isEqualTo(Status.READY);
        assertThat(inVerify.state().counters().visitExpiries()).isEqualTo(1);
    }

    @Test
    void waitingAndResume_doNotResetTheCount() {
        var once = expire(claimed(state(Stage.CODE, Status.READY), T0), T0.plus(TTL).plusSeconds(1)).state();
        var at = T0.plus(TTL).plusSeconds(2);
        var waited = applied(claimed(once, at), Tasks.waitOnJob(), at).state();
        var resumed = applied(waited, new Command.Resume(true), at).state();
        var twice = expire(claimed(resumed, at), at.plus(TTL).plusSeconds(1));

        assertThat(resumed.counters().visitExpiries()).isEqualTo(1);
        assertThat(twice.state().stage()).isEqualTo(Stage.NEEDS_RULING);
    }

    @Test
    void release_isNotAnExpiry() {
        var released = applied(claimed(state(Stage.CODE, Status.READY), T0), Tasks.release("crash"), T0).state();
        var once = expire(claimed(released, T0), T0.plus(TTL).plusSeconds(1));

        assertThat(released.counters().visitExpiries()).isZero();
        assertThat(released.counters().visitFailures()).isEqualTo(1);
        assertThat(once.state().status()).isEqualTo(Status.READY);
        assertThat(once.state().counters().visitExpiries()).isEqualTo(1);
    }

    @Test
    void activeLinkedJob_holdsTheTtl() {
        var leased = claimed(state(Stage.CODE, Status.READY), T0);

        assertThat(TaskMachine.apply(leased, new Command.Expire(true), T0.plus(TTL).plusSeconds(1)).isFailure()).isTrue();
    }

    @Test
    void activeLinkedJob_doesNotHoldTheStepDeadline() {
        var leased = claimed(state(Stage.CODE, Status.READY), T0);
        var expired = applied(leased, new Command.Expire(true), T0.plus(Duration.ofHours(6)).plusSeconds(1));

        assertThat(expired.state().status()).isEqualTo(Status.READY);
        assertThat(expired.events().getFirst().facts()).containsEntry("clock", "step-deadline");
    }

    @Test
    void heartbeat_movesTheTtl_soNoExpiry() {
        var leased = claimed(state(Stage.CODE, Status.READY), T0);
        var beat = applied(leased, new Command.Heartbeat(), T0.plus(Duration.ofMinutes(20))).state();

        assertThat(TaskMachine.apply(beat, new Command.Expire(false), T0.plus(TTL).plusSeconds(1)).isFailure()).isTrue();
    }

    private static TaskState claimed(TaskState ready, Instant at) {
        return applied(ready, Tasks.claim(), at).state();
    }

    private static Transition expire(TaskState leased, Instant at) {
        return applied(leased, new Command.Expire(false), at);
    }
}
