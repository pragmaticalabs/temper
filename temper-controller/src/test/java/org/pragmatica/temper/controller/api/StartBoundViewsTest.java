package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.PortGroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling R-13, amended, read side ([Views#jobState], [Views#unitState]): a GRANTED unit with no recorded start whose
/// grant is older than the start bound shows UNKNOWN, and its QUEUED job UNKNOWN, whatever the store's mark did; one
/// this controller knows runs with no recorded start (`HostLinks.unrecordedStarts`) shows UNKNOWN at once.
class StartBoundViewsTest {
    private static final Duration BOUND = LinkTiming.DEFAULT.startBound();
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Set<UnitAttempt> KNOWN = Set.of(UnitAttempt.unitAttempt(UnitRef.unitRef(JobId.jobId(1).unwrap(),
                                                                                                  UnitName.MAIN),
                                                                                  1));

    /// The bound is the launch allowance (prepare 15 min + start 20 min) plus the start-write window: 5 attempts of
    /// 5 s and the pauses 200 ms + 1 s + 2 s + 5 s.
    @Test
    void startBound_isLaunchAllowancePlusTheStartWriteWindow() {
        assertThat(LinkTiming.DEFAULT.launchAllowance().duration()).isEqualTo(Duration.ofMinutes(35));
        assertThat(BOUND).isEqualTo(Duration.ofMinutes(35).plusSeconds(25).plusMillis(8200));
    }

    @Test
    void grantedPastTheBound_withoutStart_showsUnknown_andItsQueuedJobToo() {
        var job = Rows.job(1, JobState.QUEUED, List.of(Rows.granted(NOW.minus(BOUND).minusMillis(1), none())));
        var view = Views.jobView(job, NOW, BOUND, Set.of());

        assertThat(view.state()).isEqualTo(JobState.UNKNOWN);
        assertThat(view.units().getFirst().state()).isEqualTo(UnitState.UNKNOWN);
    }

    /// Before the bound the unit may still be launching: GRANTED, and its job QUEUED until a start is recorded.
    @Test
    void grantedAtTheBound_showsGranted_andItsJobQueued() {
        var job = Rows.job(1, JobState.QUEUED, List.of(Rows.granted(NOW.minus(BOUND), none())));
        var view = Views.jobView(job, NOW, BOUND, Set.of());

        assertThat(view.state()).isEqualTo(JobState.QUEUED);
        assertThat(view.units().getFirst().state()).isEqualTo(UnitState.GRANTED);
    }

    /// A unit waiting in bb-hold starts when the group is free, without bound: never derived.
    @Test
    void waitingForTheHold_isNeverDerived() {
        var job = Rows.job(1, JobState.QUEUED, List.of(Rows.granted(NOW.minus(Duration.ofDays(1)), some(PortGroup.WAITING))));

        assertThat(Views.jobState(job, NOW, BOUND, Set.of())).isEqualTo(JobState.QUEUED);
        assertThat(Views.unitState(job.id(), job.units().getFirst(), NOW, BOUND, Set.of())).isEqualTo(UnitState.GRANTED);
    }

    /// m1-design §14: a unit the controller knows runs shows UNKNOWN right after its grant, long before the bound, and
    /// even while waiting in bb-hold (its `UnitStarted` came, so the hold was granted).
    @Test
    void knownUnrecordedStart_showsUnknownAtOnce_waitingOrNot() {
        var fresh = Rows.job(1, JobState.QUEUED, List.of(Rows.granted(NOW, none())));
        var waiting = Rows.job(1, JobState.QUEUED, List.of(Rows.granted(NOW, some(PortGroup.WAITING))));

        assertThat(Views.jobView(fresh, NOW, BOUND, KNOWN).state()).isEqualTo(JobState.UNKNOWN);
        assertThat(Views.jobView(fresh, NOW, BOUND, KNOWN).units().getFirst().state()).isEqualTo(UnitState.UNKNOWN);
        assertThat(Views.jobState(waiting, NOW, BOUND, KNOWN)).isEqualTo(JobState.UNKNOWN);
    }

    /// The memory only derives a GRANTED unit with no recorded start: a late start (RUNNING), the landed mark
    /// (UNKNOWN) and a completion are shown as stored, and another job's unit is not touched.
    @Test
    void knownUnrecordedStart_derivesOnlyTheGrantedUnstartedUnitOfThatJob() {
        var late = Rows.job(1, JobState.RUNNING, List.of(Rows.unit(UnitState.RUNNING, none(), none())));
        var marked = Rows.job(1, JobState.UNKNOWN, List.of(Rows.unit(UnitState.UNKNOWN, none(), none())));
        var done = Rows.job(1, JobState.PASSED, List.of(Rows.unit(UnitState.PASSED, none(), none())));
        var other = Rows.job(2, JobState.QUEUED, List.of(Rows.granted(NOW, none())));

        assertThat(Views.jobView(late, NOW, BOUND, KNOWN).units().getFirst().state()).isEqualTo(UnitState.RUNNING);
        assertThat(Views.jobState(late, NOW, BOUND, KNOWN)).isEqualTo(JobState.RUNNING);
        assertThat(Views.jobState(marked, NOW, BOUND, KNOWN)).isEqualTo(JobState.UNKNOWN);
        assertThat(Views.jobState(done, NOW, BOUND, KNOWN)).isEqualTo(JobState.PASSED);
        assertThat(Views.jobState(other, NOW, BOUND, KNOWN)).isEqualTo(JobState.QUEUED);
    }

    /// Verification F4, read side: what the controller knows is scoped to the attempt. The unit was requeued and
    /// granted again (attempt 2), so the earlier attempt's unrecorded start says nothing about this one.
    @Test
    void knownUnrecordedStart_ofAnEarlierAttempt_doesNotDeriveTheNewOne() {
        var regranted = Rows.job(1, JobState.QUEUED, List.of(Rows.granted(NOW.plusSeconds(30), none(), 2)));

        assertThat(Views.jobState(regranted, NOW.plusSeconds(31), BOUND, KNOWN)).isEqualTo(JobState.QUEUED);
        assertThat(Views.unitState(regranted.id(), regranted.units().getFirst(), NOW.plusSeconds(31), BOUND, KNOWN))
            .isEqualTo(UnitState.GRANTED);
    }

    /// Only a GRANTED unit is derived; a stored state that is not QUEUED is the job's shown state.
    @Test
    void otherStates_areShownAsStored() {
        var running = Rows.job(1, JobState.RUNNING, List.of(Rows.unit(UnitState.RUNNING, none(), none())));
        var cancelling = Rows.job(2, JobState.CANCELLING, List.of(Rows.unit(UnitState.CANCELLING, none(), none())));

        assertThat(Views.jobState(running, NOW, BOUND, Set.of())).isEqualTo(JobState.RUNNING);
        assertThat(Views.jobState(cancelling, NOW, BOUND, Set.of())).isEqualTo(JobState.CANCELLING);
    }
}
