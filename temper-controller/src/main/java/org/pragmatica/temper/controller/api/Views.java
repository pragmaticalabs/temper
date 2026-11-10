package org.pragmatica.temper.controller.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.pragmatica.temper.controller.host.UnitAttempt;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.rerun.RerunOutcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.api.UnitView;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.RerunRow;
import org.pragmatica.temper.store.api.UnitRow;
import org.pragmatica.temper.store.pg.WireRecords;

import static org.pragmatica.lang.Option.some;


/// Store rows → API views (m0-design §4.3). A REFUSED job's `reason` is its refusal code. A unit that is active but
/// whose lease has passed is shown with `leaseExpired` ("RUNNING(unknown)", m0-design §6.4). A unit's port-group hold
/// is shown as `waiting`/`held` with when it entered that state and its bb-* ticket (m1-design §1.3, Q-M1-17). M4
/// (m4-design §11): a job shows its `dedicatedCpu`, a unit its current attempt, the CPU class of that attempt's host,
/// its side effects and its lost attempts, all as stored.
///
/// Every state shown is the derived one ([#jobState], [#unitState]; ruling R-13, amended): the store's UNKNOWN mark is
/// the durable record, but it is a write, and a store that serves reads while it refuses or hangs that write would
/// otherwise show a running unit GRANTED and its job QUEUED (m1-design §14: a job whose start facts were lost never
/// shows QUEUED while it runs). A GRANTED unit with no recorded start is therefore shown UNKNOWN, and a QUEUED job with
/// such a unit UNKNOWN too, when either:
///
/// - this controller knows it runs: its current attempt (the row's attempt number) is in `unrecorded`
///   (`HostLinks.unrecordedStarts`), i.e. its `UnitStarted` arrived and its start write has been in flight
///   `LinkTiming.unrecordedAfter` (1 s) or an attempt of it failed, or the start was dropped, or a reconcile found
///   its container running with no recorded start; it stays there until the start is recorded or the unit completes.
///   Shown UNKNOWN at once, bb-hold `waiting` or not. A healthy start lands inside `unrecordedAfter`, so it never
///   flickers to UNKNOWN. The caller takes that snapshot BEFORE it reads the row;
/// - or, the backstop for a controller with no such memory (restarted, before the reconnect's reconcile finds the
///   unit): its grant is older than the start bound (`LinkTiming.startBound`), and it is not waiting in bb-hold
///   (`port_group = waiting`, whose start waits for the group without bound).
///
/// Otherwise a just-granted unit shows GRANTED and its job QUEUED, as before (m0-design §5.3: the job turns RUNNING
/// when a unit's start is recorded; nothing names a starting state). A late start shows RUNNING and a landed mark the
/// stored UNKNOWN, as stored. The derivation is display only: capacity counts a GRANTED unit as occupying either way.
public sealed interface Views {
    static JobView jobView(JobRow row, Instant now, Duration startBound, Set<UnitAttempt> unrecorded) {
        return JobView.jobView(row.id().value(),
                               jobState(row, now, startBound, unrecorded),
                               row.verdict(),
                               row.state() == JobState.REFUSED
                               ? row.refusalCode()
                               : row.reason(),
                               row.submittedBy().value(),
                               row.submittedAt(),
                               row.priority(),
                               row.keep(),
                               row.fresh(),
                               row.superseded(),
                               new SourceView(row.ref().map(Ref::value),
                                              row.sha().map(Sha::value)),
                               RecipeDto.from(row.recipe()),
                               row.units()
                                  .stream()
                                  .map(unit -> unitView(row.id(),
                                                        unit,
                                                        now,
                                                        startBound,
                                                        unrecorded))
                                  .toList(),
                               rerunViews(row.reruns()),
                               row.dedicatedCpu());
    }

    /// A job's reruns (m2-design §5.3): each with its stored state (its verdict once terminal) and its per-class
    /// outcomes. The stored state, not the derived one [G]: the line shows a rerun's progress, and its own view
    /// shows the derived state.
    static List<RerunView> rerunViews(List<RerunRow> reruns) {
        return reruns.stream()
                     .map(Views::rerunView)
                     .toList();
    }

    private static RerunView rerunView(RerunRow rerun) {
        return RerunView.rerunView(rerun.job().value(),
                                   rerun.state(),
                                   rerun.annotations().stream().filter(RerunOutcome::isClassOutcome).toList());
    }

    static UnitView unitView(JobId job, UnitRow unit, Instant now, Duration startBound, Set<UnitAttempt> unrecorded) {
        return UnitView.unitView(unit.name().value(),
                                 unitState(job, unit, now, startBound, unrecorded),
                                 unit.reason(),
                                 unit.host().map(HostName::value),
                                 unit.rc(),
                                 unit.startedAt(),
                                 unit.endedAt(),
                                 unit.imageId(),
                                 unit.containerId(),
                                 unit.killedBy().map(KilledBy::from),
                                 leaseExpired(unit, now),
                                 unit.portGroup().map(PortGroup::wire),
                                 unit.portGroupSince(),
                                 unit.ticket(),
                                 unit.attempt(),
                                 unit.sharedCpu(),
                                 some(unit.sideEffects().wire()),
                                 unit.lostAttempts().stream().map(WireRecords::storedAttemptDto).toList());
    }

    /// The job's state as shown: a stored QUEUED job with a unit shown UNKNOWN is UNKNOWN; otherwise the stored state.
    static JobState jobState(JobRow row, Instant now, Duration startBound, Set<UnitAttempt> unrecorded) {
        return row.state() == JobState.QUEUED && row.units()
                                                    .stream()
                                                    .anyMatch(unit -> unitState(row.id(),
                                                                                unit,
                                                                                now,
                                                                                startBound,
                                                                                unrecorded) == UnitState.UNKNOWN)
               ? JobState.UNKNOWN
               : row.state();
    }

    /// The unit's state as shown: UNKNOWN for a GRANTED unit with no recorded start that this controller knows runs
    /// (`unrecorded`), or, not waiting in bb-hold, whose grant is older than `startBound`; otherwise the stored state.
    static UnitState unitState(JobId job, UnitRow unit, Instant now, Duration startBound, Set<UnitAttempt> unrecorded) {
        return startUnrecorded(job, unit, now, startBound, unrecorded)
               ? UnitState.UNKNOWN
               : unit.state();
    }

    private static boolean startUnrecorded(JobId job,
                                           UnitRow unit,
                                           Instant now,
                                           Duration startBound,
                                           Set<UnitAttempt> unrecorded) {
        return unit.state() == UnitState.GRANTED
               && unit.startedAt()
                      .isEmpty()
               && (knownUnrecorded(job, unit, unrecorded) || startOverdue(unit, now, startBound));
    }

    /// The unit's current attempt (its number, m4-design §4.1) is the one whose start this controller knows is
    /// unrecorded; an earlier attempt of a requeued unit says nothing about this one.
    private static boolean knownUnrecorded(JobId job, UnitRow unit, Set<UnitAttempt> unrecorded) {
        return unrecorded.contains(UnitAttempt.unitAttempt(UnitRef.unitRef(job, unit.name()),
                                                           unit.attempt()));
    }

    private static boolean startOverdue(UnitRow unit, Instant now, Duration startBound) {
        return unit.portGroup()
                   .filter(PortGroup.WAITING::equals)
                   .isEmpty() && unit.grantedAt()
                                     .filter(granted -> granted.plus(startBound)
                                                               .isBefore(now))
                                     .isPresent();
    }

    private static boolean leaseExpired(UnitRow unit, Instant now) {
        return active(unit.state()) && unit.leaseUntil()
                                           .filter(until -> until.isBefore(now))
                                           .isPresent();
    }

    private static boolean active(UnitState state) {
        return state == UnitState.GRANTED || state == UnitState.UNKNOWN || state == UnitState.RUNNING || state == UnitState.CANCELLING;
    }

    record unused() implements Views {}
}
